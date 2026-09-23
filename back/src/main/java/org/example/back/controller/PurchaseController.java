package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.PurchaseQueryDTO;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.service.PurchaseService;
import org.example.back.service.DocumentTimelineService;
import org.example.back.vo.BatchDeleteResultVO;
import org.example.back.vo.PurchaseSourceOptionVO;
import org.example.back.vo.DocumentTimelineVO;
import org.example.back.vo.PurchaseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/business/purchases")
public class PurchaseController {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private DocumentTimelineService documentTimelineService;

    @GetMapping("/page")
    public Result<PageResult<PurchaseVO>> page(PurchaseQueryDTO queryDTO) {
        return Result.success(purchaseService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<PurchaseVO> getById(@PathVariable Long id) {
        return Result.success(purchaseService.getById(id));
    }

    // D104：单据流程时间线——谁在哪一步做了什么（与详情同读权限口径，守卫在 Service 内）
    @GetMapping("/{id}/timeline")
    public Result<DocumentTimelineVO> timeline(@PathVariable Long id) {
        return Result.success(documentTimelineService.getPurchaseTimeline(id));
    }

    @GetMapping("/options/returnable")
    public Result<List<PurchaseSourceOptionVO>> returnableOptions(@RequestParam(required = false) Long goodsId) {
        return Result.success(purchaseService.returnableOptions(goodsId));
    }

    /** D124：批量「最近成交价」（已入库+正常最新明细价→标准进价→null），供到货提交预填单价 */
    @GetMapping("/latest-prices")
    public Result<Map<Long, BigDecimal>> latestPrices(@RequestParam(required = false) List<Long> goodsIds) {
        return Result.success(purchaseService.latestPrices(goodsIds));
    }

    @PostMapping
    @PreventDuplicateSubmit(intervalMs = 1800, message = "请勿重复提交进货单")
    public Result<Void> create(@Valid @RequestBody PurchaseSaveDTO dto) {
        purchaseService.create(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @AuditLog(module = "进货管理", action = "删除", targetType = "进货单")
    @PreventDuplicateSubmit(intervalMs = 1200, message = "删除请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        purchaseService.delete(id);
        return Result.success();
    }

    @PostMapping("/batch-delete")
    @PreventDuplicateSubmit(intervalMs = 1000, message = "删除请求过于频繁，请稍后再试")
    public Result<BatchDeleteResultVO> batchDelete(@RequestBody List<Long> ids) {
        return Result.success(purchaseService.batchDelete(ids));
    }

    @PutMapping("/{id}/void")
    @AuditLog(module = "进货管理", action = "作废", targetType = "进货单",
            detail = "'作废 进货单 #' + #id + (#dto?.reason != null ? '，原因：' + #dto.reason : '')")
    @RequireAdmin("仅管理员可作废进货单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交作废请求")
    public Result<Void> voidDocument(@PathVariable Long id, @RequestBody(required = false) DocumentVoidDTO dto) {
        purchaseService.voidDocument(id, dto);
        return Result.success();
    }

    @PutMapping("/{id}/arrive")
    @AuditLog(module = "进货管理", action = "到货确认", targetType = "进货单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交到货确认")
    public Result<Void> arrive(@PathVariable Long id) {
        purchaseService.arrive(id);
        return Result.success();
    }

    @PutMapping("/{id}/confirm-receive")
    @AuditLog(module = "进货管理", action = "确认入库", targetType = "进货单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交确认入库请求")
    public Result<Void> confirmReceive(@PathVariable Long id) {
        purchaseService.confirmReceive(id);
        return Result.success();
    }
}
