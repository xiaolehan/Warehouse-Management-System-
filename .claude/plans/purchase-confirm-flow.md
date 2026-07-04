# 阶段 3.7：商品进货 / 商品退货增加确认环节

## 背景
- **商品进货**（biz_purchase）：采购建单 → 直接 `increaseStock` 加库存，无确认。
- **商品退货**（biz_purchase_return）：采购建单 → 直接 `decreaseStock` 减库存，无确认。
两者均缺仓储确认环节，与销售出库 D14 / 销售退货 D22 / 采购申请入库 D26 的"仓储枢纽确认"范式不一致。

## 已确认设计
- **商品进货三步**：建单(不加库存) → 采购到货确认(推仓储,不加库存) → 仓储确认入库(加库存)
- **商品退货三步**：建单(通知仓储,不减库存) → 仓储确认出库(减库存) → 采购确认退货成功(终态)
- **改名**："进货退货" → "商品退货"
- 对齐 D14/D22 范式：`bizStatus`(1正常/2作废/3红冲) 不动，新增 `confirm_status` 表达入库/出库确认流程

---

## A. 商品进货

### 状态机（confirm_status）
`1 待到货`(建单) → `2 待入库确认`(采购到货确认,推仓储) → `3 已入库`(仓储确认入库,加库存)

### DB（biz_purchase 加字段）
```sql
ALTER TABLE `biz_purchase`
  ADD COLUMN `confirm_status` TINYINT NOT NULL DEFAULT 3 COMMENT '入库确认: 1-待到货, 2-待入库确认, 3-已入库' AFTER `biz_status`,
  ADD COLUMN `arrive_time` DATETIME DEFAULT NULL COMMENT '采购到货确认时间' AFTER `operation_time`,
  ADD COLUMN `confirmer_id` BIGINT DEFAULT NULL COMMENT '入库确认人ID(仓储)' AFTER `arrive_time`,
  ADD COLUMN `confirmer_name` VARCHAR(50) DEFAULT NULL COMMENT '入库确认人姓名' AFTER `confirmer_id`,
  ADD COLUMN `confirm_time` DATETIME DEFAULT NULL COMMENT '仓储确认入库时间' AFTER `confirmer_name`,
  ADD KEY `idx_purchase_confirm_status` (`confirm_status`);
```
- db.sql CREATE TABLE 同步加字段；末尾追加 ALTER 7.5
- 存量数据 `confirm_status` 默认 3（已入库兼容，因存量建单即加库存）

### 后端
- **Entity BizPurchase**：加 confirmStatus / arriveTime / confirmerId / confirmerName / confirmTime
- **PurchaseService**：
  - `create`：设 confirm_status=1，**移除 increaseStock**（不加库存），不发消息（待到货）
  - `arrive(id)`：采购权限，校验 status=1，set arrive_time，CAS 1→2，推仓储消息
  - `confirmReceive(id)`：仓储权限，校验 status=2，`increaseStock` 加库存，set confirmer/confirm_time，CAS 2→3，revoke 消息
  - `delete`：仅 confirm_status=1 可删（未入库），**不减库存**；已入库不可删走作废
  - `voidDocument`：confirm_status=3 作废 `decreaseStock` 回冲；<3 作废不减库存
  - `returnableOptions`：加 `confirm_status=3` 过滤（仅已入库进货单可退）
  - `createInternal`（D26 拆出的无权限方法）：confirmReceive 复用它加库存？**不**——商品进货确认入库直接用本类 `increaseStock`，不生成 biz_purchase（自身就是 biz_purchase）；createInternal 是采购申请入库复用生成 biz_purchase 的，与此不同。
- **MessageService**：加 `sendPurchaseArrivedToWarehouseAdmins(purchaseNo, operatorName, purchaseId)`，绑 biz_type="purchase"
- **Controller** `/business/purchases`：加 `PUT /{id}/arrive`（采购）、`PUT /{id}/confirm-receive`（仓储）
- **VO PurchaseVO**：加 confirmStatus / confirmStatusText / arriveTime / confirmerName / confirmTime

### 前端
- **PurchaseView.vue**：状态列（confirmStatusText）+ 操作列（confirm_status=1→"到货确认"采购；=2→"确认入库"仓储）+ 详情加到货时间/入库确认人/入库时间
- **路由** `business/purchase` deptCodes `['purchase']` → `['purchase','warehouse']`
- **仓储菜单**加"进货入库确认"入口（/business/purchase，对齐"销售出库确认"）

---

## B. 商品退货

### 状态机（confirm_status）
`1 待出库确认`(建单,通知仓储,不减库存) → `2 待退货确认`(仓储确认出库,减库存) → `3 已退货`(采购确认退货成功,终态)

### DB（biz_purchase_return 加字段）
```sql
ALTER TABLE `biz_purchase_return`
  ADD COLUMN `confirm_status` TINYINT NOT NULL DEFAULT 3 COMMENT '退货确认: 1-待出库确认, 2-待退货确认, 3-已退货' AFTER `biz_status`,
  ADD COLUMN `confirmer_id` BIGINT DEFAULT NULL COMMENT '出库确认人ID(仓储)' AFTER `operation_time`,
  ADD COLUMN `confirmer_name` VARCHAR(50) DEFAULT NULL COMMENT '出库确认人姓名' AFTER `confirmer_id`,
  ADD COLUMN `confirm_time` DATETIME DEFAULT NULL COMMENT '仓储确认出库时间' AFTER `confirmer_name`,
  ADD COLUMN `completer_id` BIGINT DEFAULT NULL COMMENT '退货完成确认人ID(采购)' AFTER `confirm_time`,
  ADD COLUMN `completer_name` VARCHAR(50) DEFAULT NULL COMMENT '退货完成确认人姓名' AFTER `completer_id`,
  ADD COLUMN `complete_time` DATETIME DEFAULT NULL COMMENT '采购确认退货成功时间' AFTER `completer_name`,
  ADD KEY `idx_return_confirm_status` (`confirm_status`);
```
- db.sql CREATE TABLE 同步加字段；末尾追加 ALTER 7.6
- 存量数据 `confirm_status` 默认 3（已退货兼容）

### 后端
- **Entity BizPurchaseReturn**：加 confirmStatus / confirmerId / confirmerName / confirmTime / completerId / completerName / completeTime
- **PurchaseReturnService**：
  - `create`：设 confirm_status=1，**移除 decreaseStock**（不减库存），推仓储消息（待出库确认）
  - `confirmOut(id)`：仓储权限，校验 status=1，`decreaseStock` 减库存，set confirmer/confirm_time，CAS 1→2，revoke 消息
  - `complete(id)`：采购权限，校验 status=2，set completer/complete_time，CAS 2→3（终态，不动库存）
  - `delete`：仅 confirm_status=1 可删（未出库，未减库存），不减库存
  - `voidDocument`：confirm_status≥2 作废 `increaseStock` 回补；=1 作废不减库存
  - `validateReturnableQuantity`：统计已退数量加 `confirm_status>=2` 过滤（已出库才占可退额度；待出库确认=1 的不占）
- **MessageService**：加 `sendPurchaseReturnPendingConfirmToWarehouseAdmins(returnNo, operatorName, returnId)`，绑 biz_type="purchase_return"
- **Controller** `/business/purchase-returns`：加 `PUT /{id}/confirm-out`（仓储）、`PUT /{id}/complete`（采购）
- **VO PurchaseReturnVO**：加 confirmStatus / confirmStatusText / confirmerName / confirmTime / completerName / completeTime

### 前端
- **PurchaseReturnView.vue**：状态列 + 操作列（=1→"确认出库"仓储；=2→"确认退货成功"采购）+ 详情加出库确认人/时间/退货完成人/时间
- **路由** `business/purchase-return` deptCodes `['purchase']` → `['purchase','warehouse']`
- **采购菜单**"进货退货" → "商品退货"（layout line 33）
- **仓储菜单**加"商品退货出库确认"入口（/business/purchase-return）

---

## 消息生命周期（D21 范式，CLAUDE.md 关键约束）
- 商品进货到货 → 推仓储（biz_type="purchase"），confirmReceive/delete/void 撤
- 商品退货建单 → 推仓储（biz_type="purchase_return"），confirmOut/complete/delete/void 撤

## 图表/统计检查
- HomeService / BizPurchaseMapper 若统计进货金额/数量，应加 `confirm_status=3` 过滤（仅已入库）— 实施时 grep 检查并调整
- BizPurchaseReturnMapper 统计退货应加 `confirm_status>=2`（已出库）

## 验收（E2E）
**商品进货**：建单(1,库存不变) → 到货(2,推仓储,库存不变) → 仓储确认入库(3,库存+,确认人/时间,消息撤) ✅
**商品退货**：建单(1,通知,库存不变) → 仓储确认出库(2,库存-,确认人/时间) → 采购确认退货成功(3,终态) ✅
**权限**：采购不能确认入库/出库（403）；仓储不能到货/建退货（403）
**delete/void**：未入库/未出库状态删/作废不动库存；已入库/已出库作废回冲
**菜单**：采购"商品退货"改名 + 仓储新增"进货入库确认""商品退货出库确认"两入口
测试数据清理、库存恢复

## 不在范围
- 商品进货到货确认不改数量（按建单数量入库；到货数量≠采购数量的场景后续可加 arrive_quantity）
- 商品退货无采购撤回/仓储驳回中间态（建单后只能走完或作废，简化）
- 历史单据作废/红冲审批流（createApprovalOrderAPI）不变

## 文档
- task_plan.md 加阶段 3.7 + D27/D28 决策
- progress.md 记会话
- CLAUDE.md 不需改（消息生命周期已覆盖）
