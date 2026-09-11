# Defer 清理（13 Minor + 5 架构债）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清零阶段 21 终审 defer 的 13 项 Minor 与会话 27 评审 defer 的 5 项架构债，分 4 组提交，不改业务语义。

**Architecture:** 按 spec（`docs/superpowers/specs/2026-09-11-defer-cleanup-design.md`）分 4 组：①后端 Minor ②前端 Minor ③前端架构 B/C/E+浮窗 D ④A 项 target_route（含 DDL，单独评审）。每组独立完成验证后提交再进下一组。

**Tech Stack:** Spring Boot + MyBatis-Plus + Mockito/JUnit5（back/）；Vue3 + Element Plus + Vite（front/）；MySQL 8（`warehouse_management`，wms_user/wms_pass）。

## Global Constraints

- 不改任何业务逻辑语义；库存零变动；测试数据用完即清理。
- 后端验证：`cd back && ./mvnw clean compile` → `./mvnw test`（基线 179/179）。
- 前端验证：`cd front && npm run build`。
- 业务异常为 HTTP 200 + body code 封装，curl 负测看 body 的 `code`，不看 HTTP 状态码。
- 提交信息结尾带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`；推送走 VS Code 面板（shell 推送会被拦）。
- #9（null bizStatus 标「已作废」，防御性安全）**明确不改**。
- dev 服务器运行中（后端 8080/前端 5173）；跨 commit git 操作前 `fuser -k 8080/tcp`、`fuser -k 5173/tcp`，操作完重启。

---

## 第 1 组：后端 Minor（#1-#8 + #3 报告）

### Task 1: ProductionPickService 三处小修（#4/#5/#6）+ 两个测试

**Files:**
- Modify: `back/src/main/java/org/example/back/service/ProductionPickService.java`（:212-215、:135-137、:242）
- Test: `back/src/test/java/org/example/back/service/ProductionPickServiceTest.java`

**Interfaces:**
- Consumes: 既有 helper `terminatableOrder(int status)`（:327，设 id=7L/orderNo="PRO-X"/remark="原备注"）、`mockTerminateBase(order)`（:359）、`terminateDTO(reason, goodsId, qty)`（:376）。
- Produces: 无新签名（行为/注解级改动）。

- [ ] **Step 1: 写失败测试 #5（createReturn null goodsId 静默过滤）**

追加到 ProductionPickServiceTest（`createReturn_rejectsWhenItemsEmpty` 之后）：

```java
    @Test
    void createReturn_nullGoodsIdItem_silentlyFiltered() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("SC-0001");
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        BaseGoods goods = new BaseGoods();
        goods.setId(50L);
        goods.setGoodsName("螺丝");
        when(baseGoodsMapper.selectById(50L)).thenReturn(goods);

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        ProductionReturnItemDTO bad = new ProductionReturnItemDTO();
        bad.setGoodsId(null); // 脏数据：与 terminate 过滤口径对齐后应被静默过滤
        bad.setQuantity(5);
        ProductionReturnItemDTO good = new ProductionReturnItemDTO();
        good.setGoodsId(50L);
        good.setQuantity(3);
        ProductionReturnCreateDTO dto = new ProductionReturnCreateDTO();
        dto.setItems(List.of(bad, good));

        service.createReturn(7L, dto);

        ArgumentCaptor<BizPickListDetail> dcap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(dcap.capture()); // 仅 good 一行
        assertEquals(50L, dcap.getValue().getGoodsId());
    }

    @Test
    void createReturn_allItemsInvalid_throws() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        ProductionReturnItemDTO bad = new ProductionReturnItemDTO();
        bad.setGoodsId(null);
        bad.setQuantity(5);
        ProductionReturnCreateDTO dto = new ProductionReturnCreateDTO();
        dto.setItems(List.of(bad));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createReturn(7L, dto));
        assertTrue(ex.getMessage().contains("无有效退料明细行"));
    }
```

- [ ] **Step 2: 跑测试确认 #5 第一个测试失败**

Run: `cd back && ./mvnw test -Dtest=ProductionPickServiceTest#createReturn_nullGoodsIdItem_silentlyFiltered`
Expected: FAIL（现状不过滤 goodsId，`insertReturnList` 对 null goodsId 抛「物料不存在: id=null」）

- [ ] **Step 3: 实现 #5——createReturn 过滤补 goodsId 判空**

ProductionPickService.java:135-137：

```java
        List<ProductionReturnItemDTO> items = dto.getItems().stream()
                .filter(i -> i.getGoodsId() != null && i.getQuantity() != null && i.getQuantity() > 0)
                .toList();
```

- [ ] **Step 4: 实现 #4——terminate DTO null 守卫显式化**

ProductionPickService.java:212-215 替换为（错误文案不变）：

```java
        if (dto == null) {
            throw BusinessException.validateFail("终止原因不能为空");
        }
        String reason = dto.getReason();
        if (reason == null || reason.trim().isEmpty()) {
            throw BusinessException.validateFail("终止原因不能为空");
        }
```

- [ ] **Step 5: 实现 #6——computeReturnablePreview 补只读事务注解**

ProductionPickService.java:242 方法签名上方加（类已 import org.springframework.transaction.annotation.Transactional）：

```java
    @Transactional(readOnly = true)
    public ProductionReturnableVO computeReturnablePreview(Long orderId) {
```

- [ ] **Step 6: 补 #4 特征测试（行为不变，锁定文案）**

追加：

```java
    @Test
    void terminate_nullDto_throws() {
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.terminate(7L, null));
        assertEquals("终止原因不能为空", ex.getMessage());
    }
```

- [ ] **Step 7: 全量跑该测试类**

Run: `cd back && ./mvnw test -Dtest=ProductionPickServiceTest`
Expected: 全绿（新增 3 例 + 既有全过）

### Task 2: #7 测试缺口补 4 例

**Files:**
- Test: `back/src/test/java/org/example/back/service/ProductionPickServiceTest.java`

**Interfaces:**
- Consumes: helper `pickListOf(id, type, status)`（:339）、`detailOf(pickListId, goodsId, name, qty)`（:349）、`mockReturnableData(qty)`（:365，goods 50）、`mockReturnableData` 同款的两次 selectList stub 模式（见 `computeReturnablePreview_netOfIssuedMinusReturned` :481）；`PickListService.TYPE_SUPPLY` 常量已存在（ProductionPickService.java:255 已用）。
- Produces: 无。

- [ ] **Step 1: 追加 4 个测试**

```java
    @Test
    void computeReturnablePreview_supplyIssuedCountsAsPicked() {
        // SUPPLY（补料）已发料与 PICK 同口径计入「已领」
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pickListOf(10L, PickListService.TYPE_SUPPLY, PickListService.STATUS_ISSUED)));
        when(pickListDetailMapper.selectList(any())).thenReturn(
                List.of(detailOf(10L, 50L, "螺丝", 5)));
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g));
        when(pickListMapper.selectOne(any())).thenReturn(null);

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(1, vo.getItems().size());
        assertEquals(5, vo.getItems().get(0).getQuantity());
    }

    @Test
    void terminate_itemNotInReturnableList_throws() {
        // 退料明细不在已领未退清单内 → 拒（ensureWithinReturnable max==null 分支）
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6); // 仅 goods 50 可退 6
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate(7L, terminateDTO("x", 99L, 1)));
        assertTrue(ex.getMessage().contains("不在该任务单已领未退清单内"));
    }

    @Test
    void terminate_existingRemark_appendsWithSeparator() {
        // appendRemark：旧备注非空 → "旧 | 终止原因: x"
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS)); // remark="原备注"
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("销售单取消");
        dto.setItems(List.of());

        service.terminate(7L, dto);

        ArgumentCaptor<BizProductionOrder> cap = ArgumentCaptor.forClass(BizProductionOrder.class);
        verify(productionOrderMapper).updateById(cap.capture());
        assertEquals("原备注 | 终止原因: 销售单取消", cap.getValue().getRemark());
    }

    @Test
    void terminate_blankRemark_replacesWithReason() {
        // appendRemark：旧备注空 → 仅后缀
        BizProductionOrder order = terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS);
        order.setRemark(null);
        mockTerminateBase(order);
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("销售单取消");
        dto.setItems(List.of());

        service.terminate(7L, dto);

        ArgumentCaptor<BizProductionOrder> cap = ArgumentCaptor.forClass(BizProductionOrder.class);
        verify(productionOrderMapper).updateById(cap.capture());
        assertEquals("终止原因: 销售单取消", cap.getValue().getRemark());
    }

    @Test
    void computeReturnablePreview_multiGoods_filtersNonPositive() {
        // 多物料：净额>0 才进清单（goods 51 全退净额 0 被过滤）
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pickListOf(10L, PickListService.TYPE_PICK, PickListService.STATUS_ISSUED),
                pickListOf(11L, PickListService.TYPE_RETURN, PickListService.STATUS_ISSUED)));
        when(pickListDetailMapper.selectList(any())).thenReturn(
                List.of(detailOf(10L, 50L, "螺丝", 10), detailOf(10L, 51L, "螺母", 5)),
                List.of(detailOf(11L, 50L, "螺丝", 4), detailOf(11L, 51L, "螺母", 5)));
        BaseGoods g50 = new BaseGoods();
        g50.setId(50L);
        g50.setGoodsName("螺丝");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g50));
        when(pickListMapper.selectOne(any())).thenReturn(null);

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(1, vo.getItems().size());
        assertEquals(50L, vo.getItems().get(0).getGoodsId());
        assertEquals(6, vo.getItems().get(0).getQuantity());
    }
```

- [ ] **Step 2: 跑测试类确认全绿**

Run: `cd back && ./mvnw test -Dtest=ProductionPickServiceTest`
Expected: 全绿（注意 `terminate_itemNotInReturnableList_throws` 若 Mockito STRICT_STUBS 报 unnecessary stubbing，说明某 stub 未被命中——按既有 `terminate_qtyExceedsReturnable_throws`（:442）同款 stub 模式核对修正）

### Task 3: #1/#2 测试清理 + #8 salesOrderNo happy-path + #3 报告措辞

**Files:**
- Test: `back/src/test/java/org/example/back/service/QcServiceTest.java`（:97-112）
- Test: `back/src/test/java/org/example/back/service/SalesTimelineServiceTest.java`（:261-276）
- Test: `back/src/test/java/org/example/back/service/ProductionOrderServiceTest.java`（:528 之后）
- Modify: `.superpowers/sdd/task-2-report.md`（:12-13、:74）

**Interfaces:**
- Consumes: SalesTimelineServiceTest 既有 `order(int status)` helper（:74，id=301L/orderNo="PO260910001"/goodsId=29L/quantity=10/salesOrderId=501L，内部 stub `productionOrderMapper.selectOne`）；ProductionOrderServiceTest 既有模式 `getById_linkedSalesVoided_marksVoided`（:529，含 `productionStepService.listSteps` stub、`bizSalesMapper.selectList` stub）。
- Produces: 无。

- [ ] **Step 1: #2——terminated 测试改用 helper**

SalesTimelineServiceTest.java:262-276 替换为：

```java
    @Test
    void getTimeline_terminatedLinkedOrder_fallsBackToReschedule() {
        sales(5, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_TERMINATED);

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals("待生产排产", vo.getEstimatedDeliveryText());
        assertTrue(vo.getNodes().stream().anyMatch(n -> "scheduled".equals(n.getKey())
                && n.getDescription() != null && n.getDescription().contains("已终止")));
    }
```

- [ ] **Step 2: #1——QcServiceTest 清理（helper 化 + 删死字段）**

QcService.record（QcService.java:59-67）执行序为 `requireOrder` → `ensureTestable`（已终止即抛）→ `normalizePoint/normalizeResult`——**testPoint/result 在已终止分支从不被消费，是死字段**；且文件已有 `order(long id)` helper（:43，设 STATUS_IN_PROGRESS）。QcServiceTest.java:97-112 整体替换为：

```java
    @Test
    void record_terminatedOrder_throws() {
        // D73：已终止单质检冻结（ensureTestable 先于测点/结果校验，dto 仅需 orderId）
        BizProductionOrder order = order(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(orderMapper.selectById(7L)).thenReturn(order);

        QcSaveDTO dto = new QcSaveDTO();
        dto.setOrderId(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.record(dto));
        assertTrue(ex.getMessage().contains("已终止"));
    }
```

- [ ] **Step 3: #8——salesOrderNo 正常单 happy-path 测试**

ProductionOrderServiceTest.java `getById_linkedSalesVoided_marksVoided` 之后追加：

```java
    @Test
    void getById_linkedSalesNormal_mapsSalesOrderNo() {
        // bizStatus=1 正常销售单 → VO.salesOrderNo = 原始单号（无标注）
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PRO-X");
        order.setGoodsId(29L);
        order.setGoodsName("PTO153");
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        order.setSalesOrderId(501L);
        when(orderMapper.selectById(7L)).thenReturn(order);
        when(productionStepService.listSteps(order)).thenReturn(null);
        BizSales sales = new BizSales();
        sales.setId(501L);
        sales.setSalesNo("XS260910001");
        sales.setBizStatus(1); // 正常
        when(bizSalesMapper.selectList(any())).thenReturn(List.of(sales));

        ProductionOrderVO vo = service.getById(7L);

        assertEquals("XS260910001", vo.getSalesOrderNo());
    }
```

- [ ] **Step 4: #3——报告过时措辞修正**

`.superpowers/sdd/task-2-report.md`：
- :12-13 两条「`revokePriceDeviationApprovals(id)` 之后调用」的行尾各补 `（修复轮已移至 stock 回补之后，见文末）`；
- :74「stock 回补逻辑之前，符合 brief」改为「stock 回补逻辑之**后**（修复轮修正，见文末「What changed」）」。

- [ ] **Step 5: 全量验证 + 提交第 1 组**

Run: `cd back && ./mvnw clean compile && ./mvnw test`
Expected: BUILD SUCCESS，测试全绿（179 → 188：+Task1×3 +Task2×5 +Task3×1）

```bash
git add back/ .superpowers/sdd/task-2-report.md
git commit -m "refactor(back): defer 清理第 1 组——terminate/createReturn 守卫对齐 + 只读事务 + 测试补全（Minor #1-#8）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 第 2 组：前端 Minor（#10/#12/#13/#14）

### Task 4: #10 statusTagType + #12 五处 await

**Files:**
- Modify: `front/src/views/business/ProductionOrderView.vue`（:1044、:668、:721、:780、:896、:1005）

- [ ] **Step 1: #10——默认分支 danger → info**

:1044 替换为：

```js
const statusTagType = (s) => (s === 1 ? 'info' : s === 2 ? 'warning' : s === 3 ? 'primary' : s === 4 ? 'success' : s === 7 ? 'danger' : 'info')
```

- [ ] **Step 2: #12——async 上下文 5 处补 await**

- `refreshDetail`（:668）：`loadList()` → `await loadList()`
- `doPickSubmit` try 块末尾（:721）：`loadList()` → `await loadList()`
- `doReturnSubmit` try 块（:780）：`loadList()` → `await loadList()`
- `handleTerminate` try 块（:896）：`loadList()` → `await loadList()`
- 补料 `.then` 回调（:1005）：`.then((res) => {` → `.then(async (res) => {`，回调内 `loadList()` → `await loadList()`

同步回调（handleSearch/resetSearch/分页/onMounted/closeCreate）**不动**。

- [ ] **Step 3: build 验证**

Run: `cd front && npm run build`
Expected: ✓ built，无错误

### Task 5: #13 五视图 handleVoid 清理 + #14 缩进

**Files:**
- Modify: `front/src/views/business/SalesView.vue`（模板 :106、函数 :468-496）
- Modify: `front/src/views/business/PurchaseView.vue`（模板 :81、函数 :393）
- Modify: `front/src/views/business/PurchaseReturnView.vue`（模板 :81、函数 :399）
- Modify: `front/src/views/business/SalesReturnView.vue`（模板 :85、函数 :436）
- Modify: `front/src/views/business/ProductionView.vue`（模板 :68、函数 :345-367）

**Interfaces:**
- Consumes: 后端红冲逻辑保留（D74），`createApprovalOrderAPI` 与 `voidProductionAPI` 签名不变。
- Produces: `handleVoid(row)` 单参（五视图）。
- 不动：`VoidApprovalView.vue:144-146` 的 `void_red` 历史兜底分支（存量审批单仍可能是红冲类型）。

- [ ] **Step 1: 四张审批制视图（Sales/Purchase/PurchaseReturn/SalesReturn）统一替换**

函数体（以 SalesView 为例，其余三视图仅 `bizType` 值不同：`'purchase'` / `'purchase_return'` / `'sales_return'`）：

```js
const handleVoid = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入作废原因', '作废单据', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '默认: 手工作废',
      inputValue: ''
    })

    const res = await createApprovalOrderAPI({
      bizType: 'sales',
      bizId: row.id,
      requestAction: 'void',
      reason: value || ''
    })
    if (res.code !== 200) {
      throw new Error(res.msg || '操作失败')
    }

    ElMessage.success('作废审批已提交，等待仓储管理员处理')
    await loadList()
  } catch (error) {
    if (error?.message && error.message !== 'cancel') {
      ElMessage.error(error.message)
    }
  }
}
```

注意：成功文案从「作废并红冲审批已提交…」修正为「作废审批已提交…」（原文案在红冲入口隐藏后已名不符实）。

- [ ] **Step 2: ProductionView 替换（保留 payload 显式 createRedFlush: false）**

```js
const handleVoid = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入作废原因', '作废单据', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '默认: 手工作废',
      inputValue: ''
    })

    const res = await voidProductionAPI(row.id, { reason: value || '', createRedFlush: false })
    if (res.code !== 200) {
      throw new Error(res.msg || '操作失败')
    }

    ElMessage.success('已作废')
    await loadList()
  } catch (error) {
    if (error?.message && error.message !== 'cancel') {
      ElMessage.error(error.message)
    }
  }
}
```

- [ ] **Step 3: 五视图模板调用点去第二参**

五处 `handleVoid(scope.row, false)` → `handleVoid(scope.row)`。

- [ ] **Step 4: #14——缩进规整**

检查五视图「操作」列按钮块（D74 删按钮后遗留的缩进不齐），按文件主体缩进风格（2 空格）规整；**只动空白字符，不动任何逻辑行**。

- [ ] **Step 5: build + diff 走查 + 提交第 2 组**

Run: `cd front && npm run build`
Expected: ✓ built

`git diff` 走查：确认除上述改动外无逻辑行变动。

```bash
git add front/src/views/business/
git commit -m "refactor(front): defer 清理第 2 组——statusTagType 默认值/await 一致性/红冲死分支与过时文案清理（Minor #10/#12/#13/#14）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 第 3 组：前端架构 B/C/E + 浮窗 D

### Task 6: B 后端——AuthzService.WARNING_DEPT_CODES 常量收口

**Files:**
- Modify: `back/src/main/java/org/example/back/service/AuthzService.java`（:21-27 常量区）
- Modify: `back/src/main/java/org/example/back/service/GoodsService.java`（:63-72）
- Modify: `back/src/main/java/org/example/back/service/HomeService.java`（:97-99）

**Interfaces:**
- Consumes: `AuthzService.requireAnyDeptAdminOrSuperAdmin(Collection<String>, String)` 重载已存在（:152）。
- Produces: `AuthzService.WARNING_DEPT_CODES`（`List<String>`，值 = warehouse/purchase/production/sales，**顺序与现状 GoodsService 一致**）。

- [ ] **Step 1: AuthzService 常量区追加**

```java
    /** 预警中心/预警数可见部门（后端单一数据源；前端对应 front/src/utils/constants.js WARNING_DEPT_CODES） */
    public static final List<String> WARNING_DEPT_CODES =
            List.of(DEPT_WAREHOUSE, DEPT_PURCHASE, DEPT_PRODUCTION, DEPT_SALES);
```

（若无 `import java.util.List;` 则补。）

- [ ] **Step 2: GoodsService.requireGoodsPageAccess 引用化**

:65-71 替换为：

```java
            authzService.requireAnyDeptAdminOrSuperAdmin(
                    "仅仓储、采购、生产或销售部门管理员可访问预警中心",
                    AuthzService.WARNING_DEPT_CODES
            );
```

- [ ] **Step 3: HomeService 引用化**

:97-99 替换为：

```java
        String deptCode = userInfo.getDeptCode();
        if (deptCode != null && AuthzService.WARNING_DEPT_CODES.contains(deptCode)) {
```

- [ ] **Step 4: 编译 + 相关测试**

Run: `cd back && ./mvnw clean compile && ./mvnw test -Dtest='GoodsServiceTest,HomeServiceTest'`
Expected: 全绿（行为等价）

### Task 7: B 前端——utils/constants.js + 三处引用

**Files:**
- Create: `front/src/utils/constants.js`
- Modify: `front/src/router/index.js`（:115-119）
- Modify: `front/src/views/home/components/AdminHome.vue`（:140-149）
- Modify: `front/src/views/home/components/EmployeeHome.vue`（:283）

- [ ] **Step 1: 新建 constants.js**

```js
// 预警中心/预警卡片可见部门（前端单一数据源；与后端 AuthzService.WARNING_DEPT_CODES 对齐）
export const WARNING_DEPT_CODES = ['warehouse', 'purchase', 'sales', 'production']
```

- [ ] **Step 2: router 引用化**

顶部 import 区加 `import { WARNING_DEPT_CODES } from "@/utils/constants"`；:118 替换为：

```js
          meta: { roles: ['admin'], deptCodes: WARNING_DEPT_CODES }
```

- [ ] **Step 3: AdminHome 引用化（反向排除改正向包含）**

import 同上。:140-143 替换为：

```js
  if (!WARNING_DEPT_CODES.includes(deptCode.value)) {
    return [...baseCards, { label: '上次登录', value: formatTime(summary.value.lastLoginTime) }]
  }
```

语义说明（有意对齐）：原逻辑仅排除 hr/finance，理论上系统管理部等其他 admin 也见预警卡；改后仅 4 个业务部门可见——与预警中心菜单/后端预警中心权限口径一致。

- [ ] **Step 4: EmployeeHome 引用化**

import 同上。:283 替换为：

```js
  if (WARNING_DEPT_CODES.includes(deptCode.value)) {
```

### Task 8: C——checkRouteAccess 守卫组合共享

**Files:**
- Modify: `front/src/utils/auth.js`（:110-115）
- Modify: `front/src/router/index.js`（:254-267）

**Interfaces:**
- Consumes: 既有原子函数 `canAccessRoles`/`hasDeptAccess`（不动）。
- Produces: `checkRouteAccess(meta, currentRole?, currentDeptCode?)` → `{ ok: boolean, reason: 'role' | 'dept' | null }`；`canAccessRouteMeta` 签名不变（变薄包装）。

- [ ] **Step 1: auth.js 新增 checkRouteAccess + canAccessRouteMeta 薄包装**

:110-115 替换为：

```js
// 路由 meta 鉴权组合判定（roles + deptCodes）：守卫按 reason 出文案，组件侧用 canAccessRouteMeta 预判可达性
export const checkRouteAccess = (meta = {}, currentRole = getRole(), currentDeptCode = getDeptCode()) => {
  if (meta.roles && !canAccessRoles(currentRole, meta.roles)) return { ok: false, reason: 'role' }
  if (Array.isArray(meta.deptCodes) && meta.deptCodes.length > 0 && !hasDeptAccess(currentDeptCode, meta.deptCodes, currentRole)) return { ok: false, reason: 'dept' }
  return { ok: true, reason: null }
}

export const canAccessRouteMeta = (meta = {}, currentRole = getRole(), currentDeptCode = getDeptCode()) =>
  checkRouteAccess(meta, currentRole, currentDeptCode).ok
```

- [ ] **Step 2: router 守卫改调共享组合（两条文案保留）**

import 行（:3）的 `@/utils/auth` 导入列表加 `checkRouteAccess`。:254-267 两个 if 块替换为：

```js
      const { ok, reason } = checkRouteAccess(to.meta || {}, role, deptCode)
      if (!ok) {
        ElMessage.error(reason === 'role' ? '无权限访问该页面' : '当前部门无权限访问该页面')
        return next('/403')
      }
      return next()
```

超管白名单块（:249-252）**不动**。

### Task 9: E——home-metrics.css 抽取（仅逐字相同段）

**Files:**
- Create: `front/src/styles/home-metrics.css`
- Modify: `front/src/main.js`（导入一次）
- Modify: `front/src/views/home/components/AdminHome.vue`、`front/src/views/home/components/EmployeeHome.vue`（删重复块）

- [ ] **Step 1: diff 两组件候选四段**

逐字对比 `.metric-grid` / `.metric-card` / `.metric-card strong` / `.metric-card strong.metric-value--alert`（AdminHome :446-472 区域、EmployeeHome :693-713 区域）。已知：`.metric-value--alert` 段两文件逐字相同；`.metric-card strong` 疑似不同（AdminHome 多 `color: #0f172a;`）。

- [ ] **Step 2: 只把逐字相同的段写入 home-metrics.css**

确定内容（至少含）：

```css
/* 首页 metric 卡片共享样式（AdminHome/EmployeeHome 逐字重复段收口；定义不同的段仍留各组件内） */
.metric-card strong.metric-value--alert {
  color: #dc2626;
  font-weight: 800;
}
```

若 Step 1 确认其他段也逐字相同，一并移入；不同的段**不强行统一**（grilling Q5 定案）。

- [ ] **Step 3: main.js 导入 + 两组件删除已抽段**

`front/src/main.js` 样式导入区加 `import './styles/home-metrics.css'`；两组件 `<style>` 内删除已抽取的选择器块。

### Task 10: D——浮窗 id 级基线（MessageCenter.vue）

**Files:**
- Modify: `front/src/components/MessageCenter.vue`（:91-94、:144-202、:311-318）

**Interfaces:**
- Consumes: `getMessagePageAPI({ pageNum, pageSize, read })`（既有，page 返回 `{ records, total }`，按 create_time desc——第一页即最新）；`withJumpPath`（:137）；`notifiedMessageIds`（:94，保留防复弹）。
- Produces: 无对外签名变化（组件内部）。

- [ ] **Step 1: 基线变量替换**

:92-93 替换为：

```js
// 浮窗基线 = 未读 id 集合 + 未读总数（id 级，可检出同窗口 +1/-1 抵消）；首轮拉取建立，存量未读不轰炸
let baselineUnreadIds = null
let baselineUnreadTotal = 0
```

- [ ] **Step 2: notifyNewMessages 改接收消息数组**

:144-184 整体替换为：

```js
// 新消息浮窗：≤3 条逐条弹（点击跳转），>3 条聚合一条（点击开邮箱）
const notifyNewMessages = (fresh, displayCount) => {
  fresh.forEach((m) => notifiedMessageIds.add(m.id))
  if (fresh.length <= 3) {
    fresh.forEach((m) => {
      ElNotification({
        title: m.title || '新消息',
        message: m.content || '',
        type: 'warning',
        position: 'bottom-right',
        duration: 6000,
        onClick: () => {
          if (m.jumpPath) {
            handleMessageClick(m)
          } else {
            drawerVisible.value = true
          }
        }
      })
    })
    return
  }
  ElNotification({
    title: '新消息提醒',
    message: `您有 ${displayCount} 条新未读消息，点击查看站内邮箱。`,
    type: 'warning',
    position: 'bottom-right',
    duration: 6000,
    onClick: () => {
      drawerVisible.value = true
    }
  })
}
```

- [ ] **Step 3: loadUnreadCount 改 id 基线轮询**

:186-202 整体替换为：

```js
const loadUnreadCount = async () => {
  if (!showMessageCenter.value) return
  try {
    const res = await getMessagePageAPI({ pageNum: 1, pageSize: 10, read: false })
    if (res.code !== 200) return
    const records = res.data?.records || []
    const total = Number(res.data?.total ?? records.length)
    const currentIds = new Set(records.map((m) => m.id))
    unreadCount.value = total
    if (baselineUnreadIds === null) {
      // 首轮仅建基线，存量未读不轰炸
      baselineUnreadIds = currentIds
      baselineUnreadTotal = total
      return
    }
    const fresh = records
      .filter((m) => !baselineUnreadIds.has(m.id) && !notifiedMessageIds.has(m.id))
      .map(withJumpPath)
    const delta = total - baselineUnreadTotal
    // 先更新基线再弹窗，避免慢请求期间下一轮轮询重入重复拉取
    baselineUnreadIds = currentIds
    baselineUnreadTotal = total
    if (fresh.length) {
      notifyNewMessages(fresh, Math.max(fresh.length, delta))
    }
  } catch {
    // 瞬时失败保持上一次基线与角标，不清零
  }
}
```

- [ ] **Step 4: 确认 getUnreadMessageCountAPI 引用清理**

MessageCenter.vue 内不再引用 `getUnreadMessageCountAPI` 时，从文件顶部 import 中移除（`front/src/api/message.js` 的导出保留，可能有其他调用方——grep 确认后再决定仅清 import）。

- [ ] **Step 5: build + 走查 + 提交第 3 组**

Run: `cd front && npm run build`
Expected: ✓ built

走查要点：首轮不弹 / 基线先更新 / catch 不重置基线 / notifiedMessageIds 仍防复弹 / handleRead 的 `notifiedMessageIds.delete` 与 handleReadAll 的 `.clear()` 不变。

```bash
git add back/src/main/java/org/example/back/service/AuthzService.java back/src/main/java/org/example/back/service/GoodsService.java back/src/main/java/org/example/back/service/HomeService.java front/src/
git commit -m "refactor(warning,message): defer 清理第 3 组——预警名单常量收口（前后端）/守卫组合共享/Home CSS 抽取/浮窗 id 级基线（B/C/E/D）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 第 4 组：A 项 target_route（D75，单独评审后提交）

### Task 11: DDL + 实体/VO 透出

**Files:**
- Modify: `db.sql`（末尾追加 17.x 段）
- Modify: `back/src/main/java/org/example/back/entity/SysMessage.java`（bizId 字段后）
- Modify: `back/src/main/java/org/example/back/vo/MessageVO.java`（bizId 字段后）
- Modify: `back/src/main/java/org/example/back/service/MessageService.java`（toVO :738-749）

- [ ] **Step 1: db.sql 追加 17.x 段 + 本地执行**

```sql
-- =====================================================================
-- 17.x D75：站内消息跳转目标 target_route（ADR-0006）
-- =====================================================================
ALTER TABLE sys_message ADD COLUMN target_route VARCHAR(100) NULL COMMENT '跳转目标路由（前端优先使用，空则按 bizType 映射兜底）';
```

本地执行：写入 /tmp/17x.sql 后 `mysql -u wms_user -pwms_pass warehouse_management < /tmp/17x.sql`。

- [ ] **Step 2: SysMessage 实体加字段（bizId 之后）**

```java
    /** D75 消息跳转目标：前端路由字面值，接收方点击跳该列表页；空则前端按 bizType 映射兜底 */
    private String targetRoute;
```

- [ ] **Step 3: MessageVO 加字段（bizId 之后）**

```java
    /** D75 消息跳转目标（发送方显式指定；为空前端按 bizType 映射兜底） */
    private String targetRoute;
```

- [ ] **Step 4: toVO 透出**

MessageService.java toVO 中 `vo.setBizId(message.getBizId());` 之后加：

```java
        vo.setTargetRoute(message.getTargetRoute());
```

### Task 12: MessageService 路由常量 + 18 发送点 + 单测

**Files:**
- Modify: `back/src/main/java/org/example/back/service/MessageService.java`
- Test: `back/src/test/java/org/example/back/service/MessageServiceTest.java`

**Interfaces:**
- Produces（签名变化，仅 MessageService 内部）：
  - `sendToDeptAdminsWithBiz(Long deptId, String title, String content, String bizType, Long bizId, String targetRoute)`
  - `sendToUserWithBiz(Long userId, String title, String content, String bizType, Long bizId, String targetRoute)`
  - 18 个 public sendXxx 签名**不变**（路由在方法体内传入）。

- [ ] **Step 1: 常量区追加（文件顶部常量区）**

```java
    // ==================== 消息跳转目标（D75）：前端路由字面值，与 front 路由表对齐 ====================
    private static final String ROUTE_SALES = "/business/sales";
    private static final String ROUTE_SALES_RETURN = "/business/sales-return";
    private static final String ROUTE_PURCHASE = "/business/purchase";
    private static final String ROUTE_PURCHASE_RETURN = "/business/purchase-return";
    private static final String ROUTE_PURCHASE_REQUEST = "/business/purchase-request";
    private static final String ROUTE_PICK_LIST = "/business/pick-list";
    private static final String ROUTE_PRODUCTION_ORDER = "/business/production-order";
    private static final String ROUTE_VOID_APPROVAL = "/system/void-approval";
```

- [ ] **Step 2: 两个 WithBiz helper 加第 6 参**

`sendToDeptAdminsWithBiz`（:673）签名加 `String targetRoute`，循环内 `message.setBizId(bizId);` 后加 `message.setTargetRoute(targetRoute);`。
`sendToUserWithBiz`（:703）同样处理。
无 biz 包装 `sendToDeptAdmins`（:669）/ `sendToUser`（:698）调用处补 `null` 第 6 参（保持无路由）。

- [ ] **Step 3: 18 个发送点逐一对表传路由**

| # | 方法 | 调用处末参改为 |
|---|---|---|
| 1 | sendSalesPendingConfirmToWarehouseAdmins | `"sales", salesId, ROUTE_SALES` |
| 2 | sendSalesDemandToProductionAdmins | `"sales", salesId, ROUTE_PRODUCTION_ORDER` |
| 3 | sendSalesReadyToShipToUser | `"sales", salesId, ROUTE_SALES` |
| 4 | sendSalesReturnPendingConfirmToWarehouseAdmins | `"sales_return", returnId, ROUTE_SALES_RETURN` |
| 5 | sendPickListFailureToSalesAdmins | `"pick_list", pickId, ROUTE_SALES` |
| 6 | sendPickIssuedToProductionAdmins | `"pick_list", pickId, ROUTE_PICK_LIST` |
| 7 | sendPickIssueFailedToProductionAdmins | `"pick_list", pickId, ROUTE_PICK_LIST` |
| 8 | sendPickReturnPendingToWarehouseAdmins | `"pick_list", pickId, ROUTE_PICK_LIST` |
| 9 | sendPickPendingToWarehouseAdmins | `"pick_list", pickId, ROUTE_PICK_LIST` |
| 10 | sendKitCompleteToProductionAdmins | `"production_order", orderId, ROUTE_PRODUCTION_ORDER` |
| 11 | sendKitShortageToPurchaseAdmins | `"production_order", orderId, ROUTE_PURCHASE_REQUEST` |
| 12 | sendSalesCancelledToProductionAdmins | `"production_order", orderId, ROUTE_PRODUCTION_ORDER` |
| 13 | sendPurchaseRequestToPurchaseAdmins | `"purchase_request", requestId, ROUTE_PURCHASE_REQUEST` |
| 14 | sendPurchaseRequestArrivedToWarehouseAdmins | `"purchase_request", requestId, ROUTE_PURCHASE_REQUEST` |
| 15 | sendPurchaseRequestClaimedToSourceApplicant | `"purchase_request", requestId, ROUTE_PURCHASE_REQUEST` |
| 16 | sendPurchaseArrivedToWarehouseAdmins | `"purchase", purchaseId, ROUTE_PURCHASE` |
| 17 | sendPurchaseReturnPendingConfirmToWarehouseAdmins | `"purchase_return", returnId, ROUTE_PURCHASE_RETURN` |
| 18 | sendPriceDeviationToSuperAdmin | 直构 SysMessage：`message.setBizId(salesId);` 后加 `message.setTargetRoute(ROUTE_VOID_APPROVAL);` |

（各方法内实参名以现场为准——上表 `#` 与 bizId 变量名仅供定位；逐处改完对照本表自查一遍。）

- [ ] **Step 4: MessageServiceTest 补 targetRoute 断言**

文件头部 import 追加：`org.example.back.entity.SysDept`、`org.example.back.entity.SysUser`、`org.mockito.ArgumentCaptor`、`static org.mockito.Mockito.verify`。类末尾追加 2 例：

```java
    // ---------- D75：消息跳转目标 target_route 落库 ----------

    @Test
    void sendKitComplete_writesTargetRoute() {
        SysDept dept = new SysDept();
        dept.setId(3L);
        when(sysDeptMapper.selectOne(any())).thenReturn(dept);
        SysUser admin = new SysUser();
        admin.setId(21L);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));

        service.sendKitCompleteToProductionAdmins("PO-1", "PTO153", 2, "PR-1", 7L);

        ArgumentCaptor<SysMessage> captor = ArgumentCaptor.forClass(SysMessage.class);
        verify(sysMessageMapper).insert(captor.capture());
        assertEquals("/business/production-order", captor.getValue().getTargetRoute());
    }

    @Test
    void sendPriceDeviation_writesVoidApprovalRoute() {
        SysUser superadmin = new SysUser();
        superadmin.setId(1L);
        when(sysUserMapper.selectOne(any())).thenReturn(superadmin);

        service.sendPriceDeviationToSuperAdmin("XS-1", "销售甲", new java.math.BigDecimal("0.08"), 55L);

        ArgumentCaptor<SysMessage> captor = ArgumentCaptor.forClass(SysMessage.class);
        verify(sysMessageMapper).insert(captor.capture());
        assertEquals("/system/void-approval", captor.getValue().getTargetRoute());
    }
```

- [ ] **Step 5: 编译 + 全量单测**

Run: `cd back && ./mvnw clean compile && ./mvnw test`
Expected: 全绿（188 → 190）

### Task 13: 前端 resolveJumpPath targetRoute 优先

**Files:**
- Modify: `front/src/components/MessageCenter.vue`（resolveJumpPath :127-135）

- [ ] **Step 1: 替换 resolveJumpPath**

```js
// D75：消息自带跳转目标优先（可达性校验，不可达/缺失回落 bizType 映射——存量消息兼容）
const resolveJumpPath = (item) => {
  if (!item) return null
  if (item.targetRoute && canAccessPath(item.targetRoute)) return item.targetRoute
  if (!item.bizType) return null
  // 超管仅能进超管中心：价格偏离审批消息（biz_type=sales）映射到审批页
  if (isSuperAdmin(getRole())) {
    return item.bizType === 'sales' ? '/system/void-approval' : null
  }
  const candidates = BIZ_ROUTE_MAP[item.bizType] || []
  return candidates.find((path) => canAccessPath(path)) || null
}
```

- [ ] **Step 2: build**

Run: `cd front && npm run build`
Expected: ✓ built

### Task 14: A 项 E2E + 独立评审 + 提交

**Files:** 无新增（验证与提交）

- [ ] **Step 1: 重启后端 + curl E2E**

`fuser -k 8080/tcp` 后重启后端（`cd back && nohup ./mvnw spring-boot:run > /tmp/wms-backend.log 2>&1 &`）。E2E 链路：
1. 登录 sales_admin 建一张缺货销售单（库存 0 商品）→ 触发 `sendSalesDemandToProductionAdmins`；
2. 登录 production_admin 拉 `GET /api/system/messages/page?read=false` → 断言该消息 VO 含 `targetRoute: "/business/production-order"`；
3. 数据库直查 `SELECT target_route FROM sys_message ORDER BY id DESC LIMIT 1;` 双重确认；
4. 负测：旧消息（target_route 为 NULL）VO 字段为 null（前端走兜底，不回 500）；
5. 清理：删除测试销售单（连带撤消息），确认库存零变动。

- [ ] **Step 2: 只读审查子代理按 spec 逐条核对**

派只读子代理（prompt 写明：只读、禁止任何写操作与 git 写操作），核对清单：① 18 发送点路由与 spec 映射表逐一对表；② helper 第 6 参所有调用点已补齐（无遗漏编译告警）；③ toVO/VO/实体透出完整；④ DDL 注释与 db.sql 段号；⑤ 前端兜底链完整（targetRoute → 不可达 → bizType 映射 → null）；⑥ 无 bizType/revoke 改动。产出 findings 列表，有问题先修。

- [ ] **Step 3: 用户终审 diff 后提交第 4 组**

```bash
git add db.sql back/ front/src/components/MessageCenter.vue
git commit -m "feat(message): D75 消息跳转目标 target_route——sys_message 加列 + 18 发送点显式路由 + 前端优先使用（ADR-0006）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 收尾（全部 4 组完成后）

- [ ] progress.md 补会话 28 记录；task_plan.md「增量优化」小节后补 defer 清理完成段 + 「📊 总体进度」更新；两者随最后一组或单独 docs commit 提交。
- [ ] 重启两端 + 浏览器硬刷新验证；D 项浮窗按 grilling Q3 由用户手测一轮。
