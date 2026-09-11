# ADR-0008: 年度经营统计口径（财务端）

> 状态：accepted（2026-09-11 会话 30，grilling 三轮 16 题定案 + to-spec 留档 `.scratch/annual-stats/spec.md`）

## 背景

系统历经多轮业务改造（采购申请多品流程、销售/采购退货、红冲机制、成本快照），财务端仍只有「销售统计图表」页且最粗粒度只到「按天」，采购侧零统计代码。财务管理员无法回答「每年采购花了多少、销售收了多少、赚了多少」。

备选方案：
- **商品价格趋势**（每商品各年度平均进价/售价）：进价只存当前值（CONTEXT.md），历史价散在单据上，口径复杂，不做；
- **扩展现有 SalesChartView 加「年度视角」**：该页已有销售/毛利两套视角，再塞采购+年度会拥挤，不采纳；
- **收支差当毛利**（销售额 − 当年采购额）：混入库存变动（今年买的货可能明年才卖），非会计意义毛利，会误导，不采纳；
- **独立新页面 + 已实现销售毛利口径**（采纳）。

## 决策

新增「年度经营统计」页（`/business/annual-stats`），按自然年汇总五行指标，一年一行、年份倒序，配分组柱状图，支持 xlsx 导出（POI 范式同 BOM 导出）。口径如下：

| 指标 | 来源与过滤 | 归年时间 |
|---|---|---|
| 采购总支出 | `biz_purchase`（`biz_status=1 AND confirm_status=3 AND is_deleted=0`）− `biz_purchase_return`（同严口径）。**单一来源**：采购申请「确认入库」会逐明细写入 `biz_purchase`（`PurchaseRequestService.confirmReceive` → `PurchaseService.createInternal`），`biz_purchase` 是唯一入库交易表，已覆盖采购申请渠道——**不可再对 `biz_purchase_request_detail` 求和，否则该渠道金额翻倍**（code-review 实测发现并修正了 spec 阶段「两来源互斥」的错误假设） | 进货单 `COALESCE(confirm_time, operation_time)`；采购退货 `COALESCE(complete_time, operation_time)` |
| 销售总营收 | `biz_sales`（`is_deleted=0 AND biz_status=1 AND confirm_status=2`，严口径同毛利视角）− `biz_sales_return`（同严口径） | 双方均 `operation_time` |
| 销售成本 | 销售单 `cost_total_price` 快照 − 退货成本快照（同营收口径） | 同上 |
| 毛利 | 销售额 − 销售成本（**已实现销售毛利**） | — |
| 毛利率 | 毛利 ÷ 销售额 × 100；**销售额 ≤ 0 时为 null**（页面显示「—」） | — |

补充规则：

1. **退货按退货单自身发生时间归年冲减**，不回溯原单年份（允许出现负值行）。
2. 年份集合 = 销售年 ∪ 成本年 ∪ 采购年的并集；只有单侧数据的年份照常列出、缺侧为 0。
3. **权限**：仅财务部门管理员可见（含导出接口）——Controller 类级 `@SaCheckRole(admin|superadmin)` + 方法级 `@SaCheckRole("admin")` 挡超管/员工，Service 层显式拒绝超管并做部门校验（与利润分析同级，因含采购进价敏感数据）。
4. 不加筛选器（全部年份一次返回）、不做下钻（无商品/品牌/客户/供应商维度）、无新表无 schema 变更。

## Consequence

- 年度数字可与「销售统计图表」毛利视角交叉验证（同一套严过滤 + 成本快照）。
- 跨年退货会在退货年产生冲减（可能负值行），是口径的有意选择而非 bug；报表读者需知悉（页面工具栏已注明口径）。
- 后续若加月度/季度统计，沿用同一套过滤与归年字段，仅换 GROUP BY 粒度。
