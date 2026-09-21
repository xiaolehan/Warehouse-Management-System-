# 10 · D119 物料退货查看态「来源进货单」显示修复

Type: task
Status: ready-for-agent
Blocked by: 01（进货单多行化后来源变为「单+行」，本修复随新结构一起做）

## 根因（已查证，纯显示 bug）
- 两端共用 PurchaseReturnView.vue + 同一 VO，数据一致（biz_purchase_return.source_purchase_no 建单快照，PurchaseReturnService.toVO 不 JOIN）；
- 查看弹窗「来源进货单」是 disabled el-select，显示依赖 sourcePurchaseOptions 下拉选项；
- D96 规定可退选项仅建单角色（采购成员/超管）加载（PurchaseReturnView.vue:525），仓储进页面不拉选项 → 选项空 → el-select 回退显示裸 sourcePurchaseId 数字；
- 采购端有选项 → 显示「单号 | 物料 | 可退:N」正常。

## 范围

### 前端（PurchaseReturnView.vue）
- 查看态（dialogType==='view'）：来源进货单改为**纯文本展示**（sourcePurchaseNo；多行化后展示「单号 + 退货行汇总」），不再复用 el-select；
- 新建/编辑态保持 el-select 不变；
- 顺带正名：view 态「可退数量」当前错填该单退货数量 returnQuantity，查看态改显来源行可退量语义或直接隐藏该字段（退货数量字段已表达实际退货量）。

### 后端
- 无改动（sourcePurchaseNo 已在 VO）。票 01 多行化后 VO 带退货行摘要供文本展示。

### 测试
- 手测：仓储管理员查看任意进货退货单 → 来源进货单显示单号文本而非数字；采购端显示不回归；新建退货下拉链路不受影响。

## 验收
- 同一退货单，采购端与仓储端查看弹窗的来源进货单显示一致（同一张单号）。
