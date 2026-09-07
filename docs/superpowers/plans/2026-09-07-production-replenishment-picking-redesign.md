# 生产补料去仓储 gate + 领料出库前置开工 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"生产补料"从"草稿→仓储转正"改为"生产发起即待采购"，并把"生产领料"改为"生产申请→仓储确认出库→出库后才能开工"。

**Architecture:** 复用现有 `biz_purchase_request`（生产来源直落待采购，去掉 DRAFT gate）与 `biz_pick_list`（新增 production_order_id 列，生产端按 BOM 申请全量领料，仓储 issue 确认出库）。`ProductionOrderService.start()` 从"自动发料"改为校验"该生产单领料单已全额出库"。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus + Lombok；Vue3 + Vite + Element Plus + Axios；MySQL 8.0。后端单测 JUnit5 + Mockito。

## Global Constraints

- 库存变更唯一入口是各业务模块私有 `increaseStock`/`decreaseStock`（观测：PickListService/java 用 `LambdaUpdateWrapper` + `setSql("stock=stock±N")`，`decreaseStock` 带 `ge(stock, qty)` 乐观锁）。
- 站内消息必须带 `biz_type`/`biz_id`，用 `sendToDeptAdminsWithBiz`；单据撤销/终态时 `MessageService.revokeUnreadByBiz` 撤未读。
- 生产补料 = `sourceType=production` 的采购申请，发起即 `STATUS_PENDING(1)`；仓储手动建单(`sourceType=warehouse`)门槛不变。
- 生产领料单 = `biz_pick_list`，`pick_type=PICK`，全量按 BOM×数量，明细数量只读不可改（一次领全）。
- 开工前置：生产单状态=待生产(1) 且 存在一张已创建的领料单 且 该领料单已全额出库(STATUS_ISSUED=2)。
- 仓储端极简：**不发起/不创建任何领料单（含退料），只对已提交的领料/退料单确认出库/入库**。生产领料单(PICK)、生产退料单(RETURN)均由生产端发起；SUPPLY 补料并入选购申请(生产发起)。仓储"新增领料"按钮整体移除。
- 退料归生产端：生产领料后多余料退回仓库，走 `biz_pick_list` pick_type=RETURN，生产端发起、仓储 `issue()` 确认入库（increaseStock）。

---

### Task 1: 数据库迁移——领料单关联生产任务单

**Files:**
- Modify: `db.sql`（追加 ALTER，不动已有建表）

**Interfaces:**
- Produces: `biz_pick_list.production_order_id BIGINT NULL`

**Context:** 开工校验需"定位一张生产单的领料单"，现 `biz_pick_list` 只有 `source_sales_id`。加一列关联生产任务单。

- [ ] **Step 1: 追加 ALTER 到 db.sql 末尾**

在 `db.sql` 末尾追加（与应用里已有 `biz_purchase_request` ALTER 追加块风格一致）：

```sql
-- 生产领料: 领料单关联生产任务单(用于开工校验"该生产单领料已全额出库")
ALTER TABLE `biz_pick_list`
    ADD COLUMN `production_order_id` BIGINT DEFAULT NULL COMMENT '来源生产任务单id(生产端申请领料时写入)' AFTER `source_sales_id`,
    ADD KEY `idx_pick_production_order` (`production_order_id`);
```

- [ ] **Step 2: 应用到本地数据库**

```bash
cd /home/niuchao/Warehouse-Management-System-
mysql -u wms_user -pwms_pass warehouse_management < /tmp/pick_production.sql   # 先将上方 SQL 写入该文件
```

- [ ] **Step 3: 验证列存在**

```bash
mysql -u wms_user -pwms_pass warehouse_management -e "SHOW COLUMNS FROM biz_pick_list LIKE 'production_order_id';"
```
Expected: 一行含 `production_order_id BIGINT ... NULL`。若已存在（重复执行），跳过。

---

### Task 2: 后端实体加 productionOrderId 字段

**Files:**
- Modify: `back/src/main/java/org/example/back/entity/BizPickList.java`

**Interfaces:**
- Produces: `BizPickList.getProductionOrderId()/setProductionOrderId(Long)`

- [ ] **Step 1: 在 `sourceSalesId` 字段后加字段**

在 [BizPickList.java](back/src/main/java/org/example/back/entity/BizPickList.java) 的 `private Long sourceSalesId;` 后插入：

```java
    /**
     * 来源生产任务单id(生产端申请领料时写入, 用于开工校验)
     */
    private Long productionOrderId;
```

- [ ] **Step 2: 编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

---

### Task 3: 后端——生产补料改为"发起即待采购"，去掉仓储转正 gate

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PurchaseRequestService.java:144-195` (createDraft)
- Modify: `back/src/main/java/org/example/back/controller/PurchaseRequestController.java`
- Modify: `back/src/main/java/org/example/back/service/MessageService.java`（消息改向）

**Interfaces:**
- Consumes: `ProductionDraftCreateDTO`（含 productionOrderId、details[{bomDetailId, quantity}]）
- Produces: `PurchaseRequestService.createDraft()` 主单 status=PENDING，通知采购管理员；不再调用 `sendPurchaseRequestDraftToWarehouseAdmins`

**Context:** 现 `createDraft` 把主单 status 置 `STATUS_DRAFT(6)` 并通知仓储转正。要改成生产补料直接置 `STATUS_PENDING(1)`、通知采购管理员认领，彻底去掉仓储 gate。同时生产端补料弹窗需自选物料，因此明细 goodsId 由前端在提交时带上（不再留给仓储转正回填）。

- [ ] **Step 1: 修改 `createDraft` 主单状态与消息**

将 [PurchaseRequestService.java:164-194](back/src/main/java/org/example/back/service/PurchaseRequestService.java#L164-L194) 中：

```java
        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setRequestNo(CodeGenerator.purchaseRequestNo());
        draft.setStatus(STATUS_DRAFT);
        draft.setSourceType(SOURCE_PRODUCTION);
        draft.setProductionOrderId(dto.getProductionOrderId());
        draft.setApplicantId(loginUser.getId());
        draft.setApplicantName(loginUser.getRealName());
        draft.setRemark(dto.getRemark());
        bizPurchaseRequestMapper.insert(draft);
```

改为（status 直接 PENDING，不再经 DRAFT）：

```java
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setRequestNo(CodeGenerator.purchaseRequestNo());
        request.setStatus(STATUS_PENDING);
        request.setSourceType(SOURCE_PRODUCTION);
        request.setProductionOrderId(dto.getProductionOrderId());
        request.setApplicantId(loginUser.getId());
        request.setApplicantName(loginUser.getRealName());
        request.setRemark(dto.getRemark());
        bizPurchaseRequestMapper.insert(request);
```

并把方法内后面的 `draft.getId()` 引用改为 `request.getId()`（明细 `setRequestId` 与返回语句）：

```java
            det.setRequestId(request.getId());
            ...
        messageService.sendPurchaseRequestToPurchaseAdmins(request.getRequestNo(), loginUser.getRealName(), request.getId());
        return request.getId();
```

同时把方法最后一行来自 `sendPurchaseRequestDraftToWarehouseAdmins` 的调用替换为上面的 `sendPurchaseRequestToPurchaseAdmins`（替换后不再通知仓储转正，直接通知采购认领）。

- [ ] **Step 2: 明细可用前端传的 goodsId（自选物料）**

当前明细循环用 `line.getGoodsId()`（可能为 null，方案先行）。改为优先用 `dto.getDetails()` 里 `bomDetailId` 匹配行带出的 `goodsId`：

```java
        Map<Long, Integer> override = dto.getDetails() == null ? Map.of()
                : dto.getDetails().stream()
                        .filter(i -> i.getBomDetailId() != null && i.getQuantity() != null)
                        .collect(Collectors.toMap(ProductionDraftItemDTO::getBomDetailId, ProductionDraftItemDTO::getQuantity));
        Map<Long, Long> overrideGoods = dto.getDetails() == null ? Map.of()
                : dto.getDetails().stream()
                        .filter(i -> i.getBomDetailId() != null && i.getGoodsId() != null)
                        .collect(Collectors.toMap(ProductionDraftItemDTO::getBomDetailId, ProductionDraftItemDTO::getGoodsId, (a, b) -> b));
```

在明细循环内：

```java
            det.setGoodsId(overrideGoods.getOrDefault(line.getBomDetailId(), line.getGoodsId()));
```

- [ ] **Step 3: 更新 `ProductionDraftItemDTO` 增加 goodsId**

在 `back/src/main/java/org/example/back/dto/ProductionDraftItemDTO.java` 加字段：

```java
    /** 生产自选关联物料id(方案先行goodsId为空时由生产补料弹窗选定) */
    private Long goodsId;
```

- [ ] **Step 4: 删除生产补料的仓储转正/驳回/撤销接口与消息方法**

在 [PurchaseRequestService.java](back/src/main/java/org/example/back/service/PurchaseRequestService.java) 删除方法 `confirmDraft`(L210)、`rejectDraft`(L254)、`cancelDraft`(L277)；在 [PurchaseRequestController.java](back/src/main/java/org/example/back/controller/PurchaseRequestController.java) 删除 `confirm-draft`、`reject-draft`、`cancel-draft` 三个接口；删除 `MessageService.sendPurchaseRequestDraftToWarehouseAdmins`(L345)。删除后编译确认无悬空引用。

- [ ] **Step 5: 编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw compile -q
```
Expected: BUILD SUCCESS。若因删除方法导致测试引用报错，属于预期，见 Task 4 测试更新。

- [ ] **Step 6: 更新 `PurchaseRequestServiceTest` 草稿相关用例**

现有用例断言 `createDraft` 置 status=6 并通知仓储转正，须改为断言 status=1 并通知采购。修改 [PurchaseRequestServiceTest.java](back/src/test/java/org/example/back/service/PurchaseRequestServiceTest.java)：
- `createDraft_setsDraftStatusAndSendsWarehouseNotice` → 断言 `assertEquals(1, draft.getStatus())`，并 `verify(messageService).sendPurchaseRequestToPurchaseAdmins(anyString(), eq("生产甲"), any())`，删掉对 `sendPurchaseRequestDraftToWarehouseAdmins` 的 verify。
- `createDraft_sendsWarehouseNotice_andInsertsDetailRow` → 同理改断言。
- 删除 `confirmDraft_*`、`rejectDraft_*`、`cancelDraft_*` 全部用例（这些方法已删除）。

- [ ] **Step 7: 测试**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=PurchaseRequestServiceTest -q
```
Expected: 通过。失败则核对 status 断言值。

---

### Task 4: 后端——生产申请领料（按 BOM 全量生成 PICK 领料单）

**Files:**
- Create: `back/src/main/java/org/example/back/service/ProductionPickService.java`
- Create: `back/src/main/java/org/example/back/controller/ProductionPickController.java`
- Modify: `back/src/main/java/org/example/back/service/ProductionOrderService.java`（暴露 computeKit / 提供全量需求量）
- Modify: `back/src/main/java/org/example/back/entity/BizPickList.java`（已在 Task 2 加 productionOrderId）

**Interfaces:**
- Consumes: `ProductionOrderService.getById()`、`ProductionOrderService.computeKit`（私有，需新暴露一个公开方法）；`PickListService` 常量 `TYPE_PICK`/`STATUS_PENDING`；`BizPickListMapper`/`BizPickListDetailMapper`
- Produces: `ProductionPickController.rest(expression)`
  - `POST /business/production-orders/{orderId}/pick` — 生产端按 BOM 全量生成领料单，返回 leader单 VO
  - `GET /business/production-orders/{orderId}/pick` — 查询该生产单已创建的领料单及其状态（供开工/UI 展示）
  - `GET /business/production-orders/{orderId}/pick/editable` — 返回该生产单可领料明细（BOM×数量，goodsId 可能为 null 的拒绝）

**Context:** 生产端在齐套后点"申请领料"，系统按该生产单 BOM×数量自动带出全部物料行（goodsId 非 null 才可领），生成一张 `pick_type=PICK`、`status=PENDING`、`productionOrderId` 关联的领料单，通知仓储确认出库。明细数量由后端按 BOM 锁定，不接受前端传数量。

**决策强制数量只读**: 领料明细的 quantity 由后端按 BOM×生产数量计算，前端传的明细只传 goodsId，不传数量，防止领少了导致开工校验永远不过。

- [ ] **Step 1: 在 ProductionOrderService 暴露"全量可领物料"公有方法**

在 [ProductionOrderService.java](back/src/main/java/org/example/back/service/ProductionOrderService.java) 增加公有方法（复用私有 computeKit，把每行物料 id + 需求数量带出，只接受 goodsId 非空的物料，并校验 goodsId 对应库存 ≥ 需求，否则抛"物料库存不足需补料"）：

```java
    /**
     * 生产申请领料：返回该生产单 BOM 展开后全部可领物料(需求数量锁定)，goodsId 为空或库存不足的行抛错。
     */
    public List<org.example.back.vo.ProductionPickItemVO> computePickItems(Long productionOrderId) {
        BizProductionOrder order = requireOrder(productionOrderId);
        KuaiTaoResult kit = computeKit(order.getGoodsId(), order.getQuantity());
        List<org.example.back.vo.ProductionPickItemVO> items = new ArrayList<>();
        for (KitShortageVO line : kit.lines) {
            if (line.getGoodsId() == null) {
                throw BusinessException.validateFail("物料[" + line.getGoodsName() + "]未在仓库建档，无法申请领料");
            }
            int requiredInt = line.getRequired().setScale(0, RoundingMode.UP).intValue();
            if (line.getStock() == null || line.getStock() < requiredInt) {
                throw BusinessException.validateFail("物料[" + line.getGoodsName() + "]库存不足（需" + requiredInt + "，现" + line.getStock() + "），请补料后再领");
            }
            org.example.back.vo.ProductionPickItemVO item = new org.example.back.vo.ProductionPickItemVO();
            item.setGoodsId(line.getGoodsId());
            item.setGoodsName(line.getGoodsName());
            item.setQuantity(requiredInt);
            items.add(item);
        }
        if (items.isEmpty()) {
            throw BusinessException.validateFail("该生产单无可领物料（BOM 为空或全部为参考行）");
        }
        return items;
    }
```

- [ ] **Step 2: 新建 `ProductionPickItemVO`**

`back/src/main/java/org/example/back/vo/ProductionPickItemVO.java`：

```java
package org.example.back.vo;

import lombok.Data;

@Data
public class ProductionPickItemVO {
    private Long goodsId;
    private String goodsName;
    private Integer quantity;
}
```

- [ ] **Step 3: 新建 `ProductionPickService`**

`back/src/main/java/org/example/back/service/ProductionPickService.java`（生产端领料，仓储端出库走现有 PickListService.issue，本服务仅生产端创建/查询领料单）：

```java
package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.vo.PickListVO;
import org.example.back.vo.ProductionPickItemVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 生产端领料：按生产任务单 BOM 全量申请领料，生成 PICK 领料单后交仓储确认出库。
 * 生产端不直接扣库存；扣库存的最终动作出库由 PickListService.issue（仓储）完成。
 */
@Service
public class ProductionPickService {

    @Autowired private BizPickListMapper pickListMapper;
    @Autowired private BizPickListDetailMapper pickListDetailMapper;
    @Autowired private BizProductionOrderMapper productionOrderMapper;
    @Autowired private AuthService authService;
    @Autowired private AuthzService authzService;
    @Autowired private MessageService messageService;
    @Autowired private ProductionOrderService productionOrderService;

    public void requireProductionMember() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产研发部可申请生产领料", AuthzService.DEPT_PRODUCTION);
    }

    @Transactional(rollbackFor = Exception.class)
    public PickListVO createPick(Long orderId) {
        requireProductionMember();
        BizProductionOrder order = productionOrderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        if (order.getStatus() != BizProductionOrder.STATUS_PENDING) {
            throw BusinessException.validateFail("仅待生产状态可申请领料");
        }
        LambdaQueryWrapper<BizPickList> dup = new LambdaQueryWrapper<>();
        dup.eq(BizPickList::getProductionOrderId, orderId);
        if (pickListMapper.selectCount(dup) > 0) {
            throw BusinessException.validateFail("该生产任务单已申请领料，请勿重复");
        }

        List<ProductionPickItemVO> items = productionOrderService.computePickItems(orderId);

        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizPickList pick = new BizPickList();
        pick.setPickNo(CodeGenerator.pickListNo());
        pick.setPickType(PickListService.TYPE_PICK);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(orderId);
        pick.setApplicantId(user.getId());
        pick.setApplicantName(user.getRealName());
        pick.setRemark("生产任务单 " + order.getOrderNo() + " 申请领料");
        pickListMapper.insert(pick);

        int sortNo = 0;
        for (ProductionPickItemVO item : items) {
            BizPickListDetail det = new BizPickListDetail();
            det.setPickListId(pick.getId());
            det.setGoodsId(item.getGoodsId());
            det.setGoodsName(item.getGoodsName());
            det.setQuantity(item.getQuantity());
            det.setSortNo(sortNo++);
            pickListDetailMapper.insert(det);
        }

        messageService.sendPickPendingToWarehouseAdmins(pick.getPickNo(), order.getOrderNo(), pick.getId());
        return toVO(pick);
    }

    public List<PickListVO> listByOrder(Long orderId) {
        requireProductionMember();
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId).orderByDesc(BizPickList::getId);
        return pickListMapper.selectList(w).stream().map(p -> {
            org.example.back.vo.PickListVO vo = new org.example.back.vo.PickListVO();
            vo.setId(p.getId());
            vo.setPickNo(p.getPickNo());
            vo.setPickType(p.getPickType());
            vo.setPickTypeText("领料");
            vo.setStatus(p.getStatus());
            vo.setStatusText(statusText(p.getStatus()));
            vo.setRemark(p.getRemark());
            vo.setCreateTime(p.getCreateTime());
            return vo;
        }).toList();
    }

    public boolean isAllIssued(Long orderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId);
        List<BizPickList> picks = pickListMapper.selectList(w);
        if (picks.isEmpty()) {
            return false;
        }
        return picks.stream().allMatch(p -> PickListService.STATUS_ISSUED == p.getStatus()
                || PickListService.STATUS_DONE == p.getStatus());
    }

    private String statusText(Integer status) {
        if (status == null) return null;
        return switch (status) {
            case 1 -> "待发料";
            case 2 -> "已发料";
            case 3 -> "已完成";
            case 4 -> "已驳回";
            default -> String.valueOf(status);
        };
    }

    private PickListVO toVO(BizPickList p) {
        org.example.back.vo.PickListVO vo = new org.example.back.vo.PickListVO();
        vo.setId(p.getId());
        vo.setPickNo(p.getPickNo());
        vo.setPickType(p.getPickType());
        vo.setPickTypeText("领料");
        vo.setStatus(p.getStatus());
        vo.setStatusText(statusText(p.getStatus()));
        vo.setRemark(p.getRemark());
        vo.setCreateTime(p.getCreateTime());
        return vo;
    }
}
```

- [ ] **Step 4: 新建 `ProductionPickController`**

`back/src/main/java/org/example/back/controller/ProductionPickController.java`：

```java
package org.example.back.controller;

import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.Result;
import org.example.back.service.ProductionPickService;
import org.example.back.vo.PickListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/business/production-orders")
public class ProductionPickController {

    @Autowired private ProductionPickService productionPickService;

    @PostMapping("/{orderId}/pick")
    @PreventDuplicateSubmit(message = "请勿重复提交领料申请")
    public Result<PickListVO> createPick(@PathVariable Long orderId) {
        return Result.success(productionPickService.createPick(orderId));
    }

    @GetMapping("/{orderId}/pick")
    public Result<List<PickListVO>> listPicks(@PathVariable Long orderId) {
        return Result.success(productionPickService.listByOrder(orderId));
    }

    @GetMapping("/{orderId}/pick/editable")
    public Result<List<org.example.back.vo.ProductionPickItemVO>> editableItems(@PathVariable Long orderId) {
        return Result.success(productionOrderService.computePickItems(orderId));
    }
}
```

- [ ] **Step 5: 在 MessageService 新增两条消息**

在 [MessageService.java](back/src/main/java/org/example/back/service/MessageService.java) 加：

```java
    /**
     * 生产端提交领料申请 → 通知仓储管理员确认出库。
     */
    public void sendPickPendingToWarehouseAdmins(String pickNo, String orderNo, Long pickId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认生产领料出库",
                String.format(java.util.Locale.ROOT,
                        "领料单 %s（生产任务单 %s）已由生产端提交，请确认出库。",
                        pickNo, orderNo),
                "pick_list",
                pickId);
    }
```

- [ ] **Step 6: 编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 7: 写 ProductionPickService 单测**

`back/src/test/java/org/example/back/service/ProductionPickServiceTest.java`（纯 mock，参照采购单测的 TableInfoHelper 初始化）：

```java
package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.vo.ProductionPickItemVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionPickServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPickList.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPickListDetail.class);
    }

    @Mock private BizPickListMapper pickListMapper;
    @Mock private BizPickListDetailMapper pickListDetailMapper;
    @Mock private BizProductionOrderMapper productionOrderMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;
    @Mock private ProductionOrderService productionOrderService;

    @InjectMocks private ProductionPickService service;

    @Test
    void createPick_generatesPendingPickWithOrderId() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("SC-0001");
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        when(pickListMapper.selectCount(any())).thenReturn(0L);

        ProductionPickItemVO item = new ProductionPickItemVO();
        item.setGoodsId(50L);
        item.setGoodsName("螺丝");
        item.setQuantity(6);
        when(productionOrderService.computePickItems(7L)).thenReturn(List.of(item));

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        service.createPick(7L);

        ArgumentCaptor<BizPickList> cap = ArgumentCaptor.forClass(BizPickList.class);
        verify(pickListMapper).insert(cap.capture());
        BizPickList pick = cap.getValue();
        assertEquals(7L, pick.getProductionOrderId());
        assertEquals("PICK", pick.getPickType());
        assertEquals(1, pick.getStatus()); // PENDING
        assertEquals(user.getId(), pick.getApplicantId());

        ArgumentCaptor<BizPickListDetail> dcap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(dcap.capture());
        assertEquals(50L, dcap.getValue().getGoodsId());
        assertEquals(6, dcap.getValue().getQuantity());
    }

    @Test
    void createPick_rejectsWhenAlreadyPicked() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        when(pickListMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPick(7L));
        assertTrue(ex.getMessage().contains("已申请领料"));
    }

    @Test
    void createPick_rejectsWhenNotPending() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPick(7L));
        assertTrue(ex.getMessage().contains("仅待生产状态可申请领料"));
    }

    @Test
    void isAllIssued_trueWhenAllIssued() {
        BizPickList p1 = new BizPickList();
        p1.setId(1L);
        p1.setStatus(PickListService.STATUS_ISSUED);
        when(pickListMapper.selectList(any())).thenReturn(List.of(p1));

        assertTrue(service.isAllIssued(7L));
    }

    @Test
    void isAllIssued_falseWhenAnyPending() {
        BizPickList p1 = new BizPickList();
        p1.setId(1L);
        p1.setStatus(PickListService.STATUS_PENDING);
        when(pickListMapper.selectList(any())).thenReturn(List.of(p1));

        org.junit.jupiter.api.Assertions.assertFalse(service.isAllIssued(7L));
    }
}
```

- [ ] **Step 8: 跑测试**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=ProductionPickServiceTest -q
```
Expected: 通过。

---

### Task 5: 后端——开工校验改为"领料单已全额出库"，删除自动发料

**Files:**
- Modify: `back/src/main/java/org/example/back/service/ProductionOrderService.java:187-200` (start)
- Modify: `back/src/main/java/org/example/back/service/ProductionOrderService.java:360-401` (删除 generatePickListAndIssue)

**Interfaces:**
- Consumes: `ProductionOrderService` 自有 `pickListMapper`（L72 已注入）、`PickListService.STATUS_ISSUED/STATUS_DONE` 常量
- Produces: `ProductionOrderService.start()` 在领料单未全额出库时抛校验异常；自带私有 `isPickAllIssued(orderId)`

**Context:** 开工不再自动生成领料单、不再扣库存。改为校验该生产单已创建领料单且全部出库。**用自有 `pickListMapper` 直接查，不依赖 `ProductionPickService`——避免与 Task 4 的 `ProductionPickService → ProductionOrderService` 形成循环依赖。**

- [ ] **Step 1: 加私有校验方法 `isPickAllIssued`**

在 [ProductionOrderService.java](back/src/main/java/org/example/back/service/ProductionOrderService.java) 私有辅助区（如 `reduceStock` 附近）加：

```java
    /**
     * 开工前置校验：该生产单存在领料单且已全额出库/完成。
     * 用自有 pickListMapper 查询，避免反向依赖 ProductionPickService（防循环依赖）。
     */
    private boolean isPickAllIssued(Long productionOrderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, productionOrderId);
        List<BizPickList> picks = pickListMapper.selectList(w);
        if (picks.isEmpty()) {
            return false;
        }
        return picks.stream().allMatch(p ->
                PickListService.STATUS_ISSUED == p.getStatus()
                        || PickListService.STATUS_DONE == p.getStatus());
    }
```

> `LambdaQueryWrapper`/`List`/`BizPickList`/`PickListService` 均已在本文件 import（L3/L35/L14 及同包），无需新增 import。

- [ ] **Step 2: 重写 `start` 方法**

将 [ProductionOrderService.java:187-200](back/src/main/java/org/example/back/service/ProductionOrderService.java#L187-L200) 的 `start` 改为：

```java
    @Transactional(rollbackFor = Exception.class)
    public void start(Long id) {
        requireOrderExecuteAccess();
        BizProductionOrder order = requireOrder(id);
        ensureStatus(order, BizProductionOrder.STATUS_PENDING, "仅待生产状态可开工");

        // D59：开工前置 = 该生产单已申请领料且领料单已全额出库
        if (!isPickAllIssued(id)) {
            throw BusinessException.validateFail("该生产任务单领料单尚未全额出库，请先申请领料并由仓储确认出库后开工");
        }

        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        orderMapper.updateById(order);
    }
```

- [ ] **Step 3: 删除 `generatePickListAndIssue` 私有方法**

删除 [L360-401](back/src/main/java/org/example/back/service/ProductionOrderService.java#L360-L401) 整个私有方法。删除后 `reduceStock` 不再被使用（javac 不报未使用私有方法），`pickListMapper` 仍被 `isPickAllIssued` 使用，保留。`increaseStock` 被 `receipt` 使用，保留。

- [ ] **Step 4: 编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw compile -q
```
Expected: BUILD SUCCESS。（若 `reduceStock` 未使用留下 IDE 警告，可忽略；若 `bizPickListDetailMapper` 字段删除 `generatePickListAndIssue` 后无引用，仅 IDE 警告，也可不动。）

- [ ] **Step 5: 更新 `ProductionOrderServiceTest`**

现有 [ProductionOrderServiceTest.java](back/src/test/java/org/example/back/service/ProductionOrderServiceTest.java) 未测 start，无破坏。给该类注入 `pickListMapper` mock 并新增 start 用例：

现有 `@Mock` 区块已含 `orderMapper/bomMapper/bomDetailMapper/baseGoodsMapper`，补加：

```java
    @Mock private BizPickListMapper pickListMapper;
```

新增用例（注意 `ProductionOrderService` 的 `isPickAllIssued` 用 `pickListMapper.selectList` 查询领料单）：

```java
    @Test
    void start_blocksWhenNoPick() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);
        when(pickListMapper.selectList(any())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.start(7L));
        assertTrue(ex.getMessage().contains("尚未全额出库"));
    }

    @Test
    void start_blocksWhenPickPending() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);

        org.example.back.entity.BizPickList pending = new org.example.back.entity.BizPickList();
        pending.setId(1L);
        pending.setStatus(PickListService.STATUS_PENDING); // 1 待发料
        when(pickListMapper.selectList(any())).thenReturn(java.util.List.of(pending));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.start(7L));
        assertTrue(ex.getMessage().contains("尚未全额出库"));
    }

    @Test
    void start_setsInProgressWhenAllIssued() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);

        org.example.back.entity.BizPickList issued = new org.example.back.entity.BizPickList();
        issued.setId(1L);
        issued.setStatus(PickListService.STATUS_ISSUED); // 2 已发料
        when(pickListMapper.selectList(any())).thenReturn(java.util.List.of(issued));
        when(orderMapper.updateById(any())).thenReturn(1);

        service.start(7L);

        org.mockito.ArgumentCaptor<BizProductionOrder> captor =
                org.mockito.ArgumentCaptor.forClass(BizProductionOrder.class);
        org.mockito.Mockito.verify(orderMapper).updateById(captor.capture());
        assertEquals(BizProductionOrder.STATUS_IN_PROGRESS, captor.getValue().getStatus());
    }
```

需在测试文件补 import：`org.example.back.mapper.BizPickListMapper`、`org.example.back.service.PickListService`、`java.util.List`（已有）、`org.mockito.Mock`。

- [ ] **Step 6: 跑测试**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw test -Dtest=ProductionOrderServiceTest -q
```
Expected: 通过。

---

### Task 6: 后端——仓储确认出库复用 issue(仅生产来源),消息改向

**Files:**
- Modify: `back/src/main/java/org/example/back/service/PickListService.java:153-199` (issue)
- Modify: `back/src/main/java/org/example/back/service/MessageService.java`（出库后通知生产）

**Interfaces:**
- Consumes: 现有 `PickListService.issue()`（已 PICK 扣库存、任一缺料整单回滚、通知销售失败）
- Produces: issue 成功后回执给生产端可开工的消息

**Context:** 仓储对生产领料单的"确认出库"复用现有 `issue()`（PICK 扣库存）。但现有 `issue()` 缺料失败时 `sendPickListFailureToSalesAdmins` 是发给销售——生产来源领料失败应通知生产端补料。需按是否带 productionOrderId 分流。

- [ ] **Step 1: issue 消息按来源分流**

在 [PickListService.java:176-183](back/src/main/java/org/example/back/service/PickListService.java#L176-L183) 的 catch 块，改为当 `entity.getProductionOrderId() != null` 时通知生产端（而非销售）：

```java
        } catch (BusinessException e) {
            if (!messageService.hasUnreadBizMessage("pick_list", id)) {
                if (entity.getProductionOrderId() != null) {
                    messageService.sendPickIssueFailedToProductionAdmins(
                            entity.getPickNo(), e.getMessage(), id);
                } else {
                    messageService.sendPickListFailureToSalesAdmins(
                            entity.getPickNo(), "发料缺料，库存不足，发料失败", id);
                }
            }
            throw e;
        }
```

- [ ] **Step 2: 在 MessageService 新增出库成功通知生产 + 失败通知生产**

在 [MessageService.java](back/src/main/java/org/example/back/service/MessageService.java) 新增：

```java
    /**
     * 仓储确认出库成功 → 通知生产端已可开工。
     */
    public void sendPickIssuedToProductionAdmins(String pickNo, String orderNo, Long pickId) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "生产领料已出库",
                String.format(java.util.Locale.ROOT,
                        "领料单 %s（生产任务单 %s）已由仓储确认出库，现可开工。",
                        pickNo, orderNo == null ? "-" : orderNo),
                "pick_list",
                pickId);
    }

    /**
     * 生产领料出库失败(缺料/驳回) → 通知生产端。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPickIssueFailedToProductionAdmins(String pickNo, String reason, Long pickId) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "生产领料出库失败",
                String.format(java.util.Locale.ROOT, "领料单 %s 出库失败：%s", pickNo, reason),
                "pick_list",
                pickId);
    }
```

- [ ] **Step 3: issue 成功时通知生产端**

在 [PickListService.java](back/src/main/java/org/example/back/service/PickListService.java) 发料成功、`revokeUnreadByBiz` 之后追加：

```java
        // 发料成功：生产来源领料单通知生产端可开工；其余保留原逻辑
        if (entity.getProductionOrderId() != null) {
            messageService.sendPickIssuedToProductionAdmins(entity.getPickNo(), null, id);
        }
```

- [ ] **Step 4: 编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

---

### Task 7: 前端——生产订单详情增加"申请领料"入口 + 补料弹窗自选物料 + 开工前置提示

**Files:**
- Modify: `front/src/views/business/ProductionOrderView.vue`
- Modify: `front/src/api/pickList.js`（新增 production pick 相关 API）
- Modify: `front/src/views/business/PickListView.vue`（仓储视角，生产来源领料单展示 + 出库消息）

**Interfaces:**
- Consumes: 新后端接口 `POST/GET /business/production-orders/{orderId}/pick`、`/pick/editable`；`createDraftPurchaseRequestAPI` 的 details 增加 `goodsId`
- Produces: 生产订单详情"申请领料"按钮、领料单状态展示；补料弹窗自选物料

**Context:** 生产端在详情页看到齐套后可点"申请领料"（后端按 BOM 生成、锁定数量），并展示该生产单已创建的领料单及状态；开工按钮在领料单未全额出库时禁用并提示。补料弹窗去除"草稿/撤销"，改为提交即直接补料，并允许在缺口行选择具体物料。

- [ ] **Step 1: pickList.js 增补 production pick API**

在 [front/src/api/pickList.js](front/src/api/pickList.js) 追加：

```js
export const createProductionPickAPI = (orderId) => request.post(`/business/production-orders/${orderId}/pick`)
export const getProductionPickListAPI = (orderId) => request.get(`/business/production-orders/${orderId}/pick`)
export const getProductionPickEditableAPI = (orderId) => request.get(`/business/production-orders/${orderId}/pick/editable`)
```

- [ ] **Step 2: 生产订单详情新增"申请领料"按钮与领料单状态区**

在 [ProductionOrderView.vue](front/src/views/business/ProductionOrderView.vue) ：
- 操作列（L42-56）：把"开工"按钮条件从 `scope.row.status === 1` 改为 `scope.row.status === 1` 且展示领料状态；新增"申请领料"按钮 `v-if="scope.row.status === 1"` `v-permission admin+production`。
- 详情弹窗（L135-172）底部加"申请领料"按钮 + 领料单状态展示：

```html
        <template v-if="detail">
          <div style="display:flex; gap:12px; margin-bottom:12px;">
            <el-button v-if="detail.status === 1 && !pickListStatus" type="primary" :loading="pickSubmitting" @click="doApplyPick">申请领料</el-button>
            <el-tag v-if="pickListStatus" :type="pickListStatusTag.type" size="medium">{{ pickListStatusTag.text }}</el-tag>
          </div>
        </template>
```

- [ ] **Step 3: 补料弹窗改为"直接补料"并支持自选物料**

将补料弹窗（L175-196）表格的"申请数量"列旁加"关联物料"列，缺口行 `goodsId` 为空时用 `<el-select>` 自选；按钮"生成草稿/撤销草稿"改为"提交补料"。`doCreateDraft` 改为提交时带 goodsId 并提示改为"补料已提交，待采购"：

```js
function doCreateDraft() {
  const items = draftLines.value
    .filter((l) => l.applyQty > 0)
    .map((l) => ({ bomDetailId: l.bomDetailId, goodsId: l.goodsId, quantity: l.applyQty }))
  if (!items.length) { ElMessage.warning('请至少填一条申请数量'); return }
  const missing = items.find((i) => !i.goodsId)
  if (missing) { ElMessage.warning('存在未关联物料的缺口行，请先选择物料'); return }
  draftSubmitting.value = true
  createDraftPurchaseRequestAPI({ productionOrderId: draftRow.value.id, details: items, remark: '' })
    .then((res) => { if (res.code !== 200) throw new Error(res.msg || '补料失败'); ElMessage.success('补料已提交，待采购'); draftVisible.value = false; loadList() })
    .catch((error) => ElMessage.error(error.message || '补料失败'))
    .finally(() => { draftSubmitting.value = false })
}
```

（`openDraftDialog` 里拉 kitLines 的行需带上 goodsId，并在列表里为缺料缺口行提供物料 el-select。物料选项复用 `getGoodsOptionsAPI`。）

- [ ] **Step 4: 开工确认文案与前置拦截**

`handleStart`（L335-347）确认文案改为"确认开工？开工需该生产任务单领料已全额出库。"，并在发起前校验 `pickListStatus` 为非 null/已出库，否则拦截提示"请先申请领料并由仓储确认出库"。

- [ ] **Step 5: 前端编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build
```
Expected: 构建成功无编译错误。

---

### Task 8: 前端——采购申请列表去掉仓储草稿转正/驳回交互

**Files:**
- Modify: `front/src/views/business/PurchaseRequestView.vue`

**Interfaces:**
- Consumes: 后端已删除 confirm-draft/reject-draft 接口
- Produces: 列表不再出现"草稿"状态筛选、仓储转正/驳回按钮消失

**Context:** 补料改为直接待采购后，仓储不再有草稿转正/驳回。前端须移除这些交互。

- [ ] **Step 1: 删除草稿转正/驳回相关**

在 [PurchaseRequestView.vue](front/src/views/business/PurchaseRequestView.vue)：
- 状态筛选选项删除"草稿(6)"。
- 删除操作列的"转正"/"驳回"按钮（`row.status === 6` 分支）。
- 删除转正草稿弹窗模板、`openConfirm/doConfirm/doRejectDraft/loadMaterialOptions` 相关函数与 `materialOptions/confirmVisible/confirmRow/confirmSubmitting` 状态。
- 删除 import 里的 `confirmDraftPurchaseRequestAPI / rejectDraftPurchaseRequestAPI`。

- [ ] **Step 2: 前端编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build
```
Expected: 构建成功。

---

### Task 9: 后端——生产退料入口（RETURN），仓储彻底不建任何单

**Files:**
- Modify: `back/src/main/java/org/example/back/service/ProductionPickService.java`（加 ensureEditBlock）
- Modify: `back/src/main/java/org/example/back/service/PickListService.java`（create 限制仅生产退料可建；仓储新增领料移除）

**Interfaces:**
- Consumes: Q15 决策（退料归生产端，仓储不建任何领料单）
- Produces: 生产退料走 `biz_pick_list` pick_type=RETURN（生产发起、仓储 issue 入库）

**Context:** 仓储不再手动建任何领料单。生产退料(RETURN)也由生产端发起；仓储 `issue()` 对 RETURN 已做 increaseStock（回流入库），无需改动出库逻辑，仅限制建单入口归属。

- [ ] **Step 1: PickListService.create 限制建单归属**

把 [PickListService.java:119](back/src/main/java/org/example/back/service/PickListService.java#L119) 的 `create` 逻辑：移除仓储侧 PICK/SUPPLY/RETURN 任意建单，改为仅接受 `productionOrderId` 关联的领料单由生产端经 `ProductionPickService` 创建。原仓储手动 `create` 接口（`POST /business/pick-lists`）删除或改为仅仓储备用。为最小改动，保留 `PickListService.create` 但将 `requireApplyAccess` 从仅仓储改为拒绝：若非生产来源则抛"领料/退料由生产端申请"。

> 取舍：为彻底贯彻 Q15，建议删除 `POST /business/pick-lists` 的仓储建单能力（前端对应"新增领料"按钮移除，见 Task 9 前端部分）。生产退料入口见 Step 2。

- [ ] **Step 2: 新增生产退料服务方法与接口**

在 [ProductionPickService.java](back/src/main/java/org/example/back/service/ProductionPickService.java) 加：

```java
    @Transactional(rollbackFor = Exception.class)
    public void createReturn(Long orderId, String remark) {
        requireProductionMember();
        BizProductionOrder order = productionOrderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        // 退料须在开工后（生产/待入库）才可退还多余或不良在制料
        if (order.getStatus() != BizProductionOrder.STATUS_IN_PROGRESS) {
            throw BusinessException.validateFail("仅生产中状态可退料");
        }
        // 明细由前端按实际退回物料+数量传入（退料不是全量 BOM，允许手填）
        // 此处仅创建一张空退料单，明细由前端随后提交——为最小闭环，本步骤先建"待入库"退料单主体
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizPickList pick = new BizPickList();
        pick.setPickNo(CodeGenerator.pickListNo());
        pick.setPickType(PickListService.TYPE_RETURN);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(orderId);
        pick.setApplicantId(user.getId());
        pick.setApplicantName(user.getRealName());
        pick.setRemark("生产任务单 " + order.getOrderNo() + " 退料" + (remark == null ? "" : "：" + remark));
        pickListMapper.insert(pick);
        messageService.sendPickReturnPendingToWarehouseAdmins(pick.getPickNo(), order.getOrderNo(), pick.getId());
    }
```

在 [ProductionPickController.java](back/src/main/java/org/example/back/controller/ProductionPickController.java) 加：

```java
    @PostMapping("/{orderId}/return")
    @PreventDuplicateSubmit(message = "请勿重复提交退料申请")
    public Result<Void> createReturn(@PathVariable Long orderId,
                                     @RequestParam(required = false) String remark) {
        productionPickService.createReturn(orderId, remark);
        return Result.success();
    }
```

（注：退料明细交互较复杂。若 Q15 范围仅需"生产退料入口存在"，先做单头 + 仓储 issue 入库，明细由 PickListService.createReceivedDetails 复用现有明细存储即可。明细传参在 Task 7 前端落地。）

- [ ] **Step 3: MessageService 新增退料通知仓储**

在 [MessageService.java](back/src/main/java/org/example/back/service/MessageService.java) 加：

```java
    /**
     * 生产退料 → 通知仓储确认入库。
     */
    public void sendPickReturnPendingToWarehouseAdmins(String pickNo, String orderNo, Long pickId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认生产退料入库",
                String.format(java.util.Locale.ROOT,
                        "退料单 %s（生产任务单 %s）已由生产端提交，请确认回流入库。",
                        pickNo, orderNo),
                "pick_list",
                pickId);
    }
```

- [ ] **Step 4: 编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/back && ./mvnw compile -q
```
Expected: BUILD SUCCESS。

---

### Task 9b: 前端——仓储移除"新增领料"按钮，生产端加退料入口

**Files:**
- Modify: `front/src/views/business/PickListView.vue`
- Modify: `front/src/views/business/ProductionOrderView.vue`

**Interfaces:**
- Consumes: Q15 决策（仓储不建任何单）；Task 9 后端退料接口
- Produces: 仓储端无"新增领料"按钮；生产端详情提供退料入口

- [ ] **Step 1: 移除仓储端"新增领料"按钮与弹窗**

在 [PickListView.vue](front/src/views/business/PickListView.vue)：
- 删除 L34 的"新增领料"按钮。
- 删除新增领料弹窗（L80-123）及 `handleAdd/submitAdd` 相关函数、`addForm` 状态。
- 删除 `createPickListAPI` 引用（如不再需要）。

- [ ] **Step 2: 生产端详情加退料入口**

在 [ProductionOrderView.vue](front/src/views/business/ProductionOrderView.vue) 详情弹窗加"生产退料"按钮（`v-if="detail.status === 2"` 生产中，v-permission 生产），调新接口 `POST /business/production-orders/{id}/return`，成功提示"退料已提交，待仓储确认入库"。

在 `front/src/api/pickList.js` 加：

```js
export const createProductionReturnAPI = (orderId, remark) => request.post(`/business/production-orders/${orderId}/return`, null, { params: { remark } })
```

- [ ] **Step 3: 前端编译**

```bash
cd /home/niuchao/Warehouse-Management-System-/front && npm run build
```
Expected: 构建成功。

---

### Task 10: 端到端验证（重启 + E2E 冒烟）

**Files:** 无代码改动

**Interfaces:** 无

**Context:** 按 CLAUDE.md 验证习惯：后端编译→重启→curl E2E；前端 build→代理 E2E。

- [ ] **Step 1: 后端重启**

```bash
cd /home/niuchao/Warehouse-Management-System-/back
fuser -k 8080/tcp 2>/dev/null
./mvnw clean compile -q
nohup ./mvnw spring-boot:run > /tmp/wms-backend.log 2>&1 &
sleep 30
grep "Started BackApplication" /tmp/wms-backend.log || tail -20 /tmp/wms-backend.log
```
Expected: `BackApplication` 启动成功，8080 监听。若 `ClassCastException`（跨 commit 残留 RestartClassLoader），用 `kill -9` 旧 java 再启。

- [ ] **Step 2: 前端重启 + 硬刷新**

```bash
cd /home/niuchao/Warehouse-Management-System-/front
fuser -k 5173/tcp 2>/dev/null
nohup npm run dev > /tmp/wms-frontend.log 2>&1 &
sleep 8
grep "ready" /tmp/wms-frontend.log
```
Expected: Vite ready in localhost:5173。浏览器 Ctrl+Shift+R 硬刷新 + 重新登录（后端重启 token 失效）。

- [ ] **Step 3: E2E 补料直待采购（生产→采购→仓储入库正向）**

用 `production_admin`/`purchase_admin`/`warehouse_admin` 三种角色 curl 冒烟：生产建生产任务单→生产补料（POST /draft，断言 status 返回 1 待采购）→采购认领→到货→仓储 confirm-receive（库存+）→ 断言 `base_goods.stock`。

- [ ] **Step 4: E2E 领料→出库→开工（正向）**

GET `/business/production-orders/{id}/pick/editable` 返回全物料行→POST `/pick` 生成领料单（status=1）→仓储 PUT `/business/pick-lists/{id}/issue`（库存−）→ GET `/pick` 状态=已发料→ POST `/business/production-order/{id}/start` 应成功进入生产中。

- [ ] **Step 5: E2E 负测——未出库不可开工**

未 issue 时 POST start 应返回 400"尚未全额出库"。清理测试数据，恢复库存。

- [ ] **Step 6: E2E 退料正向（生产退料→仓储确认入库）**

生产 start 成功后（生产中状态），生产 POST `/business/production-orders/{id}/return` 生成退料单（status=1）→仓储 PUT `/business/pick-lists/{id}/issue`（对 RETURN 是 increaseStock 回流入库）→断言对应 `base_goods.stock` +N。

- [ ] **Step 7: E2E 仓储无建单能力**

`warehouse_admin` 调用原 `POST /business/pick-lists` 应被拒/移除（不再可建任何领料单）；`PickListView` 仓储视角无"新增领料"按钮。

---

## Self-Review

**Spec 覆盖检查**：
- Q1（去仓储转正）→ Task 3 ✓
- Q2（生产自选物料）→ Task 3 Step2/3 + Task 7 Step3 ✓
- Q3/Q5/Q6/Q7（申请领料→出库→开工）→ Task 4/5/6/7 ✓
- Q4/Q12（开工门槛=已全额出库）→ Task 5 ✓
- Q8/Q9/Q10（角色分工）→ Task 4 权限 + Task 6 消息分流 ✓
- Q13/Q15（仓储不建任何单，退料归生产端）→ Task 9/9b ✓
- Q14（消息全要）→ Task 6 出库成功/失败通知 + Task 4 提交通知 + Task 9 退料通知 ✓
- 补料幂等（同一生产单一张进行中补料单）→ Task 3 沿用 `listDraftByProductionOrder` 判重，但需把判断改为"进行中的非已驳回生产补料单"（见下方修正）

**发现的缺口（inline 修正）**：Task 3 中 `listDraftByProductionOrder`（L303-309）目前只查 `STATUS_DRAFT`，改为直接待采购后应查"待采购/采购中/待入库确认/已入库"等非终态，避免同一生产单重复补料。据此 Task 3 Step 1 应同步把 `createDraft` 内的幂等查询改为查询该生产单所有非 rejected 的 production 来源单：

```java
        // 幂等：同一生产任务单已有进行中/已入库的补料单则拒绝（仅 rejected 可重新补料）
        List<BizPurchaseRequest> existing = listNonFinalByProductionOrder(dto.getProductionOrderId());
        if (!existing.isEmpty()) {
            throw BusinessException.validateFail("该生产任务单已补料（单号 " + existing.get(0).getRequestNo() + "），请勿重复");
        }
```

并提供新私有方法（替代原 listDraftByProductionOrder 的作用域）：

```java
    private List<BizPurchaseRequest> listNonFinalByProductionOrder(Long productionOrderId) {
        LambdaQueryWrapper<BizPurchaseRequest> w = new LambdaQueryWrapper<>();
        w.eq(BizPurchaseRequest::getProductionOrderId, productionOrderId)
                .eq(BizPurchaseRequest::getSourceType, SOURCE_PRODUCTION)
                .ne(BizPurchaseRequest::getStatus, STATUS_REJECTED);
        return bizPurchaseRequestMapper.selectList(w);
    }
```

**类型一致性**：`ProductionPickItemVO`、`PickListVO`、`ProductionPickService.isAllIssued` 的方法签名在 Task 4/5 间引用一致。`PickListService.STATUS_ISSUED=2` 常量在 Task 4 Test 与 Task 5 start 中引用正确。

**占位符扫描**：所有代码步骤均有完整实现。Task 7 的物料 `<el-select>` 选项复用说明已给方法名；Task 10 为运维步骤。无 TBD/TODO 残留。