package org.example.back.controller;

import org.example.back.common.result.PageResult;
import org.example.back.common.result.Result;
import org.example.back.dto.LoginLogQueryDTO;
import org.example.back.dto.OperationLogQueryDTO;
import org.example.back.entity.SysLoginLog;
import org.example.back.entity.SysOperationLog;
import org.example.back.service.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/system/audit")
public class AuditController {

    private static final String XLSX_MEDIA = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private AuditService auditService;

    @GetMapping("/login-logs/page")
    public Result<PageResult<SysLoginLog>> loginLogPage(LoginLogQueryDTO queryDTO) {
        return Result.success(auditService.loginLogPage(queryDTO));
    }

    @GetMapping("/login-logs/{id}")
    public Result<SysLoginLog> loginLogDetail(@PathVariable Long id) {
        return Result.success(auditService.getLoginLogById(id));
    }

    @GetMapping("/operation-logs/page")
    public Result<PageResult<SysOperationLog>> operationLogPage(OperationLogQueryDTO queryDTO) {
        return Result.success(auditService.operationLogPage(queryDTO));
    }

    @GetMapping("/operation-logs/{id}")
    public Result<SysOperationLog> operationLogDetail(@PathVariable Long id) {
        return Result.success(auditService.getOperationLogById(id));
    }

    // ==================== D78：日志手动删除（仅超管，物理删除，删除动作留痕） ====================

    @DeleteMapping("/operation-logs/{id}")
    public Result<Integer> deleteOperationLog(@PathVariable Long id) {
        return Result.success(auditService.deleteOperationLog(id));
    }

    @PostMapping("/operation-logs/batch-delete")
    public Result<Integer> deleteOperationLogs(@RequestBody List<Long> ids) {
        return Result.success(auditService.deleteOperationLogs(ids));
    }

    @PostMapping("/operation-logs/delete-by-query")
    public Result<Integer> deleteOperationLogsByQuery(@RequestBody OperationLogQueryDTO queryDTO) {
        return Result.success(auditService.deleteOperationLogsByQuery(queryDTO));
    }

    @DeleteMapping("/login-logs/{id}")
    public Result<Integer> deleteLoginLog(@PathVariable Long id) {
        return Result.success(auditService.deleteLoginLog(id));
    }

    @PostMapping("/login-logs/batch-delete")
    public Result<Integer> deleteLoginLogs(@RequestBody List<Long> ids) {
        return Result.success(auditService.deleteLoginLogs(ids));
    }

    @PostMapping("/login-logs/delete-by-query")
    public Result<Integer> deleteLoginLogsByQuery(@RequestBody LoginLogQueryDTO queryDTO) {
        return Result.success(auditService.deleteLoginLogsByQuery(queryDTO));
    }

    // ==================== D79：操作日志 xlsx 导出（跟随筛选条件） ====================

    @GetMapping("/operation-logs/export")
    public ResponseEntity<byte[]> exportOperationLogs(OperationLogQueryDTO queryDTO) throws IOException {
        String filename = "操作日志-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(auditService.exportOperationLogs(queryDTO));
    }
}
