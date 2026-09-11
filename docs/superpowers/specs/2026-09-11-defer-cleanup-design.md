# Defer 清理设计 — 18 项（13 Minor + 5 架构债）

> 日期：2026-09-11（会话 28 brainstorming 定案，用户逐项确认）
> 来源：阶段 21 终审 defer 的 13 项 Minor（`.superpowers/sdd/progress.md`）+ 会话 27 评审 defer 的 5 项架构债（progress.md 会话 27 段）

## 已定决策

| 项 | 决策 | 说明 |
|---|---|---|
| 范围 | 全清 18 项 | 低风险先行，A 项最后做并单独评审 |
| A 项（消息跳转目标） | sys_message 加 `target_route` 列 | 发送方显式指定；旧 BIZ_ROUTE_MAP 留作存量兜底；bizType 不动 → revokeUnreadByBiz 22 处调用点零影响 |
| B 项（预警部门名单） | 前后端各收口一个常量 | 6 处 → 2 处，不加 API |
| 执行策略 | 分 4 组顺序提交 | 每组独立验证后进下一组；主会话直接实施（非 SDD 子代理） |
| #9（null bizStatus 标「已作废」） | 不改 | 防御性且安全，记档 |

**共同约束**：不改任何业务逻辑语义；库存零变动；负测看响应 body 的 code（HTTP 恒 200 包装）。

---

## 第 1 组：后端 Minor（#1/2/3/4/5/6/7/8）

涉及文件：`back/src/main/java/org/example/back/service/ProductionPickService.java` 及对应测试。

| # | 改动 | 位置 | 要点 |
|---|---|---|---|
| 4 | terminate DTO null 守卫显式化 | ProductionPickService.java:212 | 入口显式判 `dto == null`，错误文案保持「终止原因不能为空」（行为零变化） |
| 5 | createReturn items 过滤补 `goodsId != null` | ProductionPickService.java:135-137 | 与 terminate 行 219-221 口径对齐；null goodsId 从 400 变为静默过滤 |
| 6 | computeReturnablePreview 补 `@Transactional(readOnly = true)` | ProductionPickService.java:242 | 类级无注解，仅加方法级 |
| 7 | 补 4 类测试缺口 | ProductionPickServiceTest | ① SUPPLY 侧净额（补料已发料计入已领）② 退料明细不在已领清单分支 ③ appendRemark 备注追加 ④ 多物料混合 |
| 1 | QcServiceTest terminated 测试清理 | QcServiceTest.java:98-107 | 删内联构造/死 DTO 字段，改用既有 helper 范式 |
| 2 | SalesTimelineServiceTest terminated 测试清理 | SalesTimelineServiceTest.java:262-269 | 改用 `order(int status)` helper 传 `STATUS_TERMINATED` |
| 8 | salesOrderNo happy-path 测试 | SalesTimelineServiceTest | 新增一例：bizStatus=1 正常关联时 VO 映射 salesOrderNo |
| 3 | task-2-report 过时措辞修正 | `.superpowers/sdd/task-2-report.md` | 纯报告文件装饰 |

验证：`./mvnw clean compile` + 全量单测（179 → 预计 184+）。

---

## 第 2 组：前端 Minor（#10/12/13/14）

| # | 改动 | 位置 | 要点 |
|---|---|---|---|
| 10 | statusTagType 死分支 | ProductionOrderView.vue:1044 | 保留显式 `s === 7 ? 'danger'`，默认分支 `'danger'` → `'info'`（7 语义显式化，未知状态不再误标 danger） |
| 12 | loadList() await 一致性 | ProductionOrderView.vue | 仅改 async 上下文 5 处 fire-and-forget（refreshDetail:669 / doPickSubmit:721 / doReturnSubmit:780 / handleTerminate:896 / 补料 .then:1005）；同步回调（handleSearch/分页/onMounted）不动 |
| 13 | 红冲死三元清理 | SalesView:468 / PurchaseView:393 / PurchaseReturnView:399 / SalesReturnView:436 / ProductionView:345 | `handleVoid(row, createRedFlush)` 删第二参，三元直写（title「作废单据」/requestAction `'void'`）；模板 `handleVoid(scope.row, false)` → `handleVoid(scope.row)`；ProductionView payload 保留 `createRedFlush: false` 显式字段（后端 D74 红冲逻辑保留，契约不动）；VoidApprovalView:144-146 void_red 历史兜底**不动** |
| 14 | 缩进不齐 | 3 视图按钮块 | 纯格式化，零逻辑变化 |

验证：`npm run build` + diff 走查确认无逻辑行变动。

---

## 第 3 组：前端架构 B/C/E + 浮窗 D

### B 预警部门名单收口（6 处 → 2 处）

- 新建 `front/src/utils/constants.js` 导出 `WARNING_DEPT_CODES = ['warehouse','purchase','sales','production']`。
- 前端 3 处引用化：router/index.js:115-119（stock-warning meta deptCodes）、AdminHome.vue:140-149（hr/finance 反向排除改为 `WARNING_DEPT_CODES.includes(deptCode)` 正向判断，语义等价）、EmployeeHome.vue:283。
- layout/index.vue 预警菜单项是 per-dept 独立块（行 38/53/73/102），天然分散不强行收。
- 后端：GoodsService.java:63-75 四部门名单提取为 AuthzService 静态常量 `WARNING_DEPT_CODES`（`List<String>`），GoodsService 引用。

### C 守卫组合共享

- `front/src/utils/auth.js` 新增 `checkRouteAccess(meta, role, deptCode)` 返回 `{ ok, reason: 'role'|'dept'|null }`（复用既有 canAccessRoles/hasDeptAccess 原子函数）。
- `canAccessRouteMeta` 变薄包装（返回 `.ok`），对外签名不变。
- router/index.js:240-273 守卫改调 checkRouteAccess，按 reason 映射两条既有错误文案（「无权限访问该页面」/「当前部门无权限访问该页面」，文案不变）。
- 超管路径白名单（SUPERADMIN_ALLOWED_PATHS）保持守卫独有，不进共享函数。

### E Home CSS 抽共享

- 新建 `front/src/styles/home-metrics.css`：收纳 `.metric-value--alert`（当前 AdminHome.vue:469-472 与 EmployeeHome.vue:710-713 重复定义）及两组件重复的 metric 卡片样式段。
- main.js 导入一次；两组件删除各自重复块。

### D 浮窗 id 级基线（MessageCenter.vue）

弃用计数比较（lastUnreadCount，行 93/195-198），基线改为「未读 id 集合 + 未读总数」：

- 每 15s 轮询 `getMessagePageAPI({ pageNum: 1, pageSize: 10, read: false })`（既有接口，MessageQueryDTO.read 已支持过滤，page 返回 total）。
- 新消息 = 当前页记录中 id 不在基线集合的项（并集扣除 notifiedMessageIds 防复弹）→ ≤3 逐条弹（点击跳转）、>3 聚合一条（delta = currentTotal − baselineTotal）。
- 基线 id 集合每轮整体替换为当前页 id 集 + 更新 baselineTotal → 同窗口 +1/−1 抵消时，新 id 仍在差集中被检出（修复目标边界）。
- 保留三条既有语义：首轮不弹存量（首轮仅建基线）；基线先更新再弹窗防重入；轮询瞬时失败不清零角标/不重置基线。

验证：`npm run build` + 代码走查。

---

## 第 4 组：A 项 target_route（含 DDL，单独评审）

### DDL

`sys_message` 加列（db.sql 追加新段 + 本地执行）：

```sql
ALTER TABLE sys_message ADD COLUMN target_route VARCHAR(100) NULL COMMENT '跳转目标路由（前端优先使用，空则按 bizType 映射兜底）';
```

存量为 NULL，前端走 BIZ_ROUTE_MAP 兜底。

### 后端

- SysMessage 实体 + MessageVO 加 `targetRoute` 字段。
- `sendToDeptAdminsWithBiz`（MessageService.java:673）/ `sendToUserWithBiz` 加 route 形参；路由字面值收口为 MessageService 内常量。
- 18 个带 biz 的 sendXxx 逐一传目标路由（按「接收方该去哪处理」定）：

| sendXxx | biz_type | 接收方 | target_route |
|---|---|---|---|
| sendSalesPendingConfirmToWarehouseAdmins | sales | 仓储 | /business/sales |
| sendSalesDemandToProductionAdmins | sales | 生产 | /business/production-order |
| sendSalesReadyToShipToUser | sales | 建单销售本人 | /business/sales |
| sendSalesReturnPendingConfirmToWarehouseAdmins | sales_return | 仓储 | /business/sales-return |
| sendPickListFailureToSalesAdmins | pick_list | 销售 | /business/sales |
| sendPickIssuedToProductionAdmins | pick_list | 生产 | /business/pick-list |
| sendPickIssueFailedToProductionAdmins | pick_list | 生产 | /business/pick-list |
| sendPickReturnPendingToWarehouseAdmins | pick_list | 仓储 | /business/pick-list |
| sendPickPendingToWarehouseAdmins | pick_list | 仓储 | /business/pick-list |
| sendKitCompleteToProductionAdmins | production_order | 生产 | /business/production-order |
| sendKitShortageToPurchaseAdmins | production_order | 采购 | /business/purchase-request |
| sendSalesCancelledToProductionAdmins | production_order | 生产 | /business/production-order |
| sendPurchaseRequestToPurchaseAdmins | purchase_request | 采购 | /business/purchase-request |
| sendPurchaseRequestArrivedToWarehouseAdmins | purchase_request | 仓储 | /business/purchase-request |
| sendPurchaseRequestClaimedToSourceApplicant | purchase_request | 生产/仓储 | /business/purchase-request |
| sendPurchaseArrivedToWarehouseAdmins | purchase | 仓储 | /business/purchase |
| sendPurchaseReturnPendingConfirmToWarehouseAdmins | purchase_return | 仓储 | /business/purchase-return |
| sendPriceDeviationToSuperAdmin | sales | 超管 | /system/void-approval |

- 8 个无 biz 的人事/工作单消息传 null（无跳转目标）。
- bizType 不动 → `revokeUnreadByBiz` 全部 22 处调用点零影响（已核实清单）。

### 前端

- `resolveJumpPath`（MessageCenter.vue:127-135）改为：`msg.targetRoute && canAccessPath(targetRoute)` 优先；为空或不可达时回落 BIZ_ROUTE_MAP 候选数组（存量消息兼容）。
- 浮窗点击跳转同口径。
- 超管 sales→/system/void-approval 特判保留作存量兜底。

### 测试与验证

- MessageServiceTest 补 targetRoute 写库 + VO 透出断言。
- curl E2E：触发真实消息（如建缺货销售单）→ 验 DB 列值 → 验 page VO 透出 → 负测：无 route 存量消息兜底跳转逻辑不变。
- 全量单测 + `npm run build` + 重启两端 curl 复验。

---

## 提交计划

| 组 | 内容 | 验证门槛 |
|---|---|---|
| 1 | 后端 Minor #1-8 | clean compile + 全量单测绿 |
| 2 | 前端 Minor #10/12/13/14 | npm run build |
| 3 | 架构 B/C/E + 浮窗 D | npm run build + 走查 |
| 4 | A 项 target_route | DDL + 单测 + curl E2E + 重启复验，**单独评审后提交** |

推送走 VS Code 源代码管理面板（shell 推送会被分类器拦）。
