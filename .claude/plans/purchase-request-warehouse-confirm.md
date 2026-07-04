# 阶段 3.6：采购入库增加「仓储确认」环节

## 背景
当前采购申请流程：仓储建单(1待采购) → 采购认领(2采购中) → **采购直接转入库(3已入库，加库存)**。采购管理员认领后直接入库加库存，跳过仓储确认，与销售出库(D14)/销售退货入库(D22)的"仓储枢纽确认"范式不一致。库存变更应统一由仓储确认。

## 目标
采购到货后，采购管理员提交入库申请（**不加库存**，推送仓储）；仓储管理员确认后才入库加库存。对齐 D22 销售退货确认入库范式。

## 状态机（新）
- `1 待采购`（仓储建单）→ 推送采购 admin
- `2 采购中`（采购认领 process）
- `5 待入库确认`（采购到货 arrive，填数量+单价，不加库存，推仓储）【新增状态】
- `3 已入库`（仓储确认入库 confirm-receive，加库存，记 confirmer）
- `4 已驳回`（采购驳回 reject，仅 1 可驳）
- `5 → 2`：采购撤回到货 `arrive-cancel`（采购主动）**或** 仓储驳回到货 `arrive-reject`（仓储主动）

## 已确认设计决策
- 到货不符：仓储驳回回到采购中(2)，可循环纠错
- 数量+单价：采购到货时填，仓储确认不改（不符走驳回）
- 到货撤回：采购可主动撤回到货(5→2)重新提交

---

## 后端改动

### 1. DB（db.sql + 本地 ALTER）
`biz_purchase_request` 加 4 字段，`status` 注释加 5：
```sql
ALTER TABLE `biz_purchase_request`
  ADD COLUMN `arrive_time` DATETIME DEFAULT NULL COMMENT '采购到货提交时间' AFTER `operation_time`,
  ADD COLUMN `confirmer_id` BIGINT DEFAULT NULL COMMENT '入库确认人ID(仓储管理员)' AFTER `receive_time`,
  ADD COLUMN `confirmer_name` VARCHAR(50) DEFAULT NULL COMMENT '入库确认人姓名' AFTER `confirmer_id`,
  ADD COLUMN `confirm_time` DATETIME DEFAULT NULL COMMENT '仓储确认入库时间' AFTER `confirmer_name`;
ALTER TABLE `biz_purchase_request` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-待采购, 2-采购中, 3-已入库, 4-已驳回, 5-待入库确认';
```
- db.sql CREATE TABLE 同步加字段 + 改注释；末尾追加 ALTER 段（项目惯例，供存量库升级）
- 本地库执行 ALTER

### 2. Entity `BizPurchaseRequest.java`
加 `arriveTime / confirmerId / confirmerName / confirmTime`；status 注释加 5

### 3. Service `PurchaseRequestService.java`
- 加常量 `STATUS_AWAITING_CONFIRM = 5`
- **拆 `receive` 为两步**：
  - `arrive(id, dto)`：采购权限，校验 status=2；写明细 `unit_price`（到货价，逐条）；set `arrive_time`；CAS status 2→5；推送仓储消息；**不加库存**。复用 `PurchaseRequestReceiveDTO`
  - `confirmReceive(id)`：仓储权限，校验 status=5；逐条转 `biz_purchase` 加库存（用明细 quantity+unitPrice，复用 `PurchaseService.create`）；set `confirmer_id/name`+`confirm_time`；CAS status 5→3；`revokeUnreadByBiz("purchase_request", id)`
- **加 `arriveCancel(id)`**：采购权限，5→2，revoke 仓储消息
- **加 `arriveReject(id)`**：仓储权限，5→2，revoke 仓储消息
- `process(1→2)` / `reject(1→4)` / `delete(仅1)` **不变**
- `toVO` 加 arriveTime/confirmerName/confirmTime；`statusText` 加 5→"待入库确认"
- 权限：到货/撤回用 `requirePurchaseAccess`；确认入库/驳回到货用 `requireWarehouseAccess`（新增私有方法，复用 AuthzService.DEPT_WAREHOUSE）

### 4. Controller `PurchaseRequestController.java`
- 删 `PUT /{id}/receive`
- 加 `PUT /{id}/arrive`（采购，@RequireAdmin+@AuditLog+@PreventDuplicateSubmit）
- 加 `PUT /{id}/confirm-receive`（仓储）
- 加 `PUT /{id}/arrive-cancel`（采购）
- 加 `PUT /{id}/arrive-reject`（仓储）

### 5. MessageService.java
- 加 `sendPurchaseRequestArrivedToWarehouseAdmins(requestNo, operatorName, requestId)`：调 `sendToDeptAdminsWithBiz("purchase_request", requestId)`，推仓储"待确认采购入库"

### 6. VO `PurchaseRequestVO.java`
加 `arriveTime / confirmerId / confirmerName / confirmTime`；statusText 含 5

---

## 前端改动

### 1. `api/purchaseRequest.js`
- `receivePurchaseRequestAPI` → `arrivePurchaseRequestAPI`（`PUT /{id}/arrive`）
- 加 `confirmReceivePurchaseRequestAPI` / `arriveCancelPurchaseRequestAPI` / `arriveRejectPurchaseRequestAPI`

### 2. `views/business/PurchaseRequestView.vue`
- 状态选项加 `5 待入库确认`；`statusTagType` 加 5
- **采购侧操作**：status=2 → "到货提交"按钮（原"转入库"改名，调 arrive，复用对话框填数量+单价）；status=5 → "撤回到货"按钮（调 arriveCancel）
- **仓储侧操作**：status=5 → "确认入库"按钮（ElMessageBox 核对明细后调 confirmReceive）+ "驳回入库"按钮（调 arriveReject）
- 到货对话框标题改"采购到货提交"
- 详情对话框加 到货时间 / 确认人 / 确认时间
- 权限指令：到货/撤回 `deptCodes:['purchase']`；确认入库/驳回入库 `deptCodes:['warehouse']`

### 3. 路由/菜单
- 路由 deptCodes 已含 `warehouse+purchase`，不变
- 仓储菜单"采购申请"(layout line 64) 复用，**不新增菜单**（仓储在该页看 status=5 的单并确认）

---

## 验收（E2E）
1. 仓储建单(1) → 采购认领(2) → 采购到货(5)：**库存不变**，推送仓储消息（biz_type=purchase_request, biz_id）
2. 仓储确认入库(3)：库存+数量，confirm_time/confirmer 记录，消息撤
3. 采购撤回到货(5→2) → 重新到货(5)：撤回时消息撤、到货时重建
4. 仓储驳回入库(5→2) → 采购重新到货：消息撤
5. 到货后库存未变（确认前不动库存）
6. 非采购到货被拒 403；非仓储确认入库被拒 403
7. 测试数据清理、库存恢复
8. 前端 `npm run build` 通过

## 不在本次范围
- `reject` 不扩展到采购中(2)（采购中终止采购的边缘场景，保留待后续）
- 到货驳回/撤回不记 reason（退回采购中重新到货即可）
- 历史已入库(3)单据 `receive_time` 保留兼容，新流程用 `confirm_time`

## 文档
- `task_plan.md` 加阶段 3.6 + D26 决策
- `progress.md` 记会话
