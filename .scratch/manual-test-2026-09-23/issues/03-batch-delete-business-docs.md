# 03 · 批量删除—业务单据（销售/销售退货/进货/进货退货/采购申请/生产入库/领料）

Type: task
Status: ready-for-human
Blocked by: 02（共用 BatchDeleteResultVO 与前端范式）

同 02 号票定案（范围/权限/尽力而为/UI 范式），本票覆盖业务单据 7 页。单据页「撤销/作废」按钮不动。

## 范围（7 页）

| 页面 | 单删现状（守卫+状态校验） | 批量端点 |
|---|---|---|
| 销售出库确认 SalesView | SalesService.delete：销售 admin + 单据状态可删 | /business/sales/batch-delete |
| 销售退货出库确认 SalesReturnView | SalesReturnService.delete 同构 | /business/sales-return/batch-delete |
| 物料进货 PurchaseView | PurchaseService.delete | /business/purchase/batch-delete |
| 进货退货 PurchaseReturnView | PurchaseReturnService.delete | /business/purchase-return/batch-delete |
| 采购申请 PurchaseRequestView | PurchaseRequestService.delete：申请人本人 + 待采购状态 | /business/purchase-request/batch-delete |
| 生产入库 ProductionView | ProductionService.delete | /business/production/batch-delete |
| 领料单 PickListView | PickListService.delete | /business/pick-list/batch-delete |

## 统一约定
- 每模块 batch-delete：守卫一次，逐行跑单删同款状态/归属校验（尽力而为），D21 消息撤销逐行执行。
- 失败明细含单号（如 PR20260923…：仅待采购状态可撤销）。
- 单测：守卫负测 + 混合成败聚合 + 状态不可删行进 failures。

## 验收
- 7 页批量删除可用；不可删单据（已确认/已作废等）留在列表并见于失败明细。
