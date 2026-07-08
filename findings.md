# 研究与发现 (Findings)

> 记录在规划与实施过程中对代码库、业务逻辑、依赖关系的研究发现。
> 每条发现标注来源文件与位置，便于回溯。

---

## 🏗️ 项目总体结构

- **架构：** 前后端分离
  - 后端：`back/`（Spring Boot + Maven，`pom.xml`、`src/`）
  - 前端：`front/`（Vue + Vite，`package.json`、`vite.config.js`）
  - 数据库：`db.sql`
- **核心业务域：** 认证授权、用户与部门管理、商品进销退存、库存预警、统计分析、工作要求闭环、历史单据审批作废/红冲、公告定向投放、站内消息、超时治理、AI 知识问答助手。

---

## 🔍 研究记录

### 阶段 3 调研：缺货识别与采购触发（2026-07-01）

**目标：** 复用现有资产，新增"采购申请单"层，打通 仓储识别缺货 → 采购申请 → 采购入库 链路。

#### 已有可复用资产（不要重造）

| 资产 | 位置 | 复用方式 |
|------|------|----------|
| `biz_purchase` 进货表（单表单行，create 时 `increaseStock` 直接 `stock = stock + qty`） | `back/.../entity/BizPurchase.java`、`service/PurchaseService.java` | 作为"采购入库"唯一入口；申请单转入库时调 `PurchaseService.create()` |
| `base_goods.warning_stock`（默认 10，已索引） | `db.sql`、`entity/BaseGoods.java` | 作为缺货识别阈值（stock ≤ warning_stock 即缺货）；`HomeService.countLowStockGoods()` 已用此条件 |
| `purchase` 部门 + `purchase_admin` / `purchase_employee` | `db.sql`、`AuthzService.DEPT_PURCHASE` | 采购申请单流转目标角色 |
| 主从表 + 状态机范式 | `BizPickList` / `BizPickListDetail` / `PickListService` | 申请单采用主从表（多商品），状态机复用同一套常量风格 |
| `CodeGenerator.purchaseNo()` = `PUR`+时间戳+3随机 | `common/util/CodeGenerator.java` | 新增 `purchaseRequestNo()` = `PR`+时间戳+3随机 |
| `MessageService.sendToDeptAdmins(deptId,title,content)` | `service/MessageService.java` | 申请单创建后推送采购 admin（参照 `sendSalesPendingConfirmToWarehouseAdmins`） |
| `@RequireAdmin` / `@AuditLog` / `@PreventDuplicateSubmit` | `common/annotation/` | Controller 直接复用 |
| 前端 `PurchaseView.vue`（查询/表格/对话框范式） | `front/src/views/business/PurchaseView.vue` | 新建 `PurchaseRequestView.vue` 镜像其结构 |
| `api/business.js` 采购接口封装 | `front/src/api/business.js` | 新增 purchaseRequest 系列接口 |
| 路由 deptCodes + layout 菜单 | `front/src/router/index.js`、`layout/index.vue` | 仓储菜单加"采购申请"，采购菜单加"采购申请处理" |

#### 关键代码位置

- `PurchaseService.create()` — 入库加库存逻辑（`wrapper.setSql("stock = stock + " + quantity)`），申请单转入库时调用
- `PurchaseService.increaseStock/decreaseStock` — 私有方法，库存增减
- `HomeService.countLowStockGoods()` (`HomeService.java:221`) — `stock <= warning_stock` 计数，缺货识别可复用同一条件
- `PickListService` STATUS_PENDING(1)/ISSUED(2)/DONE(3)/REJECTED(4) — 状态机常量风格参照
- `AuthzService`：`requireDeptAdminOrSuperAdmin(DEPT_PURCHASE)` / `requireAnyDeptAdminOrSuperAdmin(WAREHOUSE,PURCHASE)` — 权限收口

#### 参考文档设计指引（`document/wms系统改造参考参考资料.md`）

- 当前缺口：无仓储侧物料统计 / 缺货识别 / 采购触发环节
- 建议：仓储确认销售出库时自动识别缺货 → 生成 `biz_purchase_request` 采购申请单 → 流转采购 admin → 采购到货入库后回写申请单状态
- 复用范式：参照"工作要求"闭环（申请→采购→到货→入库）
- 新增表：`biz_purchase_request`；建议 `base_goods` 加"安全库存阈值"字段（**注：现有 `warning_stock` 已可承担此职责，倾向复用而非新增列**）
- 接口：`/api/business/purchase-requests/*`

#### 设计要点（待用户确认后写入决策）

- 申请单结构：主从表（多商品明细），对齐 PickList 范式
- 状态机：待采购(1) → 采购中(2) → 已入库(3) / 已驳回(4)
- 入库方式：复用 `PurchaseService.create()`，申请单明细逐条转 biz_purchase，全部入库后申请单→已入库
- 缺货识别：仓储 admin 在"采购申请"页查看 stock ≤ warning_stock 商品，勾选生成申请单（手动触发优先，自动触发留待后续）

---

## 阶段 5 调研：六项需求改进方案 (2026-07-08)

**触发：** 用户提 6 项需求（销售员商品销售/退货、价格偏离超管审批、采购拒绝备注、采购申请预计到货+认领人、采购员进退货、领料失败反馈销售）。4 个并行调研 agent 全量扫描代码库，逐项对照现状。

### 需求一：销售员商品销售 + 销售退货 —— ✅ 已具备

- `BizSales`/`SalesService`/`SalesView.vue`：销售 admin 建单（confirm_status=1 不扣库存）-> 仓储 admin 确认出库（=2 扣库存）。`SalesService.java:153-191`(create) / `:197-224`(confirm)。
- `BizSalesReturn`/`SalesReturnService`/`SalesReturnView.vue`：销售 admin 建退货单（=1 不减库存）-> 仓储 admin 确认入库（=2 加库存）。`SalesReturnService.java:103-144`(create) / `:149-177`(confirm)。
- 权限：create 限 sales dept admin；confirm 限 warehouse dept admin；超管全通；前端 `v-permission` 一致。
- **结论：** 销售 admin 已具备商品销售与销售退货，无结构性缺口。仅建单对话框未展示商品 `salePrice`（标准售价）供比对——与需求二相关。

### 需求二：销售价偏离售价 -> 超管审批 -> 仓库确认出库 —— 🆕 新增（主工作量）

**已具备可复用资产：**
- 标准售价 `base_goods.sale_price`（`db.sql:249`、`BaseGoods.java:31`）；实际售价 `biz_sales.unit_price`（`db.sql:368`、`BizSales.java:27`）；经 `goods_id` 关联，`SalesService.create` 已同时加载（`:158,161`，`resolveUnitPrice` fallback 即 `goods.getSalePrice()`）。
- 超管角色 `ROLE_SUPERADMIN`（`AuthzService.java:18`）+ `isSuperAdmin()`/`requireSuperAdmin()`（`:50-52,95-99`，已在 Audit/Security/Dept 服役）+ 种子账号 `superadmin/123456`（`db.sql:525`，全库唯一约束 `:29,40`）。
- 审批框架 `BizApprovalOrder`/`ApprovalService`/`ApprovalController`（`db.sql:453-492`）：含 biz_type/biz_id、before/after 快照、状态机、pending 唯一约束、审计日志。
- 确认出库门闸：`SalesService.confirm`（`:197-224`），`decreaseStock`（`:205`）之前即插入点；退货对称 `SalesReturnService.confirm`（`:149-177`，`increaseStock` 前 `:158`）。

**缺口（需新增）：**
1. 偏离阈值配置（无 config 表/字段；grep `priceDeviation`/`价格偏离` 零命中）。
2. `biz_sales` 标记"价格偏离待超管审批"的标志列（现仅 confirm_status 1/2、biz_status 1/2/3）。
3. 审批路由到**超管**：现 `ApprovalService.requireApprovalModuleAccess`（`:397-401`）路由到**仓储**，非超管。
4. 新 `request_action`（如 `price_deviation_confirm`）：现 `validateAction`（`:374-379`）仅 `void`/`void_red`；`executeVoidByApproval`（`:196-208`）只执行作废，不能执行 confirm。
5. confirm 前置校验：偏离且未经超管审批则拦截。
6. 销售员提交价格偏离审批的入口（`ensureRequesterCanSubmitApproval` `:381-395` 现仅处理本部门作废）。
7. 前端：建单展示标准售价 + 提交审批入口 + 超管审批列表/按钮。
8. `GoodsOptionVO` 未暴露 `salePrice`（UI 展示缺口）。

### 需求三：采购管理员拒绝采购 + 备注 —— ✅ 已具备

- `biz_purchase_request.reject_reason`（`db.sql:1127`）+ 实体 `rejectReason`（`BizPurchaseRequest.java:57`）。
- `PurchaseRequestService.reject()`（`:313-331`）：1->4 持久化 reason（`:325`）；DTO `@NotBlank`（`PurchaseRequestRejectDTO.java:9`）；仅采购 admin。
- 前端驳回对话框 textarea + 详情展示（`PurchaseRequestView.vue:204-214,164`）。
- **结论：** 已具备。**注意：** 仅 status=1（待采购）可驳回；已认领（status=2）不可驳回，仅 arriveCancel/arriveReject 回退至采购中。

### 需求四：采购申请预计到货时间 + 认领人 —— ⚠️ 部分具备

- **认领人：已具备**——`operator_id`/`operator_name`（`db.sql:1119-1120`）+ `operation_time`（认领时间 `:1121`）。`process()`（`PurchaseRequestService.java:156-177`）认领时写 `operatorId/Name=loginUser`（`:169-171`）。前端列表"采购处理人"列 + 详情"认领时间"（`PurchaseRequestView.vue:49,159`）。字段名 operator 非 claimer，功能即认领人。
- **预计到货时间：缺失**——`biz_purchase_request` 仅有实际 `arrive_time`（到货提交时写，`:1207`/`PurchaseRequestService.java:213`），无 `expected_arrival_time`；entity/VO/前端均无；grep `expected|预计到货` 零命中。
- **缺口：** 新增 `expected_arrival_time` 列 + entity/VO 字段 + 决定录入时机（认领时 `process()` 还是建单时 `create()`）+ 前端录入与展示。`process()` 现 controller 无 body（`PurchaseRequestController.java:60-63`），需加 DTO。

### 需求五：采购员采购进货 + 退货 —— ✅ 已具备（admin）

- `PurchaseService.create()`（`:172-200`）-> `requirePurchaseModuleAccess()` = **采购 admin 专属**（`:73-75`）；建单 confirm_status=1 不加库存。
- `PurchaseReturnService.create()`（`:123-157`）-> `requirePurchaseReturnModuleAccess()` = **采购 admin 专属**（`:71-73`）；建单 confirm_status=1 不减库存。
- 3.7 范式：采购建单 + 到货/确认退货成功；仓储确认入库/出库（动库存）。前端 `v-permission deptCodes:['purchase']` 控新建按钮。
- **结论：** 采购 admin 已具备进货与退货建单。**注意：** `role=employee` 的采购员工被路由 `roles:['admin']`（`router/index.js:59,65`）完全锁出。若"采购人员"含普通员工，则需放开员工权限（与 3.7 职责分离设计冲突，需定夺）。

### 需求六：生产领料失败反馈销售 —— ❌ 缺失

- `PickListService` **未注入 MessageService**（grep 零命中），`reject()`（`:208-225`）静默置 status=4，`issue()` 缺料（`:162-164` -> `decreaseStock` `:319-328` SQL `stock>=qty` 失败抛 400）整单回滚，**均无任何通知**。
- 可复用：`DEPT_SALES`（`AuthzService.java:22`）；`sendToDeptAdminsWithBiz`（`MessageService.java:387`，**private**）；`revokeUnreadByBiz`（`:274-283`）；`sourceSalesId` 可选关联（`BizPickList.java:28`，set 于 `:119`）；`BizSales.operatorId` 可定位销售员（`BizSales.java:46`）。
- **缺口：**
  1. `MessageService` 加 public `sendPickListFailureToSalesAdmins(...)`（对齐 D21，biz_type=`pick_list`）。
  2. `PickListService` 注入 MessageService（+ BizSalesMapper 若按销售员定向），`reject()` 成功后发反馈；`issue()` 缺料路径发反馈。
  3. `delete()`/终态调 `revokeUnreadByBiz("pick_list", id)`。
  4. 设计决策：缺料是临时异常（现 all-or-nothing 回滚），反馈策略——(a) 每次缺料异常即通知销售（可能噪音/重试竞态）；(b) 新增持久化"缺料"状态再通知（改动大）。
  5. 前端 `PickListView.vue:34` 仍对 sales dept 显示"新增领料"按钮，但后端 `requireApplyAccess`（`:253-256`）仓储专属——前后端不一致（d50849a 收口仓库改了路由/菜单，组件内按钮判定未同步），需顺手修。

### 需求-现状汇总

| # | 需求 | 现状 | 工作量 |
|---|---|---|---|
| 1 | 销售员商品销售+退货 | ✅ 已具备 | 仅 E2E 复核 |
| 2 | 价格偏离->超管审批->仓库确认 | 🆕 新增 | 大（主工作量） |
| 3 | 采购拒绝+备注 | ✅ 已具备 | 仅 E2E 复核 |
| 4 | 预计到货+认领人 | ⚠️ 认领人已有/预计到货缺失 | 小 |
| 5 | 采购员进退货 | ✅ 已具备（admin） | 仅 E2E 复核（员工范围待定） |
| 6 | 领料失败反馈销售 | ❌ 缺失 | 中 |

---

## ⚠️ 风险与注意事项

- 需求二审批机制选型（复用 BizApprovalOrder vs 轻量标志位）影响工作量与可追溯性，需用户定夺。
- 需求五若放开员工权限，与 3.7 职责分离（库存变更仓储收口）设计冲突，需谨慎。
- 需求六缺料反馈策略（实时通知 vs 持久化缺料态）影响状态机改动范围。
- d50849a（会话 8）未入 progress.md，需补登。

- 暂无

---

## 📚 参考资料

- 项目 README.md
- 文档目录：`document/`、`projectmd/`、`AImd/`
