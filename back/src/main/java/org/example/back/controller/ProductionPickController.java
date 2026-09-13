package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.Result;
import org.example.back.dto.ProductionReturnCreateDTO;
import org.example.back.dto.ProductionTerminateDTO;
import org.example.back.service.ProductionPickService;
import org.example.back.vo.PickListVO;
import org.example.back.vo.ProductionPickItemVO;
import org.example.back.vo.ProductionReturnableVO;
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

    @PostMapping("/{orderId}/return")
    @PreventDuplicateSubmit(message = "请勿重复提交退料申请")
    public Result<Void> createReturn(@PathVariable Long orderId, @Valid @RequestBody ProductionReturnCreateDTO dto) {
        productionPickService.createReturn(orderId, dto);
        return Result.success();
    }

    @GetMapping("/{orderId}/pick")
    public Result<List<PickListVO>> listPicks(@PathVariable Long orderId) {
        return Result.success(productionPickService.listByOrder(orderId));
    }

    @GetMapping("/{orderId}/pick/editable")
    public Result<List<ProductionPickItemVO>> editableItems(@PathVariable Long orderId) {
        return Result.success(productionPickService.editableItems(orderId));
    }

    /** D73：终止弹窗「已领未退」预览（生产成员可读） */
    @GetMapping("/{orderId}/returnable")
    public Result<ProductionReturnableVO> returnable(@PathVariable Long orderId) {
        return Result.success(productionPickService.computeReturnablePreview(orderId));
    }

    /** D73：手动终止生产任务单（仅生产管理员；同事务生成终止退料单） */
    @PostMapping("/{orderId}/terminate")
    @PreventDuplicateSubmit(message = "请勿重复提交终止")
    @AuditLog(module = "生产任务单", action = "终止", targetType = "生产任务单",
            detail = "'终止生产任务单 #' + #orderId + '，退料 ' + #dto.items?.size() + ' 行，原因：' + #dto.reason")
    public Result<Void> terminate(@PathVariable Long orderId, @Valid @RequestBody ProductionTerminateDTO dto) {
        productionPickService.terminate(orderId, dto);
        return Result.success();
    }
}
