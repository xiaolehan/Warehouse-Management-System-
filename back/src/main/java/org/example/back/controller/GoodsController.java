package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.GoodsQueryDTO;
import org.example.back.dto.GoodsSaveDTO;
import org.example.back.dto.QuickProductDTO;
import org.example.back.service.GoodsService;
import org.example.back.vo.BatchDeleteResultVO;
import org.example.back.vo.GoodsOptionVO;
import org.example.back.vo.GoodsPurchaseHistoryVO;
import org.example.back.vo.GoodsVO;
import org.example.back.vo.QuickProductVO;
import org.example.back.vo.OptionVO;
import org.example.back.vo.SupplierMatchVO;
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

    // D129：批量查询物料「最新供应商」——采购申请全链参考列 + 到货提交预填绑定值（口径同商品资料页）
    @GetMapping("/latest-suppliers")
    public Result<java.util.Map<Long, org.example.back.vo.GoodsLatestSupplierVO>> latestSuppliers(
            @RequestParam java.util.List<Long> ids) {
        return Result.success(goodsService.computeLatestSuppliers(ids));
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

    // D121：销售建单内嵌「+新品」快速建品（ADR-0017）——同名成品直接选用（existing=true），字段服务端强制
    @PostMapping("/quick-product")
    @PreventDuplicateSubmit(message = "请勿重复提交快速建品请求")
    public Result<QuickProductVO> quickCreateProduct(@Valid @RequestBody QuickProductDTO dto) {
        return Result.success(goodsService.quickCreateProduct(dto));
    }

    @PutMapping("/{id}")
    @PreventDuplicateSubmit(message = "请勿重复提交商品编辑请求")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody GoodsSaveDTO dto) {
        goodsService.update(id, dto);
        return Result.success();
    }

    // D109：未知物料匹配供应商——仓储人工触发，按最新采购申请明细到货备注斜杠前的名称回绑
    @PostMapping("/{id}/match-supplier")
    @PreventDuplicateSubmit(message = "请勿重复提交匹配请求")
    @AuditLog(module = "物料管理", action = "匹配供应商", targetType = "物料",
            detail = "'为物料 #' + #id + '「' + #result.data?.goodsName + '」匹配供应商「' "
                    + "+ #result.data?.supplierName + '」（来源采购申请单：' + #result.data?.sourceRequestNo "
                    + "+ '，到货备注：' + #result.data?.sourceRemark + '）'")
    public Result<SupplierMatchVO> matchSupplier(@PathVariable Long id) {
        return Result.success(goodsService.matchSupplier(id));
    }

    @DeleteMapping("/{id}")
    @PreventDuplicateSubmit(intervalMs = 1000, message = "删除请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        goodsService.delete(id);
        return Result.success();
    }

    @PostMapping("/batch-delete")
    @PreventDuplicateSubmit(intervalMs = 1000, message = "删除请求过于频繁，请稍后再试")
    public Result<BatchDeleteResultVO> batchDelete(@RequestBody List<Long> ids) {
        return Result.success(goodsService.batchDelete(ids));
    }
}