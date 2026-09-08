# D61 预计到货时间行级化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 采购申请单的预计到货时间从整单级下钻到每个明细行（各行各自 预计到货时间+到货备注），采购认领时按行填写、采购中可修改、认领时通知来源申请人。

**Architecture:** `biz_purchase_request_detail` 新增 `expected_arrival_time`/`arrival_remark` 两列；主表 `expected_arrival_time` 删除（存量回填到明细行后 DROP）。认领接口 DTO 改为行级 items；新增「修改到货计划」接口（仅采购中 status=2 可调）；认领时经 MessageService（D21 范式带 biz 绑定）向来源申请人部门发行级到货摘要。前端认领对话框改行级表格（带统一填充），列表列聚合展示。

**Tech Stack:** Spring Boot + MyBatis-Plus（back/）、Vue3 + Element Plus + Vite（front/）、MySQL 8.0。

## Global Constraints

- 后端 8080（context-path `/api`），前端 5173（Vite 代理 `/api → 8080`）；MySQL 库 `warehouse_management`，账号 `wms_user`/`wms_pass`。
- db.sql **只追加** 11.x 节 DDL（沿用 8.x/10.x 的 ALTER 风格，不改历史 CREATE TABLE）；本地库执行放到 Task 7（提前执行会让运行中的旧后端因主表缺列报错）。
- 站内消息必须走 D21 范式：`sendToDeptAdminsWithBiz(deptId, title, content, bizType, bizId)`，`bizType="purchase_request"`；生命周期撤销沿用 `process()` 内已有的 `revokeUnreadByBiz` 调用点（认领先 revoke 旧消息再发新消息）。
- 语义红线：明细行已有的 `remark` 是 BOM 物料描述快照（ADR-0002），新字段叫 `arrivalRemark`（到货备注），**两者互不复用**（CONTEXT.md 已落词条）。
- 必填口径：认领/修改时每行 `expectedArrivalTime` 必填（DTO 校验 + service 校验 + 前端校验三层）；`arrivalRemark` 选填，空串落库为 NULL。
- `updateArrivalPlan` 仅 status=2（采购中）可调；status=5（待入库确认）及以后锁定。
- 测试写法遵循 `PurchaseRequestServiceTest` 既有范式：`@ExtendWith(MockitoExtension.class)` + `@BeforeAll` 里 `TableInfoHelper.initTableInfo` 初始化 lambda 缓存（`BizPurchaseRequest` 与 `BizPurchaseRequestDetail` 两个类都已初始化过，直接沿用）。
- 提交信息：中文 conventional commits（`feat(scope): 描述`），结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- 改动后验证：`./mvnw compile` → `./mvnw test -Dtest=PurchaseRequestServiceTest`；前端 `npm run build`；最后重启两端 + curl E2E + 测试数据清理。

---

### Task 1: db.sql 追加 11.x DDL（本地库执行推迟到 Task 7）

**Files:**
- Modify: `db.sql`（文件末尾追加）

**Interfaces:**
- Produces: 明细表新列 `expected_arrival_time` DATETIME、`arrival_remark` VARCHAR(200)；主表列 `expected_arrival_time` 删除。Task 2-6 的实体/VO 字段名与此对应；Task 7 才对本地库执行。

- [ ] **Step 1: 在 db.sql 末尾追加 DDL 节**

```sql
-- ============================================================
-- 11.x D61 预计到货时间行级化：明细行各自预计到货时间+到货备注，主表整单级字段废除
-- 1) 明细加 expected_arrival_time / arrival_remark（到货备注与 remark 物料描述快照互不复用）
-- 2) 存量回填：主表整单值下放各行（NULL 保持 NULL，不追溯）
-- 3) 删除主表 expected_arrival_time，查询口径统一走行级
-- ============================================================
ALTER TABLE `biz_purchase_request_detail`
    ADD COLUMN `expected_arrival_time` DATETIME DEFAULT NULL COMMENT '预计到货时间(采购认领时按行填写,采购中可改,D61)' AFTER `quantity`,
    ADD COLUMN `arrival_remark` VARCHAR(200) DEFAULT NULL COMMENT '到货备注(供应商/发货方式等采购口径,与remark物料描述快照独立,D61)' AFTER `expected_arrival_time`;

UPDATE biz_purchase_request_detail d
    JOIN biz_purchase_request r ON d.request_id = r.id
    SET d.expected_arrival_time = r.expected_arrival_time
    WHERE d.expected_arrival_time IS NULL;

ALTER TABLE `biz_purchase_request`
    DROP COLUMN `expected_arrival_time`;
```

操作：把上面的 SQL（含注释头）追加到 `/home/niuchao/Warehouse-Management-System-/db.sql` 末尾（当前文件 1477 行，追加后约 1497 行）。

- [ ] **Step 2: 语法自检（不执行）**

Run: `tail -35 /home/niuchao/Warehouse-Management-System-/db.sql`
Expected: 能看到 11.x 注释头 + 2 条 ALTER + 1 条 UPDATE，且 `DROP COLUMN` 在 UPDATE 之后。

- [ ] **Step 3: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System-
git add db.sql
git commit -m "feat(purchase): db.sql 11.x D61 预计到货时间行级化DDL(明细加列+存量回填+主表删列)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: 明细实体/VO 加字段 + toDetailVO 映射

**Files:**
- Modify: `back/src/main/java/org/example/back/entity/BizPurchaseRequestDetail.java`（quantity 字段后加两个字段，约 :38-43）
- Modify: `back/src/main/java/org/example/back/vo/PurchaseRequestDetailVO.java`（quantity 字段后加两个字段，约 :31-33）
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`（toDetailVO，约 :602-618）
- Test: 运行既有测试确认不回归（新字段的行为测试在 Task 3/4/5）

**Interfaces:**
- Produces: `BizPurchaseRequestDetail.getExpectedArrivalTime()/getArrivalRemark()`、`PurchaseRequestDetailVO.getExpectedArrivalTime()/getArrivalRemark()`（LocalDateTime / String）。Task 3 的行级写入、Task 6 的前端展示都消费这两个名字。

- [ ] **Step 1: 实体加字段**

在 `BizPurchaseRequestDetail.java` 的 `quantity` 字段（:38）之后插入：

```java
    /**
     * 预计到货时间(采购认领时按行填写,采购中可改;D61)
     */
    private LocalDateTime expectedArrivalTime;

    /**
     * 到货备注(供应商/发货方式等采购口径,与remark物料描述快照独立;D61)
     */
    private String arrivalRemark;
```

- [ ] **Step 2: VO 加字段**

在 `PurchaseRequestDetailVO.java` 的 `quantity` 字段（:31）之后插入：

```java
    /** 预计到货时间（采购认领时按行填写，采购中可改；D61） */
    private LocalDateTime expectedArrivalTime;

    /** 到货备注（供应商/发货方式等采购口径，与 remark 物料描述快照独立；D61） */
    private String arrivalRemark;
```

- [ ] **Step 3: toDetailVO 补映射**

`PurchaseRequestService.toDetailVO()`（:602-618）中，在 `vo.setQuantity(detail.getQuantity());`（:613）之后插入：

```java
        vo.setExpectedArrivalTime(detail.getExpectedArrivalTime());
        vo.setArrivalRemark(detail.getArrivalRemark());
```

- [ ] **Step 4: 编译 + 既有测试不回归**

Run: `cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q compile && ./mvnw test -Dtest=PurchaseRequestServiceTest -q`
Expected: BUILD SUCCESS，Tests run: 10, Failures: 0（既有 10 个用例全过）。

- [ ] **Step 5: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System-
git add back/src/main/java/org/example/back/entity/BizPurchaseRequestDetail.java back/src/main/java/org/example/back/vo/PurchaseRequestDetailVO.java back/src/main/java/org/example/back/service/PurchaseRequestService.java
git commit -m "feat(purchase): 明细实体/VO加预计到货时间与到货备注字段(D61)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: 认领 DTO 行级化 + process() 重写 + 主表字段清理（TDD）

**Files:**
- Modify: `back/src/main/java/org/example/back/dto/PurchaseRequestProcessDTO.java`（整体重写为行级 items）
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`（process() :317-339 重写；toVO() :588 删一行；私有辅助加 toValidatedItemMap/buildArrivalSummary/trimToNull）
- Modify: `back/src/main/java/org/example/back/entity/BizPurchaseRequest.java`（删 :41-44 expectedArrivalTime 字段）
- Modify: `back/src/main/java/org/example/back/vo/PurchaseRequestVO.java`（删 :45-48 expectedArrivalTime 字段）
- Test: `back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java`（追加 3 个用例）

**Interfaces:**
- Consumes: Task 2 的明细字段。
- Produces: `PurchaseRequestProcessDTO{ items: List<ProcessItemDTO{detailId, expectedArrivalTime, arrivalRemark}> }`（Task 5 的 updateArrivalPlan 复用同一 DTO）；`service.process(Long, PurchaseRequestProcessDTO)` 行级语义；私有方法 `toValidatedItemMap(dto, details)`、`buildArrivalSummary(details, itemMap)`、`trimToNull(String)`（Task 4/5 复用）。主表实体/VO 不再有 `expectedArrivalTime`。

- [ ] **Step 1: 写失败测试（追加到 PurchaseRequestServiceTest 末尾，类内）**

```java
    // ---------- D61：认领按行写预计到货时间+到货备注 ----------
    @Test
    void process_writesPerLineArrivalInfo() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("采购乙");
        when(authService.getUserInfo()).thenReturn(user);

        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(1);
        request.setRequestNo("PR-1");
        request.setSourceType("production");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        BizPurchaseRequestDetail d2 = new BizPurchaseRequestDetail();
        d2.setId(102L);
        d2.setGoodsName("钢板");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1, d2));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);

        LocalDateTime t1 = LocalDateTime.of(2026, 9, 15, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 20, 0, 0);
        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(
                processItem(101L, t1, " 厂家A直发 "),
                processItem(102L, t2, null)));

        service.process(5L, dto);

        ArgumentCaptor<BizPurchaseRequestDetail> detCap =
                ArgumentCaptor.forClass(BizPurchaseRequestDetail.class);
        verify(bizPurchaseRequestDetailMapper, times(2)).updateById(detCap.capture());
        List<BizPurchaseRequestDetail> updated = detCap.getAllValues();
        assertEquals(t1, updated.get(0).getExpectedArrivalTime());
        assertEquals("厂家A直发", updated.get(0).getArrivalRemark(), "备注应 trim");
        assertEquals(t2, updated.get(1).getExpectedArrivalTime());
        assertNull(updated.get(1).getArrivalRemark(), "空备注应落 NULL");
        verify(messageService).revokeUnreadByBiz("purchase_request", 5L);
    }

    private static PurchaseRequestProcessDTO.ProcessItemDTO processItem(Long detailId, LocalDateTime time, String remark) {
        PurchaseRequestProcessDTO.ProcessItemDTO item = new PurchaseRequestProcessDTO.ProcessItemDTO();
        item.setDetailId(detailId);
        item.setExpectedArrivalTime(time);
        item.setArrivalRemark(remark);
        return item;
    }

    // ---------- D61：任一行缺预计到货时间 → 整单退回 ----------
    @Test
    void process_rejectsWhenAnyLineMissingTime() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(1);
        request.setRequestNo("PR-1");
        request.setSourceType("warehouse");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        BizPurchaseRequestDetail d2 = new BizPurchaseRequestDetail();
        d2.setId(102L);
        d2.setGoodsName("钢板");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1, d2));

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(processItem(101L, LocalDateTime.of(2026, 9, 15, 0, 0), null)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.process(5L, dto));
        assertTrue(ex.getMessage().contains("缺少预计到货时间"), "实际: " + ex.getMessage());
        verify(bizPurchaseRequestMapper, never()).update(any(), any());
        verify(messageService, never()).revokeUnreadByBiz(anyString(), any());
    }

    // ---------- D61：非待采购状态不可认领（回归保护） ----------
    @Test
    void process_rejectsWhenNotPending() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(2);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.process(5L, new PurchaseRequestProcessDTO()));
        assertEquals("仅待采购状态可认领", ex.getMessage());
    }
```

同时在该测试文件的 import 区补：

```java
import org.example.back.dto.PurchaseRequestProcessDTO;
import org.mockito.Mockito;
import java.time.LocalDateTime;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
```

（`assertEquals`/`assertNull`/`assertThrows`/`assertTrue`/`never`/`verify`/`when`/`any` 文件里已有；若编译报缺失按提示补。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=PurchaseRequestServiceTest -q`
Expected: 编译失败（`PurchaseRequestProcessDTO` 无 `setItems`/`ProcessItemDTO`）。

- [ ] **Step 3: 重写 PurchaseRequestProcessDTO**

整个文件替换为：

```java
package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购认领/修改到货计划 DTO（D61 行级化）：按明细行填写预计到货时间（必填）与到货备注（选填）。
 */
@Data
public class PurchaseRequestProcessDTO {

    @NotEmpty(message = "请填写各行预计到货时间")
    @Valid
    private List<ProcessItemDTO> items;

    @Data
    public static class ProcessItemDTO {

        @NotNull(message = "明细ID不能为空")
        private Long detailId;

        @NotNull(message = "预计到货时间必填")
        private LocalDateTime expectedArrivalTime;

        @Size(max = 200, message = "到货备注不能超过200字")
        private String arrivalRemark;
    }
}
```

- [ ] **Step 4: 重写 process() 并清理主表字段**

4a. `PurchaseRequestService.process()`（:317-339）整体替换为：

```java
    // ============================== 采购认领（转采购中，行级到货计划 D61） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void process(Long id, PurchaseRequestProcessDTO dto) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待采购状态可认领");
        }

        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail("采购申请明细为空，无法认领");
        }
        Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> itemMap = toValidatedItemMap(dto, details);

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_PENDING)
                .set(BizPurchaseRequest::getStatus, STATUS_PURCHASING)
                .set(BizPurchaseRequest::getOperatorId, loginUser.getId())
                .set(BizPurchaseRequest::getOperatorName, loginUser.getRealName())
                .set(BizPurchaseRequest::getOperationTime, LocalDateTime.now());
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单已被处理，禁止重复认领");
        }
        // 行级写入预计到货时间+到货备注
        for (BizPurchaseRequestDetail detail : details) {
            PurchaseRequestProcessDTO.ProcessItemDTO item = itemMap.get(detail.getId());
            detail.setExpectedArrivalTime(item.getExpectedArrivalTime());
            detail.setArrivalRemark(trimToNull(item.getArrivalRemark()));
            bizPurchaseRequestDetailMapper.updateById(detail);
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
        messageService.sendPurchaseRequestClaimedToSourceApplicant(
                entity.getRequestNo(), loginUser.getRealName(), entity.getSourceType(),
                buildArrivalSummary(details, itemMap), id);
    }
```

（`sendPurchaseRequestClaimedToSourceApplicant` 在 Task 4 实现；本任务先写调用会导致编译失败，所以 **Task 3 与 Task 4 的实现步骤在同一工作会话内连续完成**——若严格分两步编译，可临时把这两行调用注释掉，Task 4 再放开。推荐直接连做。）

4b. 在「私有辅助」区（:515 起）追加：

```java
    /** D61：校验认领/修改到货计划的行级 items——逐行必填时间、detailId 必须与单据明细一一对应 */
    private Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> toValidatedItemMap(
            PurchaseRequestProcessDTO dto, List<BizPurchaseRequestDetail> details) {
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            throw BusinessException.validateFail("请填写各行预计到货时间");
        }
        Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> itemMap = new java.util.HashMap<>();
        for (PurchaseRequestProcessDTO.ProcessItemDTO item : dto.getItems()) {
            if (item.getDetailId() == null || item.getExpectedArrivalTime() == null) {
                throw BusinessException.validateFail("预计到货时间必填");
            }
            itemMap.put(item.getDetailId(), item);
        }
        for (BizPurchaseRequestDetail detail : details) {
            if (!itemMap.containsKey(detail.getId())) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName() + "]缺少预计到货时间");
            }
        }
        return itemMap;
    }

    /** D61：行级到货摘要（消息用），格式「轴承 2026-09-15；钢板 2026-09-20」 */
    private String buildArrivalSummary(List<BizPurchaseRequestDetail> details,
                                       Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> itemMap) {
        return details.stream()
                .map(d -> d.getGoodsName() + " " + itemMap.get(d.getId()).getExpectedArrivalTime().toLocalDate())
                .collect(Collectors.joining("；"));
    }

    private String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }
```

4c. 删除主表字段：
- `BizPurchaseRequest.java`：删 :41-44（`expectedArrivalTime` 字段及其注释）。
- `PurchaseRequestVO.java`：删 :45-48（同名字段及注释）。
- `PurchaseRequestService.toVO()`：删 `vo.setExpectedArrivalTime(entity.getExpectedArrivalTime());`（:588）。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=PurchaseRequestServiceTest -q`（若因 Task 4 方法未实现而编译失败，先做 Task 4 再回来跑）
Expected: Tests run: 13, Failures: 0。

- [ ] **Step 6: 全局残留检查**

Run: `grep -rn "expectedArrivalTime\|expected_arrival_time" /home/niuchao/Warehouse-Management-System-/back/src/main/java`
Expected: 仅剩 `BizPurchaseRequestDetail.java`、`PurchaseRequestDetailVO.java`、`PurchaseRequestProcessDTO.java` 中的行级字段；`BizPurchaseRequest`/`PurchaseRequestVO`/`PurchaseRequestService.toVO` 无残留。

- [ ] **Step 7: Commit（与 Task 4 实现一并提交亦可；若分开，本任务先提交 DTO/process/主表清理）**

```bash
cd /home/niuchao/Warehouse-Management-System-
git add back/src/main/java/org/example/back/dto/PurchaseRequestProcessDTO.java back/src/main/java/org/example/back/service/PurchaseRequestService.java back/src/main/java/org/example/back/entity/BizPurchaseRequest.java back/src/main/java/org/example/back/vo/PurchaseRequestVO.java back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java
git commit -m "feat(purchase): 认领改行级到货计划,主表预计到货字段下线(D61)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: 认领通知来源申请人（D21 范式）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/MessageService.java`（在 `sendPurchaseRequestArrivedToWarehouseAdmins` 之后，约 :413 前，新增方法）
- Test: `back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java`（追加 1 个用例）

**Interfaces:**
- Consumes: Task 3 中 `process()` 已调用的 `messageService.sendPurchaseRequestClaimedToSourceApplicant(requestNo, operatorName, sourceType, arrivalSummary, requestId)`。
- Produces: `MessageService.sendPurchaseRequestClaimedToSourceApplicant(String requestNo, String operatorName, String sourceType, String arrivalSummary, Long requestId)`——sourceType=production → 生产部管理员，其余（warehouse）→ 仓储部管理员；biz 绑定 `purchase_request`/requestId，复用 process() 既有 revoke 点的生命周期管理。

- [ ] **Step 1: 写失败测试（追加到测试类）**

```java
    // ---------- D61：认领通知来源申请人（行级到货摘要） ----------
    @Test
    void process_notifiesSourceApplicantWithArrivalSummary() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("采购乙");
        when(authService.getUserInfo()).thenReturn(user);

        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(1);
        request.setRequestNo("PR-1");
        request.setSourceType("production");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(processItem(101L, LocalDateTime.of(2026, 9, 15, 0, 0), null)));

        service.process(5L, dto);

        verify(messageService).sendPurchaseRequestClaimedToSourceApplicant(
                eq("PR-1"), eq("采购乙"), eq("production"),
                Mockito.contains("轴承 2026-09-15"), eq(5L));
    }
```

（`eq` 已 import；`Mockito.contains` 是 ArgumentMatchers.contains(String) 的子串匹配。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=PurchaseRequestServiceTest -q`
Expected: 编译失败（MessageService 无该方法）。

- [ ] **Step 3: MessageService 新增方法**

在 `sendPurchaseRequestArrivedToWarehouseAdmins`（:395-412）方法结束后插入：

```java
    /**
     * D61 采购认领后向来源申请人推送行级到货摘要（生产补料→生产部管理员，仓储建单→仓储部管理员）。
     * biz 绑定 purchase_request/requestId：认领时 process() 先 revoke 旧待处理消息再发本条，
     * 后续终态（入库/驳回/撤销）由既有 revokeUnreadByBiz 调用点统一回收未读。
     */
    public void sendPurchaseRequestClaimedToSourceApplicant(String requestNo, String operatorName,
                                                            String sourceType, String arrivalSummary, Long requestId) {
        String deptCode = PurchaseRequestService.SOURCE_PRODUCTION.equals(sourceType)
                ? AuthzService.DEPT_PRODUCTION : AuthzService.DEPT_WAREHOUSE;
        Long deptId = resolveDeptIdByCode(deptCode);
        if (deptId == null) {
            return;
        }
        String operator = StringUtils.hasText(operatorName) ? operatorName : "采购管理员";
        sendToDeptAdminsWithBiz(
                deptId,
                "采购申请单已认领",
                String.format(Locale.ROOT,
                        "采购申请单 %s 已由 %s 认领，预计到货：%s",
                        requestNo, operator, arrivalSummary == null ? "-" : arrivalSummary),
                "purchase_request",
                requestId
        );
    }
```

（MessageService 与 PurchaseRequestService 同包，无需 import；`AuthzService`/`StringUtils`/`Locale` 该文件已在用。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=PurchaseRequestServiceTest -q`
Expected: Tests run: 14, Failures: 0。

- [ ] **Step 5: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System-
git add back/src/main/java/org/example/back/service/MessageService.java back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java
git commit -m "feat(purchase): 认领通知来源申请人行级到货摘要(D21范式,D61)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: 修改到货计划接口（仅采购中可改，TDD）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`（新增 updateArrivalPlan，放在 process() 之后）
- Modify: `back/src/main/java/org/example/back/controller/PurchaseRequestController.java`（process 端点后新增路由）
- Test: `back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java`（追加 3 个用例）

**Interfaces:**
- Consumes: Task 3 的 `toValidatedItemMap`/`trimToNull`；Task 2 的明细字段。
- Produces: `PUT /business/purchase-requests/{id}/arrival-plan`（body 与认领相同的 `PurchaseRequestProcessDTO`）；`service.updateArrivalPlan(Long, PurchaseRequestProcessDTO)`。

- [ ] **Step 1: 写失败测试（追加到测试类）**

```java
    // ---------- D61：修改到货计划（仅采购中） ----------
    @Test
    void updateArrivalPlan_updatesDetailRowsInPurchasingStatus() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(2);
        request.setRequestNo("PR-1");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1));

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(processItem(101L, LocalDateTime.of(2026, 9, 25, 0, 0), "改发厂家B")));

        service.updateArrivalPlan(5L, dto);

        ArgumentCaptor<BizPurchaseRequestDetail> detCap =
                ArgumentCaptor.forClass(BizPurchaseRequestDetail.class);
        verify(bizPurchaseRequestDetailMapper).updateById(detCap.capture());
        assertEquals(LocalDateTime.of(2026, 9, 25, 0, 0), detCap.getValue().getExpectedArrivalTime());
        assertEquals("改发厂家B", detCap.getValue().getArrivalRemark());
        // 修改不触发任何消息与撤销
        verify(messageService, never()).sendPurchaseRequestClaimedToSourceApplicant(any(), any(), any(), any(), any());
        verify(messageService, never()).revokeUnreadByBiz(anyString(), any());
    }

    @Test
    void updateArrivalPlan_rejectsWhenNotPurchasing() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(5); // 待入库确认，锁定
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateArrivalPlan(5L, new PurchaseRequestProcessDTO()));
        assertEquals("仅采购中状态可修改到货计划", ex.getMessage());
    }

    @Test
    void updateArrivalPlan_rejectsUnknownDetailId() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(2);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1));

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(processItem(999L, LocalDateTime.of(2026, 9, 25, 0, 0), null)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateArrivalPlan(5L, dto));
        assertTrue(ex.getMessage().contains("缺少预计到货时间"), "未知 detailId 应视为缺行, 实际: " + ex.getMessage());
        verify(bizPurchaseRequestDetailMapper, never()).updateById(any(BizPurchaseRequestDetail.class));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=PurchaseRequestServiceTest -q`
Expected: 编译失败（无 updateArrivalPlan）。

- [ ] **Step 3: Service 实现**

在 `PurchaseRequestService.process()` 之后新增：

```java
    // ============================== 修改到货计划（采购中可改，D61） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void updateArrivalPlan(Long id, PurchaseRequestProcessDTO dto) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PURCHASING) {
            throw BusinessException.validateFail("仅采购中状态可修改到货计划");
        }
        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> itemMap = toValidatedItemMap(dto, details);
        for (BizPurchaseRequestDetail detail : details) {
            PurchaseRequestProcessDTO.ProcessItemDTO item = itemMap.get(detail.getId());
            detail.setExpectedArrivalTime(item.getExpectedArrivalTime());
            detail.setArrivalRemark(trimToNull(item.getArrivalRemark()));
            bizPurchaseRequestDetailMapper.updateById(detail);
        }
    }
```

- [ ] **Step 4: Controller 新增路由**

在 `PurchaseRequestController` 的 `process` 端点（:69-76）之后插入：

```java
    /**
     * D61 修改到货计划：采购中状态可按行调整预计到货时间与到货备注（厂家延期/换厂家）。
     */
    @PutMapping("/{id}/arrival-plan")
    @RequireAdmin("仅采购管理员可修改到货计划")
    @AuditLog(module = "采购申请", action = "修改到货计划", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交修改请求")
    public Result<Void> updateArrivalPlan(@PathVariable Long id, @Valid @RequestBody PurchaseRequestProcessDTO dto) {
        purchaseRequestService.updateArrivalPlan(id, dto);
        return Result.success();
    }
```

- [ ] **Step 5: 跑测试确认通过 + 编译**

Run: `cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=PurchaseRequestServiceTest -q && ./mvnw -q compile`
Expected: Tests run: 17, Failures: 0；BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System-
git add back/src/main/java/org/example/back/service/PurchaseRequestService.java back/src/main/java/org/example/back/controller/PurchaseRequestController.java back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java
git commit -m "feat(purchase): 新增修改到货计划接口(仅采购中可改,D61)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: 前端改造（行级认领/修改对话框 + 列表聚合 + 详情/到货列）

**Files:**
- Modify: `front/src/api/purchaseRequest.js`（新增 updateArrivalPlanAPI）
- Modify: `front/src/views/business/PurchaseRequestView.vue`（列表列 :55-57、操作列 :61-83、详情对话框 :160-224、到货对话框 :227-252、认领对话框 :254-267、script 各处）

**Interfaces:**
- Consumes: 后端新 DTO `items[{detailId, expectedArrivalTime, arrivalRemark}]`（认领与修改同一结构）；明细 VO 的 `expectedArrivalTime`/`arrivalRemark`。
- Produces: `updateArrivalPlanAPI(id, data)`；`formatArrivalRange(details)`；认领与修改共用 `openArrivalPlanDialog(row, mode)`（mode: 'process' | 'update'）。

- [ ] **Step 1: API 层加方法**

`front/src/api/purchaseRequest.js` 的 `processPurchaseRequestAPI` 行后加：

```js
export const updateArrivalPlanAPI = (id, data) => request.put(`/business/purchase-requests/${id}/arrival-plan`, data)
```

- [ ] **Step 2: 列表「预计到货」列改聚合展示**

替换 :55-57：

```html
        <el-table-column label="预计到货" width="150">
          <template #default="{ row }">{{ formatArrivalRange(row.details) }}</template>
        </el-table-column>
```

操作列 :61 `<el-table-column label="操作" width="360" fixed="right">` 改为 `width="420"`，并在「到货提交」按钮（:70-71）后插入：

```html
            <!-- 采购：采购中 → 修改到货计划（D61） -->
            <el-button link type="primary" v-if="row.status === 2"
              v-permission="{ roles: ['admin'], deptCodes: ['purchase'] }" @click="handleUpdatePlan(row)">修改到货计划</el-button>
```

- [ ] **Step 3: 详情对话框——去整单项、明细表加两列**

3a. 删除 :168 整行 `<el-descriptions-item label="预计到货">{{ formatDate(viewData.expectedArrivalTime) }}</el-descriptions-item>`。

3b. 两组生产补料明细表（:179-192 与 :196-214 的 el-table 内，`备注`列之后、`数量`列之前各插入）：

```html
            <el-table-column label="预计到货" width="110">
              <template #default="{ row }">{{ formatDate(row.expectedArrivalTime) }}</template>
            </el-table-column>
            <el-table-column label="到货备注" min-width="100">
              <template #default="{ row }">{{ row.arrivalRemark || '—' }}</template>
            </el-table-column>
```

3c. 仓储来源明细表（:217-224 的 el-table 内，`数量`列后插入）：

```html
        <el-table-column label="预计到货" width="110">
          <template #default="{ row }">{{ formatDate(row.expectedArrivalTime) }}</template>
        </el-table-column>
        <el-table-column label="到货备注" min-width="100">
          <template #default="{ row }">{{ row.arrivalRemark || '—' }}</template>
        </el-table-column>
```

- [ ] **Step 4: 到货提交对话框加只读「预计到货」列**

在 :242-246（采购单价列）之前插入：

```html
        <el-table-column label="预计到货" width="110">
          <template #default="{ row }">{{ formatDate(row.expectedArrivalTime) }}</template>
        </el-table-column>
```

并把 `handleArrive` 中 items 映射（:496-502）补一个字段：

```js
    receiveForm.items = (res.data?.details || []).map(d => ({
      detailId: d.id,
      goodsName: d.goodsName,
      requestQuantity: d.quantity,
      quantity: d.quantity,
      unitPrice: d.unitPrice ? Number(d.unitPrice) : null,
      expectedArrivalTime: d.expectedArrivalTime
    }))
```

- [ ] **Step 5: 认领对话框改行级表格（与「修改到货计划」共用）**

5a. 整体替换 :254-267 的认领对话框：

```html
    <!-- 认领 / 修改到货计划对话框（D61 行级） -->
    <el-dialog v-model="processVisible" :title="processForm.mode === 'process' ? '认领采购申请单' : '修改到货计划'" width="760px" :close-on-click-modal="false">
      <el-alert :title="processForm.mode === 'process'
        ? '认领后状态变为采购中；请按行填写预计到货时间（必填）与到货备注（不同物料厂家不同，到货时间可不同）。'
        : '修改后立即生效，各行预计到货时间与到货备注将更新。'"
        type="info" :closable="false" style="margin-bottom: 12px" />
      <div style="margin-bottom: 8px; display: flex; align-items: center; gap: 8px">
        <span>统一填充：</span>
        <el-date-picker v-model="applyAllDate" type="date" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择日期" style="width: 170px" />
        <el-button @click="applyDateToAll">应用到全部行</el-button>
      </div>
      <el-table :data="processForm.items" border size="small">
        <el-table-column label="商品" min-width="150">
          <template #default="{ row }">{{ row.goodsName }}</template>
        </el-table-column>
        <el-table-column label="数量" width="70">
          <template #default="{ row }">{{ row.quantity }}</template>
        </el-table-column>
        <el-table-column label="预计到货时间" width="185">
          <template #default="{ row }">
            <el-date-picker v-model="row.expectedArrivalTime" type="date" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="必选" style="width: 155px" />
          </template>
        </el-table-column>
        <el-table-column label="到货备注" min-width="150">
          <template #default="{ row }">
            <el-input v-model="row.arrivalRemark" placeholder="供应商/发货方式等（选填）" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="processVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitProcess">{{ processForm.mode === 'process' ? '确认认领' : '保存修改' }}</el-button>
      </template>
    </el-dialog>
```

5b. script 替换。删掉 :334-335 的 `const processVisible... processForm = reactive({ id: null, expectedArrivalTime: null })`，改为：

```js
const processVisible = ref(false)
const processForm = reactive({ id: null, mode: 'process', items: [] })
const applyAllDate = ref(null)

// D61：行级到货计划——认领与修改共用；mode: process=认领(待采购), update=修改(采购中)
const openArrivalPlanDialog = async (row, mode) => {
  try {
    const res = await getPurchaseRequestDetailAPI(row.id)
    if (res.code !== 200) throw new Error(res.msg || '查询明细失败')
    processForm.id = row.id
    processForm.mode = mode
    processForm.items = (res.data?.details || []).map(d => ({
      detailId: d.id,
      goodsName: d.goodsName,
      quantity: d.quantity,
      expectedArrivalTime: d.expectedArrivalTime ? String(d.expectedArrivalTime).slice(0, 19) : null,
      arrivalRemark: d.arrivalRemark || ''
    }))
    applyAllDate.value = null
    processVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '加载明细失败')
  }
}
const handleProcess = (row) => openArrivalPlanDialog(row, 'process')
const handleUpdatePlan = (row) => openArrivalPlanDialog(row, 'update')

const applyDateToAll = () => {
  if (!applyAllDate.value) return ElMessage.warning('请先选择统一填充的日期')
  processForm.items.forEach(i => { i.expectedArrivalTime = applyAllDate.value })
}

// D61：列表「预计到货」聚合——各行相同显示单日期，不同显示最早~最晚
const formatArrivalRange = (details) => {
  const dates = [...new Set((details || [])
    .map(d => d.expectedArrivalTime)
    .filter(Boolean)
    .map(t => String(t).slice(0, 10)))]
    .sort()
  if (!dates.length) return '—'
  return dates.length === 1 ? dates[0] : `${dates[0]} ~ ${dates[dates.length - 1]}`
}

const submitProcess = async () => {
  if (processForm.items.some(i => !i.expectedArrivalTime)) return ElMessage.warning('请填写全部行的预计到货时间')
  const payload = {
    items: processForm.items.map(i => ({
      detailId: i.detailId,
      expectedArrivalTime: i.expectedArrivalTime,
      arrivalRemark: i.arrivalRemark || undefined
    }))
  }
  submitting.value = true
  try {
    if (processForm.mode === 'process') {
      const res = await processPurchaseRequestAPI(processForm.id, payload)
      if (res.code !== 200) throw new Error(res.msg || '认领失败')
      ElMessage.success('已认领，状态变为采购中')
    } else {
      const res = await updateArrivalPlanAPI(processForm.id, payload)
      if (res.code !== 200) throw new Error(res.msg || '修改失败')
      ElMessage.success('到货计划已更新')
    }
    processVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    submitting.value = false
  }
}
```

同时删除旧 `handleProcess`（:470-474）与旧 `submitProcess`（:476-489），import 区补 `updateArrivalPlanAPI`：

```js
import {
  getPurchaseRequestPageAPI, getPurchaseRequestDetailAPI, getShortageGoodsAPI,
  createPurchaseRequestAPI, processPurchaseRequestAPI, updateArrivalPlanAPI, arrivePurchaseRequestAPI,
  confirmReceivePurchaseRequestAPI, arriveCancelPurchaseRequestAPI, arriveRejectPurchaseRequestAPI,
  rejectPurchaseRequestAPI, deletePurchaseRequestAPI
} from '@/api/purchaseRequest'
```

- [ ] **Step 6: 构建验证**

Run: `cd /home/niuchao/Warehouse-Management-System-/front && npm run build`
Expected: 构建成功无报错。

- [ ] **Step 7: Commit**

```bash
cd /home/niuchao/Warehouse-Management-System-
git add front/src/api/purchaseRequest.js front/src/views/business/PurchaseRequestView.vue
git commit -m "feat(view): 认领/修改到货计划行级表单+列表聚合展示+详情到货列(D61)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: 应用 DDL → 重启两端 → curl E2E → 文档记录

**Files:**
- Modify: `task_plan.md`（追加阶段 15 / D61 决策与任务清单）
- Modify: `progress.md`（顶部追加会话 18 记录）
- 数据库：本地执行 db.sql 11.x 节

**Interfaces:**
- Consumes: Task 1 的 DDL、Task 3-6 的全部改动。前序任务完成后本任务做端到端验证与文档。

- [ ] **Step 1: 应用 DDL 到本地库**

```bash
cd /home/niuchao/Warehouse-Management-System-
awk '/-- 11\.x D61/,0' db.sql > /tmp/d61.sql
mysql -u wms_user -pwms_pass warehouse_management < /tmp/d61.sql
mysql -u wms_user -pwms_pass warehouse_management -e "SHOW COLUMNS FROM biz_purchase_request_detail LIKE 'expected_arrival_time'; SHOW COLUMNS FROM biz_purchase_request LIKE 'expected_arrival_time';"
```

Expected: 第一个 SHOW 返回 `expected_arrival_time | datetime`，第二个返回空（列已删）。
注意：此步之后、后端重启前，运行中的旧后端会因主表缺列报 SQL 错误——属预期，立即执行下一步。

- [ ] **Step 2: 重启两端**

```bash
fuser -k 8080/tcp; fuser -k 5173/tcp; sleep 3
ss -tln | grep -E ':(8080|5173)' || echo "ports clean"
```

（若 8080 仍被占：`ss -tlnp | grep :8080` 找残留 java pid，`kill -9 <pid>`。）

```bash
cd /home/niuchao/Warehouse-Management-System-/back && nohup ./mvnw spring-boot:run > /tmp/wms-backend.log 2>&1 &
cd /home/niuchao/Warehouse-Management-System-/front && nohup npm run dev > /tmp/wms-frontend.log 2>&1 &
```

轮询就绪（最多 240s，`ss -tln | grep :8080`），并确认日志无 `APPLICATION FAILED TO START` / `BUILD FAILURE`。

- [ ] **Step 3: curl E2E（认领行级化全链路）**

```bash
BASE=http://localhost:8080/api
# 1) 登录取 token
WT=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d '{"username":"warehouse_admin","password":"123456"}' | grep -oE '"token":"[^"]+' | cut -d'"' -f4)
PT=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d '{"username":"purchase_admin","password":"123456"}' | grep -oE '"token":"[^"]+' | cut -d'"' -f4)
# 2) 取两个真实商品 id 建单（仓储）
GIDS=$(mysql -u wms_user -pwms_pass warehouse_management -N -e "SELECT id FROM base_goods WHERE status=1 AND type='material' LIMIT 2" | tr '\n' ' ')
G1=$(echo $GIDS | cut -d' ' -f1); G2=$(echo $GIDS | cut -d' ' -f2)
curl -s -X POST $BASE/business/purchase-requests -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
  -d "{\"remark\":\"D61-E2E\",\"details\":[{\"goodsId\":$G1,\"quantity\":5},{\"goodsId\":$G2,\"quantity\":2}]}"
# 3) 取最新待采购单 id + 明细 id
RID=$(curl -s "$BASE/business/purchase-requests/page?pageNum=1&pageSize=1&status=1" -H "Authorization: Bearer $PT" | grep -oE '"id":[0-9]+' | head -1 | cut -d: -f2)
DIDS=$(curl -s "$BASE/business/purchase-requests/$RID" -H "Authorization: Bearer $PT" | grep -oE '"details":\[.*' | grep -oE '"id":[0-9]+' | cut -d: -f2)
D1=$(echo $DIDS | head -1); D2=$(echo $DIDS | tail -1)
# 4) 认领（行级，两行不同时间+备注）
curl -s -X PUT "$BASE/business/purchase-requests/$RID/process" -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"detailId\":$D1,\"expectedArrivalTime\":\"2026-09-15T00:00:00\",\"arrivalRemark\":\"厂家A直发\"},{\"detailId\":$D2,\"expectedArrivalTime\":\"2026-09-20T00:00:00\"}]}"
# 5) 断言详情行级字段
curl -s "$BASE/business/purchase-requests/$RID" -H "Authorization: Bearer $PT" | grep -c 'arrivalRemark\|expectedArrivalTime'
# 6) 修改到货计划（改一行）
curl -s -X PUT "$BASE/business/purchase-requests/$RID/arrival-plan" -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"detailId\":$D1,\"expectedArrivalTime\":\"2026-09-25T00:00:00\",\"arrivalRemark\":\"改发厂家B\"},{\"detailId\":$D2,\"expectedArrivalTime\":\"2026-09-20T00:00:00\"}]}"
# 7) 负测a：未知 detailId → 期望 code!=200 且 msg 含"缺少预计到货时间"
curl -s -X PUT "$BASE/business/purchase-requests/$RID/arrival-plan" -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"detailId\":99999,\"expectedArrivalTime\":\"2026-09-25T00:00:00\"}]}"
# 8) 负测b：仓储权限调修改 → 期望 code!=200（仅采购管理员）
curl -s -X PUT "$BASE/business/purchase-requests/$RID/arrival-plan" -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"detailId\":$D1,\"expectedArrivalTime\":\"2026-09-25T00:00:00\"}]}"
# 9) 到货提交 → 待入库确认
curl -s -X PUT "$BASE/business/purchase-requests/$RID/arrive" -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"detailId\":$D1,\"quantity\":5,\"unitPrice\":1.5},{\"detailId\":$D2,\"quantity\":2,\"unitPrice\":3}]}"
# 10) 负测c：待入库确认状态修改 → 期望 "仅采购中状态可修改到货计划"
curl -s -X PUT "$BASE/business/purchase-requests/$RID/arrival-plan" -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"detailId\":$D1,\"expectedArrivalTime\":\"2026-09-25T00:00:00\"}]}"
# 11) 撤回到货（回采购中；**不确认入库**，避免动库存）
curl -s -X PUT "$BASE/business/purchase-requests/$RID/arrive-cancel" -H "Authorization: Bearer $PT"
# 12) 消息验证：仓储管理员应收到「采购申请单已认领」（来源申请人=仓储）
curl -s "$BASE/system/messages/page?pageNum=1&pageSize=5" -H "Authorization: Bearer $WT" | grep -o '采购申请单已认领' | head -1
```

Expected: 步骤 4/6 返回 `code:200`；步骤 5 输出 ≥2（两行各有新字段）；步骤 7/8/10 的 msg 分别含 `缺少预计到货时间`、权限拒绝、`仅采购中状态可修改到货计划`；步骤 12 输出 `采购申请单已认领`。

- [ ] **Step 4: 清理测试数据（恢复原状，不动库存）**

```bash
mysql -u wms_user -pwms_pass warehouse_management -e "UPDATE biz_purchase_request SET is_deleted=1 WHERE id=$RID; UPDATE biz_purchase_request_detail SET is_deleted=1 WHERE request_id=$RID;"
# 撤回认领通知未读消息（biz 绑定回收；sys_message 为软删结构，列名以 SHOW COLUMNS 实际为准）
mysql -u wms_user -pwms_pass warehouse_management -e "UPDATE sys_message SET is_deleted=1 WHERE biz_type='purchase_request' AND biz_id=$RID;"
```

Expected: 列表页该单消失；库存无任何变化（全程未 confirmReceive）。

- [ ] **Step 5: 后端全量测试回归**

Run: `cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -q`
Expected: BUILD SUCCESS，0 Failures。

- [ ] **Step 6: 文档记录**

6a. `task_plan.md` 末尾追加阶段 15 小节（沿用阶段 14 的格式：决策 + 任务清单 + 详见），核心决策行：

```markdown
### 阶段 15：预计到货时间行级化（D61，2026-09-08 会话 18）

**决策（D61）：** ① 采购申请明细行各自带「预计到货时间」（认领必填）+「到货备注」（选填，与 BOM 物料描述快照 remark 独立）；② 范围=所有采购申请单（不分来源）；③ 主表 expected_arrival_time 废除，存量回填明细行后删列；④ 采购中（status=2）可经 PUT /arrival-plan 修改，待入库确认起锁定；⑤ 认领时向来源申请人（production→生产部/warehouse→仓储部）发行级到货摘要（D21 带 biz）；⑥ 列表聚合展示（相同单日期/不同最早~最晚），认领对话框行级表格+统一填充。CONTEXT.md「认领/到货备注」词条已同步。

- [x] B1 db.sql 11.x DDL（明细加列+回填+主表删列）
- [x] B2 明细实体/VO/DTO 行级化 + process 重写 + 主表字段清理（17 单测）
- [x] B3 认领通知来源申请人（D21）
- [x] B4 PUT /arrival-plan（仅采购中）
- [x] F1 前端行级认领/修改对话框（统一填充）+ 列表聚合 + 详情/到货列
- [x] E2E 认领→修改→负测×3→消息验证→清理
```

6b. `progress.md` 顶部按既有格式追加会话 18 条目（概述：D61 行级化落地 + 测试基线 + 遗留：无）。

- [ ] **Step 7: 最终提交**

```bash
cd /home/niuchao/Warehouse-Management-System-
git add task_plan.md progress.md
git commit -m "docs(plan): 阶段15 D61 预计到货时间行级化落地记录

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

推送留在会话收尾由用户经 VS Code 源代码管理面板完成（CLAUDE.md 教训 3/4）。

---

## Self-Review 记录

1. **Spec 覆盖**：共享理解 8 条决策 → 决策1(范围)→Task 3/6 全单据统一；决策2(字段)→Task 1/2；决策3(认领行级填)→Task 3/6；决策4(可改)→Task 5；决策5(主表删除+回填)→Task 1/3；决策6(列表聚合+统一填充)→Task 6；决策7(必填)→DTO 注解+toValidatedItemMap+前端校验；决策8(认领通知)→Task 4。无遗漏。
2. **占位符扫描**：所有步骤含真实代码/命令/期望输出；无 TBD/“适当处理”。
3. **类型一致性**：`PurchaseRequestProcessDTO.items[].{detailId,expectedArrivalTime,arrivalRemark}` 在 Task 3（DTO）、Task 5（复用）、Task 6（前端 payload）三处一致；`sendPurchaseRequestClaimedToSourceApplicant(String,String,String,String,Long)` 定义（Task 4）与调用（Task 3 4a）签名一致；实体/VO 字段名 `expectedArrivalTime`/`arrivalRemark` 前后端一致。
