package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.BomQueryDTO;
import org.example.back.dto.BomSaveDTO;
import org.example.back.service.BomService;
import org.example.back.vo.BomVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/base/bom")
public class BomController {

    @Autowired
    private BomService bomService;

    @GetMapping("/page")
    public Result<PageResult<BomVO>> page(BomQueryDTO queryDTO) {
        return Result.success(bomService.page(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<BomVO> getById(@PathVariable Long id) {
        return Result.success(bomService.getById(id));
    }

    @PostMapping
    @PreventDuplicateSubmit(message = "请勿重复提交 BOM 新增请求")
    public Result<Void> create(@Valid @RequestBody BomSaveDTO dto) {
        bomService.create(dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    @PreventDuplicateSubmit(message = "请勿重复提交 BOM 编辑请求")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BomSaveDTO dto) {
        bomService.update(id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreventDuplicateSubmit(intervalMs = 1000, message = "删除请求过于频繁，请稍后再试")
    public Result<Void> delete(@PathVariable Long id) {
        bomService.delete(id);
        return Result.success();
    }
}