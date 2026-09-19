package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.GoodsQueryDTO;
import org.example.back.dto.GoodsSaveDTO;
import org.example.back.service.GoodsService;
import org.example.back.vo.GoodsOptionVO;
import org.example.back.vo.GoodsPurchaseHistoryVO;
import org.example.back.vo.GoodsVO;
import org.example.back.vo.OptionVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/base/goods")
public class GoodsController {

    @Autowired
    private GoodsService goodsService;

    @GetMapping("/page")
    public Result<PageResult<GoodsVO>> page(GoodsQueryDTO queryDTO) {
        return Result.success(goodsService.page(queryDTO));
    }

    @GetMapping("/options")
    public Result<List<GoodsOptionVO>> options(@RequestParam(required = false) String type,
                                               @RequestParam(required = false) Boolean hasBom) {
        return Result.success(goodsService.options(type, hasBom));
    }

    @GetMapping("/{id}")
    public Result<GoodsVO> getById(@PathVariable Long id) {
        return Result.success(goodsService.getById(id));
    }

    // D102：进价历史——该物料全部有效已入库采购记录（仅采购部门成员/超管，服务端把关）
    @GetMapping("/{id}/purchase-price-history")
    public Result<List<GoodsPurchaseHistoryVO>> purchasePriceHistory(@PathVariable Long id) {
        return Result.success(goodsService.purchasePriceHistory(id));
    }

    @PostMapping
    @PreventDuplicateSubmit(message = "请勿重复提交商品新增请求")
    public Result<Void> create(@Valid @RequestBody GoodsSaveDTO dto) {
        goodsService.create(dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    @PreventDuplicateSubmit(message = "请勿重复提交商品编辑请求")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody GoodsSaveDTO dto) {
        goodsService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreventDuplicateSubmit(intervalMs = 1000, message = "删除请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        goodsService.delete(id);
        return Result.success();
    }
}