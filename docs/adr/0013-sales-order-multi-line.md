# 销售单头行结构改造（一客户多型号）

> 状态：accepted（D110；销售单从「一行=一张单（一单一成品）」改为「头单 + 多明细行（一客户 N 种成品）」）

## 背景

用户需求原话：「销售单开单一家客户公司只能匹配单一型号，需改为一客户可选多型号成品匹配」。排查确认系统本无客户-型号绑定约束——根因是 biz_sales **一单一品结构**（goods_id/quantity/unit_price/cost_*/total_price 全在主表，无明细行表）。改造是三批需求中最大的工程，牵动出库确认、退货、价格偏离审批、生产联动、履约时间线、报表统计与站内消息全链路。

## 决策

1. **头行拆分，头表只留汇总**：新增 `biz_sales_detail`（行：goods_id/goods_name/quantity/unit_price/cost_* 快照/total_price/sort_no）；`biz_sales` 头表删除单品字段，改 `total_quantity`/`total_amount`（建单算好，单据不可编辑）。同一成品在一张单内**只允许一行**（重复报错让用户合并），保证「生产任务单 ↔ 销售行」可由 (头单, 成品) 唯一解析。销售退货对称拆 `biz_sales_return_detail`。
2. **整单一次确认出库，不拆单**（用户拍板）：仓储确认时逐行条件扣库存（`stock >= 行数量`），任一行不足**整单失败回滚**并提示缺货行；不出现部分出库。
3. **销售退货同步多行化**：退货单选来源销售单后**按行退**，行级可退量 = 原单行数量 − 该行被有效退货（正常+已确认入库）累计；行成本快照优先 `SOURCE_SALE`（取原行 cost_unit_price），否则回退采购价/商品价/零。
4. **价格偏离审批整单一笔**：逐行对照标准售价判偏离，任一行偏离即建**一张**审批单（biz_id 仍挂头单），request_reason 列出全部偏离行（行号+成品+偏离幅度）；超管一次批准/驳回整单；仓储确认前校验整单级「存在已通过审批」——不做行级审批（与 biz_approval_order 绑头单的机制一致）。
5. **行级缺货标识**：建单不拦（D69 定制模式），列表仓储视角按行比 stock 给缺货标记；建单时现货不足的行**汇总一条**消息通知生产管理员（列明缺货行），不再逐行刷屏。
6. **生产联动锚定明细行**：`biz_production_order` 新增 `sales_detail_id`（新增列，不改旧 `sales_order_id` 头单关联——头单号展示/作废通知等仍走头单）。关联校验升级为：头单正常+待出库，且头单下存在**该成品的明细行**（据「一成品一行」约束唯一解析出行，前端仍只传头单 id，零改动复用）。
7. **履约时间线按明细行展示**（用户拍板方案 A）：`GET /sales/{id}/timeline` 返回逐行时间线（每行自己的 8 节点+预计可交付时间，行级生产单/缺料/入库进度），详情页每行一根；单行单据观感与旧版一致。
8. **报表统计全部下沉到行聚合**：BizSalesMapper/BizSalesReturnMapper 全部 SUM/GROUP BY（金额/数量/成本/Top5/品牌毛利/日趋势/年度）改为 `detail JOIN 头表`（头表供时间/状态/逻辑删过滤，行表供商品与金额维度），口径与旧 SQL 逐条对齐（biz_status=1、confirm_status=2、is_deleted 过滤语义不变）；头行表上的 `v_sales_detail` 视图同步重建到明细表。
9. **红冲死代码随行拆移除**：D99 已封死「作废并冲抵」路径（入口与审批侧双封），voidDocument 内不可达的红冲复制块不随头行改造移植，直接删除；biz_status=3 枚举与 source_id 字段保留（历史兼容）。
10. **消息文案汇总化**：跨部门消息里的「成品 %s×%d」单行字段改为汇总描述（单行 `PTO153×5`、多行 `PTO153×5、轴承×3`），biz_type/biz_id 绑定与撤销点不变（D21）。

## Consequence

- 无存量数据迁移负担（改造时本地 biz_sales/biz_sales_return 均为 0 行），直接重定义表结构；db.sql 规范段与本地库同步重造。
- 列表不再有单一「销售单价」，改为销售总额/数量汇总+均价；「出库商品」列显示「PTO153 等 3 种」。
- 一成品一行约束是行解析的基石：放开它必须同时改生产联动解析逻辑。除应用层去重校验（友好报错）外，db 层有 `uk_sales_goods(sales_id, goods_id)` / `uk_return_source_line(return_id, source_sales_detail_id)` 唯一键兜底并发（review 补强）。
- `biz_production_order.sales_detail_id` 是建单时写入的行级锚点（决策 6）；当前行级读取（时间线/生产联动）走 (sales_order_id, goods_id) 解析，该列留给行级直连场景，非死字段。
- 头表 total_quantity/total_amount 是冗余汇总，与行合计的一致性由「建单算好 + 单据不可编辑」保证（销售单无编辑功能，D106）。
- 统计 SQL 全部走 detail JOIN head，head 单表查询只剩时间范围极值（min/max operation_time，与行无关）。
