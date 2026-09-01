# 生产缺料 → 采购申请联动 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 生产任务单缺料时，生产端一键生成采购申请草稿 → 仓储转正（补物料）→ 走现有采购链 → 确认入库时回挂 BOM 明细 → 齐套可开工。

**Architecture:** 复用 `biz_purchase_request`/`biz_purchase_request_detail`，新增草稿状态 `STATUS_DRAFT=6` 与来源/溯源列。`PurchaseRequestService` 增 `createDraft`/`getDraftByProductionOrder`/`confirmDraft`/`rejectDraft`/`cancelDraft`，并扩展现有 `confirmReceive` 在库存落账事务内回挂 BOM 明细 `goods_id`。`ProductionOrderService` 暴露 `computeShortageForOrder` 复用私有 `computeKit`。仓储转正后完全走现有采购链，采购/到货/入库逻辑零改动。

**Tech Stack:** Spring Boot / MyBatis-Plus / H2+Mockito (test)、Vue3 / Element-Plus / axios。

## Global Constraints

- 依赖下限与现有约定见 `CLAUDE.md`：改代码后 `./mvnw compile`（增量编译若遇 `Unresolved compilation problem` 用 `./mvnw clean compile`）；前端 `npm run build`。
- 跨 commit git 操作前先停 dev 服务器（`fuser -k 8080/tcp`、`fuser -k 5173/tcp`）；重启后浏览器硬刷新 + 重新登录。
- 现有 `_page`/`getById` 的读权限（仓储+采购）与写权限（`requireXxx`）常分开，改一处勿漏另一处；新加生产草稿入口需独立生产权限。
- 明细行 `goods_id` 在现有 `create()`（仓储手动建单）路径**仍必填**——只放宽草稿路径，不放松正式单校验。
- 每步 commit；消息生命周期用带 biz 的 `sendToDeptAdminsWithBiz`（或现有包装方法），终态 `revokeUnreadByBiz("purchase_request", id)`。
- spec（本计划的唯一需求来源）：`docs/superpowers/specs/2026-09-01-production-shortage-purchase-design.md`。

---

## 文件结构映射

| 职责 | 文件 |
|---|---|
| 状态/来源常量 + 草稿 CRUD 与转正/回挂 | `back/.../service/PurchaseRequestService.java` |
| 暴露缺料草稿需要的缺口行（复用 computeKit） | `back/.../service/ProductionOrderService.java` |
| 草稿通知接线 | `back/.../service/MessageService.java` |
| 草稿 REST 端点 | `back/.../controller/PurchaseRequestController.java` |
| 新 DTO（建草稿/转正/草稿驳回） | `back/.../dto/ProductionDraftCreateDTO.java` 等 |
| 实体加列 | `back/.../entity/BizPurchaseRequest.java` `BizPurchaseRequestDetail.java` |
| 齐套行带 bomDetailId | `back/.../vo/KitShortageVO.java` `PurchaseRequestDetailVO.java` |
| 前端 API | `front/src/api/purchaseRequest.js` |
| 生产端补料 UI | `front/src/views/business/ProductionOrderView.vue` |
| 仓储端草稿 UI | `front/src/views/business/PurchaseRequestView.vue` |
| DDL | `db.sql` |

---

### Task 1: 数据库迁移（草稿状态 + 来源/溯源列）

**Files:**
- Modify: `db.sql`（末尾按编号段追加 `ALTER TABLE`）

**Interfaces:**
- 产生：`biz_purchase_request` 增 `source_type`、`production_order_id`；`biz_purchase_request_detail` 的 `goods_id` 改可空、增 `bom_detail_id`。

- [ ] **Step 1: 在 `db.sql` 末尾追加迁移**

在 `db.sql` 最末尾追加（沿用 `-- 8.x` 编号段风格）：
```sql
-- 8.x 生产缺料→采购申请草稿：状态6 + 来源 + 来源生产任务单
ALTER TABLE `biz_purchase_request`
    MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-待采购, 2-采购中, 3-已入库, 4-已驳回, 5-待入库确认, 6-草稿(生产补料待仓储转正)',
    ADD COLUMN `source_type` VARCHAR(20) DEFAULT 'warehouse' COMMENT '来源: production-生产缺料补料, warehouse-仓储手动' AFTER `status`,
    ADD COLUMN `production_order_id` BIGINT DEFAULT NULL COMMENT '来源生产任务单id(仅production来源有值)' AFTER `source_type`,
    ADD KEY `idx_pr_source` (`source_type`),
    ADD KEY `idx_pr_production_order` (`production_order_id`);

ALTER TABLE `biz_purchase_request_detail`
    MODIFY COLUMN `goods_id` BIGINT DEFAULT NULL COMMENT '物料id(草稿可空, 转正时仓储补齐)',
    ADD COLUMN `bom_detail_id` BIGINT DEFAULT NULL COMMENT '对应BOM明细id(确认入库时回挂goods_id)' AFTER `goods_id`;
```

- [ ] **Step 2: 应用到本地库**

本地执行（需先有 esp32 正在跑的 dev 库；若表结构已被上面 ALTER 改动则忽略报错并确认生效）：
```bash
cd /home/niuchao/Warehouse-Management-System-
mysql -u wms_user -pwms_pass warehouse_management < db.sql
```
用 `DESCRIBE biz_purchase_request;` 确认 `source_type`/`production_order_id` 存在，`biz_purchase_request_detail` 中 `goods_id` 为 nullable、`bom_detail_id` 存在。

- [ ] **Step 3: Commit**

```bash
git add db.sql && git commit -m "feat(db): 采购申请草稿状态与来源/溯源/回挂列"
```

---

### Task 2: 实体与 VO 加列（使列对 MyBatis-Plus 可用）

**Files:**
- Modify: `back/src/main/java/org/example/back/entity/BizPurchaseRequest.java`
- Modify: `back/src/main/java/org/example/back/entity/BizPurchaseRequestDetail.java`
- Modify: `back/src/main/java/org/example/back/vo/KitShortageVO.java`
- Modify: `back/src/main/java/org/example/back/vo/PurchaseRequestDetailVO.java`

**Interfaces:**
- 产生：`BizPurchaseRequest.getSourceType()/getProductionOrderId()`；`BizPurchaseRequestDetail.getBomDetailId()`；`KitShortageVO.getBomDetailId()/setBomDetailId(...)`；`PurchaseRequestDetailVO.getBomDetailId()`。

- [ ] **Step 1: 实体 `BizPurchaseRequest` 加字段**

在 `private Integer status;` 之后插入：
```java
    /** 来源: production-生产缺料补料, warehouse-仓储手动 */
    private String sourceType;

    /** 来源生产任务单id(仅production来源有值) */
    private Long productionOrderId;
```

- [ ] **Step 2: 实体 `BizPurchaseRequestDetail` 加字段**

在 `private Long goodsId;` 之后插入：
```java
    /** 对应BOM明细id(确认入库时回挂goods_id) */
    private Long bomDetailId;
```

- [ ] **Step 3: `KitShortageVO` 加字段**

在 `private Long goodsId;` 之后插入：
```java
    /** 对应BOM明细id(方案先行回挂用) */
    private Long bomDetailId;
```

- [ ] **Step 4: `PurchaseRequestDetailVO` 加字段**

在 `private Long goodsId;` 之后插入：
```java
    /** 对应BOM明细id */
    private Long bomDetailId;
```

- [ ] **Step 5: 编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q compile
```
期望：BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: 采购申请草稿/回挂实体与VO加列"
```

---

### Task 3: 齐套行携带 bomDetailId + 暴露缺料口（生产端补料数据源）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/ProductionOrderService.java`
- Test: `back/src/test/java/org/example/back/service/ProductionOrderServiceTest.java`（新建）

**Interfaces:**
- 产生：`public List<KitShortageVO> ProductionOrderService.computeShortageForOrder(Long productionOrderId)` —— 返回该任务单 `deficit>0` 的缺料行（含 `bomDetailId`）。

- [ ] **Step 1: 写失败测试**

新建 `ProductionOrderServiceTest.java``，验证 `computeShortageForOrder` 只返回缺口行且带 bomDetailId：
```java
package org.example.back.service;

import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.entity.BaseGoods;
import org.example.back.vo.KitShortageVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionOrderServiceTest {

    @Mock private BizProductionOrderMapper orderMapper;
    @Mock private BizBomMapper bomMapper;
    @Mock private BizBomDetailMapper bomDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;

    @InjectMocks private ProductionOrderService service;

    @Test
    void computeShortageForOrder_returnsOnlyDeficitLinesWithBomDetailId() {
        // 待生产订单，成品 id=29，数量 2
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BizBom bom = new BizBom();
        bom.setId(1L);
        when(bomMapper.selectOne(any())).thenReturn(bom);

        // 两行 BOM：螺丝需2×3=6 vs 库存10(够)，板1需2×2=4 vs 库存1(缺3)
        BizBomDetail screw = new BizBomDetail();
        screw.setId(11L); screw.setBomId(1L); screw.setGoodsId(50L);
        screw.setComponentName("螺丝"); screw.setIsReference(0);
        screw.setQuantity(BigDecimal.valueOf(3));
        BizBomDetail board = new BizBomDetail();
        board.setId(12L); board.setBomId(1L); board.setGoodsId(51L); board.setIsReference(0);
        board.setComponentName("板1"); board.setQuantity(BigDecimal.valueOf(2));
        when(bomDetailMapper.selectList(any())).thenReturn(List.of(screw, board));

        BaseGoods g50 = new BaseGoods(); g50.setId(50L); g50.setGoodsName("螺丝"); g50.setStock(10);
        BaseGoods g51 = new BaseGoods(); g51.setId(51L); g51.setGoodsName("板1"); g51.setStock(1);
        when(baseGoodsMapper.selectById(50L)).thenReturn(g50);
        when(baseGoodsMapper.selectById(51L)).thenReturn(g51);

        List<KitShortageVO> shortage = service.computeShortageForOrder(7L);

        // 只剩缺口行（板1），且带 bomDetailId
        assertEquals(1, shortage.size());
        assertEquals("板1", shortage.get(0).getGoodsName());
        assertEquals(12L, shortage.get(0).getBomDetailId());
        assertEquals(3, shortage.get(0).getDeficit().intValue());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=ProductionOrderServiceTest test
```
期望：FAIL（`computeShortageForOrder` 不存在）。

- [ ] **Step 3: `computeKit` 补 set bomDetailId + 新增 `computeShortageForOrder`**

在 `computeKit` 循环内、`KitShortageVO line = new KitShortageVO();` 之后加一行：
```java
            line.setBomDetailId(d.getId());
```
在 `generatePickListAndIssue` 方法之后新增公开方法（复用私有 `computeKit`）：
```java
    /**
     * 供采购申请草稿：返回该任务单存在缺口(deficit>0)的物料行，含 bomDetailId 供回挂定位。
     */
    public List<KitShortageVO> computeShortageForOrder(Long productionOrderId) {
        BizProductionOrder order = requireOrder(productionOrderId);
        KuaiTaoResult kit = computeKit(order.getGoodsId(), order.getQuantity());
        return kit.lines.stream()
                .filter(l -> l.getDeficit() != null && l.getDeficit().intValue() > 0)
                .toList();
    }
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=ProductionOrderServiceTest test
```
期望：BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: 齐套行携带bomDetailId并暴露生产缺料口"
```

---

### Task 4: 消息接线（草稿 → 仓储；移除建单直叮采购）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/MessageService.java`
- Modify: `back/src/main/java/org/example/back/service/ProductionOrderService.java`

**Interfaces:**
- 产生：`MessageService.sendPurchaseRequestDraftToWarehouseAdmins(String requestNo, String applicantName, Long requestId)`。

- [ ] **Step 1: 新增草稿待转正消息方法**

在 `sendPurchaseRequestToPurchaseAdmins` 之后新增：
```java
    /**
     * 生产缺料补料草稿生成后通知仓储管理员转正。
     */
    public void sendPurchaseRequestDraftToWarehouseAdmins(String requestNo, String applicantName, Long requestId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        String applicant = StringUtils.hasText(applicantName) ? applicantName : "生产管理员";
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待转正补料草稿",
                String.format(
                        Locale.ROOT,
                        "缺料补料草稿 %s 由 %s 生成，请补充物料并转正。",
                        requestNo, applicant
                ),
                "purchase_request",
                requestId
        );
    }
```

- [ ] **Step 2: `ProductionOrderService.create()` 移除直叮采购**

把 `create(...)` 中这段删掉（含 `if (kit.hasShortage) { ... }` 整个块）：
```java
        // 有缺口即通知采购管理员补料（D42）
        if (kit.hasShortage) {
            messageService.sendKitShortageToPurchaseAdmins(
                    saved.getOrderNo(),
                    saved.getGoodsName(),
                    kit.summary(saved.getQuantity()),
                    saved.getId()
            );
        }
```
同时删掉无用的 `kit` 局部变量？——`kit` 仍被 `order.setKitStatus(...)` 与 `vo.setKitLines(kit.lines)` 使用，**保留**。`saved` 仍被 `toVO(saved)` 用，保留。仅移除消息调用块。

（若 `sendKitShortageToPurchaseAdmins` 从此无调用方，可保留方法体不删——避免牵连其它引用；用 `grep` 确认无其它调用后也可连方法一起删，但非必需。）

- [ ] **Step 3: 编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q compile
```
期望：BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: 草稿待转正通知仓储；生产建单不再直叮采购"
```

---

### Task 5: createDraft（生产一键生成采购申请草稿）+ getDraftByProductionOrder

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`
- Create: `back/src/main/java/org/example/back/dto/ProductionDraftCreateDTO.java`
- Create: `back/src/main/java/org/example/back/dto/ProductionDraftItemDTO.java`
- Modify: `back/src/main/java/org/example/back/controller/PurchaseRequestController.java`
- Test: `back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java`（新建）

**Interfaces:**
- Consumes: `ProductionOrderService.computeShortageForOrder(Long)`；`KitShortageVO.getBomDetailId()/getGoodsId()/getGoodsName()/getDeficit()`。
- 产生：
```java
public static final int STATUS_DRAFT = 6;
public static final String SOURCE_PRODUCTION = "production";
public static final String SOURCE_WAREHOUSE = "warehouse";
public Long createDraft(ProductionDraftCreateDTO dto);   // 返回草稿 id
public PurchaseRequestVO getDraftByProductionOrder(Long productionOrderId);
```

- [ ] **Step 1: 新增常量与注入**

`PurchaseRequestService` 顶部常量区（`STATUS_AWAITING_CONFIRM = 5` 后）加：
```java
    public static final int STATUS_DRAFT = 6;    // 草稿(生产补料待仓储转正)

    public static final String SOURCE_PRODUCTION = "production";
    public static final String SOURCE_WAREHOUSE = "warehouse";
```
`@Autowired` 区新增：
```java
    @Autowired
    private ProductionOrderService productionOrderService;
```

- [ ] **Step 2: DTO**

`ProductionDraftItemDTO.java`：
```java
package org.example.back.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ProductionDraftItemDTO {
    /** 对应BOM明细id（生产缺料行来源） */
    private Long bomDetailId;

    /** 申请数量覆盖值（不传则用缺口）；>0 才生效 */
    @Min(value = 1, message = "申请数量必须大于0")
    private Integer quantity;
}
```
`ProductionDraftCreateDTO.java`：
```java
package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ProductionDraftCreateDTO {
    @NotNull(message = "生产任务单不能为空")
    private Long productionOrderId;

    private String remark;

    @Valid
    private List<ProductionDraftItemDTO> details;   // 可选：按 bomDetailId 覆盖申请数量
}
```

- [ ] **Step 3: 写失败测试**

`PurchaseRequestServiceTest.java`（新建，公共需 mock 的成员照 `ApprovalServiceTest` 风格；`BizBomDetailMapper` 第 Task 8 才用到，现在先 mock 以备后续扩展）：
```java
package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionDraftCreateDTO;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.KitShortageVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseRequestServiceTest {

    @Mock private BizPurchaseRequestMapper bizPurchaseRequestMapper;
    @Mock private BizPurchaseRequestDetailMapper bizPurchaseRequestDetailMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;
    @Mock private PurchaseService purchaseService;
    @Mock private ProductionOrderService productionOrderService;

    @InjectMocks private PurchaseRequestService service;

    @Test
    void createDraft_setsDraftStatusAndSendsWarehouseNotice() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        // 无既有草稿（幂等前置通过）
        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(51L);
        line.setGoodsName("板1");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        Long draftId = service.createDraft(dto);

        // 主单应为草稿状态 + 生产来源
        org.mockito.ArgumentCaptor<BizPurchaseRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BizPurchaseRequest.class);
        org.mockito.Mockito.verify(bizPurchaseRequestMapper).insert(captor.capture());
        BizPurchaseRequest draft = captor.getValue();
        assertEquals(6, draft.getStatus());
        assertEquals("production", draft.getSourceType());
        assertEquals(7L, draft.getProductionOrderId());
        assertEquals(user.getId(), draft.getApplicantId());
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=PurchaseRequestServiceTest test
```
期望：FAIL（`createDraft` 不存在）。

- [ ] **Step 5: 实现 createDraft + getDraftByProductionOrder + 权限助手**

先补 import（在文件顶部 import 区加，缺的才加）：
```java
import org.example.back.dto.ProductionDraftItemDTO;
import org.example.back.vo.KitShortageVO;
```
（`java.util.Map`、`java.util.stream.Collectors` PRS 已导入；`java.math.RoundingMode` 用全限定名如无导入。）

在 `create(...)`（仓储手动建单）之前插入新方法（放"建单"区）：
```java
    // ============================== 生产缺料草稿 ==============================

    /**
     * 生产一键补料：从生产任务单缺料行生成采购申请草稿(DRAFT)。幂等——同一任务单只允许一张草稿。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createDraft(ProductionDraftCreateDTO dto) {
        requireProductionDraftAccess();
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        // 幂等：同一生产任务单已有草稿则拒绝，除非已流转
        List<BizPurchaseRequest> existing = listDraftByProductionOrder(dto.getProductionOrderId());
        if (!existing.isEmpty()) {
            throw BusinessException.validateFail("该生产任务单已生成补料草稿，请先转正或驳回");
        }

        List<KitShortageVO> shortage = productionOrderService.computeShortageForOrder(dto.getProductionOrderId());
        if (shortage.isEmpty()) {
            throw BusinessException.validateFail("该生产任务单当前无缺料，无需补料");
        }
        Map<Long, Integer> override = dto.getDetails() == null ? Map.of()
                : dto.getDetails().stream()
                        .filter(i -> i.getBomDetailId() != null && i.getQuantity() != null && i.getQuantity() > 0)
                        .collect(Collectors.toMap(ProductionDraftItemDTO::getBomDetailId, ProductionDraftItemDTO::getQuantity));

        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setRequestNo(CodeGenerator.purchaseRequestNo());
        draft.setStatus(STATUS_DRAFT);
        draft.setSourceType(SOURCE_PRODUCTION);
        draft.setProductionOrderId(dto.getProductionOrderId());
        draft.setApplicantId(loginUser.getId());
        draft.setApplicantName(loginUser.getRealName());
        draft.setRemark(dto.getRemark());
        bizPurchaseRequestMapper.insert(draft);

        int sortNo = 0;
        for (KitShortageVO line : shortage) {
            Integer qty = override.getOrDefault(line.getBomDetailId(), ceilDeficit(line.getDeficit()));
            if (qty == null || qty <= 0) {
                continue;
            }
            BizPurchaseRequestDetail det = new BizPurchaseRequestDetail();
            det.setRequestId(draft.getId());
            det.setGoodsId(line.getGoodsId());
            det.setGoodsName(line.getGoodsName());
            det.setQuantity(qty);
            det.setBomDetailId(line.getBomDetailId());
            det.setSortNo(sortNo++);
            bizPurchaseRequestDetailMapper.insert(det);
        }
        if (sortNo == 0) {
            throw BusinessException.validateFail("无有效缺料行可补料");
        }

        messageService.sendPurchaseRequestDraftToWarehouseAdmins(draft.getRequestNo(), loginUser.getRealName(), draft.getId());
        return draft.getId();
    }

    /**
     * 生产端查看某生产任务单的补料草稿（供 UI 判断是否已生成/撤销）。
     */
    public PurchaseRequestVO getDraftByProductionOrder(Long productionOrderId) {
        requireProductionDraftAccess();
        List<BizPurchaseRequest> drafts = listDraftByProductionOrder(productionOrderId);
        return drafts.isEmpty() ? null : toVO(drafts.get(0));
    }

    private static int ceilDeficit(BigDecimal deficit) {
        return deficit.setScale(0, java.math.RoundingMode.UP).intValue();
    }

    private List<BizPurchaseRequest> listDraftByProductionOrder(Long productionOrderId) {
        LambdaQueryWrapper<BizPurchaseRequest> w = new LambdaQueryWrapper<>();
        w.eq(BizPurchaseRequest::getProductionOrderId, productionOrderId)
                .eq(BizPurchaseRequest::getSourceType, SOURCE_PRODUCTION)
                .eq(BizPurchaseRequest::getStatus, STATUS_DRAFT);
        return bizPurchaseRequestMapper.selectList(w);
    }
```
将 `toDetailVO` 补上 `bomDetailId`（Task 2 已加 VO 字段）：
```java
        vo.setBomDetailId(detail.getBomDetailId());
```
在私有权限区新增：
```java
    private void requireProductionDraftAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_PRODUCTION, "仅生产研发部管理员可生成/管理补料草稿");
    }
```

- [ ] **Step 6: Controller 端点**

`PurchaseRequestController.java` 的 `shortage-goods` 之后、`create` 之前新增：
```java
    /**
     * 生产缺料补料草稿生成（生产研发部）。
     */
    @PostMapping("/draft")
    @RequireAdmin("仅生产研发部管理员可生成补料草稿")
    @AuditLog(module = "采购申请", action = "生成补料草稿", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1800, message = "请勿重复提交补料草稿")
    public Result<Long> createDraft(@Valid @RequestBody ProductionDraftCreateDTO dto) {
        return Result.success(purchaseRequestService.createDraft(dto));
    }

    @GetMapping("/draft/{productionOrderId}")
    public Result<PurchaseRequestVO> getDraftByProductionOrder(@PathVariable Long productionOrderId) {
        return Result.success(purchaseRequestService.getDraftByProductionOrder(productionOrderId));
    }
```
补 import：`import org.example.back.dto.ProductionDraftCreateDTO;`。

- [ ] **Step 7: 运行测试，确认通过**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=PurchaseRequestServiceTest test
```
期望：BUILD SUCCESS。

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: 生产一键补料生成采购申请草稿 + 查询端点"
```

---

### Task 6: confirmDraft（仓储转正：补物料 → PENDING）+ rejectDraft（仓储驳回）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`
- Create: `back/src/main/java/org/example/back/dto/DraftConfirmDTO.java`
- Create: `back/src/main/java/org/example/back/dto/DraftConfirmItemDTO.java`
- Create: `back/src/main/java/org/example/back/dto/DraftRejectDTO.java`
- Modify: `back/src/main/java/org/example/back/controller/PurchaseRequestController.java`
- Modify: `back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java`

**Interfaces:**
- Consumes: `STATUS_DRAFT`、`listDetails(Long)`。
- 产生：`public void confirmDraft(Long id, DraftConfirmDTO dto);` `public void rejectDraft(Long id, DraftRejectDTO dto);`

- [ ] **Step 1: DTO**

`DraftConfirmItemDTO.java`：
```java
package org.example.back.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DraftConfirmItemDTO {
    @NotNull(message = "明细不能为空")
    private Long detailId;

    @NotNull(message = "请选择物料")
    private Long goodsId;
}
```
`DraftConfirmDTO.java`：
```java
package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class DraftConfirmDTO {
    @NotNull(message = "转正明细不能为空")
    @Size(min = 1, message = "至少选择一条物料待转正")
    @Valid
    private List<DraftConfirmItemDTO> items;
}
```
`DraftRejectDTO.java`：
```java
package org.example.back.dto;

import lombok.Data;

@Data
public class DraftRejectDTO {
    private String reason;
}
```

- [ ] **Step 2: 写失败测试（追加到 PurchaseRequestServiceTest）**

```java
    @Test
    void confirmDraft_rejectsWhenAGoodsIdIsMissing() {
        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setId(3L);
        draft.setStatus(6); // DRAFT
        when(bizPurchaseRequestMapper.selectById(3L)).thenReturn(draft);

        // 两行，仅给其中一行传了物料
        org.example.back.entity.BizPurchaseRequestDetail d1 = new org.example.back.entity.BizPurchaseRequestDetail();
        d1.setId(100L); d1.setRequestId(3L); d1.setGoodsId(null); d1.setGoodsName("板1");
        org.example.back.entity.BizPurchaseRequestDetail d2 = new org.example.back.entity.BizPurchaseRequestDetail();
        d2.setId(101L); d2.setRequestId(3L); d2.setGoodsId(null); d2.setGoodsName("螺丝");
        when(bizPurchaseRequestDetailMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(List.of(d1, d2));

        DraftConfirmDTO dto = new DraftConfirmDTO();
        DraftConfirmItemDTO item = new DraftConfirmItemDTO();
        item.setDetailId(100L);
        item.setGoodsId(66L);
        dto.setItems(List.of(item));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirmDraft(3L, dto));
        assertEquals("明细[螺丝]未关联物料，无法转正", ex.getMsg());
    }
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=PurchaseRequestServiceTest test
```
期望：FAIL（`confirmDraft` 不存在）。

- [ ] **Step 4: 实现 confirmDraft + rejectDraft**

在 `createDraft` 相关方法之后新增：
```java
    /**
     * 仓储转正：给草稿中 goods_id 为空的行补物料，全部齐备后置 PENDING 进入采购链。此步不回挂。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmDraft(Long id, DraftConfirmDTO dto) {
        requireWarehouseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_DRAFT) {
            throw BusinessException.validateFail("仅草稿状态可转正");
        }

        Map<Long, Long> assign = dto.getItems().stream()
                .collect(Collectors.toMap(DraftConfirmItemDTO::getDetailId, DraftConfirmItemDTO::getGoodsId,
                        (a, b) -> b));
        List<BizPurchaseRequestDetail> details = listDetails(id);
        if (details.isEmpty()) {
            throw BusinessException.validateFail("草稿明细为空，无法转正");
        }

        for (BizPurchaseRequestDetail detail : details) {
            Long newGoodsId = assign.get(detail.getId());
            if (newGoodsId == null && detail.getGoodsId() == null) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName() + "]未关联物料，无法转正");
            }
            if (newGoodsId != null && !newGoodsId.equals(detail.getGoodsId())) {
                LambdaUpdateWrapper<BizPurchaseRequestDetail> dw = new LambdaUpdateWrapper<>();
                dw.eq(BizPurchaseRequestDetail::getId, detail.getId())
                        .set(BizPurchaseRequestDetail::getGoodsId, newGoodsId);
                bizPurchaseRequestDetailMapper.update(null, dw);
            }
        }

        LambdaUpdateWrapper<BizPurchaseRequest> uw = new LambdaUpdateWrapper<>();
        uw.eq(BizPurchaseRequest::getId, id)
                .eq(BizPurchaseRequest::getStatus, STATUS_DRAFT)
                .set(BizPurchaseRequest::getStatus, STATUS_PENDING);
        int rows = bizPurchaseRequestMapper.update(null, uw);
        if (rows != 1) {
            throw BusinessException.validateFail("草稿状态已变更，请刷新后重试");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
        messageService.sendPurchaseRequestToPurchaseAdmins(entity.getRequestNo(), entity.getApplicantName(), id);
    }

    /**
     * 仓储驳回草稿：置 rejected 保留审计。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rejectDraft(Long id, DraftRejectDTO dto) {
        requireWarehouseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_DRAFT) {
            throw BusinessException.validateFail("仅草稿状态可驳回");
        }
        LambdaUpdateWrapper<BizPurchaseRequest> uw = new LambdaUpdateWrapper<>();
        uw.eq(BizPurchaseRequest::getId, id)
                .eq(BizPurchaseRequest::getStatus, STATUS_DRAFT)
                .set(BizPurchaseRequest::getStatus, STATUS_REJECTED)
                .set(BizPurchaseRequest::getRejectReason,
                        StringUtils.hasText(dto == null ? null : dto.getReason()) ? dto.getReason() : "仓储驳回草稿");
        int rows = bizPurchaseRequestMapper.update(null, uw);
        if (rows != 1) {
            throw BusinessException.validateFail("草稿状态已变更，请刷新后重试");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
    }
```

- [ ] **Step 5: Controller 端点**

在 `createDraft` 端点之后新增：
```java
    @PutMapping("/{id}/confirm-draft")
    @RequireAdmin("仅仓储管理员可转正补料草稿")
    @AuditLog(module = "采购申请", action = "转正草稿", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交转正请求")
    public Result<Void> confirmDraft(@PathVariable Long id, @Valid @RequestBody DraftConfirmDTO dto) {
        purchaseRequestService.confirmDraft(id, dto);
        return Result.success();
    }

    @PutMapping("/{id}/reject-draft")
    @RequireAdmin("仅仓储管理员可驳回补料草稿")
    @AuditLog(module = "采购申请", action = "驳回草稿", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交驳回请求")
    public Result<Void> rejectDraft(@PathVariable Long id, @RequestBody DraftRejectDTO dto) {
        purchaseRequestService.rejectDraft(id, dto);
        return Result.success();
    }
```
补 import：`DraftConfirmDTO`、`DraftRejectDTO`。

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=PurchaseRequestServiceTest test
```
期望：BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: 仓储转正补料草稿(补物料→待采购) + 驳回"
```

---

### Task 7: cancelDraft（生产申请人撤销自家草稿）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`
- Modify: `back/src/main/java/org/example/back/controller/PurchaseRequestController.java`
- Modify: `back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java`

**Interfaces:**
- 产生：`public void cancelDraft(Long id);`

- [ ] **Step 1: 写失败测试（追加）**

```java
    @Test
    void cancelDraft_forbidsOtherApplicant() {
        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setId(3L);
        draft.setStatus(6);
        draft.setApplicantId(10L);
        when(bizPurchaseRequestMapper.selectById(3L)).thenReturn(draft);

        LoginResponse.UserInfoVO other = new LoginResponse.UserInfoVO();
        other.setId(99L);
        when(authService.getUserInfo()).thenReturn(other);
        when(authzService.isSuperAdmin()).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancelDraft(3L));
        assertEquals("仅申请人本人可撤销草稿", ex.getMsg());
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=PurchaseRequestServiceTest test
```
期望：FAIL（`cancelDraft` 不存在）。

- [ ] **Step 3: 实现 cancelDraft**

```java
    /**
     * 生产申请人撤销自家草稿：置 rejected 保留审计。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelDraft(Long id) {
        requireProductionDraftAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_DRAFT) {
            throw BusinessException.validateFail("仅草稿状态可撤销");
        }
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        if (!entity.getApplicantId().equals(loginUser.getId()) && !authzService.isSuperAdmin()) {
            throw BusinessException.forbidden("仅申请人本人可撤销草稿");
        }
        LambdaUpdateWrapper<BizPurchaseRequest> uw = new LambdaUpdateWrapper<>();
        uw.eq(BizPurchaseRequest::getId, id)
                .eq(BizPurchaseRequest::getStatus, STATUS_DRAFT)
                .set(BizPurchaseRequest::getStatus, STATUS_REJECTED)
                .set(BizPurchaseRequest::getRejectReason, "申请人撤销草稿");
        int rows = bizPurchaseRequestMapper.update(null, uw);
        if (rows != 1) {
            throw BusinessException.validateFail("草稿状态已变更，请刷新后重试");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
    }
```

- [ ] **Step 4: Controller 端点**

在 `rejectDraft` 之后新增：
```java
    @PostMapping("/{id}/cancel-draft")
    @RequireAdmin("仅生产研发部管理员可撤销自家补料草稿")
    @AuditLog(module = "采购申请", action = "撤销草稿", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交撤销请求")
    public Result<Void> cancelDraft(@PathVariable Long id) {
        purchaseRequestService.cancelDraft(id);
        return Result.success();
    }
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=PurchaseRequestServiceTest test
```
期望：BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: 生产申请人撤销自家补料草稿"
```

---

### Task 8: confirmReceive 确认入库时回挂 BOM 明细 goods_id

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`
- Modify: `back/src/main/java/org/example/back/mapper/BizBomDetailMapper.java`
- Modify: `back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java`

**Interfaces:**
- Consumes: `BizBomDetailMapper`、`BizPurchaseRequestDetail.getBomDetailId()/getGoodsId()`。
- 产生：确认入库同一事务内把 `biz_bom_detail.goods_id` 回挂为该行物料（仅原件为空才写）。

- [ ] **Step 1: 注入 BizBomDetailMapper**

`PurchaseRequestService` 加注入：
```java
    @Autowired
    private org.example.back.mapper.BizBomDetailMapper bizBomDetailMapper;
```
确认 `BizBomDetailMapper extends BaseMapper<BizBomDetail>`（是，已有）。若缺 `selectById`/`update` 能力则继承 `BaseMapper` 已带。

- [ ] **Step 2: 写失败测试**

在测试类补一个 `@Mock private org.example.back.mapper.BizBomDetailMapper bizBomDetailMapper;` 并追加：
```java
    @Test
    void confirmReceive_backlinksBomDetailGoodsIdOnlyWhenNull() {
        // 到货申请单（待入库确认）
        BizPurchaseRequest req = new BizPurchaseRequest();
        req.setId(5L);
        req.setStatus(5); // AWAITING_CONFIRM
        req.setRequestNo("PR-test");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(req);

        org.example.back.entity.BizPurchaseRequestDetail d1 = new org.example.back.entity.BizPurchaseRequestDetail();
        d1.setId(100L); d1.setGoodsId(66L); d1.setBomDetailId(12L);
        d1.setArriveQuantity(3); d1.setQuantity(3); d1.setGoodsName("板1");
        d1.setUnitPrice(new java.math.BigDecimal("1.50"));
        when(bizPurchaseRequestDetailMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(d1));

        // BOM 明细原 goods_id 为 null → 应回挂 66
        org.example.back.entity.BizBomDetail bomDetail = new org.example.back.entity.BizBomDetail();
        bomDetail.setId(12L);
        bomDetail.setGoodsId(null);
        when(bizBomDetailMapper.selectById(12L)).thenReturn(bomDetail);
        // confirmReceive 末尾对主单做乐观锁更新（set改为 PENDING=3），stub 返回 1 避免抛"状态已变更"
        when(bizPurchaseRequestMapper.update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        service.confirmReceive(5L);

        // 回挂：BOM 明细 goods_id 更新为 66
        org.mockito.ArgumentCaptor<org.example.back.entity.BizBomDetail> cap =
                org.mockito.ArgumentCaptor.forClass(org.example.back.entity.BizBomDetail.class);
        org.mockito.Mockito.verify(bizBomDetailMapper).updateById(cap.capture());
        assertEquals(66L, cap.getValue().getGoodsId());
    }
```
（`confirmReceive` 内部会调 `purchaseService.createInternal(...)` 与 `bizPurchaseRequestMapper.update`——这些 mock 默认返回 null/0，但不影响回挂断言；`when(...update...).thenReturn(...)` 若需可补。）

- [ ] **Step 3: 运行测试，确认失败**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=PurchaseRequestServiceTest test
```
期望：FAIL（回挂未实现——`updateById` 未被调用）。

- [ ] **Step 4: 实现回挂**

`confirmReceive(...)` 现有循环里、`purchaseService.createInternal(...)` 之后追加回挂逻辑（同一事务）：
```java
            // 回挂：确认入库后把该行对应 BOM 明细 goods_id 写为物料(仅原为空才写，避免覆盖已回挂)
            if (detail.getBomDetailId() != null) {
                org.example.back.entity.BizBomDetail bomDetail = bizBomDetailMapper.selectById(detail.getBomDetailId());
                if (bomDetail != null && bomDetail.getGoodsId() == null && detail.getGoodsId() != null) {
                    bomDetail.setGoodsId(detail.getGoodsId());
                    bizBomDetailMapper.updateById(bomDetail);
                }
            }
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q -Dtest=PurchaseRequestServiceTest test
```
期望：BUILD SUCCESS。同时跑全部现有测试防回归：
```bash
./mvnw -q test
```
期望：BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: 采购申请确认入库时回挂BOM明细goods_id"
```

---

### Task 9: 后端编译 + 全量测试 + 重启验证

**Files:**
- （仅验证，不改业务代码）

- [ ] **Step 1: 全量测试**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q test
```
期望：BUILD SUCCESS。

- [ ] **Step 2: 走了迁移后可热启/重启后端**

重启后端（含新增列）：
```bash
fuser -k 8080/tcp 2>/dev/null; sleep 1
cd /home/niuchao/Warehouse-Management-System-/back && nohup ./mvnw spring-boot:run > /tmp/wms-backend.log 2>&1 &
sleep 20 && ss -tln | grep 8080
```
期望：8080 处于 LISTEN。

- [ ] **Step 3: curl 冒烟（生产建草稿 → 仓储转正 → 采购认领 → 到货 → 入库回挂）**

用三个角色 token 依序调用（具体请求见下，token 用 `login` 换取；物料/成品 id 以本库实际为准；测试完清理并恢复库存）：
```bash
# 登录（生产/仓储/采购三个管理员）
# ...
```
人工执行：①生产 admin post `/business/purchase-requests/draft`(productionOrderId=缺料任务单) → 断言返回草稿 id；②仓储 admin get `/business/purchase-requests/draft/{productionOrderId}` → 断言 DRAFT+detail 含 goodsId null 行；③仓储 admin put `/{id}/confirm-draft`(给 null 行补 goodsId) → 断言 status=1；④采购 admin put `/{id}/process` → status=2；⑤采购 admin put `/{id}/arrive`(带 arriveQuantity+unitPrice) → status=5；⑥仓储 admin put `/{id}/confirm-receive` → status=3，且该 BOM 明细 `goods_id` 已回挂、库存增加。

- [ ] **Step 4: Commit（若 Step1-3 无业务改动则跳过 commit）**

---

### Task 10: 前端 API 层

**Files:**
- Modify: `front/src/api/purchaseRequest.js`

**Interfaces:**
- 产生：`createDraftPurchaseRequestAPI`、`getDraftByProductionOrderAPI`、`confirmDraftPurchaseRequestAPI`、`rejectDraftPurchaseRequestAPI`、`cancelDraftPurchaseRequestAPI`。

- [ ] **Step 1: 追加 API 函数**

在 `front/src/api/purchaseRequest.js` 末尾追加：
```js
// 生产缺料补料草稿
export const createDraftPurchaseRequestAPI = (data) =>
  instance.post('/business/purchase-requests/draft', data)
export const getDraftByProductionOrderAPI = (productionOrderId) =>
  instance.get(`/business/purchase-requests/draft/${productionOrderId}`)
export const confirmDraftPurchaseRequestAPI = (id, data) =>
  instance.put(`/business/purchase-requests/${id}/confirm-draft`, data)
export const rejectDraftPurchaseRequestAPI = (id, data) =>
  instance.put(`/business/purchase-requests/${id}/reject-draft`, data)
export const cancelDraftPurchaseRequestAPI = (id) =>
  instance.post(`/business/purchase-requests/${id}/cancel-draft`)
```
（`instance` 为该文件既有的 axios 实例名，如不同请对齐。）

- [ ] **Step 2: 构建**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build
```
期望：BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(web): 补料草稿 API"
```

---

### Task 11: 生产端补料 UI（ProductionOrderView）

**Files:**
- Modify: `front/src/views/business/ProductionOrderView.vue`

**Interfaces:**
- Consumes: `createDraftPurchaseRequestAPI`、`getDraftByProductionOrderAPI`、`cancelDraftPurchaseRequestAPI`、`getProductionOrderDetailAPI`。
- 行为：任务单状态 1(待生产) 且齐套 partial/block 的行，操作栏出现「补料」；点击打开草稿对话框。

- [ ] **Step 1: 加 import**

在既有 API import 区补：
```js
import {
  createDraftPurchaseRequestAPI,
  getDraftByProductionOrderAPI,
  cancelDraftPurchaseRequestAPI,
} from '@/api/purchaseRequest'
```

- [ ] **Step 2: 主表加「补料」操作按钮 + 状态列显示草稿态**

在操作列（status=1 的「开工」旁边）追加一个按钮，仅当行 `kitStatus` 为 `partial`/`block` 且 `status===1` 时显示：
```html
<el-button
  v-if="(scope.row.status === 1) && (scope.row.kitStatus === 'partial' || scope.row.kitStatus === 'block')"
  type="warning" link @click="openDraftDialog(scope.row)"
>补料</el-button>
```
（`v-permission="{ roles: ['admin'], deptCodes: ['production'] }"` 可选加，与既有「作废」一致。）

- [ ] **Step 3: 草稿对话框（模板）**

在 template 内新增：显示该任务单缺料行 + 每行可改数量 + 「生成草稿」/「撤销草稿」（若已有草稿）：
```html
<el-dialog v-model="draftVisible" :title="`补料草稿 - ${draftRow.orderNo || ''}`" width="720px">
  <el-alert v-if="draftStatusText" :title="draftStatusText" type="info" :closable="false" style="margin-bottom:12px" />
  <el-table :data="draftLines" border>
    <el-table-column prop="goodsName" label="物料" min-width="140" />
    <el-table-column label="需用量" width="90">
      <template #default="s">{{ s.row.required }}</template>
    </el-table-column>
    <el-table-column prop="stock" label="库存" width="70" />
    <el-table-column label="缺口" width="70">
      <template #default="s">{{ s.row.deficit }}</template>
    </el-table-column>
    <el-table-column label="申请数量" width="110">
      <template #default="s">
        <el-input-number v-model="s.row.applyQty" :min="0" size="small" />
      </template>
    </el-table-column>
  </el-table>
  <template #footer>
    <el-button v-if="existingDraftId" type="danger" @click="doCancelDraft">撤销草稿</el-button>
    <el-button v-else type="primary" :loading="draftSubmitting" @click="doCreateDraft">生成草稿</el-button>
  </template>
</el-dialog>
```

- [ ] **Step 4: 脚本逻辑**

新增 data/state 与 handlers：
```js
const draftVisible = ref(false)
const draftRow = ref({})
const draftLines = ref([])
const existingDraftId = ref(null)
const draftStatusText = ref('')
const draftSubmitting = ref(false)

function openDraftDialog(row) {
  draftRow.value = row
  draftVisible.value = true
  draftSubmitting.value = false
  existingDraftId.value = null
  draftStatusText.value = ''
  draftLines.value = []
  // 判断是否已有草稿
  getDraftByProductionOrderAPI(row.id).then((res) => {
    const p = res.data
    if (p) {
      existingDraftId.value = p.id
      draftStatusText.value = `已生成补料草稿（单号 ${p.requestNo}）`
    }
  })
  // 拉详情拿 kitLines，映射成可编辑行
  getProductionOrderDetailAPI(row.id).then((res) => {
    const vo = res.data
    draftLines.value = (vo.kitLines || [])
      .filter((l) => (l.deficit || 0) > 0)
      .map((l) => ({ ...l, applyQty: Math.ceil(l.deficit) }))
  })
}

function doCreateDraft() {
  const items = draftLines.value
    .filter((l) => l.applyQty > 0)
    .map((l) => ({ bomDetailId: l.bomDetailId, quantity: l.applyQty }))
  if (!items.length) {
    ElMessage.warning('请至少填一条申请数量')
    return
  }
  draftSubmitting.value = true
  createDraftPurchaseRequestAPI({ productionOrderId: draftRow.value.id, details: items, remark: '' })
    .then(() => {
      ElMessage.success('补料草稿已生成，待仓储转正')
      draftVisible.value = false
      loadList() // 或刷新当前列表
    })
    .finally(() => { draftSubmitting.value = false })
}

function doCancelDraft() {
  cancelDraftPurchaseRequestAPI(existingDraftId.value).then(() => {
    ElMessage.success('草稿已撤销')
    draftVisible.value = false
    loadList()
  })
}
```
（`ElMessage`/`ref`/`loadList` 需与该文件既有导入/命名一致——按现有代码对齐 `useUserStore`/`ElMessage`。）

- [ ] **Step 5: 构建**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build
```
期望：BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(view): 生产端补料草稿生成与撤销"
```

---

### Task 12: 仓储端草稿 UI（PurchaseRequestView）

**Files:**
- Modify: `front/src/views/business/PurchaseRequestView.vue`

**Interfaces:**
- Consumes: `confirmDraftPurchaseRequestAPI`、`rejectDraftPurchaseRequestAPI`、`getGoodsMaterialOptionsAPI`。
- 行为：过滤支持草稿(状态6)；草稿行显示「转正」「驳回」；转正对话框给 goodsId 空行选物料。

- [ ] **Step 1: 加 import**

```js
import { confirmDraftPurchaseRequestAPI, rejectDraftPurchaseRequestAPI } from '@/api/purchaseRequest'
import { getGoodsMaterialOptionsAPI } from '@/api/base'
```

- [ ] **Step 2: 状态过滤 + 状态标签 + 状态文本**

status select 加 `6=草稿` 选项；`statusTagType` 加 `6: 'warning'`；后端 VO 已含 `statusText`＝"草稿"（待 Task 13 statusText 实现）。在前台 script 加 material 选项与转正对话框状态：
```js
const materialOptions = ref([])
const confirmVisible = ref(false)
const confirmRow = ref({})
const confirmSubmitting = ref(false)

function loadMaterialOptions() {
  getGoodsMaterialOptionsAPI().then((res) => {
    materialOptions.value = res.data || []
  })
}
```

- [ ] **Step 3: 表格草稿行操作按钮**

在操作列内，当 `row.status === 6` 时显示「转正」「驳回」（仓储）：
```html
<template v-if="scope.row.status === 6">
  <el-button type="primary" link @click="openConfirm(scope.row)">转正</el-button>
  <el-button type="danger" link @click="doRejectDraft(scope.row)">驳回</el-button>
</template>
```
且在明细/来源列显示草稿标识（`source_type === 'production'` 时加 tag：`production` →「生产补料」，并显示 `productionOrderId`）。

- [ ] **Step 4: 转正对话框模板**

```html
<el-dialog v-model="confirmVisible" :title="`转正草稿 - ${confirmRow.requestNo || ''}`" width="640px">
  <el-table :data="confirmRow.details || []" border>
    <el-table-column prop="goodsName" label="物料" min-width="140" />
    <el-table-column prop="quantity" label="申请数量" width="90" />
    <el-table-column label="关联物料" min-width="160">
      <template #default="s">
        <el-select v-model="s.row.goodsId" placeholder="待定，请选择物料" filterable clearable style="width: 100%">
          <el-option v-for="o in materialOptions" :key="o.goodsId" :label="o.goodsName" :value="o.goodsId" />
        </el-select>
      </template>
    </el-table-column>
  </el-table>
  <template #footer>
    <el-button @click="confirmVisible = false">取消</el-button>
    <el-button type="primary" :loading="confirmSubmitting" @click="doConfirm">转正</el-button>
  </template>
</el-dialog>
```

- [ ] **Step 5: 脚本 handlers**

```js
function openConfirm(row) {
  loadMaterialOptions()
  confirmRow.value = row
  confirmVisible.value = true
}
function doConfirm() {
  const items = (confirmRow.value.details || [])
    .filter((d) => !d.goodsId)
    .map((d) => ({ detailId: d.id, goodsId: d.goodsId }))
  const missing = items.filter((i) => !i.goodsId)
  if (missing.length) {
    ElMessage.warning('请为待定物料选择关联物料')
    return
  }
  confirmSubmitting.value = true
  confirmDraftPurchaseRequestAPI(confirmRow.value.id, { items })
    .then(() => {
      ElMessage.success('已转正为待采购')
      confirmVisible.value = false
      loadList()
    })
    .finally(() => { confirmSubmitting.value = false })
}
function doRejectDraft(row) {
  ElMessageBox.confirm('确认驳回该补料草稿？', '提示', { type: 'warning' }).then(() => {
    rejectDraftPurchaseRequestAPI(row.id, { reason: '仓储驳回' }).then(() => {
      ElMessage.success('已驳回')
      loadList()
    })
  }).catch(() => {})
}
```
（`ElMessage`/`ElMessageBox`/`loadList`/`materialOptions` 名与既有代码对齐。）

- [ ] **Step 6: 构建**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build
```
期望：BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(view): 仓储端补料草稿转正/驳回"
```

---

### Task 13: 后端补漏 —— statusText 草稿 + page 来源过滤（对齐前端）

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java`
- Modify: `back/src/main/java/org/example/back/dto/PurchaseRequestQueryDTO.java`

**Interfaces:**
- 产生：`PurchaseRequestService.statusText(6)` → "草稿"；`page` 支持 `sourceType` 过滤。
- 使前端 statusTagText 显示正确、仓储可按来源过滤。

- [ ] **Step 1: page 支持来源过滤 + statusText 草稿**

`page(...)` 的 `LambdaQueryWrapper` 加：
```java
                .eq(StringUtils.hasText(queryDTO.getSourceType()), BizPurchaseRequest::getSourceType, queryDTO.getSourceType())
```
`statusText(...)` switch 加一行：
```java
            case STATUS_DRAFT -> "草稿";
```

- [ ] **Step 2: QueryDTO 加 sourceType**

```java
    /**
     * 来源: production/warehouse，可空
     */
    private String sourceType;
```

- [ ] **Step 3: 编译 + 测试**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q test
```
期望：BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: 采购申请草稿状态文本与来源过滤"
```

---

### Task 14: 端到端验证（编译 + 双角色 E2E + 清理）

**Files:**
- （验证，不改业务代码）

- [ ] **Step 1: 后端 clean compile + 全量测试**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw -q clean compile && ./mvnw -q test
```
期望：BUILD SUCCESS。

- [ ] **Step 2: 前端 build**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build
```
期望：BUILD SUCCESS。

- [ ] **Step 3: 双角色端到端（浏览器或 curl）**

完整链路：生产建任务单(缺料) → 生产「补料」生成草稿 → 仓储转正(补物料) → 采购认领 → 到货 → 仓储确认入库(回挂+库存) → 任务单齐套 → 开工。
验证点：草稿幂等(重复补料被拦)、转正缺物料被拦、回挂仅空才写、撤销草稿通知撤销、生产只能看自家草稿、仓储看不到生产正式流转越权。

- [ ] **Step 4: 清理**

测试数据用完即清理：撤销未转正草稿 / 恢复 BOM 明细原 goods_id / 恢复库存。确认 `db.sql` 迁移已在本地应用。

- [ ] **Step 5: Commit（无业务改动则跳过）**

---

## 完成定义（Definition of Done）

- 后端 `./mvnw test` 全绿，前端 `npm run build` 通过。
- 采购申请草稿全生命周期（建草稿→仓储转正→采购链→入库回挂→齐套开工）端到端可跑。
- 回挂行为、草稿幂等、转正缺物料拦截、撤销草稿撤消息均有单测覆盖。
- 消息生命周期符合 D21 范式（终态 `revokeUnreadByBiz("purchase_request", id)`）。
- 本地库已应用 `db.sql` 迁移。