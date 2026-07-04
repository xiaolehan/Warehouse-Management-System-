package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.ProductionQueryDTO;
import org.example.back.dto.ProductionSaveDTO;
import org.example.back.service.ProductionService;
import org.example.back.vo.ProductionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

    @PutMapping("/{id}/void")
    @AuditLog(module = "生产入库", action = "作废/作废并红冲", targetType = "生产入库单")
    @RequireAdmin("仅管理员可作废生产入库单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交作废请求")
    public Result<Void> voidDocument(@PathVariable Long id, @RequestBody(required = false) DocumentVoidDTO dto) {
        productionService.voidDocument(id, dto);
        return Result.success();
    }
}
