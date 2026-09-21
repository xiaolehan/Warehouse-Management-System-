# 01 · D111 进货单头行结构改造 + 进货退货多行化

Type: task
Status: ready-for-agent
Blocked by: 无

详见 ADR-0015。grilling 两轮定案（题 1+8），Q1=A 整单粒度。

## 范围

### DB
- 新建 `biz_purchase_detail`（purchase_id、goods_id、goods_name/spec/material 快照、quantity、unit_price、total_price、sort_no；唯一键 uk_purchase_goods(purchase_id, goods_id)，索引 idx_purchase_id）。
- 新建 `biz_purchase_return_detail`（return_id、source_purchase_id、source_detail_id、goods 快照、quantity、unit_price、total_price、sort_no；唯一键 uk_return_source_line(return_id, source_detail_id)）。
- 头表废弃 goods_id/quantity/unit_price/total_price（迁移后规范段移除；增量段先保留列兼容），加 total_quantity/total_amount 汇总。
- 数据迁移：存量 biz_purchase 每行生成一条 detail；biz_purchase_return 同；本地库执行并核对。
- db.sql 规范段 3.x 进货/进货退货重写 + 种子多行化 + 增量段。

### 后端
- BizPurchaseDetail/BizPurchaseReturnDetail 实体+Mapper；PurchaseSaveDTO 改 lines 列表；PurchaseService 重写：
  - create 多行构建+同物一行去重（重复报错合并提示）、头汇总；手动进货仍待到货起步。
  - 到货确认/入库确认整单；确认入库逐行 increaseStock + 逐行回写 purchase_price。
  - delete（当天+待到货整单）/voidDocument（逐行 decreaseStock + 逐商品 refreshPurchasePriceAfterVoid 泛化）。
  - createInternal 供票 02 调用：一次调用生成一张多行单。
  - page/fillDetails 行聚合 + 商品汇总描述（对标 D110「首品名 等 N 种」）。
- PurchaseReturnService 多行化：选来源单→行级可退量（该行入库量−该行已退累计，收口公开方法共用）→逐行构建；确认出库逐行扣回/回冲；级联软删明细（D110 review 教训）。
- 适配：ApprovalService meta 聚合明细；统计 Mapper/v_purchase_detail 下沉 detail JOIN head；GoodsReferenceService 引用统计下沉；D104 进货/进货退货时间线库存影响按行汇总；进价历史端点改读明细行。

### 前端
- PurchaseView 新建弹窗多行编辑器（选物料带最近进价参考、数量/单价逐行、头汇总、同品去重、单价>0 校验）。
- PurchaseReturnView 发起退货：选来源单后按行勾选+逐行数量（上限钳制+整数 precision=0）+退款预览；**修复查看态来源单显示**（与票 10 合并验证）。
- 列表汇总列、详情行表格、作废/删除按钮整单口径不变。

### 测试
- PurchaseServiceTest/PurchaseReturnServiceTest 重写：多行入库、作废逐行回冲+进价重算、退货行级可退量/超退/重复来源行拦截、级联软删。
- E2E：手动多行进货全链；申请入库生成一张多行单（与票 02 联测）；多行退货按行退；统计/进价历史读数正确。

## 验收
- 一张补料申请确认入库后「物料进货」页只有一张单号、内含 N 行。
- 整单作废后各物料库存/进价逐行复原；无分行作废入口。
