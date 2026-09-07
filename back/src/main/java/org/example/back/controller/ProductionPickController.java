package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.Result;
import org.example.back.dto.ProductionReturnCreateDTO;
import org.example.back.service.ProductionPickService;
import org.example.back.vo.PickListVO;
import org.example.back.vo.ProductionPickItemVO;
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
    public Result<Void> createReturn(@PathVariable Long orderId, @Valid @RequestBody(required = false) ProductionReturnCreateDTO dto) {
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
}
