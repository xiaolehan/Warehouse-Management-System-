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

## ⚠️ 风险与注意事项

- 暂无

---

## 📚 参考资料

- 项目 README.md
- 文档目录：`document/`、`projectmd/`、`AImd/`
