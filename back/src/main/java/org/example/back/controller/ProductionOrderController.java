package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.ExpectedCompletionDTO;
import org.example.back.dto.ProductionBatchReleaseDTO;
import org.example.back.dto.ProductionOrderQueryDTO;
import org.example.back.dto.ProductionOrderSaveDTO;
import org.example.back.service.ProductionOrderService;
import org.example.back.service.ProductionStepService;
import org.example.back.vo.ProductionOrderVO;
import org.example.back.vo.ProductionReleasePreviewVO;
import org.example.back.vo.ProductionReleaseResultVO;
import org.example.back.vo.SalesSourceOptionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/business/production-order")
public class ProductionOrderController {

    @Autowired
    private ProductionOrderService productionOrderService;

    @Autowired
    private ProductionStepService productionStepService;

    @GetMapping("/page")
    public Result<PageResult<ProductionOrderVO>> page(ProductionOrderQueryDTO queryDTO) {
        return Result.success(productionOrderService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<ProductionOrderVO> getById(@PathVariable Long id) {
        return Result.success(productionOrderService.getById(id));
    }

    @PostMapping
    @PreventDuplicateSubmit(message = "请勿重复提交生产任务单")
    public Result<ProductionOrderVO> create(@Valid @RequestBody ProductionOrderSaveDTO dto) {
        return Result.success(productionOrderService.create(dto));
    }

    // ============================== 按销售单批量下达（D113） ==============================

    /** 可下达的销售单选项（正常且待出库，近 50 张） */
    @GetMapping("/batch-release/sales-options")
    public Result<List<SalesSourceOptionVO>> batchReleaseSalesOptions() {
        return Result.success(productionOrderService.batchReleaseSalesOptions());
    }

    /** 下达预览：销售单全部明细行的库存/BOM/在途单/已生产数量标注 */
    @GetMapping("/batch-release/preview")
    public Result<ProductionReleasePreviewVO> releasePreview(@RequestParam Long salesOrderId) {
        return Result.success(productionOrderService.releasePreview(salesOrderId));
    }

    /** 批量下达：按行生成任务单，无 BOM/在途冲突等行跳过不阻断其他行 */
    @PostMapping("/batch-release")
    @PreventDuplicateSubmit(message = "请勿重复提交批量下达")
    @AuditLog(module = "生产任务单", action = "按销售单批量下达", targetType = "生产任务单",
            detail = "'按销售单批量下达：销售单 #' + #dto.salesOrderId + '，' + #dto.items.size() + ' 行'")
    public Result<List<ProductionReleaseResultVO>> batchRelease(
            @Valid @RequestBody ProductionBatchReleaseDTO dto) {
        return Result.success(productionOrderService.batchRelease(dto));
    }

    @PostMapping("/{id}/start")
    @PreventDuplicateSubmit(message = "请勿重复开工")
    public Result<Void> start(@PathVariable Long id) {
        productionOrderService.start(id);
        return Result.success();
    }

    @PostMapping("/{id}/complete")
    @PreventDuplicateSubmit(message = "请勿重复完工")
    public Result<Void> complete(@PathVariable Long id) {
        productionOrderService.complete(id);
        return Result.success();
    }

    @PostMapping("/{id}/receipt")
    @PreventDuplicateSubmit(message = "请勿重复提交入库申请")
    public Result<Void> receipt(@PathVariable Long id) {
        productionOrderService.receipt(id);
        return Result.success();
    }

    /** D107：撤销入库申请（仓储确认/驳回前可撤，撤未读待办） */
    @PostMapping("/{id}/receipt-cancel")
    @PreventDuplicateSubmit(message = "请勿重复提交撤销请求")
    @AuditLog(module = "生产任务单", action = "撤销入库申请", targetType = "生产任务单",
            detail = "'撤销入库申请：生产任务单 #' + #id")
    public Result<Void> cancelReceipt(@PathVariable Long id) {
        productionOrderService.cancelReceipt(id);
        return Result.success();
    }

    // ============================== 工序打卡（D64） ==============================

    /** 人工工序打卡（生产部门成员） */
    @PostMapping("/{id}/steps/{stepNo}/complete")
    @PreventDuplicateSubmit(message = "请勿重复提交工序打卡")
    @AuditLog(module = "生产工序", action = "打卡", targetType = "生产任务单",
            detail = "'工序打卡：生产任务单 #' + #id + '，第 ' + #stepNo + ' 道'")
    public Result<Void> completeStep(@PathVariable Long id, @PathVariable Integer stepNo) {
        productionStepService.complete(id, stepNo);
        return Result.success();
    }

    /** 撤销打卡（本人或生产管理员） */
    @PostMapping("/{id}/steps/{stepNo}/revoke")
    @PreventDuplicateSubmit(message = "请勿重复提交撤销打卡")
    @AuditLog(module = "生产工序", action = "撤销打卡", targetType = "生产任务单",
            detail = "'撤销打卡：生产任务单 #' + #id + '，第 ' + #stepNo + ' 道'")
    public Result<Void> revokeStep(@PathVariable Long id, @PathVariable Integer stepNo) {
        productionStepService.revoke(id, stepNo);
        return Result.success();
    }

    @PostMapping("/{id}/void")
    @PreventDuplicateSubmit(message = "请勿重复作废")
    public Result<Void> voidOrder(@PathVariable Long id,
                                  @RequestParam(required = false) String reason) {
        productionOrderService.voidOrder(id, reason);
        return Result.success();
    }

    /** D71：生产手工修正预计完工时间（仅未完结单；留痕 @AuditLog） */
    @PutMapping("/{id}/expected-completion")
    @AuditLog(module = "生产任务单", action = "修正预计完工", targetType = "生产任务单",
            detail = "'修正预计完工：生产任务单 #' + #id + ' → ' + #dto.expectedCompletionTime")
    public Result<Void> updateExpectedCompletion(@PathVariable Long id,
                                                 @RequestBody ExpectedCompletionDTO dto) {
        productionOrderService.updateExpectedCompletion(id, dto.getExpectedCompletionTime());
        return Result.success();
    }
}