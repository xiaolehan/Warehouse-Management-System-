# 任务规划 (Task Plan)

> 本文件用于记录阶段、进度与决策。会话中断后可据此恢复上下文。
> 最后更新：2026-09-11

---

## 📌 任务总览

**任务标题：** WMS 二次开发 — 生产领料模块

**目标描述：**
基于 `document/wms_v1.docx` 痛点 2（生产领料耗时久，错领漏领导致工期延长），在当前开源 WMS 上新增生产领料模块（领料/补料/退料 + 仓库发料 + 领料人确认复核），解决错领漏领问题。

**参考文档：**
- `document/wms系统改造参考参考资料.md`（§5.3 生产领料模块）
- `projectmd/生产领料模块开发任务清单.md`（可执行任务拆解）

**当前状态：** ✅ 阶段 1–3.7、5–8（六项需求改进全部落地）、阶段 9–13（生产研发部产线：部门角色/BOM/生产任务单+齐套预警/质检/生产端迁移，commit 490a489）、阶段 14–17（D60–D63 增量完善）、阶段 18–21 全部完成；最近收尾：会话 27 三项体验优化（生产端预警中心/消息跳转+浮窗/首页预警数字红色，commit e395161，2026-09-11）

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

### 阶段 3.5：增量功能完善（✅ 完成 — 2026-07-04 会话 4）

用户巡检/反馈驱动的增量完善：新增「生产入库」模块 + 采购申请手动建单 + 销售退货确认误提示修复 + 商品重复添加释疑 + 运维教训归档。

#### 生产入库（新模块，仓储管理员自产零件入库）
- 范式对齐进货（`biz_purchase`/`PurchaseService`），但归属仓储部门、无供应商、生产单价可选、作废为仓储直接作废（不走跨部门审批）。

##### 后端
- [x] P1 数据库建表 `biz_production`（unit_price/total_price 可空，db.sql 追加 + 本地执行）
- [x] P2 Entity `BizProduction.java`
- [x] P3 Mapper `BizProductionMapper`
- [x] P4 DTO `ProductionSaveDTO` + `ProductionQueryDTO`
- [x] P5 VO `ProductionVO`
- [x] P6 CodeGenerator.productionNo() = `PRO`+时间戳+3随机
- [x] P7 ProductionService（page/getById/create/delete/voidDocument，含 increaseStock/decreaseStock 私有助手）
- [x] P8 ProductionController `/business/production/*`（@PreventDuplicateSubmit+@AuditLog+@RequireAdmin）
- [x] P9 编译与启动验证（E2E 全通过）

##### 前端
- [x] F1 API 封装（api/business.js 加 5 接口）
- [x] F2 `ProductionView.vue`（列表/新增/查看/当天删除/历史作废+红冲，生产单价可选）
- [x] F3 路由 `business/production`（deptCodes warehouse）+ 仓储菜单加「生产入库」（Download 图标，置于商品资料管理与生产领料之间）

#### 采购申请手动建单（纯前端增强，后端无改动）
- [x] F4 `PurchaseRequestView.vue` 加「新建采购申请」按钮 + 手动建单对话框（下拉选任意在售商品 getGoodsOptionsAPI、可增删多行、填数量/备注，提交复用 createPurchaseRequestAPI）；保留原「缺货识别建单」为快捷方式；含重复商品/未选/数量校验

#### 销售退货确认误提示修复（纯前端 bugfix）
- [x] F5 `SalesReturnView.vue` handleConfirm 删除多余的 `await loadSourceSalesOptions()`（该调用对仓储确认入库无意义，403 经 .catch 弹 ElMessage.error 误报「仅销售部门管理员可访问销售模块」），确认后仅 loadList()

#### 商品资料管理重复添加释疑（无代码改动）
- [x] 调研结论：`GoodsService.create()` 走 checkGoodsNameUnique（goods_name 精确去重，utf8mb4_unicode_ci 大小写不敏感），重名抛 400「商品名称已存在」——主数据唯一性，正确。补货走入库交易（进货/采购申请/生产入库），不在商品资料管理重复添加。

#### 文档
- [x] D 运维教训归档至 `CLAUDE.md`（6 条：跨 commit 切分支/merge 前停 dev、pkill -f 自杀陷阱、非交互 push 认证、fine-grained PAT 写权限、glm-5.2 bash 分类器宕机、PR 合并后同步 main+清分支）

#### 验收
- 生产入库建单（带单价 200 / 不带单价 200 unitPrice=null）/ 库存 103→111(+8) / sales_admin 建单 403「仅仓储管理员可访问生产入库模块」/ 当天删除回冲库存恢复 103 ✅
- 采购申请手动建单：对非缺货商品（三星24英寸显示器）建单 200 → PR260704... status=1 明细×7 → 撤销清理 200 ✅（无后端/DB 改动）
- 销售退货确认入库不再误弹 403 ✅
- 前端 build ✅ / Vite 代理 E2E ✅
- 测试数据已清理 ✅

### 阶段 3.6：采购入库增加仓储确认环节（✅ 完成 — 2026-07-04 会话 6）

采购认领后不再直接转入库，改为：采购到货提交（不加库存，推仓储）→ 仓储确认入库（加库存）。对齐 D22 销售退货确认入库范式。

**状态机（新）：** 1待采购 → 2采购中(认领) → 5待入库确认(到货,不加库存) → 3已入库(仓储确认,加库存)；5→2 可由采购撤回到货或仓储驳回入库；1→4 采购驳回。

#### 后端
- [x] DB：biz_purchase_request 加 arrive_time/confirmer_id/confirmer_name/confirm_time + status 注释加 5；明细加 arrive_quantity（db.sql CREATE + ALTER 7.3/7.4 + 本地执行）
- [x] Entity/VO 加字段（BizPurchaseRequest + BizPurchaseRequestDetail + VO）
- [x] PurchaseRequestService：拆 receive 为 arrive(2→5 不加库存,推仓储) + confirmReceive(5→3 加库存)；加 arriveCancel/arriveReject(5→2)；requireWarehouseConfirmAccess
- [x] PurchaseService 拆 createInternal(无权限校验) 供 confirmReceive 复用（operator=仓储确认人），保留 D17 biz_purchase 追溯
- [x] MessageService 加 sendPurchaseRequestArrivedToWarehouseAdmins（绑 biz）
- [x] Controller：删 receive，加 arrive/confirm-receive/arrive-cancel/arrive-reject

#### 前端
- [x] api：arrive + confirmReceive + arriveCancel + arriveReject
- [x] PurchaseRequestView：状态加 5；操作列（到货提交/撤回到货/确认入库/驳回入库）；对话框改"采购到货提交"；详情加到货时间/入库确认人/入库时间
- [x] 路由/菜单复用（仓储"采购申请"页，不新增菜单）

#### 验收（E2E 全通过）
- 主流程：建单→认领→到货(status5,库存不变,推仓储)→确认入库(status3,库存+5,confirmer=仓储管理员,消息撤) ✅
- 退回：到货→采购撤回(5→2)→重新到货(5)→仓储驳回(5→2)，库存不变 ✅
- 权限：purchase confirm-receive 403 / warehouse arrive 403 ✅
- 测试数据清理，库存恢复 ✅

#### 踩坑
- confirmReceive 首版 loginUser 声明在 for 循环后导致前向引用（javac 增量跳过未报错，IDE JDT 编译生成带错误标记的 class，运行时 `Unresolved compilation problem: loginUser cannot be resolved`）。修复：loginUser 移到循环前 + `mvnw clean compile` 强制全编译。
- 旧后端进程占 8080 致新后端启动失败（fuser -k 未杀净），需 `kill -9 <pid>` 强制清理。
- 采购到货数量可能 ≠ 申请数量，明细加 arrive_quantity 字段存到货数量（不覆盖申请数量，保留追溯）。

### 阶段 3.7：商品进货/商品退货增加确认环节（✅ 完成 — 2026-07-04 会话 7）

商品进货建单不再直接入库，商品退货建单不再直接减库存，均改为仓储确认范式。+"进货退货"改名"商品退货"。

**状态机（confirm_status，bizStatus 不动）：**
- 商品进货：1待到货 → 2待入库确认(到货) → 3已入库(仓储确认加库存)
- 商品退货：1待出库确认(建单通知) → 2待退货确认(仓储确认出库减库存) → 3已退货(采购确认退货成功)

#### 后端
- [x] DB：biz_purchase 加 confirm_status/arrive_time/confirmer_id/name/confirm_time；biz_purchase_return 加 confirm_status/confirmer_id/name/confirm_time/completer_id/name/complete_time（db.sql CREATE + ALTER 7.5/7.6 + 本地执行；存量默认 3）
- [x] Entity/VO 加字段
- [x] PurchaseService：create 不加库存设 1；arrive(1→2 推仓储)；confirmReceive(2→3 加库存)；delete 仅 1 可删；void 按 confirm_status；returnableOptions 加 confirm_status=3 过滤；createInternal 设 3（采购申请入库兼容）
- [x] PurchaseReturnService：create 不减库存设 1 推仓储；confirmOut(1→2 减库存)；complete(2→3 终态)；delete 仅 1 可删；void 按 confirm_status；validateReturnableQuantity 加 confirm_status>=2 过滤
- [x] MessageService 加 sendPurchaseArrivedToWarehouseAdmins + sendPurchaseReturnPendingConfirmToWarehouseAdmins
- [x] Controller 加 arrive/confirm-receive + confirm-out/complete
- [x] BizPurchaseMapper.latestValidUnitPrice 加 confirm_status=3 过滤

#### 前端
- [x] api 加 arrive/confirmReceive/confirmOut/complete
- [x] PurchaseView/PurchaseReturnView 加状态列+操作按钮+处理函数
- [x] 路由 purchase/purchase-return deptCodes 加 warehouse
- [x] 采购菜单"进货退货"→"商品退货"；仓储菜单加"进货入库确认"+"商品退货出库确认"

#### 验收（E2E 全通过）
- 商品进货：建单(1,库存不变)→到货(2,推仓储)→仓储确认入库(3,库存+5,confirmer,消息撤) ✅
- 商品退货：建单(1,通知,库存不变)→仓储确认出库(2,库存-3)→采购确认退货成功(3) ✅
- 权限：purchase confirm-receive 403 / warehouse arrive 403 ✅
- 测试数据清理，库存恢复 ✅

### 阶段 4：库存治理与追溯（待启动）
- 盘点 / 余料 / 成品追溯

---

### 🆕 六项需求改进轨道（阶段 5–8，2026-07-08 规划）

> 用户提 6 项需求，调研结论：需求一/三/五 admin 已具备，需求二/四/六需新增/补全，需求一/五需放开员工权限。详见 `findings.md`「阶段 5 调研」。决策 D29–D32。

### 阶段 5：销售价格偏离超管审批（✅ 完成 - 2026-07-08 会话 9）

**决策：** D29 复用 `BizApprovalOrder` 框架（新 action `price_deviation_confirm` + 超管审批路由 + 审批通过解锁仓储 confirm，不自动 confirm）；D30 比例阈值 ±5%（常量 `PRICE_DEVIATION_THRESHOLD=0.05`，后续可改配置表）；D31 仅销售单，销售退货不重复审批。

**流程：** 销售建单 -> 若 `|unit_price - sale_price|/sale_price > 5%` -> 自动建 pending 审批单 + 推超管 -> 超管通过 -> 仓储 confirm 放行扣库存；超管拒绝 -> 仓储 confirm 被拦。

#### 后端
- [x] S1 常量 `PRICE_DEVIATION_THRESHOLD = 0.05`（SalesService）
- [x] S2 `GoodsOptionVO` 加 `salePrice`；`GoodsService.options` 填充（5 参构造）
- [x] S3 `SalesService.create`：`isPriceDeviation` 探测偏离；若偏离 `createPriceDeviationApproval` 建 `BizApprovalOrder`（biz_type=sales, action=price_deviation_confirm, status=1）+ 发消息给超管
- [x] S4 `ApprovalService.validateAction` 加 `price_deviation_confirm`；`approve/reject` 改 `peekPendingOrder`+`requireApproverAccess`（该 action 限 `requireSuperAdmin`，其余仍仓储）；approve 时 price_deviation_confirm 跳过 `executeVoidByApproval`（仅置 status=2）
- [x] S5 `SalesService.confirm` 前置 `ensurePriceDeviationApproved`：偏离订单需存在 status=2 审批单，否则抛"需超管审批"
- [x] S6 `SalesService.delete/void` 调 `revokePriceDeviationApprovals`（pending 审批置 status=3）+ `revokeUnreadByBiz("sales")`
- [x] S7 `MessageService.sendPriceDeviationToSuperAdmin`（按 role=salesadmin 单点，biz_type=sales 对齐 D21）
- [x] S8 编译（`clean compile` exit 0）+ E2E
- [x] DB：`biz_approval_order.request_action` VARCHAR(20)->VARCHAR(30)（`price_deviation_confirm` 24 字符超限；ALTER 7.8 + 本地执行）

#### 前端
- [x] F1 SalesView 建单对话框：商品选项展示标准售价；销售单价下方实时偏离 % 提示（>5% 红字告警"需超管审批"）
- [x] F2 超管菜单加"价格偏离审批"入口（复用 VoidApprovalView 页面，后端按 action 路由超管）；VoidApprovalView actionOptions 加 `price_deviation_confirm`；成功提示通用化
- [x] F3 build（✓ 8.46s）

#### 验收（E2E 全通过）
- 正常价（1.50=1.50）建单 -> 无审批 -> 仓储 confirm 200，库存 500->498 ✅
- 偏离价（1.50->5.00，233%）建单 -> 自动建审批 + 超管收 1 未读消息 -> 仓储 confirm 400"需超管审批" ✅
- 超管通过（status=2）-> 仓储 confirm 200，confirmStatus=2 ✅
- 权限：warehouse admin 审 price_deviation_confirm 403"价格偏离审批需超级管理员处理"，审批状态不变 ✅
- 删除 pending 偏离单 -> 审批自动撤销（status=3）✅
- 测试数据清理：残留 0，库存恢复 500 ✅

#### 踩坑
- `biz_approval_order.request_action` 原 VARCHAR(20)，`price_deviation_confirm`(24) 超限 -> MysqlDataTruncation 500。修复：ALTER 扩至 VARCHAR(30) + db.sql DDL/注释同步。
- E2E Test 3 首版 confirm 400 实为 `@PreventDuplicateSubmit` 防抖窗口（被拦截 confirm 与 approve 后 confirm 间隔 < 窗口），非业务 bug；脚本加 `sleep 3` 越窗口后通过。真实场景审批跨人耗时，不会触发。

### 阶段 6：采购申请预计到货时间（✅ 完成 - 2026-07-08 会话 9）

**决策：** 预计到货时间在认领（process）时由采购 admin 录入。认领人已具备（`operator_id/name` + `operation_time`）。

#### 后端
- [x] P1 DB：`biz_purchase_request` 加 `expected_arrival_time DATETIME`（db.sql ALTER 7.7 + 本地执行）
- [x] P2 Entity/VO 加 `expectedArrivalTime`
- [x] P3 `PurchaseRequestProcessDTO{expectedArrivalTime}`；`process()` 写入；Controller `process` 加 `@RequestBody`
- [x] P4 编译 + E2E

#### 前端
- [x] F1 认领对话框加预计到货时间输入（el-date-picker type=date，ISO `YYYY-MM-DDTHH:mm:ss`）
- [x] F2 详情/列表展示预计到货时间（formatDate 日期显示）
- [x] F3 build + Vite E2E

#### 验收（E2E 全通过）
- 认领时填预计到货 -> 写入 -> getById 返回 ETA=2026-07-20T10:00:00 ✅
- 不填允许（可选字段）-> ETA=None，认领成功 ✅
- 权限：warehouse admin 认领 403"仅采购管理员可处理/入库/驳回采购申请单"，状态不变 ✅
- 存量数据 ETA=null 兼容；测试数据已清理 ✅

### 阶段 7：生产领料失败反馈销售（✅ 完成 - 2026-07-08 会话 9）

**决策：** 缺料反馈策略选实时通知--reject 必通知；issue 缺料异常通知一次（不新增持久化缺料态，改动最小）。采用广播销售 admin（非 sourceSalesId 定向，避免 BizSalesMapper 跨模块耦合 + 追溯简单）。

#### 后端
- [x] K1 `MessageService.sendPickListFailureToSalesAdmins`（@Transactional REQUIRES_NEW，biz_type=`pick_list`，对齐 D21）+ `hasUnreadBizMessage` 去重助手
- [x] K2 `PickListService` 注入 `MessageService`；`reject()` 成功后发反馈；`issue()` 缺料 catch 发反馈（REQUIRES_NEW 存活回滚）；`issue()` 成功撤消息；`delete()` 撤消息
- [x] K3 修 `PickListView.vue:34` 新增领料按钮 deptCodes `['sales','warehouse']`->`['warehouse']`（d50849a 收口仓库遗留不一致；router/menu 已是 warehouse-only）
- [x] K4 编译（`./mvnw clean compile` exit 0）+ E2E

#### 前端
- [x] F1 销售侧消息中心见"生产领料失败反馈"（消息复用 sys_message，无需专属标记；消息标题即反馈）
- [x] F2 build + Vite E2E（PickListView chunk 重建，proxy code=200）

#### 验收（E2E 全通过）
- 仓储驳回领料单 -> 销售收 1 条未读消息 ✅
- 仓储发料缺料（qty=999999）-> 400 + 销售收消息（REQUIRES_NEW 存活回滚）+ 库存不变 500 ✅
- 重复缺料 -> 去重（hasUnreadBizMessage，消息数不增）✅
- 撤销领料单 -> 撤未读消息（数->0）✅
- 权限：sales admin 驳回 403"仅仓储管理员可发料/驳回"，状态不变 ✅
- 测试数据清理：picklist/msg 残留 0，库存恢复 500 ✅

#### 踩坑
- JDT IDE 诊断报 phantom 语法错误（misplaced construct/record expected/unused import），实际 `./mvnw clean compile` exit 0--CLAUDE.md 记载的 JDT stale/desync 误报，以 javac 为准。

### 阶段 8：销售/采购员工建单权限放开（✅ 完成 - 2026-07-08 会话 9）

**决策：** D32 含普通员工；员工仅 create + read（不动库存，兼容 3.7 仓储收口）；delete/void/confirm 保持 admin/仓储。员工可到货/确认退货成功（采购工作流，不动库存）。

#### 后端
- [x] A1 `AuthzService` 加 `isDeptMember`/`hasDeptMemberOrSuperAdminAccess`/`requireDeptMemberOrSuperAdmin`/`requireAnyDeptMemberOrSuperAdmin`（admin OR employee 且 dept 匹配）
- [x] A2 `SalesService`/`SalesReturnService`：`requireXxxModuleAccess`->`requireDeptMemberOrSuperAdmin`（create+读），新增 `requireXxxAdminOrSuperAdmin`（delete 收口 admin）；读权限 `requireAnyDeptMemberOrSuperAdmin`
- [x] A3 `PurchaseService`/`PurchaseReturnService`：同 A2（create/arrive/complete 成员级，delete admin 级）
- [x] A4 confirm/confirmReceive/confirmOut/void-execution 保持仓储/dept-admin 不变
- [x] A5 编译（`clean compile` exit 0）+ E2E

#### 前端
- [x] F1 router：sales/sales-return/purchase/purchase-return 路由 `roles:['admin']`->`['admin','employee']`
- [x] F2 v-permission：4 视图新建按钮 + PurchaseView 到货按钮 + PurchaseReturnView 确认退货成功按钮 -> `['admin','employee']`；删除/作废/确认入库出库按钮保持 admin/warehouse
- [x] F3 layout：加 `isSalesEmployee`/`isPurchaseEmployee`/`isBizEmployee` computed + 员工菜单块（销售员工：商品销售/销售退货；采购员工：商品进货/商品退货）+ `showSidebar` 放开业务部门员工
- [x] F4 build + Vite E2E

#### 数据
- [x] 确认种子账号存在：`sales_employee`(id=4)/`purchase_employee`(id=8)，无需补

#### 验收（E2E 全通过）
- sales_employee 建销售单 200 / 读列表 200 total=22 ✅
- sales_employee 删除 403"仅销售管理员可执行该操作" / admin 删除 200 ✅
- purchase_employee 建进货 200 / 到货确认 200 ✅
- purchase_employee 删除 403"仅采购管理员可执行该操作" ✅
- warehouse confirmReceive 200 库存 500->505(+5) / purchase_employee confirmReceive 403"仅仓储管理员可确认进货入库" ✅
- Vite 代理员工登录 + 列表 code=200 ✅
- 测试数据清理：残留 0，库存恢复 500 ✅

#### 踩坑
- E2E 清理 SQL 误用 `biz_purchase_detail`（biz_purchase 为单表设计无明细表），报 ERROR 1146；改直接删 biz_purchase + 反向回冲已确认单的库存。
- 员工原无侧边栏（`showSidebar=!isEmployee`），需 `|| isBizEmployee` 放开业务部门员工 + 加专属菜单块。

### 需求三（采购拒绝备注）：已具备，E2E 复核
- [x] 阶段 6 联调时已复核 reject_reason 持久化 + 前端展示（`PurchaseRequestService.java:313-331`）；阶段 5–8 E2E 全程 reject 路径无回归

### 阶段 9：生产部门与角色（dept + 账号 + 权限基线）（✅ 完成 - 2026-08-31，commit 490a489；无独立日志小节，以 db.sql 种子/DEPT_PRODUCTION/生产菜单取证）

**决策：** D37 生产=新部门 `production`（生产研发部），复用三档角色（admin/employee）；管理员=admin+production，员工=employee+production；BOM 建档与生产任务单建单归生产管理员。删除现有「生产入库/生产领料」的 warehouse 权限→改 production。
**现状：** sys_dept 现 6 部门，无生产；`base_goods` 无 type；「生产入库/生产领料」硬编码仓储 admin。

#### 后端
- [x] P1 DB：`sys_dept` 插 `(production, 生产研发部)`；`sys_user` 建 production admin/employee 种子账号（db.sql seed + 本地执行）
- [x] P2 `AuthzService`（+ 各字符串拷贝处）加 `DEPT_PRODUCTION` 常量
- [x] P3 编译 + 启动验证（superadmin/warehouse/login 不受影响）

#### 前端
- [x] F1 `layout/index.vue` 加 `isProductionAdmin`/`isProductionEmployee` computed + 生产菜单块；`isBizEmployee` 纳入生产员工（showSidebar）
- [x] F2 `router` 加生产路由；`HomeView`/AdminHome/EmployeeHome 加生产分支
- [x] F3 build + Vite E2E

### 阶段 10：BOM 子系统（核心数据）（✅ 完成 - 2026-08-31 会话 13）

**决策：** D41 `base_goods` 加 `type`（成品/物料）+ 新建 BOM 主从表（成品→明细：物料/单台用量/规格/材质/备注）；BOM 明细尽量关联 base_goods，无法关联做成不强关联说明行（不参与齐套）；D44 现有 `product_name` 保留但不作权威；D45 BOM 由生产研发部（前身研发）录入——导入 xlsx（物料名称/规格/数量/材质/备注模板）+ 手工新增，用 PTO153 试点。

#### 后端
- [x] P1 DB：`base_goods` 加 `type`（成品/物料）；建 `biz_bom` + `biz_bom_detail`（db.sql + 本地执行）
- [x] P2 Entity/Mapper/DTO/VO：Bom / BomDetail；`BaseGoods`/GoodsDTO 加 type
- [x] P3 BomService：create/update/delete（生产管理员）/page/getById（生产/仓储只读）+ 明细关联校验
- [x] P4 导入接口（xlsx 解析 → 批量生成 BOM 明细，后端库或前端 sheetjs 解析后提交）
- [x] P5 `GoodsService.options` 支持 type 过滤（成品可选作生产成品）
- [x] P6 编译 + E2E

#### 前端
- [x] F1 BOM 维护页（生产管理员：建/改/删）+ 只读查看页（生产员工/仓储菜单）+ 导入对话框
- [x] F2 物料(Goods)管理 type 标记/筛选；生产任务单成品下拉只列成品
- [x] F3 build + Vite E2E

### 阶段 11：生产任务单 + 齐套预警（核心痛点）（✅ 完成 - 2026-08-31 会话 14；2026-09-01/09-07 有演进：领料改生产端按 BOM 申请、开工前置「领料单全额出库」、自动发料删除、补料走草稿→采购申请，见 ADR-0001/阶段 14）

**决策：** D42 齐套预警：建生产任务单选成品×数量→展开 BOM 算需求 vs 库存，够→正常、部分缺→标红/黄、严重缺→阻断开工+站内信通知采购管理员；D43 领料单从工单按 BOM 自动生成（避免漏领），生产端出库扣库存，成品入库回填。

#### 后端
- [x] P1 DB：`biz_production_order`（成品/数量/成品类型引用/状态/来源）+ 工序清单（静态子表或 JSON 字段）
- [x] P2 Entity/Mapper/DTO/VO；`CodeGenerator.productionOrderNo()`
- [x] P3 OrderService：create（含齐套展开校验）/page/getById/状态流转/作废
- [x] P4 齐套服务：BOM 展开 + 库存对比，produ采缺料明细；严重缺时 `MessageService.sendToDeptAdminsWithBiz` 通知采购 admin
- [x] P5 领料单生成：从工单 BOM×数量 自动生成 biz_pick_list（PICK），生产端发料扣库存
- [x] P6 成品入库：质检合格后 生产入库 成品库存+，回填工单
- [x] P7 编译 + E2E

#### 前端
- [x] F1 生产任务单页面：建单（选成品×数量，展示齐套结果/缺料红黄）/列表/详情/领料/入库联动
- [x] F2 缺料红黄提示 + 采购通知提示
- [x] F3 build + Vite E2E

### 阶段 12：质检记录（✅ 完成 - 2026-08-31 会话 15）

**决策：** D40 10 道工序不全追踪；8 装配工序只作静态清单；2 测试工序单独「质检记录」：测试员/结果(OK/NG)/时间/原因；NG→返工→重测（生产管理员重新派发），合格才允许生产入库；不可修复则报废(scrap)。

#### 后端
- [x] P1 DB：`biz_production_qc`（工单/产品/工序(首测|成品测)/测试员/结果/时间/原因/处置）
- [x] P2 Entity/Mapper/DTO/VO；QCService：record/rewEork(re-派发)/scrap
- [x] P3 成品入库前置：待首测+成品测均合格才允许入库；NG 未处置阻塞
- [x] P4 编译 + E2E

#### 前端
- [x] F1 质检录入界面 + NG 处置（返工/报废）按钮；工单进度展示
- [x] F2 build + Vite E2E

### 阶段 13：迁移生产入库/领料到生产端（✅ 完成 - 2026-08-31 会话 16；后续 2026-09-07 仓储端彻底不建领料单，退料入口也移到生产端）

**决策：** D38 「生产入库/生产领料」从仓储端移到生产端（实际操作者=生产员工）；仓储端保留只读台账/查询。
**现状副作用（需归位）：** 扣/加库存、领料缺料通知销售、`biz_production` 当前归档仓储。

#### 后端
- [x] P1 `PickListService`/`ProductionService` 权限 `requireDeptAdminOrSuperAdmin(DEPT_WAREHOUSE)`→生产域（建单/领料/发料/入库=生产成员；删/作废=生产 admin）
- [x] P2 仓储侧保留只读 `page/getById`（加 `requireProductionReadAccess` 含 warehouse 读）
- [x] P3 缺料/领料失败通知目标部门校正；编译 + E2E

#### 前端
- [x] F1 菜单从仓储端移到生产端；仓储端仅留查询入口；路由/按钮 deptCodes 调整
- [x] F2 build + Vite E2E

### 阶段 14：补料链路物料详情 + 未知物料自动建档（D60/ADR-0002/0003，2026-09-07 会话 10）

**决策：** ① 匹配行级四态：齐套/部分缺料/严重缺料(已绑定库存0)/未知物料(goods_id空,软删视同未知)；② 未知物料补料=内联录入(BOM预填可改)+提交时自动建档(挂缺省供应商1、进价留空、名称+规格唯一冲突则退回改绑)；③ 补料明细快照 BOM 行规格/材质/备注并带新物料标记，申请单详情按【已有物料缺口】【未知物料(新物料)】两组展示；④ 主数据 base_goods 加 spec/material 字段，物料唯一性改「名称+规格」(α1)，成品仍名称唯一；⑤ 接通建单齐套预警通知采购(死代码 sendKitShortageToPurchaseAdmins)；⑥ 采购看申请单详情快照，不开生产任务单读权限；⑦ 清理 confirmReceive 回挂 BOM 残留机制。详见 docs/adr/0002、0003 与 CONTEXT.md。

#### 后端
- [x] P1 db.sql 追加 10.x DDL：base_goods 加 spec/material；biz_purchase_request_detail 加 spec/material/remark/is_new_material；本地执行
- [x] P2 主数据：BaseGoods/GoodsSaveDTO/GoodsVO/GoodsOptionVO 加字段；GoodsService 唯一性改类型感知(物料=名称+规格 checkMaterialNameSpecUnique，成品=名称) + 新增 createMaterialFromProduction(自动建档)；BomView 预填依赖 options 返回 spec/material
- [x] P3 齐套四态：KitShortageVO 加 spec/material/remark；computeKit 未绑定行→unknown/未知物料(汇总仍算 block)；summary 缺口明细带规格+【新物料】标注
- [x] P4 建单接通齐套预警：create() 在 kitStatus=block 时调 sendKitShortageToPurchaseAdmins(voidOrder 已有 revoke)
- [x] P5 补料自动建档+快照：ProductionDraftItemDTO 加 newGoodsName/spec/material/remark/unit；createDraft 未知行→自动建档回绑 BOM 行(含规格材质回写)、明细快照+isNewMaterial；PurchaseRequestDetailVO 加字段；删除 confirmReceive 回挂块与 bizBomDetailMapper
- [x] P6 单测：ProductionOrderServiceTest(unknown 四态/summary/spec)、GoodsServiceTest(名称+规格唯一/自动建档)、PurchaseRequestServiceTest(自动建档/冲突退回/快照)；./mvnw compile + test

#### 前端
- [x] F1 ProductionOrderView：齐套三处表格加规格/材质/备注列；lineTagType 加 unknown(info)；补料弹窗两组分区，未知行=内联表单(预填可改+单位+申请数量默认缺口)，去掉强制下拉；doCreateDraft 按行类型组包；下拉文案带规格/材质
- [x] F2 PurchaseRequestView：详情弹窗 production 来源按两组分区展示(商品/规格/材质/备注/数量/单价)，warehouse 来源维持原样；列表摘要列不动
- [x] F3 GoodsView：列表加规格/材质/备注列；表单加规格/材质/备注(仓储可编辑，采购不动)；payload/openByDetail/initForm 同步
- [x] F4 BomView：物料下拉文案带规格/材质；选中物料预填行 spec/material(为空时)
- [x] F5 npm run build + 重启两端 + Vite 代理 E2E（建单预警消息→补料自动建档→申请单两组→冲突退回提示→清理测试数据）

### 阶段 15：预计到货时间行级化（D61，2026-09-08 会话 18）

**决策（D61）：** ① 采购申请明细行各自带「预计到货时间」（认领必填）+「到货备注」（选填，与 BOM 物料描述快照 remark 独立）；② 范围=所有采购申请单（不分来源）；③ 主表 expected_arrival_time 废除，存量回填明细行后删列；④ 采购中（status=2）可经 PUT /arrival-plan 修改，待入库确认起锁定；⑤ 认领时向来源申请人（production→生产部/warehouse→仓储部）发行级到货摘要（D21 带 biz）；⑥ 列表聚合展示（相同单日期/不同最早~最晚），认领对话框行级表格+统一填充。CONTEXT.md「认领/到货备注」词条已同步。

- [x] B1 db.sql 11.x DDL（明细加列+回填+主表删列）
- [x] B2 明细实体/VO/DTO 行级化 + process 重写 + 主表字段清理（17 单测）
- [x] B3 认领通知来源申请人（D21）
- [x] B4 PUT /arrival-plan（仅采购中）
- [x] F1 前端行级认领/修改对话框（统一填充）+ 列表聚合 + 详情/到货列
- [x] E2E 认领→修改→负测×3→消息验证→清理

### 阶段 16：补料入库齐套通知（D62，2026-09-08 会话 19）

- 背景：生产补料→采购入库确认后，生产侧无任何推送，需人工刷任务单才发现料齐了。
- 决策（D62）：confirmReceive 成功后仅当来源=生产补料且生产单有效（未删/未作废/未报废）时重算齐套；缺口清零即 `sendKitCompleteToProductionAdmins` 通知生产部管理员「物料已齐套可领料」（绑 production_order，作废撤销沿用 voidOrder 既有点，D21）；仍缺料沉默（列表实时齐套状态兜底）；文案含任务单号/成品×数量/补料单号，统一用「领料」。
- 已知问题（本次不修）：补料单按行部分到货→确认入库终态→若仍缺料，受"已入库补料单阻止再补料"既有规则（D59）约束，该生产单无法再补——待单独立项。
- 任务：[x] confirmReceive 齐套通知（TDD，4 单测） [x] E2E+重启 [x] 文档

### 阶段 17：领料/退料明细规格材质备注展示（D63，2026-09-09 会话 20）

- 背景：领料明细只快照 `goods_name`，仓储确认出库/看详情时只见品名+数量；同名不同规格物料（ADR-0003 唯一口径）无法区分，找货有歧义；发料确认框更是完全不显示明细。
- 决策（D63，grilling 六问定案）：① 明细行显示 规格/材质/备注，**备注=物料主数据描述**（`base_goods.description`，非 BOM 行备注——与补料单 D60 快照不同源，CONTEXT.md「生产领料」词条已更新）；② 范围=**领料+退料**（pick_type=PICK/RETURN 共用明细表与视图）；③ 展示=详情弹窗加列（物料/规格/材质/备注）+ 发料确认框逐行「品名（规格/材质）×数量」轻改；**列表页摘要不动**；④ 口径=**建单时服务端从 base_goods 快照** spec/material/remark 三列（`biz_pick_list_detail` 加列，D60 范式；写入点 3 处：createPick/createReturn/create），**历史行快照为空兜底实时读主数据**（软删/缺档显示「-」）；⑤ 用词「物料」（详情弹窗原「商品」列头改「物料」）。
- 落地（2026-09-09）：写入点实为 **2 处**（createPick/createReturn——人工建单已随阶段 13 迁移移除，计划中"3 处"修正）；5 新单测（快照×2、兜底×3），全量 **101/101**。E2E：生产建 PTO153×1（order 27，齐套）→领料（pick 20，16 行明细，58-60 号自动建档物料快照落库 ✓，老物料 NULL=主数据本无 ✓）→仓储 getById VO 含三列 ✓→UPDATE 清空快照模拟历史行→兜底实时读主数据补显 ✓→sales_admin 详情负测 body code=403 ✓（**注意：本项目异常为 HTTP 200 + body code 封装，负测须看 body**）→撤销+作废+任务单软删，库存零变动、消息已撤销、零残留。
- 任务：[x] DDL 三列（db.sql 12.x + 本地库） [x] TDD 后端：实体/VO/快照写入 2 处/兜底填充 + 5 单测（101/101） [x] 前端 PickListView 详情列+发料文案 [x] E2E+清理 [x] 文档

### 阶段 18：生产工序打卡追踪 + 质检进度列表修复（D64，2026-09-09 会话 22）✅ 完成（后端 25 个新单测全绿 + E2E 全链路通过）

- 背景：① 详情页装配工序是 8 道通用静态 SOP 文案，用户要求改为真实产线 10 道并希望"动态"；② 质检记录页列表"质检进度"恒显"未测"——根因：QcView 列表调生产任务单 `page()`，而 `qcState` 仅 `getById` 填充（ProductionOrderService.java:147），`page()` 不填 → 行数据 qcState 恒 null。
- 决策（D64，grilling 三轮定案）：① 方案选 A（逐工序追踪）而非 C（纯文案+联动）：新增 `biz_production_order_step` 工序实例表，**仅 7 道人工装配工序落库**（1 磁性材料装配 / 2 底座结构组装 / 3 手柄机构装配 / 4 PCB板焊接及安装 / 5 程序烧录 / 7 屏蔽壳安装 / 9 发合格证、条码、标签、配件及包装），第 6 首次测试 / 8 成品测试 / 10 成品入库 三行展示时从质检记录与订单状态**实时推导不落库**（避免双源不一致）；② 打卡**不是流程闸口**（完工/入库校验维持现状，质检仍是唯一质量闸口）；③ 生产部门任意成员可打卡，撤销限本人或生产管理员，仅生产中/待入库状态可打卡/撤销；④ 打卡/撤销挂 @AuditLog + @PreventDuplicateSubmit；⑤ 存量单：未完结（status 1/2/3）刷新快照为新 10 道并初始化 7 行，已完结/作废/报废保留历史快照不初始化；⑥ 部分取代 D40"静态不追踪"口径——打卡只是完成留痕，避免的仍是报工级 MES（数量/工时/派工），CONTEXT.md「工序打卡」词条已同步；⑦ bug 修复：`page()` 批量填充 qcState（QcService.buildStateBatch，一次 in 查询分组推导），无流程/模型变更。

#### 后端
- [x] B1 db.sql 13.x：biz_production_order_step 建表 + 未完结存量单快照刷新 + 步骤行初始化（本地执行；仅存量 status=3 的单 id=24 初始化 7 行，历史单 17–20 未动）
- [x] B2 Entity BizProductionOrderStep + Mapper + VO ProductionStepVO
- [x] B3 ProductionStepService：initStepsForOrder / complete / revoke / listSteps（10 行合并：人工行+推导行）；PROCESS_STEPS 常量换新 10 道
- [x] B4 ProductionOrderService：create 快照新 10 道+初始化步骤行；page() 批量填 qcState（bug 修复）；getById 返回 stepList
- [x] B5 Controller：POST /{id}/steps/{stepNo}/complete 与 /revoke（@AuditLog+@PreventDuplicateSubmit）
- [x] B6 单测：ProductionStepServiceTest 14 例（初始化/打卡/重复/非人工步骤/状态闸/撤销权限/推导合并/无行回落）+ QcServiceTest 2 例（buildStateBatch 分组/空输入）+ ProductionOrderServiceTest +1 例（page 填充 qcState 回归）——`./mvnw test` 全绿

#### 前端
- [x] F1 api：completeProductionStepAPI / revokeProductionStepAPI
- [x] F2 ProductionOrderView 详情：stepList 有值渲染工序表格（序号/工序/状态 tag/打卡人时间/打卡撤销按钮，v-permission `{ deptCodes: ['production'] }` 匹配后端任意成员+超管语义），无 stepList 回落 processList 文字列表；分隔条改「生产工序」+ 脚注说明 6/8/10 自动更新
- [x] F3 build + E2E（curl 全链路：建单初始化 10 行 → 待生产打卡拦截 → 开工 → 员工打卡/重复打卡/管理员撤他人/本人撤销/再撤销 → 派生 step6 拒打卡 → sales_admin 打卡与读详情双 403 → 首测/成品测 OK 推导 step6/8 → 入库推导 step10 → **page 行级 qcState 填充验证（QcView bug 修复）** → 测试数据软删+库存恢复）

### 阶段 19：主数据分域（物料/成品管理）+ BOM 删除治理 + 业务下拉收紧（D65–D67，2026-09-09 会话 23）

- 背景（用户三项调整）：① 现「物料管理」页成品/物料混显，要拆出「成品管理」页；② BOM 导入按钮叫"批量导入"实际一次只能导入一个 BOM；③ 删 BOM 后自动建档的成品主档残留，"下达生产任务单"下拉仍可见该成品。
- 调研结论：`page()/options()` 已支持 type 过滤；`resolveOrCreateProduct` 已按 goods_name+type=product 复用同名成品；缺货识别/首页低库存统计**不排除成品**且自动建档成品 `warning_stock=10`（新建 BOM 即被缺货识别扫出）；删 BOM=软删，下达下拉读 `base_goods type=product` 与 BOM 表无关（后端 `requireBomOfProduct` 本就拦截无 BOM 建单，纯 UX 缺口）；`biz_bom.goods_id` 唯一键 `uk_bom_goods` 使"软删后重建"必撞键报错。
- 决策（grilling 两轮定案，用户逐题确认）：**D65** 同表分页 + 成品允许手工建档（修订 D46）+ 成品全链路排除预警 + 导入改名「BOM导入」；**D66** 下达下拉只列有有效 BOM 的成品 + 删 BOM 软保护（未完结任务单二次确认）+ 安全级联清理成品主档（库存=0 且无任何单据引用）+ DROP uk_bom_goods 改应用层查重；**D67** 业务下拉收紧（销售下单/生产入库→成品，商品进货/采购申请→物料）。

#### 后端
- [x] B1 db.sql 14.x：DROP INDEX uk_bom_goods + uk_bom_code（软删后重建同成品/同编码 BOM 均会撞 DB 唯一键，查重改由应用层 checkGoodsBomUnique/checkBomCodeUnique 按 is_deleted=0 保证；本地执行；CREATE 块保留两键供 14.x DROP 顺序重放）；14.y 补强：生成列 active_goods_id/active_bom_code + 软删兼容唯一键（并发双活 BOM 的 DB 兜底）+ idx_bom_goods/idx_bom_code/idx_qc_goods 普通索引
- [x] B2 GoodsService：成品手工建档（create 分支：缺省供应商1/category=成品/warning_stock=0）；update 成品专属路径（采购不可改成品，不动 supplier/warning）；预警三处排除成品（PurchaseRequestService.listShortageGoods / HomeService.countLowStockGoods / page warningOnly）；options 加 hasBom 过滤
- [x] B3 BomService：删除治理（delete-check 未完结任务单计数 + 软保护确认后放行；级联校验：成品 stock=0 且无 生产任务单/生产入库/销售/销售退货/进货/退货/采购申请明细/领料明细/BOM明细 引用 → 软删成品主档，否则保留）；复用口径维持 type=product
- [x] B4 Controller：GoodsController options 加 hasBom；BomController 加 GET /{id}/delete-check
- [x] B5 单测：GoodsServiceTest（成品建档默认值/预警排除/hasBom）+ BomServiceTest（删除三分支：级联删/保留/软保护拦截）

#### 前端
- [x] F1 GoodsView 按 route goodsType 渲染两页（物料页现状字段 + 成品页：名称/单位/规格/备注/库存，隐藏供应商/进价/预警/材质/种类/产品名称，加创建来源列）；路由 /base/products + 菜单与物料管理并列
- [x] F2 BomView：「批量导入」→「BOM导入」；删除走 delete-check 二次确认
- [x] F3 ProductionOrderView 成品下拉改 hasBom 选项；SalesView/ProductionView→type=product；PurchaseView/PurchaseRequestView→type=material
- [x] F4 npm build + 重启两端 + curl E2E

#### 验收
- [x] 成品手工建档→建 BOM 复用；删 BOM 三分支（级联删/保留/软保护确认）；下达下拉过滤；缺货识别/首页统计无成品；四业务下拉收紧；软删后重建 BOM 不撞键；权限负测（body code）；测试数据清理

### 阶段 20：销售端三件套（售价维护 / 零库存开单 / 履约时间线联动，D68–D71，2026-09-10 会话 25 grilling 三轮定案）

- 背景（用户三项需求）：① 销售人员要看物料/成品管理页，且能像采购定进价一样定售价；② 定制公司模式：销售单=需求单，库存 0 也要能开单；③ 客户问"货何时好"，销售要挨个问生产/采购——要做销售单→生产→采购的联动与成品履约时间线（类淘宝物流）。
- 调研结论：`sale_price` 无维护入口（仅建单默认价+偏离审批基准）；建单有软校验 ensureStockSufficient（SalesService.java:491）；库存扣减在仓储确认出库（硬校验）；销售单↔生产任务单零关联；无工期/预计完工数据；齐套有行级缺口、采购明细有行级预计到货（D61）；进价列仅采购+超管可见；销售单一单一品（无明细表）。
- 决策（D68–D71，另 E1/E2 挂起见「待确认问题」）：**D68** 销售只读两页+成品页单字段编辑标准售价，进价对销售隐藏，开单改价仍走偏离审批；**D69** 建单零库存校验（允许超卖，待货销售单），出库硬校验兜底+仓储页"库存不足"标红，不支持部分发货；**D70** 缺货销售单创建→通知生产管理员（biz 绑定）；生产任务单加 sales_order_id 可空选一销售单关联；关联单对应生产入库且仍待出库→通知建单销售本人；**D71** 履约时间线 8 节点（下单/排产/物料准备/开工/装配x-7/质检/入库/发货）；预计可交付时间系统推算（缺料=max 补料行预计到货+工期；齐套=开工或当前+工期；未排产/无工期→不推算），生产任务单可手工修正（@AuditLog）；biz_bom.lead_days 可空，新品留空→"待生产评估"。

#### 后端
- [x] B1 db.sql 15.x：biz_bom 加 lead_days（可空整数）；biz_production_order 加 sales_order_id（可空）+ expected_completion_time（可空）；本地执行
- [x] B2 权限放开：商品 page/getById 读权限加 sales 部门；GoodsService update 加销售分支（仅放行 salePrice，校验 >0，仅成品）；进价可见性不动（showPrice 仍采购+超管）
- [x] B3 SalesService：移除建单软校验 ensureStockSufficient；create 时现货不足 → sendSalesDemandToProductionAdmins 通知生产管理员"有销售需求待排产"（biz_type=sales，复用 delete/void 撤未读）
- [x] B4 ProductionOrderService：create 支持选填 sales_order_id（校验销售单有效/同成品/待出库）；新增 expected_completion_time 手工修正（@AuditLog）；receipt 入库时若关联销售单仍待出库 → 站内信通知建单销售本人（recipient_user_id + biz 绑定，出库/作废撤未读）
- [x] B5 履约时间线：GET /sales/{id}/timeline —— 8 节点（下单=operation_time；排产=关联生产单 create_time；物料准备=齐套状态+缺料时最晚采购行预计到货；开工；装配=打卡 x/7；质检=首测/成品测；入库=生产入库；发货=confirm_time）+ 预计可交付时间按 D71 公式推算（SalesTimelineService）
- [x] B6 单测：销售改售价权限/校验、零库存开单、通知触发与撤回、关联校验、时间线推算四分支（现货/缺料/齐套/未排产）——154 例全绿；另补 linkableOptions 接口（生产建单关联下拉，生产/销售/仓储可读）

#### 前端
- [x] F1 路由 meta.deptCodes 加 sales（/base/goods、/base/products）+ 销售菜单加物料管理/成品管理
- [x] F2 GoodsView 成品页：售价列 + 「售价编辑」按钮（v-permission 销售部门；售价编辑态其他字段只读）
- [x] F3 SalesView 详情：履约时间线组件 SalesTimeline.vue（8 节点，已完成显实际时间，未来节点显预计/状态文案）；建单放开零库存/超卖（移除钳制与拦截，缺货提示"建单后将通知生产排产"）
- [x] F4 ProductionOrderView：建单选填关联销售单下拉（该成品的待出库销售单，随成品联动加载）；详情显关联销售单+预计完工时间+生产修正入口（可清空恢复推算）
- [x] F5 BomView 新建/编辑表单：lead_days 选填（天数整数，可空=新品待生产评估）
- [x] F6 仓储销售出库确认页：库存不足行标红"库存不足（需X/现存Y）"（当前库存列+整行淡红底色）
- [x] F7 npm build + 重启两端 + curl E2E（顺带修复存量 bug：DTO @NotBlank goodsName 拦截采购进价编辑，前端价格分支补传 goodsName 仅为过校验）

#### 验收
- [x] 销售角色进两页只读+改售价；改物料售价 403"销售部门仅可编辑成品售价"；售价≤0 400；进价列不可见（showPrice 逻辑未动）；开单改价仍触发偏离审批（未改）
- [x] 库存 0/不足均可建单（E2E goods 32 stock=0 建单 200）；建单即通知生产管理员（sys_message biz_type=sales biz_id=68"销售需求待排产"）；仓储页缺货标红（page 返回 stock=0<qty=5）；到货后可出库；消息随删除撤回（live_unread=0 验证）
- [x] 生产建单关联销售单（linkable 下拉返回 SAL…68，create salesOrderId=68 落库）；时间线 8 节点与推算分支正确（leadDays 空→"待生产评估"；手工修正→"生产确认"优先）；生产手工修正预计完工留痕（@AuditLog）；权限负测（purchase 访问 timeline body 403）；测试数据清理（任务单作废/销售单删除/售价与进价还原/库存未动）

### 阶段 21：E1/E2/E3 确认与落地——库存竞争/手动终止退料联动/红冲隐藏（D72–D74，2026-09-10 会话 26）

- 背景：阶段 20 挂起的 E1（库存竞争）/E2（关联单作废）+ 阶段 18 起遗留的 E3（作废并红冲入门前端隐藏）。用户确认后落地：E1 维持现状（D72）；E2 新增「手动终止」状态+退料联动+销售取消通知（D73）；E3 前端隐藏红冲入口（D74）。

#### 后端
- [x] B1 D72 库存竞争维持现状（无代码改动）；建单缺料/现货通知逻辑不变；出库硬校验兜底+仓储页红标退回人工协调。
- [x] B2 D73 生产任务单状态机扩展：新增 STATUS_TERMINATED=7（1/2/3 可进入，不可逆，仅生产管理员，原因必填，@AuditLog）；已终止不参与 UNFINISHED_STATUSES（齐套/预警/补料不受影响）；打卡/质检冻结。
- [x] B3 D73 终止联动退料：ProductionPickService.terminate —— 一个事务内生成 RETURN 退料单（退料量=「已领未退净额」为上界服务端兜底，防多退虚增库存）+ 置生产单 status=7 + 终止原因/终止人/终止时间落库；已有进行中退料单时跳过自动生成、前端提示人工核对。
- [x] B4 D73 销售取消通知：SalesService.delete/voidDocument 末尾，若关联生产单未终态 → sendSalesCanceledToProductionAdmins（biz_type=production_order，绑生产单 id，D21 范式）；生产单任一终态（完成/作废/报废/终止）调 revokeUnreadByBiz("production_order", id) 撤未读。
- [x] B5 D73 履约时间线联动：关联生产单已终止 → 时间线回退「待重新排产」；关联销售单号标注「已取消的销售单 /（已作废）」。
- [x] B6 D74 后端红冲逻辑完整保留（biz_status=3 负向记录不动）；仅前端入口隐藏。
- [x] B7 单测：terminate 正常路径/已进行中退料跳过/状态闸/越权/已领未退计算/终止后打卡质检冻结/销售取消通知触发与撤回/时间线联动 —— 后端 171/171 全绿（154→+17）。

#### 前端
- [x] F1 D73 ProductionOrderView：详情加「终止」按钮（v-permission 生产管理员，1/2/3 状态可见）；终止对话框预填「已领未退」净额（可改量，逐行展示物料/规格/已领/已退/可退/实退）；已终止态 tag 标灰、打卡/质检按钮禁用、关联销售单标注「已取消/已作废」。
- [x] F2 D73 SalesTimeline：关联生产单已终止 → 状态回退「待重新排产」文案。
- [x] F3 D74 五个业务页（SalesView/PurchaseView/PurchaseReturnView/SalesReturnView/ProductionView）：删「作废并红冲」按钮，「仅作废」更名「作废」；作废审批页（VoidApprovalView）删 void_red 选项（历史记录兜底显示）。
- [x] F4 npm run build 通过。

#### 验收
- [x] 终止主流程：生产中任务单（已领料部分退料）→ 终止弹窗预填已领未退净额 → 生成退料单+status=7+原因/人/时间落库 → 仓储确认退料入库 → 库存回补 ✅
- [x] 已有进行中退料单 → 终止跳过自动生成 → 提示人工核对 ✅
- [x] 状态闸：已完成/已作废/已报废/已终止 调 terminate 400 ✅；越权（warehouse/sales）403 ✅
- [x] 销售取消通知：销售单删除/作废 → 关联未终态生产单的生产管理员收通知（biz_type=production_order）；生产单任一终态 → 未读消息撤清 ✅
- [x] 时间线：已终止关联单 → 回退「待重新排产」；已取消销售单号标注 ✅
- [x] 红冲隐藏：五业务页+作废审批页 均无红冲入口；后端红冲逻辑仍可调用（历史数据兼容）✅
- [x] 负测 5 项全中；库存 17/17 回基线；测试数据零残留 ✅

### 增量优化：生产端预警中心 + 消息点击跳转/新消息浮窗 + 首页预警数字红色（2026-09-11 会话 27，commit e395161）

- 背景：用户体验巡检驱动的三项优化，非编号阶段；grilling 定案（浮窗=新消息弹通知非常驻；整卡跳转+自动已读；数字 >0 才红；生产员工补预警卡片；仅跳列表页；生产快捷入口补 3 项；预警卡片不加点击）。无 DB DDL 变更。

#### 后端
- [x] B1 预警分页鉴权放开生产 admin；生产员工工作台预警数同步放开（预警部门允许名单对齐 AuthzService 常量）
- [x] B2 `MessageVO` 透出 `bizType/bizId`（D21 字段此前未透出，前端跳转数据源）
- [x] B3 单测 174 → 179（+5：MessageServiceTest×2 / HomeServiceTest×2 / GoodsServiceTest 预警鉴权×1），全绿

#### 前端
- [x] F1 生产端预警中心：路由/生产 admin 菜单/首页快捷入口（预警中心+生产任务单+质检记录）补齐；生产员工首页补低/零库存预警卡片
- [x] F2 站内邮箱整卡点击 → 自动已读 + 跳业务列表页；`BIZ_ROUTE_MAP` 候选数组按权限取首个可达（跨部门回退：销售收 pick_list→/business/sales；采购收 production_order→/business/purchase-request；生产收 sales→/business/production-order；超管 sales→/system/void-approval）
- [x] F3 新消息 ElNotification 浮窗：15s 轮询未读数变化触发，≤3 条逐条弹（点击跳转）、>3 条聚合（真实 delta）；首轮不弹存量；基线先更新防重入；瞬时失败角标不清零
- [x] F4 `utils/auth.js` 新增 `canAccessRouteMeta`（与路由守卫同一判定，组件侧预判可达性）
- [x] F5 超管邮箱入口放开（修价格偏离审批消息无处可读缺陷）；AdminHome/EmployeeHome 预警数字 `metric-value--alert`（>0 红色加粗）
- [x] F6 `npm run build` 通过；重启后端 curl 复验全 200

#### Defer（评审后有意遗留，建议下次动相关文件顺手或单独立项）
- intent 级 bizType 重构（`sales` 等 bizType 多意图复用，前端靠角色猜路由；`revokeUnreadByBiz('sales', id)` 调用点需同步评估）
- 预警部门允许名单散落 6 处（HomeService/GoodsService/router meta/layout/AdminHome/EmployeeHome），无单一数据源
- 路由守卫与 `canAccessRouteMeta` 判定逻辑未共享组合（守卫保留两条不同错误文案）
- 浮窗计数比较边界：同 15s 窗口 +1/-1 抵消不弹（需 id 级基线根除）
- 两个 Home 组件 metric 卡片 CSS 重复未抽共享

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
| D21 | sys_message 加 biz_type/biz_id，单据删除/作废/确认时按 biz 撤销未读待办 | 消息生命周期与单据绑定，消除"有通知无单据"悬挂引用 | 2026-07-02 |
| D22 | 销售退货对齐 confirm_status 范式（建单不入库，仓储确认才入库） | 与 D14 销售单范式一致，仓储枢纽角色统一管控库存变更 | 2026-07-02 |
| D23 | 生产入库模块：归属仓储部门、无供应商、生产单价可选、作废为仓储直接作废（不走跨部门审批） | 范式对齐进货但贴合自产场景；仓储枢纽角色统一管控 | 2026-07-04 |
| D24 | 采购申请手动建单为纯前端增强（后端 create 本就支持任意在售商品，限制仅前端缺货入口） | 后端无冗余改动，前端补手动入口即解锁任意商品申请 | 2026-07-04 |
| D25 | 采购申请消息补绑 biz_type/biz_id（"purchase_request"+单据id），并在 delete/process/receive/reject 撤未读消息（对齐 D21 范式） | 修复仓储撤销/终态后采购侧消息悬挂（有通知无单据）；采购申请链路此前漏接 D21 | 2026-07-04 |
| D26 | 采购入库改为仓储确认范式：新增 5 待入库确认态，采购到货提交不加库存推仓储，仓储确认才加库存；到货可由采购撤回/仓储驳回回到采购中 | 对齐 D22 销售退货确认入库范式，库存变更统一仓储枢纽管控 | 2026-07-04 |
| D27 | 商品进货改为到货确认+仓储确认入库范式：confirm_status 1待到货→2待入库确认→3已入库，建单不加库存，仓储确认才加 | 对齐 D14/D22 仓储枢纽确认范式，库存变更统一仓储管控 | 2026-07-04 |
| D28 | 商品退货改为仓储确认出库+采购确认退货成功范式：confirm_status 1待出库确认→2待退货确认(减库存)→3已退货，建单不减库存；"进货退货"改名"商品退货" | 对齐出库范式账实一致；改名贴合语义 | 2026-07-04 |
| D29 | 价格偏离审批复用 BizApprovalOrder 框架：新 request_action=price_deviation_confirm + 超管审批路由 + 审批通过解锁仓储 confirm（不自动 confirm） | 复用已有审批/快照/审计/pending唯一约束，与作废审批一致可追溯 | 2026-07-08 |
| D30 | 偏离阈值采用比例 ±5%（常量 PRICE_DEVIATION_THRESHOLD=0.05，后续可改配置表） | 比例阈值适应高低价商品，避免微调噪音；常量先简后续可配置 | 2026-07-08 |
| D31 | 价格偏离审批仅销售单，销售退货沿用原销售单已审批价不重复审批 | 退货价继承已审批销售价，避免重复审批；范围小 | 2026-07-08 |
| D32 | 销售/采购"人员"含普通员工（role=employee）：员工可 create+read，库存变更 confirm 保持仓储收口（兼容 3.7），delete/void 保持 admin | 用户明确含员工；员工建单不动库存，与 3.7 职责分离兼容 | 2026-07-08 |
| D37 | 生产=新部门 `production`（生产研发部，既研发又生产），复用三档角色；管理员=admin+production（建/审生产任务、维护 BOM、派发、报废），员工=employee+production（领料/质检/入库/只读库存+BOM）。BOM 建档由该部门承担（承接研发职责） | 用户明确生产与研发一个部门；复用三档角色改动最小，与 D32 一致 | 2026-08-31 |
| D38 | 「生产入库/生产领料」从仓储端移到生产端（实际操作者=生产员工）；仓储端保留只读台账/查询 | 谁实际干活界面给谁；仓储收敛为报表角色 | 2026-08-31 |
| D39 | 生产人员只读看全部 `base_goods` 库存（数量层，不含金额/价格）；价格仍按 D36 归属（采购维护进价、销售见售价、仓储/生产不可见） | 延续 D36 价格归属原则，财务口径不向生产/仓储开放 | 2026-08-31 |
| D40 | 10 道工序不全追踪：8 装配工序作生产任务单静态工序清单（SOP 打印）；2 测试工序（首测/成品测）单独「质检记录」：测试员/结果(OK/NG)/时间/原因，NG→返工→重测→报废，合格才允许入库 | 追踪全部工序=整套 MES 代价大；测试是质量闸口必须落库，装配只作打印清单 | 2026-08-31 |
| D41 | `base_goods` 加 `type`（成品/物料）；新建 BOM 主从表（成品→明细：物料/单台用量/规格/材质/备注）；BOM 明细尽量关联 base_goods，无法关联做成不强关联说明行（不参与齐套） | 齐套预警须按 BOM 展开→自动算需求，必须能关联到库存件 | 2026-08-31 |
| D42 | 齐套预警：建生产任务单选成品×数量→展开 BOM 对比库存，够→正常、部分缺→标红/黄、严重缺→阻断开工+站内信通知采购管理员 | 掐掉"生产到一半缺料→现采购→工期延误"核心痛点 | 2026-08-31 |
| D43 | 领料单从生产任务单按 BOM 自动生成（避免手动对照漏领），生产端出库扣库存；成品质检合格后生产入库回填成品库存 | BOM 是唯一需求源，自动生单防漏领 | 2026-08-31 |
| D44 | 现有 `product_name` 保留但不作为权威"成品—物料"关系；新 BOM 系统以 BOM 表为准 | 避免迁移风险；product_name 此前只是自由文本标签 | 2026-08-31 |
| D45 | BOM 录入：生产研发部导入 xlsx（物料名称/规格/数量/材质/备注模板）+ 手工新增；用 PTO153 试点先跑通链路 | 现有大量成品 BOM 需批量录入；PTO153 文档作试点数据 | 2026-08-31 |
| D64 | 生产工序打卡追踪：新增 biz_production_order_step 仅落 7 道人工装配工序（第 6/8/10 道由质检/入库状态实时推导不落库），工序文案定稿 10 道；打卡不设流程闸口（质检仍是唯一闸口），生产成员可打卡、本人/生产管理员可撤销，仅生产中/待入库可操作；未完结存量单刷新快照+初始化，历史单保留；质检进度列表"未测" bug 由 page() 批量填 qcState 修复 | 用户要"动态"工序且确认走 A；打卡只是完成留痕≠报工 MES（D40 Avoid 收窄为报工级）；避免双源不一致 | 2026-09-09 |
| D65 | 主数据分域：base_goods 同表分页（物料管理/成品管理两页），成品允许手工建档（修订 D46，缺省供应商1/category=成品/预警0），成品全链路排除预警与缺货识别，BOM 导入按钮改名「BOM导入」 | 物料/成品字段差异大，混显干扰；成品无预警语义；导入一次一个 BOM 名不符实 | 2026-09-09 |
| D66 | 下达任务单下拉只列有有效 BOM 的成品（options hasBom 过滤）；删 BOM 软保护（未完结任务单>0 二次确认，force 放行）；删 BOM 安全级联（成品 stock=0 且无任何单据引用才软删主档，否则保留）；DROP uk_bom_goods/uk_bom_code 改应用层查重 | 删 BOM 后成品残留堵下达下拉是用户痛点；级联只清真孤儿；DB 唯一键不过滤软删行会误伤重建 | 2026-09-09 |
| D67 | 业务下拉收紧：销售下单/生产入库→仅成品(type=product)；商品进货/采购申请→仅物料(type=material)；生产任务单下达成品加 hasBom | 下拉只该出现业务语义内的事物，防选错 | 2026-09-09 |
| D68 | 销售部门（admin+employee）可见物料/成品管理两页（只读），成品管理页可单字段编辑标准售价（salePrice>0，镜像采购进价范式）；进价对销售隐藏；开单临时改价仍走超管偏离审批 | 定价权下放≠单据让价免审；成本保密；主数据不受销售污染 | 2026-09-10 |
| D69 | 销售建单零库存校验（允许超卖，待货销售单）；库存扣减仍在仓储确认出库硬校验兜底，仓储页缺货标红；不支持部分发货，整单到货一次出库 | 定制公司销售单=需求单，库存只代表现货可即发；部分发货改动大单独立项 | 2026-09-10 |
| D70 | 销售需求联动：缺货销售单创建→站内信通知生产管理员（biz 绑定，删/作废撤未读）；生产任务单加 sales_order_id 可空（一单最多关联一销售单，不建多对多）；关联生产单入库且销售单仍待出库→通知建单销售本人 | 销售不再挨个问生产；关联显式精准；自动生成生产单=越权决策不做 | 2026-09-10 |
| D71 | 履约时间线 8 节点（下单/排产/物料准备/开工/装配x-7/质检/入库/发货）挂销售单详情；预计可交付时间系统推算（缺料=max 补料行预计到货+工期；齐套=开工或当前+工期），仅供参考非承诺；biz_bom.lead_days 可空，新品留空显"待生产评估"，生产任务单手工修正预计完工（@AuditLog）优先于推算 | 数据全现成不加采集负担；工期是 BOM 属性且新品未知，设全局默认值会污染推算 | 2026-09-10 |
| D72 | E1 库存竞争维持现状——不做库存预留、不在出库拦截时点自动通知生产；仓储见"库存不足"红标退回销售人工协调；建单时的缺料/现货通知逻辑不变 | 预留机制复杂度高且当前业务量下人工协调够用；出库时点自动通知生产=跨域耦合，违背职责分离 | 2026-09-10 |
| D73 | E2 关联单取消联动——销售单删除/作废后通知关联未终态生产任务单的生产管理员（biz_type=production_order）；新增生产任务单状态 7=已终止（1/2/3 可终止、仅生产管理员、原因必填、不可逆、@AuditLog）；终止弹窗预填「已领未退」净额（PICK/SUPPLY 已发料起 − RETURN 已发料起）可改量，一个事务生成 RETURN 退料单由仓储确认回流入库；已有进行中退料单跳过自动生成并提示人工核对；已终止单打卡/质检冻结、时间线回退"待重新排产"、关联销售单号标注"已取消的销售单/（已作废）" | 销售取消不能直接越权终止生产（生产现场状态只有生产管理员能判断）；终止≠作废（语义不同，终止是"做了一半不做了"）；退料一个事务生成防多退虚增库存 | 2026-09-10 |
| D74 | E3 「作废并红冲」入门前端隐藏——五个业务页删红冲按钮（"仅作废"更名"作废"）、作废审批页删 void_red 选项（历史记录兜底显示）；后端红冲逻辑（biz_status=3 负向记录）完整保留 | 红冲是财务概念，当前业务不用但后端逻辑成熟，隐藏入口即可、不必删除；历史已红冲单据仍需可查看 | 2026-09-10 |
| D75 | 站内消息加 `target_route` 列：发送方显式指定跳转目标（18 个带 biz 发送点按「接收方该去哪处理」传入），前端优先使用、为空或不可达回落 BIZ_ROUTE_MAP 兜底；bizType 不细分，D21 生命周期语义不动（revokeUnreadByBiz 22 处调用点零影响）。ADR-0006 | bizType 被多意图复用（D21 生命周期/D70 需求/价格偏离），前端按角色猜路由是新意图猜错风险源；bizType 细分会破坏 D21 撤销匹配（漏改=消息悬挂） | 2026-09-11 |

---

## ⚠️ 待确认问题

### 阶段 20（E1/E2 已确认，见 D72/D73）
- **E1 库存竞争**：✅ 已确认（D72）——维持现状，不预留、不在出库拦截时点自动通知生产；仓储见"库存不足"红标退回销售人工协调。
- **E2 关联单取消**：✅ 已确认（D73）——销售单删除/作废后通知关联未终态生产任务单的生产管理员，由生产管理员判断是否「手动终止」并联动退料；不做越权自动终止。

### 阶段 1（已确认）
Q1–Q5 已全部确认，结论见决策记录 D6–D10。

### 阶段 2（已确认）
Q1–Q3 已全部确认，结论见决策记录 D11–D14。

### 阶段 3（已确认 2026-07-01）

Q1–Q6 已全部确认，结论见决策记录 D15–D20。无阻塞项。

---

## 📊 总体进度

- 完成度：阶段 1–3.7 + 5–8 + 9–21 编码 100% + 会话 27 三项体验优化（后端 179 单测全绿 + 前端 build + E2E 全通过）
- 当前阶段：会话 27 增量优化已交付（2026-09-11，commit e395161：生产端预警中心/消息跳转+浮窗/首页预警数字红色）；候选下一项：① 阶段 4（盘点/余料/成品追溯，需 grilling 定范围）② 阶段 16 登记的已知问题（补料单按行部分到货→确认入库终态后，受 D59「已入库补料单阻止再补料」约束，该生产单无法再补——待单独立项）③ defer 清理（会话 27 架构项 5 条 + 阶段 21 的 13 项 Minor）
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
