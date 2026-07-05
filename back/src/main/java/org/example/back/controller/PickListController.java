package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.PickListQueryDTO;
import org.example.back.dto.PickListRejectDTO;
import org.example.back.dto.PickListSaveDTO;
import org.example.back.service.PickListService;
import org.example.back.vo.PickListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/business/pick-lists")
public class PickListController {

    @Autowired
    private PickListService pickListService;

    @GetMapping("/page")
    public Result<PageResult<PickListVO>> page(PickListQueryDTO queryDTO) {
        return Result.success(pickListService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<PickListVO> getById(@PathVariable Long id) {
        return Result.success(pickListService.getById(id));
    }

    @PostMapping
    @RequireAdmin("仅仓储管理员可申请领料")
    @PreventDuplicateSubmit(intervalMs = 1800, message = "请勿重复提交领料单")
    public Result<Void> create(@Valid @RequestBody PickListSaveDTO dto) {
        pickListService.create(dto);
        return Result.success();
    }

    @PutMapping("/{id}/issue")
    @RequireAdmin("仅仓储管理员可发料")
    @AuditLog(module = "生产领料", action = "发料", targetType = "领料单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交发料请求")
    public Result<Void> issue(@PathVariable Long id) {
        pickListService.issue(id);
        return Result.success();
    }

    @PutMapping("/{id}/confirm")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交确认请求")
    public Result<Void> confirm(@PathVariable Long id) {
        pickListService.confirm(id);
        return Result.success();
    }

    @PutMapping("/{id}/reject")
    @RequireAdmin("仅仓储管理员可驳回")
    @AuditLog(module = "生产领料", action = "驳回", targetType = "领料单")
    @PreventDuplicateSubmit(intervalMs = 1500, message = "请勿重复提交驳回请求")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody PickListRejectDTO dto) {
        pickListService.reject(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @AuditLog(module = "生产领料", action = "撤销", targetType = "领料单")
    @PreventDuplicateSubmit(intervalMs = 1200, message = "撤销请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        pickListService.delete(id);
        return Result.success();
    }
}
