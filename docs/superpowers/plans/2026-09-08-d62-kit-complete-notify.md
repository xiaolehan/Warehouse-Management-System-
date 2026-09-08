# D62 补料入库齐套通知 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 生产补料采购单确认入库后，若对应生产任务单物料齐套（缺口清零），站内信通知生产部管理员「物料已齐套可领料」。

**Architecture:** 纯后端行为，前端零改动。confirmReceive 末尾新增私有守卫方法 `notifyKitCompleteIfReady`：production 来源 + 生产单有效 → 复用 `productionOrderService.computeShortageForOrder` 重算齐套 → 缺口为空时经 MessageService 新方法发部门消息（绑 `production_order`，D21 范式，与 D60 预警共用作废撤销点）。

**Tech Stack:** Spring Boot + MyBatis-Plus（back/），Mockito 单测，无 DB schema 变更（无 db.sql 追加）。

## Global Constraints

- 通知**仅齐套时发**：`computeShortageForOrder(productionOrderId)` 返回空才发；仍缺料沉默（页面齐套状态兜底）
- 通知对象：生产部管理员（`sendToDeptAdminsWithBiz(DEPT_PRODUCTION, …)`，D60 同范式）
- 生命周期绑定：`biz_type="production_order"`, `biz_id=生产单id`；**不新增撤销点**（voidOrder 已有 `revokeUnreadByBiz("production_order", id)`，ProductionOrderService:271）
- 防御守卫：生产单不存在 / `isDeleted==1` / `status==STATUS_VOIDED(5)` / `status==STATUS_SCRAPPED(6)` 一律不发，且**不得抛异常打断确认入库事务**
- 文案：标题「物料已齐套可领料」；内容 `生产任务单 %s（成品 %s×%d）所需物料已全部入库齐套（补料单 %s 已入库），请前往生产任务单详情申请领料。`——用「领料」不用「提货」
- 前端零改动；db.sql 零变更
- 不动物料规则：部分到货→仍缺料→"已入库补料单阻止再补料"的既有陷阱**本次不修**，记入 task_plan.md 已知问题
- TDD：先写测试（编译失败即 RED），实现后全量测试绿；测试沿用 `@BeforeAll` lambda 缓存 + ArgumentCaptor 既有风格
- 范围：仅 2 文件（PurchaseRequestService.java、MessageService.java）+ 1 测试文件 + 2 文档文件

---

### Task 1: confirmReceive 齐套通知（TDD）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`（注入 mapper + confirmReceive 接线 + 私有方法）
- Modify: `back/src/main/java/org/example/back/service/MessageService.java`（新发送方法，放在 `sendPickIssuedToProductionAdmins` 之后）
- Test: `back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java`

**Interfaces:**
- Consumes: `productionOrderService.computeShortageForOrder(Long orderId) → List<KitShortageVO>`（ProductionOrderService:378，已注入 PurchaseRequestService，空列表=齐套）；`messageService.sendToDeptAdminsWithBiz(Long deptId, String title, String content, String bizType, Long bizId)`；`AuthzService.DEPT_PRODUCTION`；`BizProductionOrder.STATUS_VOIDED=5 / STATUS_SCRAPPED=6`
- Produces: `MessageService.sendKitCompleteToProductionAdmins(String orderNo, String goodsName, Integer quantity, String requestNo, Long orderId)`（后续测试/复用依赖此签名）

- [ ] **Step 1: 写失败测试**

在 `PurchaseRequestServiceTest.java` 顶部 import 区追加：

```java
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BizProductionOrderMapper;
```

在 `@Mock private org.example.back.mapper.BizBomDetailMapper bizBomDetailMapper;` 之后追加：

```java
    @Mock private BizProductionOrderMapper bizProductionOrderMapper;
```

在类的最后一个测试方法之后追加（4 个测试 + 3 个私有助手）：

```java
    // ============================== D62：确认入库齐套通知 ==============================

    private BizPurchaseRequest awaitingConfirmRequest(String sourceType) {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(30L);
        request.setRequestNo("PR-D62-TEST");
        request.setStatus(PurchaseRequestService.STATUS_AWAITING_CONFIRM);
        request.setSourceType(sourceType);
        if (PurchaseRequestService.SOURCE_PRODUCTION.equals(sourceType)) {
            request.setProductionOrderId(7L);
        }
        return request;
    }

    private BizPurchaseRequestDetail arrivedDetail() {
        BizPurchaseRequestDetail detail = new BizPurchaseRequestDetail();
        detail.setId(301L);
        detail.setRequestId(30L);
        detail.setGoodsId(51L);
        detail.setGoodsName("轴承珠");
        detail.setQuantity(10);
        detail.setArriveQuantity(10);
        detail.setUnitPrice(BigDecimal.ONE);
        return detail;
    }

    private void stubConfirmReceive(BizPurchaseRequest request, BizPurchaseRequestDetail detail) {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(3L);
        user.setRealName("仓储管理员");
        when(authService.getUserInfo()).thenReturn(user);
        when(bizPurchaseRequestMapper.selectById(30L)).thenReturn(request);
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(detail));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void confirmReceive_notifiesProductionAdminsWhenKitComplete() {
        BizPurchaseRequest request = awaitingConfirmRequest(PurchaseRequestService.SOURCE_PRODUCTION);
        stubConfirmReceive(request, arrivedDetail());

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PO-D62");
        order.setGoodsName("PTO153");
        order.setQuantity(5);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of());

        service.confirmReceive(30L);

        verify(messageService).sendKitCompleteToProductionAdmins("PO-D62", "PTO153", 5, "PR-D62-TEST", 7L);
    }

    @Test
    void confirmReceive_skipsNotifyWhenShortageRemains() {
        BizPurchaseRequest request = awaitingConfirmRequest(PurchaseRequestService.SOURCE_PRODUCTION);
        stubConfirmReceive(request, arrivedDetail());

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PO-D62");
        order.setGoodsName("PTO153");
        order.setQuantity(5);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);
        KitShortageVO shortage = new KitShortageVO();
        shortage.setGoodsName("电阻10K");
        shortage.setDeficit(BigDecimal.valueOf(2));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(shortage));

        service.confirmReceive(30L);

        verify(messageService, never()).sendKitCompleteToProductionAdmins(anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    void confirmReceive_skipsNotifyForWarehouseSource() {
        BizPurchaseRequest request = awaitingConfirmRequest("warehouse");
        stubConfirmReceive(request, arrivedDetail());

        service.confirmReceive(30L);

        verify(productionOrderService, never()).computeShortageForOrder(anyLong());
        verify(messageService, never()).sendKitCompleteToProductionAdmins(anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    void confirmReceive_skipsNotifyWhenOrderVoided() {
        BizPurchaseRequest request = awaitingConfirmRequest(PurchaseRequestService.SOURCE_PRODUCTION);
        stubConfirmReceive(request, arrivedDetail());

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_VOIDED);
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);

        service.confirmReceive(30L);

        verify(productionOrderService, never()).computeShortageForOrder(anyLong());
        verify(messageService, never()).sendKitCompleteToProductionAdmins(anyString(), anyString(), any(), anyString(), anyLong());
    }
```

- [ ] **Step 2: 跑测试确认 RED**

Run: `cd back && ./mvnw test -Dtest=PurchaseRequestServiceTest`
Expected: 编译失败——`sendKitCompleteToProductionAdmins` 不存在（Java 静态类型下的 RED 即编译失败）

- [ ] **Step 3: MessageService 新方法**

在 `MessageService.java` 的 `sendPickIssuedToProductionAdmins` 方法（约 L338-350）之后插入：

```java
    /**
     * D62：生产单物料齐套（补料入库确认后缺口清零）→ 通知生产部管理员可申请领料。
     * 绑 biz_type=production_order；作废/报废单由调用方守卫不发，作废时随 D60 预警一并撤回未读。
     */
    public void sendKitCompleteToProductionAdmins(String orderNo, String goodsName, Integer quantity, String requestNo, Long orderId) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "物料已齐套可领料",
                String.format(Locale.ROOT,
                        "生产任务单 %s（成品 %s×%d）所需物料已全部入库齐套（补料单 %s 已入库），请前往生产任务单详情申请领料。",
                        orderNo == null ? "-" : orderNo,
                        goodsName == null ? "-" : goodsName,
                        quantity == null ? 0 : quantity,
                        requestNo == null ? "-" : requestNo),
                "production_order",
                orderId);
    }
```

（`Locale`、`AuthzService` 该文件均已 import。）

- [ ] **Step 4: PurchaseRequestService 接线**

4a. import 区追加（`KitShortageVO` 已有 import，勿重复）：

```java
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BizProductionOrderMapper;
```

4b. 在 `private BizBomDetailMapper bizBomDetailMapper;` 字段（约 L83）之后按既有 `@Autowired` 风格追加：

```java
    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;
```

4c. `confirmReceive` 末尾 `messageService.revokeUnreadByBiz("purchase_request", id);`（L464）之后追加一行：

```java
        notifyKitCompleteIfReady(entity);
```

4d. `confirmReceive` 方法之后、`// ====== 到货退回 ======` 注释区之前，插入私有方法：

```java
    /**
     * D62：生产补料入库确认后重算齐套，缺口清零即通知生产部管理员可申请领料。
     * 仅 production 来源且生产单存在（未删除/未作废/未报废）时触发；仍缺料则沉默，
     * 靠生产任务单列表实时齐套状态兜底。任何守卫命中都静默返回，不影响入库事务。
     */
    private void notifyKitCompleteIfReady(BizPurchaseRequest request) {
        if (!SOURCE_PRODUCTION.equals(request.getSourceType()) || request.getProductionOrderId() == null) {
            return;
        }
        BizProductionOrder order = bizProductionOrderMapper.selectById(request.getProductionOrderId());
        if (order == null || Integer.valueOf(1).equals(order.getIsDeleted())
                || order.getStatus() == null
                || order.getStatus() == BizProductionOrder.STATUS_VOIDED
                || order.getStatus() == BizProductionOrder.STATUS_SCRAPPED) {
            return;
        }
        List<KitShortageVO> shortage = productionOrderService.computeShortageForOrder(order.getId());
        if (!shortage.isEmpty()) {
            return;
        }
        messageService.sendKitCompleteToProductionAdmins(
                order.getOrderNo(), order.getGoodsName(), order.getQuantity(),
                request.getRequestNo(), order.getId());
    }
```

- [ ] **Step 5: 跑测试确认 GREEN**

Run: `cd back && ./mvnw test -Dtest=PurchaseRequestServiceTest`
Expected: 24 tests（20 旧 + 4 新），0 failures

- [ ] **Step 6: 全量回归**

Run: `cd back && ./mvnw test`
Expected: 97 tests（93 + 4），BUILD SUCCESS。若遇 `Unresolved compilation problem` 先 `./mvnw clean compile`。

- [ ] **Step 7: 提交**

```bash
git add back/src/main/java/org/example/back/service/PurchaseRequestService.java \
        back/src/main/java/org/example/back/service/MessageService.java \
        back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java
git commit -m "feat(production): 补料入库确认齐套即通知生产可领料(绑生产单,D62/D21)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: E2E 验证 + 重启 + 文档

**Files:**
- Modify: `task_plan.md`（阶段 16 / D62 决策记录 + 已知问题）
- Modify: `progress.md`（会话 19 条目，顶部插入）

**Interfaces:**
- Consumes: Task 1 的 `sendKitCompleteToProductionAdmins` 全链路；E2E 步骤模板见 `.superpowers/sdd/task-7-report.md`（登录/补料链 curl 写法）

- [ ] **Step 1: 重启后端加载新代码**

```bash
fuser -k 8080/tcp; sleep 2; ss -tln | grep :8080 || echo "port free"
cd back && nohup ./mvnw spring-boot:run > /tmp/wms-backend.log 2>&1 &
# 等待启动完成（日志出现 Started 或 ss 见 8080 监听）；前端 5173 未改动不动它
```

注意：禁止 `pkill -f "spring-boot:run"`（自杀陷阱，CLAUDE.md 教训 2）。若 fuser 后 8080 仍被占，`kill -9 <pid>` 强制清理。

- [ ] **Step 2: E2E（curl 全链路，不改真实库存为前提）**

复用 task-7-report.md 的补料链 curl 模式，步骤：

1. 登录 production_admin / purchase_admin / warehouse_admin 三个 token
2. 找或构造一条**缺料的生产任务单**（优先 SQL 只读探测现成待生产缺料单；没有则经 API 新建成品外的物料+BOM+任务单，记录所有新建 id 供清理）——断言该单当前 `computeShortage` 非空（可由补料接口可发起间接证明）
3. 生产端对该单发起补料（status=1 采购申请生成，记录 requestNo/id）
4. purchase_admin 认领（行级到货时间+备注，D61 接口）
5. purchase_admin 到货提交（每行到货数量=申请数量，使缺口清零）
6. warehouse_admin **确认入库**（唯一动库存的一步）——**先记录受影响物料的 `base_goods.stock` 与 `purchase_price` 原值**
7. 断言：production_admin 消息列表出现标题「物料已齐套可领料」，内容含任务单号+成品名×数量+补料单号，`biz_type=production_order`
8. 负路径断言（可选若数据允许）：另一张仍缺料的生产单走完链路后**无**该通知
9. **清理（CLAUDE.md 铁律：库存类测试数据用完即清理、恢复原库存）**：
   - `base_goods.stock` / `purchase_price` 按第 6 步记录恢复原值
   - 软删本次新建：biz_purchase（确认入库产生的进货记录）、biz_purchase_request + _detail、消息（biz 相关）、若新建了生产单/BOM/物料一并软删
   - 复查：`SELECT COUNT(*)` 确认无 `is_deleted=0` 残留测试行；库存恢复前后一致有 SQL 证据
   - **不得动用**上一轮留给用户检查的 PR260908151441615（id=22）
10. 全程禁止调用其他会改库存的接口

- [ ] **Step 3: 文档**

3a. `task_plan.md` 顶部决策区追加（编号顺延，D62）：

```markdown
**阶段 16（D62）：补料入库齐套通知**
- 背景：生产补料→采购入库确认后，生产侧无任何推送，需人工刷任务单才发现料齐了。
- 决策（D62）：confirmReceive 成功后仅当来源=生产补料且生产单有效（未删/未作废/未报废）时重算齐套；缺口清零即 `sendKitCompleteToProductionAdmins` 通知生产部管理员「物料已齐套可领料」（绑 production_order，作废撤销沿用 voidOrder 既有点，D21）；仍缺料沉默（列表实时齐套状态兜底）；文案含任务单号/成品×数量/补料单号，统一用「领料」。
- 已知问题（本次不修）：补料单按行部分到货→确认入库终态→若仍缺料，受"已入库补料单阻止再补料"既有规则（D59）约束，该生产单无法再补——待单独立项。
- 任务：[x] confirmReceive 齐套通知（TDD，4 单测） [x] E2E+重启 [x] 文档
```

3b. `progress.md` 顶部按既有格式插入会话 19 条目（设计/后端/测试/E2E/下一步 小节 + `---` 分隔符）。

- [ ] **Step 4: 提交**

```bash
git add task_plan.md progress.md
git commit -m "docs(plan): 阶段16 D62 补料入库齐套通知落地记录+已知问题登记

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Self-Review

- **Spec coverage**：Q1 仅齐套发（守卫+`shortage.isEmpty()`）✓；Q2 生产部管理员 ✓；Q3 production_order 绑定+不新增撤销点 ✓；Q4 文案含三要素+领料措辞 ✓；Q5 陷阱记 task_plan 已知问题 ✓；Q6 前端零改动 ✓；Q7 作废/删除/报废守卫 ✓
- **Placeholder scan**：无 TBD/TODO；所有代码步骤含完整代码；E2E 探测类步骤给出了明确判定标准与清理铁律
- **Type consistency**：`sendKitCompleteToProductionAdmins(String, String, Integer, String, Long)` 定义与测试 verify、notify 调用三处一致；`computeShortageForOrder` 返回 `List<KitShortageVO>` 与既有用法一致；`BizProductionOrder.isDeleted` 为 Integer、status 为 Integer，均 null 安全
