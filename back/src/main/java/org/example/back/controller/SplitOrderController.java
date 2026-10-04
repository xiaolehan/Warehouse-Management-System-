package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.SplitOrderCreateDTO;
import org.example.back.dto.SplitOrderQueryDTO;
import org.example.back.dto.SplitOrderVoidDTO;
import org.example.back.dto.SplitReturnSubmitDTO;
import org.example.back.service.SplitOrderService;
import org.example.back.vo.SplitOrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 成品拆分单（ADR-0020，D116）：仓储发起/确认出库/确认回库/作废/保留成品；
 * 生产领取/确认收货/放弃拆分/提交退料。RETURN 收发料走既有领料单接口。
 */
@RestController
@RequestMapping("/business/split-orders")
public class SplitOrderController {

    @Autowired
    private SplitOrderService splitOrderService;

    @GetMapping("/page")
    public Result<PageResult<SplitOrderVO>> page(SplitOrderQueryDTO queryDTO) {
        return Result.success(splitOrderService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<SplitOrderVO> getById(@PathVariable Long id) {
        return Result.success(splitOrderService.getById(id));
    }

    /** 仓储发起拆分 */
    @PostMapping
    @AuditLog(module = "成品拆分", action = "发起拆分", targetType = "成品拆分单",
            detail = "'发起成品拆分，明细行 #' + #dto.salesDetailId + '，数量：' + #dto.quantity")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交发起请求")
    public Result<Void> create(@Valid @RequestBody SplitOrderCreateDTO dto) {
        splitOrderService.create(dto);
        return Result.success();
    }

    /** 保留成品（行处置二选一之一） */
    @PutMapping("/keep/{salesDetailId}")
    @AuditLog(module = "成品拆分", action = "保留成品", targetType = "销售明细行")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交保留请求")
    public Result<Void> keepProduct(@PathVariable Long salesDetailId) {
        splitOrderService.keepProduct(salesDetailId);
        return Result.success();
    }

    /** 生产领取（1→2） */
    @PutMapping("/{id}/claim")
    @AuditLog(module = "成品拆分", action = "领取", targetType = "成品拆分单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交领取请求")
    public Result<Void> claim(@PathVariable Long id) {
        splitOrderService.claim(id);
        return Result.success();
    }

    /** 仓储确认成品出库（2→3，成品库存扣减） */
    @PutMapping("/{id}/confirm-outbound")
    @AuditLog(module = "成品拆分", action = "确认成品出库", targetType = "成品拆分单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交出库确认请求")
    public Result<Void> confirmOutbound(@PathVariable Long id) {
        splitOrderService.confirmOutbound(id);
        return Result.success();
    }

    /** 生产确认收货（3→4） */
    @PutMapping("/{id}/confirm-receipt")
    @AuditLog(module = "成品拆分", action = "确认收货", targetType = "成品拆分单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交收货确认请求")
    public Result<Void> confirmReceipt(@PathVariable Long id) {
        splitOrderService.confirmReceipt(id);
        return Result.success();
    }

    /** 生产放弃拆分（1/2→5 直接完成；3/4→7 待仓储确认回库） */
    @PutMapping("/{id}/abandon")
    @AuditLog(module = "成品拆分", action = "放弃拆分", targetType = "成品拆分单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交放弃请求")
    public Result<Void> abandon(@PathVariable Long id) {
        splitOrderService.abandon(id);
        return Result.success();
    }

    /** 拆分中提交 RETURN 退料（仓储确认后拆分完成） */
    @PostMapping("/{id}/submit-return")
    @AuditLog(module = "成品拆分", action = "提交拆分退料", targetType = "成品拆分单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交退料请求")
    public Result<Void> submitReturn(@PathVariable Long id, @Valid @RequestBody SplitReturnSubmitDTO dto) {
        splitOrderService.submitReturn(id, dto);
        return Result.success();
    }

    /** 仓储确认成品回库（7→5，完成方式=放弃回库，成品库存恢复） */
    @PutMapping("/{id}/confirm-restock")
    @AuditLog(module = "成品拆分", action = "确认成品回库", targetType = "成品拆分单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交回库确认请求")
    public Result<Void> confirmRestock(@PathVariable Long id) {
        splitOrderService.confirmRestock(id);
        return Result.success();
    }

    /** 仓储作废（仅限未动库存的 1/2 态） */
    @PutMapping("/{id}/void")
    @AuditLog(module = "成品拆分", action = "作废", targetType = "成品拆分单",
            detail = "'作废成品拆分单 #' + #id + '，原因：' + #dto.reason")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交作废请求")
    public Result<Void> voidOrder(@PathVariable Long id, @Valid @RequestBody SplitOrderVoidDTO dto) {
        splitOrderService.voidOrder(id, dto);
        return Result.success();
    }
}
