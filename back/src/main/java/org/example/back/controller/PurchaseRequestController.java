package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.DraftConfirmDTO;
import org.example.back.dto.DraftRejectDTO;
import org.example.back.dto.ProductionDraftCreateDTO;
import org.example.back.dto.PurchaseRequestProcessDTO;
import org.example.back.dto.PurchaseRequestQueryDTO;
import org.example.back.dto.PurchaseRequestReceiveDTO;
import org.example.back.dto.PurchaseRequestRejectDTO;
import org.example.back.dto.PurchaseRequestSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.service.PurchaseRequestService;
import org.example.back.vo.PurchaseRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/business/purchase-requests")
public class PurchaseRequestController {

    @Autowired
    private PurchaseRequestService purchaseRequestService;

    @GetMapping("/page")
    public Result<PageResult<PurchaseRequestVO>> page(PurchaseRequestQueryDTO queryDTO) {
        return Result.success(purchaseRequestService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<PurchaseRequestVO> getById(@PathVariable Long id) {
        return Result.success(purchaseRequestService.getById(id));
    }

    /**
     * 缺货识别：返回 stock ≤ warning_stock 的启用商品清单。
     */
    @GetMapping("/shortage-goods")
    @RequireAdmin("仅管理员可查看缺货商品")
    public Result<List<BaseGoods>> shortageGoods() {
        return Result.success(purchaseRequestService.listShortageGoods());
    }

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

    @PostMapping("/{id}/cancel-draft")
    @RequireAdmin("仅生产研发部管理员可撤销自家补料草稿")
    @AuditLog(module = "采购申请", action = "撤销草稿", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交撤销请求")
    public Result<Void> cancelDraft(@PathVariable Long id) {
        purchaseRequestService.cancelDraft(id);
        return Result.success();
    }

    @PostMapping
    @RequireAdmin("仅仓储管理员可创建采购申请单")
    @AuditLog(module = "采购申请", action = "建单", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1800, message = "请勿重复提交采购申请单")
    public Result<Void> create(@Valid @RequestBody PurchaseRequestSaveDTO dto) {
        purchaseRequestService.create(dto);
        return Result.success();
    }

    @PutMapping("/{id}/process")
    @RequireAdmin("仅采购管理员可认领采购申请单")
    @AuditLog(module = "采购申请", action = "认领", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交认领请求")
    public Result<Void> process(@PathVariable Long id, @Valid @RequestBody PurchaseRequestProcessDTO dto) {
        purchaseRequestService.process(id, dto);
        return Result.success();
    }

    @PutMapping("/{id}/arrive")
    @RequireAdmin("仅采购管理员可提交采购到货")
    @AuditLog(module = "采购申请", action = "到货", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交到货请求")
    public Result<Void> arrive(@PathVariable Long id, @Valid @RequestBody PurchaseRequestReceiveDTO dto) {
        purchaseRequestService.arrive(id, dto);
        return Result.success();
    }

    @PutMapping("/{id}/confirm-receive")
    @RequireAdmin("仅仓储管理员可确认采购入库")
    @AuditLog(module = "采购申请", action = "确认入库", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交确认入库请求")
    public Result<Void> confirmReceive(@PathVariable Long id) {
        purchaseRequestService.confirmReceive(id);
        return Result.success();
    }

    @PutMapping("/{id}/arrive-cancel")
    @RequireAdmin("仅采购管理员可撤回到货")
    @AuditLog(module = "采购申请", action = "撤回到货", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1200, message = "撤回请求过于频繁，请稍后再试")
    public Result<Void> arriveCancel(@PathVariable Long id) {
        purchaseRequestService.arriveCancel(id);
        return Result.success();
    }

    @PutMapping("/{id}/arrive-reject")
    @RequireAdmin("仅仓储管理员可驳回入库")
    @AuditLog(module = "采购申请", action = "驳回入库", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1200, message = "驳回请求过于频繁，请稍后再试")
    public Result<Void> arriveReject(@PathVariable Long id) {
        purchaseRequestService.arriveReject(id);
        return Result.success();
    }

    @PutMapping("/{id}/reject")
    @RequireAdmin("仅采购管理员可驳回采购申请单")
    @AuditLog(module = "采购申请", action = "驳回", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交驳回请求")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody PurchaseRequestRejectDTO dto) {
        purchaseRequestService.reject(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @AuditLog(module = "采购申请", action = "撤销", targetType = "采购申请单")
    @PreventDuplicateSubmit(intervalMs = 1200, message = "撤销请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        purchaseRequestService.delete(id);
        return Result.success();
    }
}
