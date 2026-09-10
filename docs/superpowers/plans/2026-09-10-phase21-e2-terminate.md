# 阶段 21：E2 关联单取消联动 + E3 红冲入口隐藏 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 销售单删除/作废时通知关联未终态生产任务单的生产管理员；新增生产任务单"手动终止"（状态 7）并在同一事务内按"已领未退"净额生成退料单；前端隐藏"作废并红冲"入口（后端红冲逻辑不动）。

**Architecture:** 通知走 D21 消息生命周期范式（biz_type=production_order，终态撤未读）。终止逻辑放 ProductionPickService（已依赖 ProductionOrderService 与领退料 mapper，避免循环依赖），终止+退料单生成一个 `@Transactional`。E1 维持现状（零代码，仅注释/文档转确认）。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus（LambdaQueryWrapper，@TableLogic 软删）、JUnit5 + Mockito（STRICT_STUBS）、Vue3 + Element Plus、MySQL 8。

## Global Constraints

- 项目规则见根目录 `CLAUDE.md`，必须遵守：库存唯一入口是各业务 Service 私有 `increaseStock`/`decreaseStock`；业务单据发消息必须 `sendToDeptAdminsWithBiz` 绑 biz_type/biz_id，终态 `revokeUnreadByBiz`。
- 负测断言看响应 body 的 `code`（HTTP 恒 200 包装），别看 HTTP 状态码。
- 测试统一：`cd back && ./mvnw test`；测试类 `@ExtendWith(MockitoExtension.class)` 为 STRICT_STUBS——**不要 stub 用不到的 mapper 调用**（会 UnnecessaryStubbingException）。
- 后端验证后必须重启（`fuser -k 8080/tcp` 杀端口，禁用 `pkill -f "spring-boot:run"`——自杀陷阱）；重启后旧 token 失效，E2E 重新登录。
- 决策编号：D72=E1 维持现状（已确认）、D73=E2 取消通知+手动终止+终止退料联动、D74=E3 红冲入口前端隐藏。代码注释引用这些编号。
- 前端按钮权限指令：`v-permission="{ roles: ['admin'], deptCodes: ['production'] }"`（生产管理员）。
- 分支：全部任务在 feature 分支 `feat/phase21-e2-terminate` 上提交；合并推送留给收尾阶段与用户确认。

---

### Task 0: 建 feature 分支

**Files:**
- 无（git 操作）

- [ ] **Step 1: 建分支（工作区干净时 `checkout -b` 不动文件内容，无需停 dev 服务器）**

```bash
cd /home/niuchao/Warehouse-Management-System- && git status --short && git checkout -b feat/phase21-e2-terminate
```

Expected: `Switched to a new branch 'feat/phase21-e2-terminate'`，`git status --short` 无输出（干净）。

---

### Task 1: 新状态 7=已终止 —— 常量、状态文本扫荡、db.sql 注释

**Files:**
- Modify: `back/src/main/java/org/example/back/entity/BizProductionOrder.java`
- Modify: `back/src/main/java/org/example/back/service/ProductionOrderService.java`（`statusText` switch，约 590-603 行）
- Modify: `back/src/main/java/org/example/back/service/ProductionStepService.java`（`ensureOperableStatus`，约 251-269 行）
- Modify: `back/src/main/java/org/example/back/service/QcService.java`（`ensureTestable`，约 286-302 行）
- Modify: `back/src/main/java/org/example/back/service/SalesTimelineService.java`（41 行注释、139 行作废分支、331-344 `statusText`）
- Modify: `back/src/main/java/org/example/back/vo/ProductionOrderVO.java`（23 行状态注释）
- Modify: `db.sql`（1356 行建表注释 + 文件末尾追加 16.x 段）
- Test: `back/src/test/java/org/example/back/service/ProductionStepServiceTest.java`
- Test: `back/src/test/java/org/example/back/service/QcServiceTest.java`
- Test: `back/src/test/java/org/example/back/service/SalesTimelineServiceTest.java`

**Interfaces:**
- Produces: `BizProductionOrder.STATUS_TERMINATED = 7`（后续 Task 3/4/5/6 全部依赖）。**`UNFINISHED_STATUSES` 保持 `List.of(1,2,3)` 不变**（已终止不算未完——D66 BOM 保护等仍按 1/2/3 判断）。

- [ ] **Step 1: 先写失败测试 —— ProductionStepServiceTest 追加**

在 `ProductionStepServiceTest` 类内追加（该类已有 `@Mock orderMapper`、`@InjectMocks service`，断言静态导入已齐）：

```java
    // ---------- D73：已终止订单冻结（打卡/撤销拦截） ----------

    @Test
    void complete_terminatedOrder_throws() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 1));
        assertTrue(ex.getMessage().contains("已终止"));
    }

    @Test
    void revoke_terminatedOrder_throws() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.revoke(7L, 1));
        assertTrue(ex.getMessage().contains("已终止"));
    }
```

- [ ] **Step 2: QcServiceTest 追加**

（该类已有 `@Mock orderMapper`、`@InjectMocks service`、`assertThrows`/`assertTrue` 导入）

```java
    // ---------- D73：已终止订单冻结（质检拦截） ----------

    @Test
    void record_terminatedOrder_throws() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.record(7L, new org.example.back.dto.QcRecordDTO()));
        assertTrue(ex.getMessage().contains("已终止"));
    }
```

> 注：若 `QcService.record` 的方法签名不是 `(Long, QcRecordDTO)`，先 `grep -n "public .*record" back/src/main/java/org/example/back/service/QcService.java` 看真实签名再对齐测试调用。

- [ ] **Step 3: SalesTimelineServiceTest 追加**

（该类已有 `@Mock bizSalesMapper/baseGoodsMapper/productionOrderMapper`、helper `sales(quantity, confirmStatus)` 内部已 stub `bizSalesMapper.selectById(501L)`、`goodsWithStock(stock)` helper stub `baseGoodsMapper.selectById(29L)`）

```java
    // ---------- D73：关联生产单已终止 → 时间线回退待排产 ----------

    @Test
    void getTimeline_terminatedLinkedOrder_fallsBackToReschedule() {
        sales(5, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        BizProductionOrder order = new BizProductionOrder();
        order.setId(88L);
        order.setOrderNo("PRO260910001");
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(productionOrderMapper.selectOne(any())).thenReturn(order);

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals("待生产排产", vo.getEstimatedDeliveryText());
        assertTrue(vo.getNodes().stream().anyMatch(n -> "scheduled".equals(n.getKey())
                && n.getDescription() != null && n.getDescription().contains("已终止")));
    }
```

> 注：`goodsWithStock` 的确切 helper 名以该文件现状为准（`grep -n "private void goods" SalesTimelineServiceTest.java`）；若不存在则内联 stub `when(baseGoodsMapper.selectById(29L)).thenReturn(goods)`。

- [ ] **Step 4: 跑测试确认全红（STATUS_TERMINATED 不存在，编译失败即视为红灯）**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q -Dtest='ProductionStepServiceTest,QcServiceTest,SalesTimelineServiceTest'
```

Expected: COMPILATION ERROR（`STATUS_TERMINATED` 找不到符号）。

- [ ] **Step 5: 实现 —— BizProductionOrder 常量**

`BizProductionOrder.java` 常量区（`STATUS_SCRAPPED = 6` 之后）追加：

```java
    /** D73：已终止（销售取消等外部原因中途停单，终态不可逆；区别于作废=单据不该存在） */
    public static final int STATUS_TERMINATED = 7;
```

同时把类头/`status` 字段的状态注释改为 `1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废, 6-已报废, 7-已终止`。`UNFINISHED_STATUSES` 保持 `List.of(1, 2, 3)` 不动。

- [ ] **Step 6: 实现 —— ProductionOrderService.statusText**

switch 中 `STATUS_SCRAPPED -> "已报废";` 之后加一行：

```java
            case BizProductionOrder.STATUS_TERMINATED -> "已终止";
```

- [ ] **Step 7: 实现 —— ProductionStepService.ensureOperableStatus**

在 VOIDED 分支（抛"订单已作废…"）之后、SCRAPPED 分支之前/后任意处加：

```java
        if (st == BizProductionOrder.STATUS_TERMINATED) {
            throw BusinessException.validateFail("订单已终止，不能再打卡/撤销");
        }
```

（原有兜底的"当前状态不可操作"分支保留，terminated 走专属文案。）

- [ ] **Step 8: 实现 —— QcService.ensureTestable**

在 VOIDED 分支后加：

```java
        if (st == BizProductionOrder.STATUS_TERMINATED) {
            throw BusinessException.validateFail("该订单已终止，无法质检");
        }
```

- [ ] **Step 9: 实现 —— SalesTimelineService 三处**

① 约 41 行注释：`现货判断不预留库存（E1 暂定口径，先出库先赢）。` →
```java
        // 现货判断不预留库存（D72 已确认口径：先出库先赢，不做预留，出库硬校验拦截后退回人工协调）
```

② `buildOrderNodes` 作废分支（约 139 行）改为同时覆盖已终止，文案用 statusText：

```java
        // 关联单已作废/已终止 → 排产回退为待排产（D73：关联保留，时间线如实反映）
        if (order.getStatus() != null && (order.getStatus() == BizProductionOrder.STATUS_VOIDED
                || order.getStatus() == BizProductionOrder.STATUS_TERMINATED)) {
            nodes.add(node("scheduled", "生产排产", "pending", null,
                    "关联生产任务单 " + order.getOrderNo() + " " + statusText(order.getStatus()) + "，待重新排产"));
            nodes.add(node("shipped", "发货", "pending", null, null));
            vo.setEstimatedDeliveryText("待生产排产");
            vo.setEstimatedSource("none");
            return;
        }
```

③ `statusText` switch 加：

```java
            case BizProductionOrder.STATUS_TERMINATED -> "已终止";
```

- [ ] **Step 10: 实现 —— ProductionOrderVO 注释 + db.sql**

`ProductionOrderVO.java` 状态字段注释补全为 `1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废, 6-已报废, 7-已终止`。

`db.sql` 两处：
① 约 1356 行 `biz_production_order.status` 列注释改为同上全量文案；
② 文件末尾追加：

```sql
-- =====================================================================
-- 16. 阶段 21：E2 关联单取消联动（D73）—— 生产任务单新状态 7=已终止
-- 无结构变更（status 为 TINYINT），仅同步列注释；存量库执行本段即可。
-- =====================================================================
ALTER TABLE `biz_production_order`
    MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-待生产, 2-生产中, 3-待质检, 4-已完成, 5-已作废, 6-已报废, 7-已终止';
```

然后本地执行：`mysql -u wms_user -pwms_pass warehouse_management < db.sql`（全文件幂等不可重放时，只把上面这段 ALTER 单独执行：`echo "<该ALTER>" | mysql -u wms_user -pwms_pass warehouse_management`）。

- [ ] **Step 11: 跑测试确认转绿**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q -Dtest='ProductionStepServiceTest,QcServiceTest,SalesTimelineServiceTest,ProductionOrderServiceTest'
```

Expected: BUILD SUCCESS（4 个类全过；ProductionOrderServiceTest 回归）。

- [ ] **Step 12: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System- && git add back/src/main/java/org/example/back/entity/BizProductionOrder.java back/src/main/java/org/example/back/service/ProductionOrderService.java back/src/main/java/org/example/back/service/ProductionStepService.java back/src/main/java/org/example/back/service/QcService.java back/src/main/java/org/example/back/service/SalesTimelineService.java back/src/main/java/org/example/back/vo/ProductionOrderVO.java back/src/test/java/org/example/back/service/ db.sql && git commit -m "feat(production): 阶段21 D73 新状态7=已终止——常量+打卡/质检/时间线状态扫荡（D72 E1口径转确认注释）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: 销售单删除/作废 → 通知关联未终态生产任务单

**Files:**
- Modify: `back/src/main/java/org/example/back/service/MessageService.java`（新增方法，仿 649-672 `sendToDeptAdminsWithBiz` 用法）
- Modify: `back/src/main/java/org/example/back/service/SalesService.java`（新增 mapper 依赖 + 私有方法；`delete()` 338-352 与 `voidDocument()` 373-424 各插一行调用）
- Test: `back/src/test/java/org/example/back/service/SalesServiceTest.java`

**Interfaces:**
- Consumes: `MessageService.resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION)`（已有，见 MessageService:257）；`BizProductionOrder.UNFINISHED_STATUSES`（Task 1 保持 1/2/3）。
- Produces: `MessageService.sendSalesCancelledToProductionAdmins(String salesNo, String goodsName, Integer quantity, String orderNo, Long orderId, String cancelAction)`（Task 4 不依赖；仅 SalesService 用）。

- [ ] **Step 1: 先写失败测试 —— SalesServiceTest 追加**

先给类加 mock 字段（紧跟现有 @Mock 群）：

```java
    @Mock private org.example.back.mapper.BizProductionOrderMapper bizProductionOrderMapper;
```

再追加三个测试（`SalesService.CONFIRM_PENDING` 是已有常量；`AuthzService.DEPT_SALES` / `DEPT_WAREHOUSE` 已有；`LocalDateTime`/`List`/`never`/`verify`/`eq` 按文件现有导入补齐）：

```java
    // ---------- D73：销售单删除/作废 → 通知关联未终态生产任务单 ----------

    private BizSales deletableSales() {
        BizSales entity = new BizSales();
        entity.setId(501L);
        entity.setSalesNo("XS260910001");
        entity.setGoodsId(29L);
        entity.setGoodsName("PTO153");
        entity.setQuantity(5);
        entity.setBizStatus(1);
        entity.setConfirmStatus(SalesService.CONFIRM_PENDING);
        entity.setOperationTime(LocalDateTime.now());
        return entity;
    }

    private BizProductionOrder linkedOrder(int status) {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(88L);
        order.setOrderNo("PRO260910001");
        order.setStatus(status);
        return order;
    }

    @Test
    void delete_withUnfinishedLinkedOrder_notifiesProduction() {
        when(bizSalesMapper.selectById(501L)).thenReturn(deletableSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of(linkedOrder(1)));

        service.delete(501L);

        verify(messageService).sendSalesCancelledToProductionAdmins(
                eq("XS260910001"), eq("PTO153"), eq(5), eq("PRO260910001"), eq(88L), eq("删除"));
    }

    @Test
    void delete_withFinishedLinkedOrder_doesNotNotify() {
        when(bizSalesMapper.selectById(501L)).thenReturn(deletableSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);
        // SQL 层 in(1,2,3) 过滤，已完工/已作废/已报废/已终止的关联单不会返回
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of());

        service.delete(501L);

        verify(messageService, never()).sendSalesCancelledToProductionAdmins(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void voidDocument_withUnfinishedLinkedOrder_notifiesWith作废() {
        when(bizSalesMapper.selectById(501L)).thenReturn(deletableSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizSalesMapper.update(any(), any())).thenReturn(1);
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of(linkedOrder(2)));

        service.voidDocument(501L, null);

        verify(messageService).sendSalesCancelledToProductionAdmins(
                eq("XS260910001"), eq("PTO153"), eq(5), eq("PRO260910001"), eq(88L), eq("作废"));
    }
```

> 注：若 SalesServiceTest 已有 `delete`/`voidDocument` 相关测试，它们不 stub `bizProductionOrderMapper` 也能过——Mockito 对返回 List 的方法默认返回空 List，新调用不会 NPE。若该类对 `messageService` 的其它 stub 与本测试冲突（如 `lenient`），以编译/运行结果微调。

- [ ] **Step 2: 跑测试确认红（sendSalesCancelledToProductionAdmins 不存在 → 编译失败）**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q -Dtest='SalesServiceTest'
```

Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现 —— MessageService 新方法**

`MessageService.java` 中（放在 `sendPickReturnPendingToWarehouseAdmins` 附近，与既有 sendXxx 方法同构）：

```java
    /**
     * D73：销售单删除/作废生效后，其关联的未终态生产任务单 → 通知生产管理员手动终止并退料。
     * 绑 biz_type=production_order + biz_id=生产单id：生产单终止/作废/报废/入库终态时随 D21 范式撤未读。
     */
    public void sendSalesCancelledToProductionAdmins(String salesNo, String goodsName, Integer quantity,
                                                     String orderNo, Long orderId, String cancelAction) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "关联销售单已取消",
                String.format(Locale.ROOT,
                        "销售单 %s（成品 %s×%d）已%s，其关联的生产任务单 %s 仍未完结。请评估后手动终止该任务单（终止时系统将预填已领未退物料供退料回库）。",
                        salesNo == null ? "-" : salesNo,
                        goodsName == null ? "-" : goodsName,
                        quantity == null ? 0 : quantity,
                        cancelAction == null ? "取消" : cancelAction,
                        orderNo == null ? "-" : orderNo),
                "production_order",
                orderId);
    }
```

- [ ] **Step 4: 实现 —— SalesService 接线**

① 类内 @Autowired 字段群末尾加：

```java
    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;
```

② import 区加：

```java
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BizProductionOrderMapper;
```

③ 新增私有方法（放在 `delete` 之后任意位置）：

```java
    /**
     * D73：销售单删除/作废生效后，若存在关联的未终态生产任务单 → 通知生产管理员手动终止+退料。
     * 已完工/已作废/已报废/已终止的生产单不打扰（SQL 层 in 过滤）；关联保留，生产端列表标注"已取消"。
     */
    private void notifyLinkedProductionOrderIfUnfinished(BizSales entity, String cancelAction) {
        LambdaQueryWrapper<BizProductionOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizProductionOrder::getSalesOrderId, entity.getId())
                .in(BizProductionOrder::getStatus, BizProductionOrder.UNFINISHED_STATUSES);
        for (BizProductionOrder order : bizProductionOrderMapper.selectList(wrapper)) {
            messageService.sendSalesCancelledToProductionAdmins(
                    entity.getSalesNo(), entity.getGoodsName(), entity.getQuantity(),
                    order.getOrderNo(), order.getId(), cancelAction);
        }
    }
```

④ `delete()` 中 `revokePriceDeviationApprovals(id);` 之后加一行：

```java
        notifyLinkedProductionOrderIfUnfinished(entity, "删除");
```

⑤ `voidDocument()` 中 `revokePriceDeviationApprovals(id);` 之后加一行：

```java
        notifyLinkedProductionOrderIfUnfinished(entity, "作废");
```

> 注意：`voidDocument` 里该行要放在事务内、stock 回补逻辑之后即可（同事务，失败整体回滚）。

- [ ] **Step 5: 跑测试确认转绿**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q -Dtest='SalesServiceTest'
```

Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System- && git add back/src/main/java/org/example/back/service/MessageService.java back/src/main/java/org/example/back/service/SalesService.java back/src/test/java/org/example/back/service/SalesServiceTest.java && git commit -m "feat(sales): 阶段21 D73 销售单删除/作废通知关联未终态生产任务单（biz_type=production_order）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: 手动终止 + 「已领未退」预览（ProductionPickService 核心）

**Files:**
- Create: `back/src/main/java/org/example/back/dto/ProductionTerminateDTO.java`
- Create: `back/src/main/java/org/example/back/vo/ProductionReturnableVO.java`
- Modify: `back/src/main/java/org/example/back/vo/ProductionPickItemVO.java`（加 spec/material 两个可空字段）
- Modify: `back/src/main/java/org/example/back/service/ProductionPickService.java`（新增 terminate/computeReturnablePreview + 私有助手；createReturn 抽取 insertReturnList）
- Test: `back/src/test/java/org/example/back/service/ProductionPickServiceTest.java`

**Interfaces:**
- Consumes: `BizProductionOrder.STATUS_TERMINATED/UNFINISHED_STATUSES`（Task 1）；既有 `applyGoodsSnapshot`、`loadGoodsSnapshot`、`sendPickReturnPendingToWarehouseAdmins`、`PickListService.TYPE_PICK/TYPE_SUPPLY/TYPE_RETURN`、`STATUS_PENDING/STATUS_ISSUED/STATUS_DONE/STATUS_REJECTED`。
- Produces（Task 4 依赖）:
  - `void ProductionPickService.terminate(Long orderId, ProductionTerminateDTO dto)`
  - `ProductionReturnableVO ProductionPickService.computeReturnablePreview(Long orderId)`
  - `ProductionTerminateDTO { @NotBlank String reason; @Valid List<ProductionReturnItemDTO> items; }`
  - `ProductionReturnableVO { List<ProductionPickItemVO> items; Boolean hasOpenReturn; String openReturnPickNo; }`
  - `ProductionPickItemVO` 新增 `String spec; String material;`（可空）

- [ ] **Step 1: 先写失败测试 —— ProductionPickServiceTest 追加**

（该类已有 `@BeforeAll` 初始化 TableInfo：`BizPickList`/`BizPickListDetail`/`BaseGoods`；mock 名为 `pickListMapper/pickListDetailMapper/productionOrderMapper/baseGoodsMapper/authService/authzService/messageService`；`@InjectMocks service`。需补 import：`ProductionTerminateDTO`、`ProductionReturnableVO`、`static org.mockito.Mockito.never`。）

在类内追加 helpers + 7 个测试：

```java
    // ============================== D73：手动终止 + 已领未退预览 ==============================

    private BizProductionOrder terminatableOrder(int status) {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PRO-X");
        order.setGoodsId(29L);
        order.setGoodsName("PTO153");
        order.setQuantity(2);
        order.setStatus(status);
        order.setRemark("原备注");
        return order;
    }

    private BizPickList pickListOf(long id, String type, int status) {
        BizPickList p = new BizPickList();
        p.setId(id);
        p.setPickNo("PICK-" + id);
        p.setPickType(type);
        p.setStatus(status);
        p.setProductionOrderId(7L);
        return p;
    }

    private BizPickListDetail detailOf(long pickListId, long goodsId, String name, int qty) {
        BizPickListDetail d = new BizPickListDetail();
        d.setPickListId(pickListId);
        d.setGoodsId(goodsId);
        d.setGoodsName(name);
        d.setQuantity(qty);
        return d;
    }

    /** 终止前置：订单存在 + 无待出库领料单 + 当前用户 */
    private void mockTerminateBase(BizProductionOrder order) {
        when(productionOrderMapper.selectById(7L)).thenReturn(order);
        when(pickListMapper.selectCount(any())).thenReturn(0L);
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(3L);
        user.setRealName("生产管理员");
        when(authService.getUserInfo()).thenReturn(user);
    }

    /** 一张已发料 PICK 单含 goods 50 ×qty（已领未退净额=qty）；不含 insertReturnList 的 selectById stub */
    private void mockReturnableData(int qty) {
        when(pickListMapper.selectList(any()))
                .thenReturn(List.of(pickListOf(10L, PickListService.TYPE_PICK, PickListService.STATUS_ISSUED)));
        when(pickListDetailMapper.selectList(any()))
                .thenReturn(List.of(detailOf(10L, 50L, "螺丝", qty)));
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g));
    }

    private ProductionTerminateDTO terminateDTO(String reason, long goodsId, int qty) {
        ProductionReturnItemDTO item = new ProductionReturnItemDTO();
        item.setGoodsId(goodsId);
        item.setQuantity(qty);
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason(reason);
        dto.setItems(List.of(item));
        return dto;
    }

    @Test
    void terminate_inProgressWithItems_terminatesAndCreatesReturnList() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        when(pickListMapper.selectOne(any())).thenReturn(null); // 无进行中退料单
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        when(baseGoodsMapper.selectById(50L)).thenReturn(g); // insertReturnList 明细快照

        service.terminate(7L, terminateDTO("销售交易单 SAL-1 已取消", 50L, 4));

        ArgumentCaptor<BizProductionOrder> orderCap = ArgumentCaptor.forClass(BizProductionOrder.class);
        verify(productionOrderMapper).updateById(orderCap.capture());
        assertEquals(BizProductionOrder.STATUS_TERMINATED, orderCap.getValue().getStatus());
        assertTrue(orderCap.getValue().getRemark().contains("终止原因: 销售交易单 SAL-1 已取消"));

        ArgumentCaptor<BizPickList> pickCap = ArgumentCaptor.forClass(BizPickList.class);
        verify(pickListMapper).insert(pickCap.capture());
        assertEquals(PickListService.TYPE_RETURN, pickCap.getValue().getPickType());
        assertEquals(PickListService.STATUS_PENDING, pickCap.getValue().getStatus());

        ArgumentCaptor<BizPickListDetail> detCap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(detCap.capture());
        assertEquals(4, detCap.getValue().getQuantity());

        verify(messageService).revokeUnreadByBiz("production_order", 7L);
        verify(messageService).sendPickReturnPendingToWarehouseAdmins(any(), eq("PRO-X"), any());
    }

    @Test
    void terminate_doneStatus_throws() {
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_DONE));
        assertThrows(BusinessException.class, () -> service.terminate(7L, terminateDTO("x", 50L, 1)));
    }

    @Test
    void terminate_blankReason_throws() {
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        assertThrows(BusinessException.class, () -> service.terminate(7L, terminateDTO("  ", 50L, 1)));
    }

    @Test
    void terminate_pendingPickExists_throws() {
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        when(pickListMapper.selectCount(any())).thenReturn(1L); // 有待出库领料单
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate(7L, terminateDTO("x", 50L, 1)));
        assertTrue(ex.getMessage().contains("待出库的领料单"));
    }

    @Test
    void terminate_qtyExceedsReturnable_throws() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate(7L, terminateDTO("x", 50L, 7)));
        assertTrue(ex.getMessage().contains("超过已领未退"));
    }

    @Test
    void terminate_noItems_onlyTerminates() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_PENDING));
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("销售单取消");
        dto.setItems(List.of());

        service.terminate(7L, dto);

        ArgumentCaptor<BizProductionOrder> orderCap = ArgumentCaptor.forClass(BizProductionOrder.class);
        verify(productionOrderMapper).updateById(orderCap.capture());
        assertEquals(BizProductionOrder.STATUS_TERMINATED, orderCap.getValue().getStatus());
        verify(pickListMapper, never()).insert(any());
        verify(messageService).revokeUnreadByBiz("production_order", 7L);
    }

    @Test
    void terminate_openReturnExists_skipsAutoCreate() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        when(pickListMapper.selectOne(any()))
                .thenReturn(pickListOf(11L, PickListService.TYPE_RETURN, PickListService.STATUS_PENDING));

        service.terminate(7L, terminateDTO("销售单取消", 50L, 6));

        verify(productionOrderMapper).updateById(any());
        verify(pickListMapper, never()).insert(any());
        verify(messageService).revokeUnreadByBiz("production_order", 7L);
    }

    @Test
    void computeReturnablePreview_netOfIssuedMinusReturned() {
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pickListOf(10L, PickListService.TYPE_PICK, PickListService.STATUS_ISSUED),
                pickListOf(11L, PickListService.TYPE_RETURN, PickListService.STATUS_ISSUED)));
        when(pickListDetailMapper.selectList(any())).thenReturn(
                List.of(detailOf(10L, 50L, "螺丝", 10)),  // 第一次：出库明细
                List.of(detailOf(11L, 50L, "螺丝", 4)));  // 第二次：退回明细
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        g.setSpec("M3");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g));
        when(pickListMapper.selectOne(any())).thenReturn(null);

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(1, vo.getItems().size());
        assertEquals(6, vo.getItems().get(0).getQuantity());
        assertEquals("M3", vo.getItems().get(0).getSpec());
        assertEquals(Boolean.FALSE, vo.getHasOpenReturn());
    }
```

> 注：`ArgumentCaptor`/`eq`/`verify`/`any`/`assertThrows`/`assertTrue`/`assertEquals` 该类大多已导入；缺什么补什么。`LoginResponse`/`PickListService` 与该类同包或已导入，按编译器提示补。

- [ ] **Step 2: 跑测试确认红（terminate/computeReturnablePreview/DTO/VO 不存在 → 编译失败）**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q -Dtest='ProductionPickServiceTest'
```

Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现 —— 新建 ProductionTerminateDTO**

```java
package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * D73：生产任务单手动终止入参。reason 必填（审计留痕）；
 * items 为终止退料明细（已领未退净额预填后生产管理员可改），空=无待退物料仅终止。
 */
@Data
public class ProductionTerminateDTO {

    @NotBlank(message = "终止原因不能为空")
    private String reason;

    @Valid
    private List<ProductionReturnItemDTO> items;
}
```

- [ ] **Step 4: 实现 —— 新建 ProductionReturnableVO**

```java
package org.example.back.vo;

import lombok.Data;

import java.util.List;

/**
 * D73：终止弹窗的「已领未退」预览。items=净额明细（PICK/SUPPLY 已发料起 − RETURN 已发料起，按物料分组取 >0）；
 * hasOpenReturn=true 时终止将跳过自动生成退料单（前端提示人工在既有退料单中核对覆盖）。
 */
@Data
public class ProductionReturnableVO {
    private List<ProductionPickItemVO> items;
    private Boolean hasOpenReturn;
    private String openReturnPickNo;
}
```

- [ ] **Step 5: 实现 —— ProductionPickItemVO 加两个可空字段**

类内追加：

```java
    /** 规格（终止退料预览等展示场景填充，可空） */
    private String spec;
    /** 材质（同上） */
    private String material;
```

- [ ] **Step 6: 实现 —— ProductionPickService：抽取 insertReturnList 并重写 createReturn**

`createReturn` 整方法替换为（守卫不变，循环/插入逻辑下沉到私有方法）：

```java
    /**
     * 生产端退料：按实际退回物料明细生成 RETURN 类型领料单，交仓储确认回流入库。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createReturn(Long orderId, ProductionReturnCreateDTO dto) {
        requireProductionMember();
        BizProductionOrder order = productionOrderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        if (order.getStatus() != BizProductionOrder.STATUS_IN_PROGRESS) {
            throw BusinessException.validateFail("仅生产中状态可退料");
        }
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            throw BusinessException.validateFail("请选择退料明细");
        }

        // 防止堆叠：同一张生产任务单已有待处理的退料单则拒绝（已完成/已驳回的可重提）
        if (findOpenReturn(orderId) != null) {
            throw BusinessException.validateFail("该生产任务单已有待确认的退料单");
        }

        List<ProductionReturnItemDTO> items = dto.getItems().stream()
                .filter(i -> i.getQuantity() != null && i.getQuantity() > 0)
                .toList();
        if (items.isEmpty()) {
            throw BusinessException.validateFail("无有效退料明细行");
        }

        String remark = "生产任务单 " + order.getOrderNo() + " 退料";
        if (dto.getRemark() != null && !dto.getRemark().isEmpty()) {
            remark += "：" + dto.getRemark();
        }
        insertReturnList(order, items, remark);
    }
```

新增私有方法（放在 `createReturn` 之后）：

```java
    /** 与 createReturn 防堆叠口径一致：RETURN 且状态非 已完成/已驳回 → 视为进行中退料单 */
    private BizPickList findOpenReturn(Long orderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId)
                .eq(BizPickList::getPickType, PickListService.TYPE_RETURN)
                .notIn(BizPickList::getStatus, PickListService.STATUS_DONE, PickListService.STATUS_REJECTED)
                .orderByDesc(BizPickList::getId)
                .last("LIMIT 1");
        return pickListMapper.selectOne(w);
    }

    /** 生成 RETURN 退料单（待发料）+ 明细（D63 主数据快照）+ 通知仓储确认入库；调用方负责状态/堆叠守卫 */
    private void insertReturnList(BizProductionOrder order, List<ProductionReturnItemDTO> items, String remark) {
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizPickList pick = new BizPickList();
        pick.setPickNo(CodeGenerator.pickListNo());
        pick.setPickType(PickListService.TYPE_RETURN);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(order.getId());
        pick.setApplicantId(user.getId());
        pick.setApplicantName(user.getRealName());
        pick.setRemark(remark);
        pickListMapper.insert(pick);

        int sortNo = 0;
        for (ProductionReturnItemDTO item : items) {
            BaseGoods goods = baseGoodsMapper.selectById(item.getGoodsId());
            if (goods == null) {
                throw BusinessException.validateFail(
                        String.format(Locale.ROOT, "物料不存在: id=%d", item.getGoodsId()));
            }
            BizPickListDetail det = new BizPickListDetail();
            det.setPickListId(pick.getId());
            det.setGoodsId(goods.getId());
            det.setGoodsName(goods.getGoodsName());
            det.setQuantity(item.getQuantity());
            applyGoodsSnapshot(det, goods);
            det.setSortNo(sortNo++);
            pickListDetailMapper.insert(det);
        }

        messageService.sendPickReturnPendingToWarehouseAdmins(pick.getPickNo(), order.getOrderNo(), pick.getId());
    }
```

> 行为核对：原 createReturn 在循环里 `continue` 跳过无效行、全无效时抛"无有效退料明细行"；新版先 filter 再判空，等价。原 `pick.setProductionOrderId(orderId)` 改为 `order.getId()`，同值。

- [ ] **Step 7: 实现 —— ProductionPickService 新增终止与预览**

import 区补：`org.example.back.dto.ProductionTerminateDTO`、`org.example.back.vo.ProductionReturnableVO`、`java.util.ArrayList`、`java.util.HashMap`、`java.util.LinkedHashMap`。

在 `createReturn` 区块后追加：

```java
    // ============================== 手动终止（D73） ==============================

    /**
     * D73：手动终止生产任务单（销售取消等外部原因中途停单；区别于作废=单据不该存在）。
     * 仅生产管理员；待生产/生产中/待入库可终止，终态不可逆；原因必填（审计留痕）。
     * 同事务按提交的退料明细生成 RETURN 退料单（待发料，仓储确认后回流入库）；
     * 已有进行中退料单时跳过自动生成（前端已提示人工核对覆盖）；无明细则仅终止。
     */
    @Transactional(rollbackFor = Exception.class)
    public void terminate(Long orderId, ProductionTerminateDTO dto) {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_PRODUCTION, "仅生产研发部管理员可终止生产任务单");
        BizProductionOrder order = productionOrderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        if (order.getStatus() == null || !BizProductionOrder.UNFINISHED_STATUSES.contains(order.getStatus())) {
            throw BusinessException.validateFail("仅待生产/生产中/待入库状态可终止");
        }
        String reason = dto == null ? null : dto.getReason();
        if (reason == null || reason.trim().isEmpty()) {
            throw BusinessException.validateFail("终止原因不能为空");
        }
        // 待出库领料单未收口：仓储仍可能发料给一张死单，先人工收口（撤销/驳回）再终止
        ensureNoPendingPick(orderId);

        List<ProductionReturnItemDTO> items = dto.getItems() == null ? List.of() : dto.getItems().stream()
                .filter(i -> i.getGoodsId() != null && i.getQuantity() != null && i.getQuantity() > 0)
                .toList();
        if (!items.isEmpty()) {
            ensureWithinReturnable(orderId, items);
        }

        // 终态化 + 原因留痕 + 撤未读（含"关联销售单已取消"通知——已处理完毕）
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        order.setRemark(appendRemark(order.getRemark(), "终止原因: " + reason.trim()));
        productionOrderMapper.updateById(order);
        messageService.revokeUnreadByBiz("production_order", orderId);

        // Q6 定案：已有进行中退料单 → 跳过自动生成
        if (!items.isEmpty() && findOpenReturn(orderId) == null) {
            insertReturnList(order, items, "生产任务单 " + order.getOrderNo() + " 终止退料：" + reason.trim());
        }
    }

    /**
     * D73：终止弹窗「已领未退」预览 = PICK/SUPPLY（已发料/已完成）− RETURN（已发料/已完成），按物料分组取净额>0。
     * 生产成员可读（与领料/退料一致）。
     */
    public ProductionReturnableVO computeReturnablePreview(Long orderId) {
        requireProductionMember();
        ProductionReturnableVO vo = new ProductionReturnableVO();
        vo.setItems(computeNetReturnableItems(orderId));
        BizPickList open = findOpenReturn(orderId);
        vo.setHasOpenReturn(open != null);
        vo.setOpenReturnPickNo(open == null ? null : open.getPickNo());
        return vo;
    }

    private void ensureNoPendingPick(Long orderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId)
                .in(BizPickList::getPickType, PickListService.TYPE_PICK, PickListService.TYPE_SUPPLY)
                .eq(BizPickList::getStatus, PickListService.STATUS_PENDING);
        if (pickListMapper.selectCount(w) > 0) {
            throw BusinessException.validateFail("存在待出库的领料单，请先由申请人撤销或仓储驳回后再终止");
        }
    }

    /** 服务端兜底：提交退料量不得超过「已领未退」净额，防多退导致库存虚增 */
    private void ensureWithinReturnable(Long orderId, List<ProductionReturnItemDTO> items) {
        Map<Long, Integer> returnable = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (ProductionPickItemVO vo : computeNetReturnableItems(orderId)) {
            returnable.put(vo.getGoodsId(), vo.getQuantity());
            names.put(vo.getGoodsId(), vo.getGoodsName());
        }
        for (ProductionReturnItemDTO item : items) {
            Integer max = returnable.get(item.getGoodsId());
            if (max == null) {
                throw BusinessException.validateFail(
                        "物料[id=" + item.getGoodsId() + "]不在该任务单已领未退清单内，不可退");
            }
            if (item.getQuantity() > max) {
                throw BusinessException.validateFail(
                        "物料[" + names.get(item.getGoodsId()) + "]退料数量超过已领未退（可退 " + max + "）");
            }
        }
    }

    private List<ProductionPickItemVO> computeNetReturnableItems(Long orderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId)
                .in(BizPickList::getStatus, PickListService.STATUS_ISSUED, PickListService.STATUS_DONE);
        List<BizPickList> lists = pickListMapper.selectList(w);
        if (lists.isEmpty()) {
            return List.of();
        }
        List<Long> outIds = lists.stream()
                .filter(p -> !PickListService.TYPE_RETURN.equals(p.getPickType()))
                .map(BizPickList::getId).toList();
        List<Long> inIds = lists.stream()
                .filter(p -> PickListService.TYPE_RETURN.equals(p.getPickType()))
                .map(BizPickList::getId).toList();
        Map<Long, Integer> net = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        addDetailSums(outIds, net, names, 1);
        addDetailSums(inIds, net, names, -1);
        List<Long> goodsIds = net.entrySet().stream()
                .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
        Map<Long, BaseGoods> goodsMap = loadGoodsSnapshot(goodsIds);
        List<ProductionPickItemVO> items = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : net.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            ProductionPickItemVO item = new ProductionPickItemVO();
            item.setGoodsId(e.getKey());
            item.setGoodsName(names.get(e.getKey()));
            BaseGoods g = goodsMap.get(e.getKey());
            if (g != null) {
                item.setSpec(g.getSpec());
                item.setMaterial(g.getMaterial());
            }
            item.setQuantity(e.getValue());
            items.add(item);
        }
        return items;
    }

    private void addDetailSums(List<Long> pickListIds, Map<Long, Integer> net, Map<Long, String> names, int sign) {
        if (pickListIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<BizPickListDetail> w = new LambdaQueryWrapper<>();
        w.in(BizPickListDetail::getPickListId, pickListIds);
        for (BizPickListDetail d : pickListDetailMapper.selectList(w)) {
            if (d.getGoodsId() == null || d.getQuantity() == null) {
                continue;
            }
            net.merge(d.getGoodsId(), sign * d.getQuantity(), Integer::sum);
            names.putIfAbsent(d.getGoodsId(), d.getGoodsName());
        }
    }

    private String appendRemark(String oldRemark, String suffix) {
        if (oldRemark == null || oldRemark.isBlank()) {
            return suffix;
        }
        return oldRemark + " | " + suffix;
    }
```

- [ ] **Step 8: 跑测试确认转绿**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q -Dtest='ProductionPickServiceTest'
```

Expected: BUILD SUCCESS（新 8 个 + 原有过）。

- [ ] **Step 9: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System- && git add back/src/main/java/org/example/back/dto/ProductionTerminateDTO.java back/src/main/java/org/example/back/vo/ProductionReturnableVO.java back/src/main/java/org/example/back/vo/ProductionPickItemVO.java back/src/main/java/org/example/back/service/ProductionPickService.java back/src/test/java/org/example/back/service/ProductionPickServiceTest.java && git commit -m "feat(production): 阶段21 D73 手动终止+已领未退预览——terminate一事务生成退料单/createReturn抽取insertReturnList

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: Controller 端点 + receipt 终态撤未读

**Files:**
- Modify: `back/src/main/java/org/example/back/controller/ProductionPickController.java`
- Modify: `back/src/main/java/org/example/back/service/ProductionOrderService.java`（`receipt()`，约 245-277 行）

**Interfaces:**
- Consumes: Task 3 的 `ProductionPickService.terminate/computeReturnablePreview`、`ProductionTerminateDTO`、`ProductionReturnableVO`。
- Produces: REST `GET /api/business/production-orders/{orderId}/returnable`、`POST /api/business/production-orders/{orderId}/terminate`（Task 6 前端依赖）。

- [ ] **Step 1: ProductionPickController 加两个端点**

import 区补：

```java
import org.example.back.common.annotation.AuditLog;
import org.example.back.dto.ProductionTerminateDTO;
import org.example.back.vo.ProductionReturnableVO;
```

类内追加：

```java
    /** D73：终止弹窗「已领未退」预览（生产成员可读） */
    @GetMapping("/{orderId}/returnable")
    public Result<ProductionReturnableVO> returnable(@PathVariable Long orderId) {
        return Result.success(productionPickService.computeReturnablePreview(orderId));
    }

    /** D73：手动终止生产任务单（仅生产管理员；同事务生成终止退料单） */
    @PostMapping("/{orderId}/terminate")
    @PreventDuplicateSubmit(message = "请勿重复提交终止")
    @AuditLog(module = "生产任务单", action = "终止", targetType = "生产任务单")
    public Result<Void> terminate(@PathVariable Long orderId, @Valid @RequestBody ProductionTerminateDTO dto) {
        productionPickService.terminate(orderId, dto);
        return Result.success();
    }
```

- [ ] **Step 2: ProductionOrderService.receipt() 加终态撤未读**

`receipt()` 中 `updateById` 成功之后、`notifySalesReadyToShipIfLinked` 之前加：

```java
        // D73：订单终态（已完成）——撤销该单未读待办（含"关联销售单已取消"等绑 production_order 的消息）
        messageService.revokeUnreadByBiz("production_order", id);
```

> 说明：作废（voidOrder）/报废（QcService.dispose scrap 分支）已有同款 revoke；入库完成此前漏撤，D73 补齐，三个终态口径对齐。

- [ ] **Step 3: 编译 + 全量后端测试**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q
```

Expected: BUILD SUCCESS（154+ 新增 ≈ 167 全绿）。若有既有测试因 createReturn 重构/ findOpenReturn 改动挂掉，按失败信息修测试 stub（selectCount→selectOne 口径）。

- [ ] **Step 4: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System- && git add back/src/main/java/org/example/back/controller/ProductionPickController.java back/src/main/java/org/example/back/service/ProductionOrderService.java && git commit -m "feat(production): 阶段21 D73 终止/退料预览REST端点+receipt终态撤未读对齐

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: 关联销售单「已取消」标注（fillSalesOrderNoBatch）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/ProductionOrderService.java`（`fillSalesOrderNoBatch`，约 486-497 行）
- Test: `back/src/test/java/org/example/back/service/ProductionOrderServiceTest.java`

**Interfaces:**
- Consumes: 无（独立小改）。
- Produces: 无新符号；行为变化——`ProductionOrderVO.salesOrderNo`：销售单正常→单号；已作废→`单号（已作废）`；已删除→`已取消的销售单`。

- [ ] **Step 1: 先写失败测试 —— ProductionOrderServiceTest 追加**

（该类已有 `@Mock orderMapper/bizSalesMapper/productionStepService/qcService/authzService` 等、`@InjectMocks service`。若该类还没有 TableInfo 初始化，在类顶加——它现有的 page() 测试依赖跨测试类的 lambda 缓存，顺手加固：）

```java
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), BizProductionOrder.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), BizSales.class);
    }
```

> 注：先 `grep -n "BeforeAll\|TableInfoHelper" ProductionOrderServiceTest.java`——若已有则跳过本块。`MybatisMapperBuilderAssistant` 的确切构造签名以 ProductionPickServiceTest 现有 `@BeforeAll` 写法为准，直接照抄那个文件的写法。

追加测试（用 status=7 已终止单走 getById：跳过齐套重算与 qcState，仅 stepList + 填充销售单号）：

```java
    // ---------- D73：关联销售单已删除 → 标注"已取消的销售单" ----------

    @Test
    void getById_linkedSalesDeleted_marksCancelled() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PRO-X");
        order.setGoodsId(29L);
        order.setGoodsName("PTO153");
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        order.setSalesOrderId(501L);
        when(orderMapper.selectById(7L)).thenReturn(order);
        when(productionStepService.listSteps(order)).thenReturn(null);
        // 软删后 selectList 查不到 → 标注已取消
        when(bizSalesMapper.selectList(any())).thenReturn(List.of());

        ProductionOrderVO vo = service.getById(7L);

        assertEquals("已取消的销售单", vo.getSalesOrderNo());
    }

    @Test
    void getById_linkedSalesVoided_marksVoided() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PRO-X");
        order.setGoodsId(29L);
        order.setGoodsName("PTO153");
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        order.setSalesOrderId(501L);
        when(orderMapper.selectById(7L)).thenReturn(order);
        when(productionStepService.listSteps(order)).thenReturn(null);
        BizSales sales = new BizSales();
        sales.setId(501L);
        sales.setSalesNo("XS260910001");
        sales.setBizStatus(2); // 已作废
        when(bizSalesMapper.selectList(any())).thenReturn(List.of(sales));

        ProductionOrderVO vo = service.getById(7L);

        assertEquals("XS260910001（已作废）", vo.getSalesOrderNo());
    }
```

> 注：`getById` 还调 `requireOrderReadAccess()`（authzService void mock 默认放行）。`ProductionOrderVO`/`BizSales` 若未导入则补。若 getById 对 status=7 还有其它调用路径（以实际代码为准），按编译/运行结果补 stub。

- [ ] **Step 2: 跑测试确认红**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q -Dtest='ProductionOrderServiceTest'
```

Expected: 两个新测试 FAIL（旧逻辑返回 null 单号）。

- [ ] **Step 3: 实现 —— fillSalesOrderNoBatch 替换**

```java
    /** D70/D73：批量填充关联销售单号；销售单已作废→"单号（已作废）"、已删除→"已取消的销售单" */
    private void fillSalesOrderNoBatch(List<ProductionOrderVO> records) {
        List<Long> salesIds = records.stream().map(ProductionOrderVO::getSalesOrderId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (salesIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<BizSales> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BizSales::getId, salesIds);
        Map<Long, BizSales> salesMap = bizSalesMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(BizSales::getId, s -> s));
        for (ProductionOrderVO vo : records) {
            if (vo.getSalesOrderId() == null) {
                continue;
            }
            BizSales sales = salesMap.get(vo.getSalesOrderId());
            if (sales == null) {
                vo.setSalesOrderNo("已取消的销售单");
            } else if (sales.getBizStatus() == null || sales.getBizStatus() != 1) {
                vo.setSalesOrderNo(sales.getSalesNo() + "（已作废）");
            } else {
                vo.setSalesOrderNo(sales.getSalesNo());
            }
        }
    }
```

- [ ] **Step 4: 跑测试确认转绿 + Commit**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q -Dtest='ProductionOrderServiceTest' && cd .. && git add back/src/main/java/org/example/back/service/ProductionOrderService.java back/src/test/java/org/example/back/service/ProductionOrderServiceTest.java && git commit -m "feat(production): 阶段21 D73 关联销售单已取消/已作废标注（fillSalesOrderNoBatch）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

Expected: BUILD SUCCESS。

---

### Task 6: 前端 —— 终止按钮 + 终止弹窗 + 状态 tag/筛选项

**Files:**
- Modify: `front/src/api/pickList.js`（加 2 个 API）
- Modify: `front/src/views/business/ProductionOrderView.vue`（状态选项 444-451、statusTagType 929、操作列加终止按钮、加终止弹窗 + 脚本）

**Interfaces:**
- Consumes: Task 4 的两个 REST 端点。
- Produces: `getProductionReturnableAPI(orderId)`、`terminateProductionOrderAPI(orderId, data)`。

- [ ] **Step 1: pickList.js 加 API**

文件末尾（或生产领料 API 区块内）加：

```js
// D73：终止退料预览 + 手动终止生产任务单
export const getProductionReturnableAPI = (orderId) => request.get(`/business/production-orders/${orderId}/returnable`)
export const terminateProductionOrderAPI = (orderId, data) => request.post(`/business/production-orders/${orderId}/terminate`, data)
```

- [ ] **Step 2: ProductionOrderView.vue —— 状态选项与 tag**

① `statusOptions` 数组（约 444-451）追加：

```js
  { value: 7, label: '已终止' }
```

② `statusTagType`（约 929 行 switch/映射）加：`case 7: return 'danger'`（或映射表加 `7: 'danger'`，以现有写法为准）。

- [ ] **Step 3: ProductionOrderView.vue —— 操作列加「终止」按钮**

在现有「作废」按钮（约 52-55 行，status 1/2 显示）之后加：

```html
              <el-button
                v-if="[1, 2, 3].includes(scope.row.status)" size="small" type="danger" :icon="CircleClose"
                @click="openTerminate(scope.row)" v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
              >终止</el-button>
```

操作列 `width` 从 `220` 调为 `290`（按钮变多防换行）。`<script setup>` 的图标 import 加 `CircleClose`（`@element-plus/icons-vue`）。

- [ ] **Step 4: ProductionOrderView.vue —— 终止弹窗（模板）**

在「作废确认弹窗」之后加：

```html
    <!-- D73：手动终止（销售取消等外部原因）；预填已领未退净额供核对退料，一个事务提交 -->
    <el-dialog v-model="terminateVisible" :title="`终止生产任务单 - ${terminateRow.orderNo || ''}`" width="820px" :close-on-click-modal="false">
      <el-alert
        title="终止为终态操作，不可恢复。已领物料将按下方清单生成退料单，由仓储确认入库后库存加回。"
        type="warning" :closable="false" style="margin-bottom: 12px"
      />
      <el-alert
        v-if="terminateHasOpenReturn"
        :title="`该单已有进行中退料单（${terminateOpenReturnPickNo}），本次终止不再自动生成退料单，请在既有退料单中核对退料覆盖。`"
        type="info" :closable="false" style="margin-bottom: 12px"
      />
      <el-form label-width="90px">
        <el-form-item label="终止原因" required>
          <el-input v-model="terminateReason" type="textarea" :rows="2" placeholder="必填，如：销售交易单 XS… 已取消" />
        </el-form-item>
        <el-form-item v-if="terminateItems.length" label="退料明细">
          <el-table :data="terminateItems" border size="small" style="width: 100%">
            <el-table-column type="index" label="序号" width="55" />
            <el-table-column label="物料" min-width="150">
              <template #default="{ row }">
                {{ row.goodsName }}
                <span v-if="row.spec || row.material" style="color:#909399">（{{ [row.spec, row.material].filter(Boolean).join(' / ') }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="已领未退" width="90" align="center">
              <template #default="{ row }">{{ row.maxQty }}</template>
            </el-table-column>
            <el-table-column label="退料数量" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.quantity" :min="0" :max="row.maxQty" controls-position="right" style="width: 120px" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" @click="terminateItems.splice($index, 1)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="color:#909399; font-size:12px; margin-top:6px">已耗用/损坏的物料请减量或删除该行；退料数量不可超过已领未退。</div>
        </el-form-item>
        <el-form-item v-else label="退料明细">
          <span style="color:#909399">无已领未退物料，终止后不生成退料单</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="terminateVisible = false">取消</el-button>
        <el-button type="danger" :loading="terminateSubmitting" @click="doTerminate">确认终止</el-button>
      </template>
    </el-dialog>
```

- [ ] **Step 5: ProductionOrderView.vue —— 脚本**

① api import 行（`@/api/pickList`）加 `getProductionReturnableAPI, terminateProductionOrderAPI`。

② script 内（作废弹窗状态附近）加：

```js
// D73：手动终止（仅生产管理员；预填已领未退净额，一个事务终止+生成退料单）
const terminateVisible = ref(false)
const terminateRow = ref({})
const terminateReason = ref('')
const terminateItems = ref([])
const terminateHasOpenReturn = ref(false)
const terminateOpenReturnPickNo = ref('')
const terminateSubmitting = ref(false)

async function openTerminate(row) {
  terminateRow.value = row
  terminateReason.value = ''
  terminateItems.value = []
  terminateHasOpenReturn.value = false
  terminateOpenReturnPickNo.value = ''
  try {
    const res = await getProductionReturnableAPI(row.id)
    if (res.code !== 200) throw new Error(res.msg || '加载已领未退失败')
    const data = res.data || {}
    terminateItems.value = (data.items || []).map((it) => ({ ...it, maxQty: it.quantity }))
    terminateHasOpenReturn.value = !!data.hasOpenReturn
    terminateOpenReturnPickNo.value = data.openReturnPickNo || ''
  } catch (e) {
    ElMessage.error(e.message || '加载已领未退失败')
    return
  }
  terminateVisible.value = true
}

async function doTerminate() {
  if (!terminateReason.value || !terminateReason.value.trim()) {
    ElMessage.warning('请填写终止原因')
    return
  }
  try {
    await ElMessageBox.confirm('终止为终态操作，不可恢复。确认终止该生产任务单？', '终止确认', { type: 'warning' })
  } catch {
    return
  }
  terminateSubmitting.value = true
  try {
    const items = terminateItems.value
      .filter((i) => i.quantity > 0)
      .map((i) => ({ goodsId: i.goodsId, quantity: i.quantity }))
    const res = await terminateProductionOrderAPI(terminateRow.value.id, {
      reason: terminateReason.value.trim(),
      items
    })
    if (res.code !== 200) throw new Error(res.msg || '终止失败')
    ElMessage.success(items.length ? '已终止，退料单已提交仓储确认入库' : '已终止')
    terminateVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '终止失败')
  } finally {
    terminateSubmitting.value = false
  }
}
```

> 注：若该文件的列表刷新函数名不是 `loadList`（如 `fetchList`/`loadData`），以现有「作废」按钮成功后调用的刷新函数名为准。

- [ ] **Step 6: 前端构建验证 + Commit**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build
```

Expected: 构建成功（无编译错误）。

```bash
cd /home/niuchao/Warehouse-Management-System- && git add front/src/api/pickList.js front/src/views/business/ProductionOrderView.vue && git commit -m "feat(front): 阶段21 D73 生产任务单终止按钮+终止弹窗（已领未退预填/进行中退料提示）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: E3 —— 「作废并红冲」入口前端隐藏（6 个文件）

**Files:**
- Modify: `front/src/views/business/SalesView.vue`（100-119 按钮区、6-7 tooltip）
- Modify: `front/src/views/business/PurchaseView.vue`（91-93、tooltip）
- Modify: `front/src/views/business/PurchaseReturnView.vue`（91-93、tooltip）
- Modify: `front/src/views/business/SalesReturnView.vue`（95-97、tooltip）
- Modify: `front/src/views/business/ProductionView.vue`（78-80、tooltip）
- Modify: `front/src/views/business/VoidApprovalView.vue`（117-121 actionOptions、145-147 actionLabel）

**Interfaces:**
- 无后端改动（D74：后端 void_red 逻辑完整保留，仅入口隐藏；历史红冲记录仍可展示）。

- [ ] **Step 1: 五个业务页删红冲按钮 + 「仅作废」改「作废」**

每页操作相同（以 `grep -n "红冲\|仅作废" front/src/views/business/*.vue` 定位）：
① 删除「作废并红冲」`el-button` 整块；
② 剩余「仅作废」按钮文字改为「作废」（无「仅作废」字样的页面跳过②）；
③ 页面顶部 help-label/tooltip 文案：
  - 销售/采购/采购退货/销售退货页：`当天单据可直接删除；历史单据的作废/红冲会提交给仓储管理员审批，通过后才执行。` → `当天单据可直接删除；历史单据的作废会提交给仓储管理员审批，通过后才执行。`
  - 生产页（ProductionView）：`当天单据可直接删除；历史单据作废将直接冲减库存并标记作废（可选生成红冲记录）。` → `当天单据可直接删除；历史单据作废将直接冲减库存并标记作废。`

> 注意：`handleVoid(row, createRedFlush)` 函数签名保持不变（前端只传 false 的调用保留），仅删按钮。

- [ ] **Step 2: VoidApprovalView.vue**

① `actionOptions`（约 117-121）删除 `{ value: 'void_red', label: '作废并红冲' }` 行；
② `actionLabel`（约 145-147）改为对历史 void_red 记录兜底显示：

```js
const actionLabel = (val) => {
  if (val === 'void_red') return '作废并红冲' // D74：入口已隐藏，历史记录仍可辨识
  return actionOptions.find((item) => item.value === val)?.label || val || '-'
}
```

- [ ] **Step 3: 构建 + grep 复核 + Commit**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build && grep -rn "作废并红冲\|仅作废" src/views/business/ | grep -v "D74"
```

Expected: 构建成功；grep 仅剩 VoidApprovalView 的 D74 兜底行（历史文案）。

```bash
cd /home/niuchao/Warehouse-Management-System- && git add front/src/views/business/ && git commit -m "feat(front): 阶段21 D74 红冲入门前端隐藏——五业务页删红冲按钮/审批页删选项(历史兜底)+tooltip更新

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: 全量验证 + 重启 + curl E2E + 测试数据清理

**Files:**
- 无代码改动（临时脚本用完即删）

- [ ] **Step 1: 后端全量测试 + 前端构建**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q
cd ../front && npm run build
```

Expected: 后端全绿（约 167 tests）；前端构建成功。

- [ ] **Step 2: 重启后端（前端 dev 若还在跑且代理正常可不重启）**

```bash
fuser -k 8080/tcp; sleep 2; cd /home/niuchao/Warehouse-Management-System-/back && nohup ./mvnw spring-boot:run > /tmp/wms-backend.log 2>&1 &
sleep 25 && curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/auth/captcha
```

Expected: 200。若 8080 残留旧进程杀不净，`kill -9 <pid>` 后重启，确认 `ss -tlnp | grep 8080` 是新 pid。

- [ ] **Step 3: E2E —— 通知链（销售删除/作废 → 生产管理员收消息）**

用临时 JSON 文件登录（避免内联密码被分类器拦截），依次：
1. `POST /api/auth/login` ×3：sales_admin / production_admin / warehouse_admin（密码 123456），各存 token。
2. 造链：sales 建一张关联成品的销售单（走现有建单流程，确认产生 `data.id`）；production 建一张 `salesOrderId` 关联该销售单的生产任务单（`POST /api/business/production-orders`），记 `orderId`。
3. **作废路径**：sales `POST /api/business/sales/{id}/void`（仅作废）→ 仓储审批通过（走现有作废审批接口）→ production_admin `GET /api/business/messages/page` 断言存在 title=`关联销售单已取消`、bizType=`production_order`、bizId=`orderId` 的消息。
4. **删除路径**：再建一对（销售单+关联生产单），sales `DELETE /api/business/sales/{id}`（当天单可删）→ 断言生产管理员又收到一条。
5. 生产单列表 `GET /api/business/production-orders/page`：断言已删销售单那张的 `salesOrderNo`=`已取消的销售单`。

- [ ] **Step 4: E2E —— 终止 + 退料链**

1. 新建生产任务单（不关联销售单）→ 申请领料（`POST /{orderId}/pick`）→ warehouse `POST /api/business/pick-lists/{id}/issue` 确认出库 → 记物料库存 S1。
2. production `GET /api/business/production-orders/{orderId}/returnable` → 断言 items 净额 = 领料数。
3. production `POST /api/business/production-orders/{orderId}/terminate`，body `{"reason":"销售交易单取消（E2E）","items":[{...净额减 1 行...}]}` → 断言 code=200；再 `GET /api/business/production-orders/{orderId}` 断言 status=7、remark 含 `终止原因`；`GET /{orderId}/pick` 列表存在新 RETURN 单（status=1）。
4. warehouse 对该 RETURN 单 issue → 断言物料库存回升相应数量。
5. **负测**（断言 body `code`≠200）：① production_employee 调 terminate → 403 类 code；② 已终止单再 terminate → 校验失败文案；③ reason 空 → 400；④ items 数量超净额 → `超过已领未退`；⑤ production_admin 消息列表中该单原未读消息已撤（若步骤 3 前有未读）。

- [ ] **Step 5: 清理测试数据 + 库存复原**

删除/作废本任务创建的：销售单、生产任务单（已终止的保留无妨，但优先用专门测试单并作废清理不了时保留终态单、删消息）、领/退料单按既有清理 SQL 删 detail+list；用 `SELECT stock FROM base_goods WHERE id=?` 核对物料/成品库存回到任务前基线（不一致则手工 UPDATE 复原并记录原因）。删除测试消息（`DELETE FROM sys_message WHERE biz_id IN (...)`）。

- [ ] **Step 6: 无 commit（验证任务）；在 TodoWrite 标记完成**

---

### Task 9: 文档落账（task_plan / CONTEXT / progress / ADR-0005）

**Files:**
- Modify: `task_plan.md`
- Modify: `CONTEXT.md`
- Modify: `progress.md`
- Create: `docs/adr/0005-production-order-terminate.md`

- [ ] **Step 1: task_plan.md**

① 「⚠️ 待确认问题」：E1/E2 两条移出，标注已确认；
② 决策记录追加：

```markdown
- **D72**（2026-09-10）：E1 库存竞争维持现状——不做库存预留、不在出库拦截时点自动通知生产；仓储见"库存不足"红标退回销售人工协调；建单时的缺料/现货通知逻辑不变。
- **D73**（2026-09-10）：E2 关联单取消联动——销售单删除/作废后通知关联未终态生产任务单的生产管理员（biz_type=production_order）；新增生产任务单状态 7=已终止（1/2/3 可终止、仅生产管理员、原因必填、不可逆、@AuditLog）；终止弹窗预填「已领未退」净额（PICK/SUPPLY 已发料起 − RETURN 已发料起）可改量，一个事务生成 RETURN 退料单由仓储确认回流入库；已有进行中退料单跳过自动生成并提示人工核对；已终止单打卡/质检冻结、时间线回退"待重新排产"、关联销售单号标注"已取消的销售单/（已作废）"。
- **D74**（2026-09-10）：E3 「作废并红冲」入门前端隐藏——五个业务页删红冲按钮（"仅作废"更名"作废"）、作废审批页删 void_red 选项（历史记录兜底显示）；后端红冲逻辑（biz_status=3 负向记录）完整保留。
```

③ 新增「阶段 21」小节（勾选全部完成项，条目与本计划 Task 1-8 对应）；④ 「📊 总体进度」更新。

- [ ] **Step 2: CONTEXT.md 新词条**

追加：`手动终止（生产任务单）`、`已领未退（终止退料净额）` 两个词条；既有 `履约时间线`/`销售需求联动` 词条补一句 D73 联动行为。

- [ ] **Step 3: docs/adr/0005-production-order-terminate.md**

```markdown
# ADR-0005: 生产任务单「手动终止」区别于「作废」，终止联动退料

- 状态: 已接受（2026-09-10, D73）
- 背景: 销售单取消后，进行中的生产任务单需要一个业务上"做了一半不做了"的终态，并保证已领物料回到库存，避免库存错误。
- 决策:
  - 新增状态 7=已终止（1/2/3 可进入，不可逆，仅生产管理员，原因必填）。语义区别于作废（5=单据不该存在）与报废（6=质检不合格）。
  - 终止与退料单生成一个事务（ProductionPickService.terminate），退料量以"已领未退净额"为上界服务端兜底，防多退虚增库存。
  - 已有进行中退料单时跳过自动生成（防堆叠口径与 createReturn 一致），前端提示人工核对。
  - 已终止单不参与 UNFINISHED_STATUSES（D66 BOM 保护、现货/缺料推算不受影响）；打卡/质检冻结但记录保留可见。
  - 销售取消通知绑 biz_type=production_order，生产单任一终态（完成/作废/报废/终止）撤未读。
- 备选: ① 复用"作废"——语义混淆（作废会清空步骤实例展示且含义是"不该存在"）；② 允许销售直接终止生产单——越权，生产现场状态只有生产管理员能判断。
- 影响: 状态机扩展需扫荡所有 statusText/状态闸（本阶段已覆盖打卡/质检/时间线/前端 tag）；后续新增终态判断默认不含 7。
```

- [ ] **Step 4: progress.md 会话 26 记录**

按既有倒序格式在文件顶部追加会话 26 条目：范围（E1 确认/D73 落地/D74 隐藏）、改动文件清单、测试数（154→N）、E2E 结果、遗留事项。

- [ ] **Step 5: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System- && git add task_plan.md CONTEXT.md progress.md docs/adr/0005-production-order-terminate.md && git commit -m "docs(plan): 阶段21 落地记录——D72 E1确认/D73终止联动/D74红冲隐藏 + ADR-0005

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 收尾（所有任务完成后，与用户确认再执行）

- `superpowers:finishing-a-development-branch`：合并 `feat/phase21-e2-terminate` → main、推送、删分支（推送认证见 CLAUDE.md 运维教训 3/4；合并后重启两端 dev 服务器 + curl 验证）。

## Self-Review 记录（写计划时已查）

- **规格覆盖**：E1→Task 1 注释+Task 9 D72；E2①通知→Task 2；E2②终止→Task 1/3/4/6；E2③退料联动→Task 3/6；E3→Task 7；关联单标注→Task 5；消息终态撤未读补漏→Task 4 Step 2。
- **占位符扫描**：无 TBD/TODO；测试与实现代码全量给出。
- **类型一致性**：`terminate(Long, ProductionTerminateDTO)`、`computeReturnablePreview(Long)→ProductionReturnableVO`、前端 `terminateProductionOrderAPI(orderId, {reason, items})` 与 DTO 字段 `reason/items[].goodsId/quantity` 对齐；`ProductionReturnItemDTO` 复用既有类型。
- **已知风险点**：① createReturn 抽取后既有测试的 stub 口径（selectCount→selectOne）以编译/运行结果微调；② ProductionOrderServiceTest 的 TableInfo 初始化写法照抄 ProductionPickServiceTest 的 `@BeforeAll`；③ 前端列表刷新函数名以该文件现状为准。
