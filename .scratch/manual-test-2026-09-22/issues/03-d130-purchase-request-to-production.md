# 03 · D130 采购申请创建权 仓储→生产

Type: task
Status: ready-for-human
Blocked by: 无

用户定案（2026-09-22 grilling，第三轮手测问题 3，方案 A）：普通采购申请的创建/缺货识别/撤销权整体从仓储管理员移到**生产管理员**；仓储回归「只管出入库」——保留确认入库 + 只读看单。**采购部门功能零改动**（认领/到货/驳回原样；「物料进货」页直接新建进货单的自主采购路径也不涉及）。

## 现状（守卫全景，PurchaseRequestService.java:777-800）

| 操作 | 现守卫 | 目标 |
|---|---|---|
| create 建单 | requireWarehouseAccess（仓储admin） | **requireProductionAccess（生产admin，新守卫）** |
| listShortageGoods 缺货识别 | requireWarehouseAccess | **requireProductionAccess** |
| delete 撤销申请 | requireWarehouseAccess + 申请人本人 | **申请人本人可撤**（去部门条件，见下） |
| process/updateArrivalPlan/arrive/arriveCancel/reject | requirePurchaseAccess（采购admin） | **不变** |
| confirmReceive/arriveReject | requireWarehouseConfirmAccess（仓储admin） | **不变** |
| createDraft 补料草稿 | requireProductionDraftAccess（生产admin） | **不变** |

## 范围

### 后端
- `PurchaseRequestService` 新增 `requireProductionAccess()` 守卫（`requireDeptAdminOrSuperAdmin(DEPT_PRODUCTION, "仅生产管理员可创建采购申请")`）；create（:305）与 listShortageGoods（:145）换守卫。
- **撤销死路修复（顺带解决 pending-retest.md 观察项 1）**：delete（:718-725）守卫改为「**申请人本人可撤**」——`request.operatorId == 当前用户 id`，不再叠加部门条件。理由：A 方案下建单人=生产管理员，若保留「仓储管理员+本人」双条件则无人能撤（补料草稿现状死路）；改为本人单一条件后生产可撤自己的普通申请与补料草稿。超管仍受业务写禁令约束不可撤。
- `sourceType` 语义不变：普通申请仍为 null、补料申请仍为 `production`（该字段此后含义=「是否绑生产任务单的补料」，不随创建部门变化；历史单据不受影响）。
- 错误文案同步：create/listShortageGoods 守卫消息、缺货识别入口的空态提示。

### 前端
- 路由 `business/purchase-request` meta（router/index.js:166-170）：deptCodes `warehouse,purchase` → `production,purchase,warehouse`（仓储保留只读+确认入库入口）。
- 「新建采购申请」「缺货识别建单」按钮 v-permission（PurchaseRequestView.vue:28-29）：deptCodes `warehouse` → `production`。
- 撤销按钮显示条件：仓储管理员判断 → **本人建的未在途单**（按 userId 比对申请 operatorId；user store 已有 userId，D117 范式）。
- 菜单（layout/index.vue）：生产菜单组加「采购申请」入口（命名对齐采购组「采购申请处理」→ 生产组叫「采购申请」）；仓储菜单组「采购申请」（:73）保留——页面里仓储可见的是确认入库队列与只读列表。
- GoodsView「匹配供应商」按钮（D109）不动——主数据匹配仍是仓储职责。
- 页面内角色相关文案微查一遍（如缺货识别空态「仓储可…」类措辞）。

### 测试
- 单测：生产建单过 / 仓储建单拒 / 缺货识别同口径 / 本人撤过、他人撤拒 / 补料草稿生产本人可撤（死路场景正向）/ 采购系守卫不回归。
- E2E 权限矩阵：生产建→采购认领到货→仓储确认入库全链 + 三角色负测。

## 验收
- 生产管理员能新建普通采购申请、用缺货识别建单、撤销自己建的申请（含补料草稿）；仓储管理员新建入口消失但仍能确认入库；采购链路无感知。
