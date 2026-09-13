package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.SalesQueryDTO;
import org.example.back.dto.SalesSaveDTO;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.service.SalesService;
import org.example.back.service.SalesTimelineService;
import org.example.back.vo.SalesSourceOptionVO;
import org.example.back.vo.SalesTimelineVO;
import org.example.back.vo.SalesVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/business/sales")
public class SalesController {

    @Autowired
    private SalesService salesService;

    @Autowired
    private SalesTimelineService salesTimelineService;

    @GetMapping("/page")
    public Result<PageResult<SalesVO>> page(SalesQueryDTO queryDTO) {
        return Result.success(salesService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<SalesVO> getById(@PathVariable Long id) {
        return Result.success(salesService.getById(id));
    }

    @GetMapping("/options/returnable")
    public Result<List<SalesSourceOptionVO>> returnableOptions(@RequestParam(required = false) Long goodsId) {
        return Result.success(salesService.returnableOptions(goodsId));
    }

    /** D70：生产建单关联销售单下拉（该成品正常且待出库的销售单） */
    @GetMapping("/options/linkable")
    public Result<List<SalesSourceOptionVO>> linkableOptions(@RequestParam Long goodsId) {
        return Result.success(salesService.linkableOptions(goodsId));
    }

    /** D71：销售单履约时间线（下单→排产→物料→开工→装配→质检→入库→发货 + 预计可交付时间） */
    @GetMapping("/{id}/timeline")
    public Result<SalesTimelineVO> timeline(@PathVariable Long id) {
        return Result.success(salesTimelineService.getTimeline(id));
    }

    @PostMapping
    @PreventDuplicateSubmit(intervalMs = 1800, message = "请勿重复提交销售单")
    public Result<Void> create(@Valid @RequestBody SalesSaveDTO dto) {
        salesService.create(dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @AuditLog(module = "销售管理", action = "删除", targetType = "销售单")
    @PreventDuplicateSubmit(intervalMs = 1200, message = "删除请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        salesService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/void")
    @AuditLog(module = "销售管理", action = "作废/作废并红冲", targetType = "销售单",
            detail = "((#dto?.createRedFlush == true) ? '作废并红冲' : '作废') + ' 销售单 #' + #id + (#dto?.reason != null ? '，原因：' + #dto.reason : '')")
    @RequireAdmin("仅管理员可作废销售单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交作废请求")
    public Result<Void> voidDocument(@PathVariable Long id, @RequestBody(required = false) DocumentVoidDTO dto) {
        salesService.voidDocument(id, dto);
        return Result.success();
    }

    @PutMapping("/{id}/confirm")
    @AuditLog(module = "销售管理", action = "确认出库", targetType = "销售单")
    @RequireAdmin("仅仓储管理员可确认出库")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交确认请求")
    public Result<Void> confirm(@PathVariable Long id) {
        salesService.confirm(id);
        return Result.success();
    }
}
