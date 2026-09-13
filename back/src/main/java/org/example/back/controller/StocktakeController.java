package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.StocktakeAssignDTO;
import org.example.back.dto.StocktakeCancelDTO;
import org.example.back.dto.StocktakeCreateDTO;
import org.example.back.dto.StocktakeEntryDTO;
import org.example.back.dto.StocktakeQueryDTO;
import org.example.back.dto.StocktakeRejectDTO;
import org.example.back.service.StocktakeService;
import org.example.back.vo.StocktakeAssigneeOptionVO;
import org.example.back.vo.StocktakeGoodsOptionVO;
import org.example.back.vo.StocktakeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 库存盘点（阶段 23，ADR-0010）：无删除端点，只有取消（D83）。
 */
@RestController
@RequestMapping("/business/stocktake")
public class StocktakeController {

    private static final String XLSX_MEDIA =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private StocktakeService stocktakeService;

    @GetMapping("/page")
    public Result<PageResult<StocktakeVO>> page(StocktakeQueryDTO queryDTO) {
        return Result.success(stocktakeService.page(queryDTO));
    }

    @GetMapping("/goods-options")
    public Result<List<StocktakeGoodsOptionVO>> goodsOptions(@RequestParam(required = false) String type) {
        return Result.success(stocktakeService.goodsOptions(type));
    }

    @GetMapping("/{id}")
    public Result<StocktakeVO> getById(@PathVariable Long id) {
        return Result.success(stocktakeService.getById(id));
    }

    @PostMapping
    @RequireAdmin("仅仓储管理员可创建盘点单")
    @AuditLog(module = "库存盘点", action = "建单", targetType = "盘点单",
            detail = "'创建盘点单，商品数：' + #dto.items.size()")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复创建盘点单")
    public Result<Long> create(@Valid @RequestBody StocktakeCreateDTO dto) {
        return Result.success(stocktakeService.create(dto));
    }

    @GetMapping("/assignee-options")
    public Result<List<StocktakeAssigneeOptionVO>> assigneeOptions() {
        return Result.success(stocktakeService.assigneeOptions());
    }

    // D85：改派负责人（盘点中，admin 级）
    @PutMapping("/{id}/assign")
    @RequireAdmin("仅仓储管理员可改派负责人")
    @AuditLog(module = "库存盘点", action = "改派负责人", targetType = "盘点单",
            detail = "'盘点单 #' + #id + ' 明细 #' + #dto.detailId + ' 改派负责人为 #' + #dto.assigneeId")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复改派")
    public Result<Void> assign(@PathVariable Long id, @Valid @RequestBody StocktakeAssignDTO dto) {
        stocktakeService.assign(id, dto);
        return Result.success();
    }

    @PutMapping("/{id}/entry")
    @AuditLog(module = "库存盘点", action = "录实盘", targetType = "盘点单",
            detail = "'录入实盘数：盘点单 #' + #id + '，行数：' + #dto.items.size()")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交实盘数")
    public Result<Void> entry(@PathVariable Long id, @Valid @RequestBody StocktakeEntryDTO dto) {
        stocktakeService.entry(id, dto);
        return Result.success();
    }

    @PostMapping("/{id}/import")
    @AuditLog(module = "库存盘点", action = "导入实盘", targetType = "盘点单",
            detail = "'xlsx 回填实盘数：盘点单 #' + #id")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复导入")
    public Result<Integer> importEntry(@PathVariable Long id,
                                       @RequestParam("file") MultipartFile file) throws IOException {
        return Result.success(stocktakeService.importEntry(id, file));
    }

    // D84 修订：提交不挂 @RequireAdmin——仓储员工实盘完可直接送审（审核仍收口 admin）
    @PutMapping("/{id}/submit")
    @AuditLog(module = "库存盘点", action = "提交审核", targetType = "盘点单",
            detail = "'提交盘点单审核 #' + #id")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交")
    public Result<Void> submit(@PathVariable Long id) {
        stocktakeService.submit(id);
        return Result.success();
    }

    @PutMapping("/{id}/review")
    @RequireAdmin("仅仓储管理员可审核盘点单")
    @AuditLog(module = "库存盘点", action = "审核生效", targetType = "盘点单",
            detail = "'盘点审核生效 #' + #id")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复审核")
    public Result<Void> review(@PathVariable Long id) {
        stocktakeService.review(id);
        return Result.success();
    }

    @PutMapping("/{id}/reject")
    @RequireAdmin("仅仓储管理员可驳回盘点单")
    @AuditLog(module = "库存盘点", action = "驳回", targetType = "盘点单",
            detail = "'驳回盘点单 #' + #id + '，原因：' + #dto.reason")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复驳回")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody StocktakeRejectDTO dto) {
        stocktakeService.reject(id, dto);
        return Result.success();
    }

    @PutMapping("/{id}/cancel")
    @RequireAdmin("仅仓储管理员可取消盘点单")
    @AuditLog(module = "库存盘点", action = "取消", targetType = "盘点单",
            detail = "'取消盘点单 #' + #id")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复取消")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) StocktakeCancelDTO dto) {
        stocktakeService.cancel(id, dto);
        return Result.success();
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam(defaultValue = "true") boolean blind) throws IOException {
        String filename = "盘点表-" + id + "-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''"
                                + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(stocktakeService.export(id, blind));
    }
}
