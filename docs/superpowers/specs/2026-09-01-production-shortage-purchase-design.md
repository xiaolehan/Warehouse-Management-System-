# 生产缺料 → 采购申请联动(Draft) 设计

> 日期：2026-09-01
> 状态：已与需求方沟通定稿（本 repo 内设计，非外部文档唯一来源）
> 范围：为 `00bfb22`「方案先行缺料」补上「缺料 → 采购申请 → 入库 → 回挂 → 开工」的闭环。

## 目标

生产任务单缺料时，把缺口结构化地带进现有采购申请链，仓储保建单/转正门槛；到货入库后 BOM 明细自动回挂，重算齐套即可开工。全程自动衔接，不手工重建采购申请。

## 背景与现状（`00bfb22`）

- 生产建任务单 → `ProductionOrderService.computeKit` 展开 BOM×数量，逐行算 `required/stock/deficit`，分级 `ok/partial/block`；有缺口发 `sendKitShortageToPurchaseAdmins` 直叮采购（D42）。
- `start()` 遇 `block` 阻断；`generatePickListAndIssue` 只发有库存行。
- 采购申请链已存在：仓储建单(`create`，强制 `goodsId`) → 采购认领(`process`) → 到货(`arrive`) → 仓储确认入库(`confirmReceive`, 加库存) → 回挂→重算。
- 回挂现状：在 `BomView` 手工给 BOM 明细选物料 `goodsId`。
- 缺口断点：缺料只「显示 + 直叮采购」，采购申请仍要人工去仓储模块建，未联动。

## 决策记录（问答确认）

1. **发起模型**：生产端生成「采购申请草稿」→ 仓储审核转正 → 走现有采购链。（采购申请建单门槛仍留仓储。）
2. **聚合粒度**：每张生产任务单一键生成一张草稿，含全部缺料行（一对一，溯源清晰）。
3. **数量口径**：每行默认 = 缺口(`deficit`，即 required − 库存)；生产端可手调。
4. **物料来源/回挂时机**：方案先行行(`goodsId` 为空)由仓储**转正时定物料**；BOM 明细 `goodsId` 回挂**放在确认入库时**（货真正进来才绑定）。
5. **表形态**：复用 `BizPurchaseRequest`(+`BizPurchaseRequestDetail`)，**加草稿状态**，转正即流转，不新建表。
6. **建单路径**：仓储手动建单 与 生产草稿 **两条独立建单路径**；仓储手动建单保持强校验 `goodsId`。
7. **消息**：版甲——草稿→叮仓储转正；转正→撤草稿通知+叮采购；草稿撤销/驳回→`revokeUnreadByBiz`；`create()` 直叮采购移除。
8. **草稿出口**：生产申请人(还 DRAFT)可撤销；仓储可驳回（置 rejected 留审计）。

## 数据与状态

### `biz_purchase_request`（复用，加列）
- 新状态 `STATUS_DRAFT = 6`（草稿）
- 新列 `source_type`：`production`(生产缺料) / `warehouse`(手动) — 区分建单入口，用于权限与过滤
- 新列 `production_order_id`：来源生产任务单（可空，仅 production 有值）— 溯源 & 回挂用

### `biz_purchase_request_detail`（复用，加列）
- `goodsId` 语义：草稿可空；仓储手动建单仍必填。
- 新列 `bom_detail_id`（可空）：记录该行对应 BOM 明细，确认入库回挂时用它定位 BOM 明细行。

### 状态机
```
[生产补料]     → createDraft(该行 goodsId 可空)
[仓储转正]     → confirmDraft: 补齐各行 goodsId + DRAFT→PENDING + 撤通知 + 叮采购 → 采购认领...
[草稿撤销]     → 生产申请人(还 DRAFT)撤销 / 仓储驳回(rejected) → revoke 通知
[采购/仓储链]  → process(认领)/arrive(到货)/confirmReceive(确认入库, 同事务回挂)
```

### 权限（模块内细分）
- 现有 `requireWarehouseAccess` = 仓储转正 + 仓储手动建单门槛（手动建单仍强 goodsId）。
- 新 `requireProductionDraftAccess`：**生产研发部** — 仅能建草稿 + 读/撤销**自家**草稿；不能读采购正式单流转、不能转正。
- 仓储转正时：对 `goodsId=null` 行**必填**校验，否则拒绝转正。
- 采购：认领/到货/入库（现有 `requirePurchaseAccess` / `requireWarehouseConfirmAccess`）。

## 消息生命周期（版甲）

- 生产一键补料 → 叮**仓储管理员**转正（plain 消息，带生产单号；biz=purchase_request, id=草稿 id）。
- 仓储转正 → `revokeUnreadByBiz("purchase_request", draftId)` 撤草稿通知 + 复用 `sendPurchaseRequestToPurchaseAdmins` 叮采购认领。
- 草稿撤销/驳回 → `revokeUnreadByBiz("purchase_request", draftId)`。
- `create()`（仓储手动建单）里 `sendKitShortageToPurchaseAdmins` **移除**：一旦一键补料生成草稿即接管通知；免采购收到"缺料"+“草稿待转正”两条。

## 核心应用逻辑

### A. 生成草稿（生产「一键补料」，新 `createDraft`）
- 入参：生产订单 id + 每行可手调申请数量（不传用缺口）。
- 缺料行来源**复用现成 `computeKit`**：取 `deficit>0` 行作为草稿行。
- 每行记：`component_name`、`goodsId`(可空)、`unitUsage`、`unit`、`bom_detail_id`、`申请数量`(默认缺口)。
- 落：`biz_purchase_request`(DRAFT, source=production, production_order_id) + 明细。叮仓储转正。

### B. 转正（仓储，新 `confirmDraft`）
- 校验 status=DRAFT + 各 `goodsId` 齐全（否则提示哪行没选物料）；置 PENDING。**此步不回挂**。
- 撤草稿通知 + 叮采购认领 → 进入现有采购链（认领/到货/入库零改动）。

### C. 确认入库回挂（现有 `confirmReceive` 扩展）
- 加库存同一事务内：对带 `bom_detail_id` 的行，把对应 BOM 明细 `goodsId` 写为该行物料——**仅当 BOM 明细原 `goodsId` 为空才写**（不覆盖已有回挂）。
- 库存增 + BOM 明细回挂后，齐套实时重算（`getById` 已实时调 `computeKit`）→ 翻绿可开工。

### D. 草稿撤销/驳回
- 生产申请人（DRAFT）撤销；仓储驳回（置 rejected，留审计）。都 `revokeUnreadByBiz`。

## 前端交互

### `ProductionOrderView.vue`（生产端）
- 状态 `partial/block` 的每行尾部加「+ 补料」；行选数量（默认缺口）→ 一起生成一张草稿。
- 整单「一键补料」：所有 `deficit>0` 行带默认缺口数量一键开草稿。
- 生成后任务单显示草稿状态(DRAFT) 与跳转链路说明。

### `PurchaseRequestView.vue`（仓储端）
- 列表页新增「草稿」过滤；草稿行显示来源生产单号与 source_type。
- 仓储打开草稿 → 每行 `goodsId=null` 的显示「待定物料」下拉（选现有 material）+「转正」「驳回」。

## 异常/幂等

- **一键补料幂等**：同生产单已存在 DRAFT 草稿（某行，`production_order_id`+DRAFT）→ 提示"已生成草稿"，不重复建（除非旧已转正/撤销置 rejected）。
- **转正冲突**：`confirmDraft` 用 `eq(status, DRAFT)` 乐观锁，若已转正/撤销则报"草稿状态已变更"。
- **回挂幂等**：`confirmReceive` 回挂仅 BOM 明细原 `goodsId` 为空才写，重复入库不覆盖。
- 所有装态流转带乐观锁（`eq(status, ...)`），连点/重试安全。

## 测试策略

- **后端**：`createDraft`/`confirmDraft`/回挂 三件 TDD。
  - 单测：goodsId 缺失拒转正；回挂仅空才写；草稿幂等防重（同一生产单不重复建 DRAFT）；撤销草稿撤物料通知。
- **前端**：`npm run build` + 生产/仓储双角色 E2E：
  生产建任务单(缺料) → 一键补料→草稿 → 仓储转正(补物料) → 采购认领→到货 → 仓储确认入库(回挂+加库存) → 重算齐套 → 开工。
- **验证**：后端 `./mvnw compile`（必要 `clean compile`）+ 端到端 curl（登录→建任务单→补料→转正→入库→回挂→开工→清理，恢复原库存）。

## 未来（YAGNI 缓做，不在首版）

- 多单合并补料、SMC1065 成品种子、缺料表红黄细化展示。