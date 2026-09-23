package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.InboundRejectDTO;
import org.example.back.dto.ProductionQueryDTO;
import org.example.back.dto.ProductionSaveDTO;
import org.example.back.service.ProductionService;
import org.example.back.vo.BatchDeleteResultVO;
import org.example.back.vo.ProductionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/business/production")
public class ProductionController {

    @Autowired
    private ProductionService productionService;

    @GetMapping("/page")
    public Result<PageResult<ProductionVO>> page(ProductionQueryDTO queryDTO) {
        return Result.success(productionService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<ProductionVO> getById(@PathVariable Long id) {
        return Result.success(productionService.getById(id));
    }

    @PostMapping
    @PreventDuplicateSubmit(intervalMs = 1800, message = "请勿重复提交生产入库单")
    public Result<Void> create(@Valid @RequestBody ProductionSaveDTO dto) {
        productionService.create(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @AuditLog(module = "生产入库", action = "删除", targetType = "生产入库单")
    @PreventDuplicateSubmit(intervalMs = 1200, message = "删除请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        productionService.delete(id);
        return Result.success();
    }

    @PostMapping("/batch-delete")
    @PreventDuplicateSubmit(intervalMs = 1000, message = "删除请求过于频繁，请稍后再试")
    public Result<BatchDeleteResultVO> batchDelete(@RequestBody List<Long> ids) {
        return Result.success(productionService.batchDelete(ids));
    }

    @PutMapping("/{id}/void")
    @AuditLog(module = "生产入库", action = "作废", targetType = "生产入库单",
            detail = "'作废 生产入库单 #' + #id + (#dto?.reason != null ? '，原因：' + #dto.reason : '')")
    @RequireAdmin("仅管理员可作废生产入库单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交作废请求")
    public Result<Void> voidDocument(@PathVariable Long id, @RequestBody(required = false) DocumentVoidDTO dto) {
        productionService.voidDocument(id, dto);
        return Result.success();
    }

    /** D107：仓储确认成品入库（此刻才加库存 + 生产任务单转已完成） */
    @PutMapping("/{id}/confirm-inbound")
    @AuditLog(module = "生产入库", action = "确认入库", targetType = "生产入库单",
            detail = "'确认入库：生产入库单 #' + #id")
    @RequireAdmin("仅仓储管理员可确认成品入库")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交确认请求")
    public Result<Void> confirmInbound(@PathVariable Long id) {
        productionService.confirmInbound(id);
        return Result.success();
    }

    /** D107：仓储驳回入库申请（附原因，通知生产提交人） */
    @PutMapping("/{id}/reject-inbound")
    @AuditLog(module = "生产入库", action = "驳回入库申请", targetType = "生产入库单",
            detail = "'驳回入库申请：生产入库单 #' + #id + '，原因：' + #dto.reason")
    @RequireAdmin("仅仓储管理员可驳回入库申请")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交驳回请求")
    public Result<Void> rejectInbound(@PathVariable Long id, @Valid @RequestBody InboundRejectDTO dto) {
        productionService.rejectInbound(id, dto.getReason());
        return Result.success();
    }
}
