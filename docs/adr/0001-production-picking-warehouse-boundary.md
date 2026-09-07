# 生产补料去仓储 gate，领料出库前置开工

> 状态：accepted（取代 docs/superpowers/specs/2026-09-01-production-shortage-purchase-design.md 的"仓储转正门槛/仓储定物料/开工自动发料"）

注入库存变化必须经仓储确认的纪律，把"生产补料"与"生产领料"从仓储侧的主动行为，改为生产端主导、仓储只做极简出库确认的闭环（决策标签 D59）。

## 背景

现状（D59，2026-09-01 spec 定的 Draft 方案）：生产补料生成采购申请**草稿**，仓储转正（confirm-draft）才进待采购；方案先行 BOM 行由仓储转正时定物料；开工时 `ProductionOrderService.start()` 自动生成领料单并自动扣库存（`generatePickListAndIssue`），无仓储存量出账。

产品方确认不需要这层仓储 gate：补料应直接待采购，缺料件由生产在补料弹窗自选绑定；开工必须先完成"生产申请领料 → 仓储确认出库"。

## 决策

1. **补料＝采购申请，生产发起即待采购**。`sourceType=production` 的采购申请**不经仓储转正**（去掉 confirm-draft 对 production 来源的依赖），发起即 `STATUS_PENDING` 进采购链。仓储手动建单（`sourceType=warehouse`）仍保持原有门槛不变。
2. **缺料件由生产在补料弹窗绑定**。方案先行 BOM 行（`goods_id` 空）的物料选择从"仓储转正是定料"移到"生产提交补料时"；全部缺料行选齐才允许提交。
3. **开工前置 = 领料已全额出库**。`start()` 删除自动发料（`generatePickListAndIssue`），改为校验：该生产单存在一张已创建的领料单、且已全额出库（status=ISSUED）。不计较是否缺料，正常生产也强制走"申请领料 → 仓储确认出库 → 开工"。
4. **仓储端极简角色**。仓储不发起/不创建/不审批生产补料与生产领料，只对已提交的领料单做「确认出库」扣库存（复用 `biz_pick_lists` / `PickListService.issue()`，PICK 类型 `decreaseStock`，库存不足整单回滚）。仓储手动建的 PICK/SUPPLY/RETURN 旧菜单仅保留退料与杂项场景。

## Consequence

- `biz_purchase_request` 状态机：production 来源跳过 `DRAFT→confirmDraft`，直接用 待采购→采购中→待入库确认→已入库。
- `ProductionOrderService.start()` 校验逻辑与自动发料移除，齐套口径改由"领料单已出库"驱动。
- 消息：补料齐套→叮生产、领料提交→叮仓储出库、出库完成→叮生产可开工，均走 `sendToDeptAdminsWithBiz`，撤销/终态 `revokeUnreadByBiz`。
- 取代 D59-Draft 方案：不再需要仓储转正 gate、仓储定料、开工自动发料。