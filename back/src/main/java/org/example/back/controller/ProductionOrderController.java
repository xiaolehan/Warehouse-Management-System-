package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.ProductionOrderQueryDTO;
import org.example.back.dto.ProductionOrderSaveDTO;
import org.example.back.service.ProductionOrderService;
import org.example.back.vo.ProductionOrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/business/production-order")
public class ProductionOrderController {

    @Autowired
    private ProductionOrderService productionOrderService;

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
    @PreventDuplicateSubmit(message = "请勿重复入库")
    public Result<Void> receipt(@PathVariable Long id) {
        productionOrderService.receipt(id);
        return Result.success();
    }

    @PostMapping("/{id}/void")
    @PreventDuplicateSubmit(message = "请勿重复作废")
    public Result<Void> voidOrder(@PathVariable Long id,
                                  @RequestParam(required = false) String reason) {
        productionOrderService.voidOrder(id, reason);
        return Result.success();
    }
}