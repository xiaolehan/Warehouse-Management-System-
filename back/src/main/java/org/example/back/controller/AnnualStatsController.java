package org.example.back.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import org.example.back.common.result.Result;
import org.example.back.service.AnnualStatsService;
import org.example.back.vo.AnnualStatsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 年度经营统计（ADR-0008）：仅财务部门管理员可见（方法级 admin 门控挡超管/员工，
 * Service 层再做部门级校验，与利润分析同级的敏感数据）。
 */
@RestController
@RequestMapping("/business/annual-stats")
@SaCheckRole(value = {"admin", "superadmin"}, mode = SaMode.OR)
public class AnnualStatsController {

    private static final String XLSX_MEDIA =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private AnnualStatsService annualStatsService;

    @GetMapping
    @SaCheckRole("admin")
    public Result<List<AnnualStatsVO>> list() {
        return Result.success(annualStatsService.getAnnualStats());
    }

    @GetMapping("/export")
    @SaCheckRole("admin")
    public ResponseEntity<byte[]> export() throws IOException {
        String filename = "年度经营统计-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''"
                                + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(annualStatsService.exportAnnualStats());
    }
}
