# 任务规划 (Task Plan)

> 本文件用于记录阶段、进度与决策。会话中断后可据此恢复上下文。
> 最后更新：2026-07-01

---

## 📌 任务总览

**任务标题：** WMS 二次开发 — 生产领料模块

**目标描述：**
基于 `document/wms_v1.docx` 痛点 2（生产领料耗时久，错领漏领导致工期延长），在当前开源 WMS 上新增生产领料模块（领料/补料/退料 + 仓库发料 + 领料人确认复核），解决错领漏领问题。

**参考文档：**
- `document/wms系统改造参考参考资料.md`（§5.3 生产领料模块）
- `projectmd/生产领料模块开发任务清单.md`（可执行任务拆解）

**当前状态：** ✅ 阶段 1 + 2 + 3 完成（生产领料 + 销售下单协同 + 缺货识别与采购触发）

---

## 🗂️ 阶段划分

### 阶段 1：生产领料模块（当前）

#### 后端
- [x] B1 数据库建表 `biz_pick_list` + `biz_pick_list_detail`（db.sql 追加 + 执行）
- [x] B2 Entity `BizPickList.java` + `BizPickListDetail.java`
- [x] B3 Mapper `BizPickListMapper` + `BizPickListDetailMapper`
- [x] B4 DTO（Save/Detail/Query/Reject）
- [x] B5 VO `PickListVO` + `PickListDetailVO`
- [x] B6 编号生成器 `CodeGenerator.pickListNo()`
- [x] B7 Service（page/getById/create/issue/confirm/reject/delete）
- [x] B8 Controller `/business/pick-lists/*`
- [x] B9 编译与启动验证（E2E 全部通过）

#### 前端
- [x] F1 API 封装 `pickList.js`
- [x] F2 领料单列表与操作页 `PickListView.vue`
- [x] F3 路由与菜单（router + layout 销售/仓储菜单）
- [x] F4 权限指令（复用全局 v-permission）
- [x] F5 联调验证（build 通过 + Vite 代理 E2E 通过）

#### 验收
- [x] 模块级验收标准（见任务清单 §5）全部通过

### 阶段 2：销售下单协同（✅ 完成）

**决策（Q1–Q3 已确认）：**
- D11 图表只统计已确认出库（biz_status=1 AND confirm_status=2）
- D12 本期新增 customer_name（公司名）、contract_no（合同编号）字段，对齐 wms_v1 下单文档
- D13 销售建单后推送站内消息给仓储管理员
- D14 新增 confirm_status 字段（1待仓库确认/2已确认出库），不动 biz_status 语义；存量数据默认 2

#### 后端
- [x] S1 biz_sales 加列：confirm_status/confirm_time/confirmer_id/confirmer_name/customer_name/contract_no（db.sql + ALTER 本地库）
- [x] S2 BizSales entity 加 6 字段
- [x] S3 SalesSaveDTO 加 customerName/contractNo
- [x] S4 SalesVO 加 confirmStatus/confirmStatusText/confirmTime/confirmerName/customerName/contractNo
- [x] S5 SalesService.create()：confirm_status=1，不扣库存，发消息给仓储
- [x] S6 SalesService.confirm()：仓储确认，扣库存，confirm_status→2
- [x] S7 SalesService.delete()/voidDocument()：按 confirm_status 决定是否回补库存
- [x] S8 SalesService.returnableOptions() 加 confirm_status=2 过滤；SalesReturnService.ensureSourceSalesNormal 加 confirm_status=2
- [x] S9 SalesController 加 PUT /{id}/confirm（仓储）
- [x] S10 BizSalesMapper 图表 15 处 SQL 加 AND confirm_status=2
- [x] S11 MessageService 加 sendSalesPendingConfirmToWarehouseAdmins()
- [x] S12 编译与 E2E 验证（全通过）

#### 前端
- [x] F1 api/business.js 加 confirmSalesAPI
- [x] F2 SalesView.vue：加客户名/合同编号表单字段 + 确认状态列 + 仓储确认按钮
- [x] F3 路由 sales deptCodes 加 warehouse + 仓储菜单加"销售出库确认"入口
- [x] F4 联调验证（build + Vite 代理 E2E 通过）

### 阶段 3：缺货识别与采购触发（✅ 完成 — 链路：识别→申请→采购入库）

**链路：** 仓储识别缺货（stock ≤ warning_stock）→ 生成采购申请单（主从表）→ 推送采购 admin → 采购 admin 处理/转入库（复用 `biz_purchase`）→ 入库后回写申请单状态。

**复用资产：** `biz_purchase`(入库加库存) / `base_goods.warning_stock`(缺货阈值) / `purchase` 部门 / PickList 主从表+状态机范式 / `MessageService.sendToDeptAdmins`。

#### 后端
- [x] P1 数据库建表 `biz_purchase_request` + `biz_purchase_request_detail`（db.sql 追加 + 本地库执行）
- [x] P2 Entity `BizPurchaseRequest.java` + `BizPurchaseRequestDetail.java`
- [x] P3 Mapper `BizPurchaseRequestMapper` + `BizPurchaseRequestDetailMapper`
- [x] P4 DTO（Save 多明细 @Valid 嵌套 / Detail / Query / Reject / Receive）
- [x] P5 VO `PurchaseRequestVO` + `PurchaseRequestDetailVO`（含 statusText）
- [x] P6 CodeGenerator.purchaseRequestNo() = `PR`+时间戳+3随机
- [x] P7 Service：page/getById/shortageGoods/create/process/receive(转 biz_purchase)/reject/delete
- [x] P8 Controller `/business/purchase-requests/*`，@RequireAdmin + @AuditLog + @PreventDuplicateSubmit
- [x] P9 缺货识别查询 `GET /business/purchase-requests/shortage-goods`
- [x] P10 编译与启动验证（E2E 全部通过）

#### 前端
- [x] F1 API 封装 `purchaseRequest.js`（page/detail/shortageGoods/create/process/receive/reject/delete）
- [x] F2 `PurchaseRequestView.vue`：缺货勾选建单 + 列表 + 详情 + 转入库/驳回对话框
- [x] F3 路由与菜单：仓储菜单加"采购申请"，采购菜单加"采购申请处理"
- [x] F4 权限指令（v-permission：建单限仓储 admin，认领/入库/驳回限采购 admin）
- [x] F5 联调验证（build 通过 + Vite 代理 E2E 通过）

#### 验收
- [x] 缺货识别准确（stock ≤ warning_stock，返回胖乐炒菜机 stock=5/warning=10）
- [x] 仓储建单后推送采购 admin 站内消息（sendPurchaseRequestToPurchaseAdmins）
- [x] 采购认领 → 转入库 → biz_purchase 生成(PUR260701225350744) + 库存 5→15(+10) + 申请单状态回写已入库(status=3)
- [x] 非采购 admin 建单被拒（sales_admin → 403 "仅仓储管理员可识别缺货并创建采购申请单"）
- [x] 重复入库被防抖拦截（400 "请勿重复提交入库请求"）
- [x] 测试数据清理，库存恢复 5

### 阶段 4：库存治理与追溯（待启动）
- 盘点 / 余料 / 成品追溯

---

## ✅ 关键决策记录

| 编号 | 决策内容 | 理由 | 日期 |
|------|----------|------|------|
| D1 | 客户需求仅以 `wms_v1.docx` 为唯一来源 | 用户明确指示，不考虑上一轮其他文档 | 2026-06-28 |
| D2 | 生产领料作为第一个开发模块 | 纯新增、不改动现有逻辑、风险最低、价值直接 | 2026-06-28 |
| D3 | 领料单采用单表单行模式（对齐 biz_sales） | 与现有单据范式一致；多行明细待 Q2 确认 | 2026-06-28 |
| D4 | 状态机：待发料→已发料→已完成 / 已驳回；退料直接入库 | 复核环节解决错领漏领；退料无中间态 | 2026-06-28 |
| D5 | 发料/驳回权限收口仓储管理员 | 对齐 wms_v1 仓库枢纽角色，复用 DEPT_WAREHOUSE | 2026-06-28 |
| D6 | 申请权限限定销售/仓储 admin（Q1） | 销售驱动领料的最小可用集，不新增生产角色 | 2026-06-28 |
| D7 | 领料单采用主从表，支持多商品明细（Q2） | 贴合真实领料多物料场景；参照工作要求主从模式 | 2026-06-28 |
| D8 | 关联销售单为可选（Q3） | 灵活度优先，source_sales_id 可空 | 2026-06-28 |
| D9 | 退料需仓储确认，统一走 待发料→已发料→已完成（Q4） | 管控更严，防乱退料；发料动作即入库 | 2026-06-28 |
| D10 | 本期不做领料单打印（Q5） | 预留，优先核心流程 | 2026-06-28 |
| D11 | 图表只统计已确认出库（biz_status=1 AND confirm_status=2） | 排除待确认单干扰统计 | 2026-06-30 |
| D12 | 销售单加 customer_name/contract_no 字段 | 对齐 wms_v1 下单文档 | 2026-06-30 |
| D13 | 销售建单后推送站内消息给仓储管理员 | 协同触发，复用 MessageService | 2026-06-30 |
| D14 | 新增 confirm_status（1待确认/2已出库），不动 biz_status，存量默认 2 | 语义清晰，存量兼容 | 2026-06-30 |
| D15 | 缺货识别采用手动勾选（仓储 admin 查看 stock ≤ warning_stock 商品，勾选生成申请单） | 可控低噪音，自动触发留待后续增量 | 2026-07-01 |
| D16 | 采购申请单采用主从表（多商品明细），对齐 PickList 范式 | 一次缺货常涉及多物料，参照 D7 先例 | 2026-07-01 |
| D17 | 采购入库复用 PurchaseService.create()，逐明细转 biz_purchase | 库存变更唯一入口，可追溯 | 2026-07-01 |
| D18 | 状态机 待采购(1)→采购中(2)→已入库(3)/已驳回(4)；驳回即终态 | 简单清晰，重新申请需新建单 | 2026-07-01 |
| D19 | 保留"采购中"中间态（采购 admin 认领→采购中，到货→转入库） | 区分已接单未到货与已入库，便于跟踪 | 2026-07-01 |
| D20 | 缺货阈值复用 base_goods.warning_stock，不新增 safe_stock 列 | 已有索引 + HomeService 已用，避免冗余 | 2026-07-01 |

---

## ⚠️ 待确认问题

### 阶段 1（已确认）
Q1–Q5 已全部确认，结论见决策记录 D6–D10。

### 阶段 2（已确认）
Q1–Q3 已全部确认，结论见决策记录 D11–D14。

### 阶段 3（已确认 2026-07-01）

Q1–Q6 已全部确认，结论见决策记录 D15–D20。无阻塞项。

---

## 📊 总体进度

- 完成度：阶段 1 + 2 + 3 编码 100%（后端 + 前端 + 联调验收通过）
- 当前阶段：阶段 3 完成，阶段 4 待启动
- 阻塞项：无

## 🧪 验收结果（阶段 3 缺货识别与采购触发）

| 测试项 | 结果 |
|---|---|
| 缺货识别（stock ≤ warning_stock） | ✅ 返回胖乐炒菜机 stock=5/warning=10 |
| 仓储建单 + 推送采购 admin 消息 | ✅ PR260701225350315 创建，sendPurchaseRequestToPurchaseAdmins |
| 采购认领（待采购→采购中） | ✅ status=1→2，认领人=采购管理员 |
| 转入库复用 biz_purchase 加库存 | ✅ PUR260701225350744 生成，库存 5→15(+10) |
| 状态回写已入库 | ✅ status=3，receiveTime 已记，明细 unitPrice=5.50 回写 |
| 非采购 admin 建单被拒 | ✅ sales_admin → 403 |
| 重复入库防抖拦截 | ✅ 400 "请勿重复提交入库请求" |
| 前端 build | ✅ PurchaseRequestView.js 12.58kB 无错误 |
| Vite 代理 E2E | ✅ 5173/api 代理 8080，login+shortage-goods 200 |
| 测试数据已清理 | ✅ 库存恢复 5，申请单/明细/进货单清空 |

## 🧪 验收结果（阶段 2 销售下单协同）

| 测试项 | 结果 |
|---|---|
| 建单 confirm_status=1 不扣库存 | ✅ 库存不变(500) |
| 仓储确认出库扣库存 | ✅ 状态→2，库存 495 |
| 重复确认被防抖拦截 | ✅ 400 |
| sales_admin 确认待确认单被拒 | ✅ 400 权限拦截，未扣库存 |
| 待确认单不能退货 | ✅ 400 "尚未确认出库" |
| 已确认单退货回补库存 | ✅ 495→497 |
| 图表只统计已确认出库 | ✅ 15 处 SQL 加 confirm_status=2 |
| 建单推送仓储站内消息 | ✅ sendSalesPendingConfirmToWarehouseAdmins |
| 前端 build + Vite 代理 E2E | ✅ VO 返回 confirmStatusText |
| 测试数据已清理 | ✅ 库存恢复 500 |

## 🧪 验收结果（阶段 1）

| 测试项 | 结果 |
|---|---|
| 多商品建单（PICK） | ✅ status=1，明细入库 |
| 仓储发料扣库存 | ✅ 500→498，40→39 |
| 申请人确认收货 | ✅ status=3，时间已记 |
| 退料发料回流入库 | ✅ 498→503 (+5) |
| 缺料发料整单回滚 | ✅ 400 错误，库存不变 |
| 销售 admin 发料被拒 | ✅ 403 |
| 仓储驳回 | ✅ status=4，原因已记 |
| 前端 build | ✅ 无语法错误 |
| Vite 代理 E2E | ✅ create→issue→confirm→page 全 200 |
| 测试数据已清理 | ✅ 库存恢复，领料单清空 |
