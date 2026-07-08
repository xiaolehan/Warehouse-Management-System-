# 任务规划 (Task Plan)

> 本文件用于记录阶段、进度与决策。会话中断后可据此恢复上下文。
> 最后更新：2026-07-04

---

## 📌 任务总览

**任务标题：** WMS 二次开发 — 生产领料模块

**目标描述：**
基于 `document/wms_v1.docx` 痛点 2（生产领料耗时久，错领漏领导致工期延长），在当前开源 WMS 上新增生产领料模块（领料/补料/退料 + 仓库发料 + 领料人确认复核），解决错领漏领问题。

**参考文档：**
- `document/wms系统改造参考参考资料.md`（§5.3 生产领料模块）
- `projectmd/生产领料模块开发任务清单.md`（可执行任务拆解）

**当前状态：** ✅ 阶段 1–8 全部完成（生产领料 + 销售协同 + 缺货采购 + 进退货确认 + 价格偏离超管审批 + 预计到货 + 领料反馈销售 + 员工权限放开）；六项需求改进全部落地

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

- 完成度：阶段 1 + 2 + 3 + 3.5 + 3.6 + 3.7 + 5 + 6 + 7 + 8 编码 100%（后端 + 前端 + 联调验收通过）
- 当前阶段：六项需求改进全部完成
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
