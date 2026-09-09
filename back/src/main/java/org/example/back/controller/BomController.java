package org.example.back.controller;

import jakarta.validation.Valid;
import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.BomDetailDTO;
import org.example.back.dto.BomQueryDTO;
import org.example.back.dto.BomSaveDTO;
import org.example.back.service.BomService;
import org.example.back.service.WorkRequirementAttachmentStorageService;
import org.example.back.vo.BomDeleteCheckVO;
import org.example.back.vo.BomVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/base/bom")
public class BomController {

    private static final String XLSX_MEDIA =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private BomService bomService;

    @Autowired
    private WorkRequirementAttachmentStorageService storageService;

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

    /** D66：删除前检查——返回该成品名下未完结生产任务单数，前端据此事先二次确认 */
    @GetMapping("/{id}/delete-check")
    public Result<BomDeleteCheckVO> deleteCheck(@PathVariable Long id) {
        return Result.success(bomService.deleteCheck(id));
    }

    /**
     * D66 删除治理：未完结任务单存在时须带 force=true（前端二次确认后传入）；
     * 删除成功返回级联结果说明（成品主档已清理 / 保留原因）。
     */
    @DeleteMapping("/{id}")
    @PreventDuplicateSubmit(intervalMs = 1000, message = "删除请求过于频繁，请稍后再试")
    public Result<String> delete(@PathVariable Long id,
                                 @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        return Result.success(bomService.delete(id, force));
    }

    // ============================== 组件图片 ==============================

    /** 上传组件图片，返回 storedPath 与可预览 URL */
    @PostMapping("/image")
    public Result<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        String path = bomService.uploadComponentImage(file);
        String url = "/api/base/bom/image?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        return Result.success(Map.of("path", path, "url", url));
    }

    /** 访问组件图片 */
    @GetMapping("/image")
    public ResponseEntity<Resource> image(@RequestParam String path) {
        Resource resource = storageService.loadAsResource(path);
        return ResponseEntity.ok()
                .contentType(storageService.resolveMediaType(path))
                .body(resource);
    }

    // ============================== xlsx 导出 / 导入 / 模板 ==============================

    /** 导出单个成品(BOM)全部明细（含嵌入图片） */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) throws IOException {
        String filename = "BOM-" + id + ".xlsx";
        return fileResponse(bomService.exportBom(id), filename);
    }

    /** 下载空白 BOM 导入模板 */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() throws IOException {
        return fileResponse(bomService.downloadTemplate(), "BOM导入模板.xlsx");
    }

    /** 上传 xlsx 导入 BOM 明细（按成品名称匹配，覆盖该成品已有 BOM） */
    @PostMapping("/import")
    @PreventDuplicateSubmit(message = "请勿重复提交 BOM 导入请求")
    public Result<Map<String, Integer>> importBom(
            @RequestParam("file") MultipartFile file,
            @RequestParam("goodsName") String goodsName,
            @RequestParam(value = "bomCode", required = false) String bomCode) throws IOException {
        List<BomDetailDTO> rows = bomService.parseImportRows(file.getInputStream());
        int imported = bomService.importBom(goodsName, bomCode, rows);
        return Result.success(Map.of("imported", imported));
    }

    private ResponseEntity<byte[]> fileResponse(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''"
                                + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(bytes);
    }
}