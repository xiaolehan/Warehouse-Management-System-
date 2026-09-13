package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.dto.LoginLogQueryDTO;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.OperationLogQueryDTO;
import org.example.back.entity.SysLoginLog;
import org.example.back.entity.SysOperationLog;
import org.example.back.mapper.SysLoginLogMapper;
import org.example.back.mapper.SysOperationLogMapper;
import org.example.back.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AuditService {

    /** 导出上限：超出请缩小筛选范围（审计导出是低频人工操作，不需要全量） */
    private static final int EXPORT_MAX_ROWS = 20000;

    private static final String[] EXPORT_HEADERS = {
            "时间", "用户名", "模块", "动作", "目标类型", "目标ID", "操作描述", "请求路径", "IP", "请求参数快照", "返回结果快照"
    };

    private static final DateTimeFormatter EXPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private SysLoginLogMapper sysLoginLogMapper;

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private AuthzService authzService;

    public PageResult<SysLoginLog> loginLogPage(LoginLogQueryDTO queryDTO) {
        requireSuperAdmin();
        LambdaQueryWrapper<SysLoginLog> wrapper = buildLoginLogWrapper(queryDTO);
        Page<SysLoginLog> page = sysLoginLogMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public SysLoginLog getLoginLogById(Long id) {
        requireSuperAdmin();
        SysLoginLog log = sysLoginLogMapper.selectById(id);
        if (log == null) {
            throw BusinessException.notFound("登录日志不存在");
        }
        return log;
    }

    public PageResult<SysOperationLog> operationLogPage(OperationLogQueryDTO queryDTO) {
        requireSuperAdmin();
        LambdaQueryWrapper<SysOperationLog> wrapper = buildOperationLogWrapper(queryDTO);
        Page<SysOperationLog> page = sysOperationLogMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public SysOperationLog getOperationLogById(Long id) {
        requireSuperAdmin();
        SysOperationLog log = sysOperationLogMapper.selectById(id);
        if (log == null) {
            throw BusinessException.notFound("操作日志不存在");
        }
        return log;
    }

    // ==================== D78：日志手动删除（物理删除 + 删除动作本身写操作日志留痕） ====================

    public int deleteOperationLog(Long id) {
        requireSuperAdmin();
        int count = sysOperationLogMapper.deleteById(id);
        if (count == 0) {
            throw BusinessException.notFound("操作日志不存在");
        }
        recordDeletion("操作日志", 1, "单条删除（id=" + id + "）");
        return count;
    }

    public int deleteOperationLogs(List<Long> ids) {
        requireSuperAdmin();
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.validateFail("请选择要删除的日志");
        }
        int count = sysOperationLogMapper.deleteBatchIds(ids);
        recordDeletion("操作日志", count, "批量删除（" + ids.size() + " 个 id）");
        return count;
    }

    public int deleteOperationLogsByQuery(OperationLogQueryDTO queryDTO) {
        requireSuperAdmin();
        if (!hasOperationLogFilter(queryDTO)) {
            throw BusinessException.validateFail("按条件删除须至少一个筛选条件，防止误清空全表");
        }
        LambdaQueryWrapper<SysOperationLog> wrapper = buildOperationLogWrapper(queryDTO);
        int count = sysOperationLogMapper.delete(wrapper);
        recordDeletion("操作日志", count, "按条件删除（" + describeOperationLogFilter(queryDTO) + "）");
        return count;
    }

    public int deleteLoginLog(Long id) {
        requireSuperAdmin();
        int count = sysLoginLogMapper.deleteById(id);
        if (count == 0) {
            throw BusinessException.notFound("登录日志不存在");
        }
        recordDeletion("登录日志", 1, "单条删除（id=" + id + "）");
        return count;
    }

    public int deleteLoginLogs(List<Long> ids) {
        requireSuperAdmin();
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.validateFail("请选择要删除的日志");
        }
        int count = sysLoginLogMapper.deleteBatchIds(ids);
        recordDeletion("登录日志", count, "批量删除（" + ids.size() + " 个 id）");
        return count;
    }

    public int deleteLoginLogsByQuery(LoginLogQueryDTO queryDTO) {
        requireSuperAdmin();
        if (!hasLoginLogFilter(queryDTO)) {
            throw BusinessException.validateFail("按条件删除须至少一个筛选条件，防止误清空全表");
        }
        LambdaQueryWrapper<SysLoginLog> wrapper = buildLoginLogWrapper(queryDTO);
        int count = sysLoginLogMapper.delete(wrapper);
        recordDeletion("登录日志", count, "按条件删除（" + describeLoginLogFilter(queryDTO) + "）");
        return count;
    }

    // ==================== D79：操作日志 xlsx 导出（跟随页面筛选条件） ====================

    public byte[] exportOperationLogs(OperationLogQueryDTO queryDTO) throws IOException {
        requireSuperAdmin();
        LambdaQueryWrapper<SysOperationLog> wrapper = buildOperationLogWrapper(queryDTO);
        wrapper.last("LIMIT " + EXPORT_MAX_ROWS);
        List<SysOperationLog> logs = sysOperationLogMapper.selectList(wrapper);
        if (logs.size() >= EXPORT_MAX_ROWS) {
            throw BusinessException.validateFail("导出超过 " + EXPORT_MAX_ROWS + " 行上限，请缩小筛选范围（如缩短时间段）");
        }

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("操作日志");

            Row header = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEADERS.length; i++) {
                header.createCell(i).setCellValue(EXPORT_HEADERS[i]);
            }

            int rowIndex = 1;
            for (SysOperationLog log : logs) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(log.getCreateTime() == null ? "" : log.getCreateTime().format(EXPORT_TIME_FORMAT));
                row.createCell(1).setCellValue(nullToEmpty(log.getUsername()));
                row.createCell(2).setCellValue(nullToEmpty(log.getModule()));
                row.createCell(3).setCellValue(nullToEmpty(log.getAction()));
                row.createCell(4).setCellValue(nullToEmpty(log.getTargetType()));
                row.createCell(5).setCellValue(nullToEmpty(log.getTargetId()));
                row.createCell(6).setCellValue(nullToEmpty(log.getDetail()));
                row.createCell(7).setCellValue(nullToEmpty(log.getRequestUri()));
                row.createCell(8).setCellValue(nullToEmpty(log.getIp()));
                row.createCell(9).setCellValue(nullToEmpty(log.getBeforeData()));
                row.createCell(10).setCellValue(nullToEmpty(log.getAfterData()));
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ==================== 内部助手 ====================

    private LambdaQueryWrapper<SysOperationLog> buildOperationLogWrapper(OperationLogQueryDTO queryDTO) {
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getUsername()), SysOperationLog::getUsername, queryDTO.getUsername())
                .like(StringUtils.hasText(queryDTO.getModule()), SysOperationLog::getModule, queryDTO.getModule())
                .like(StringUtils.hasText(queryDTO.getAction()), SysOperationLog::getAction, queryDTO.getAction())
                .like(StringUtils.hasText(queryDTO.getTargetType()), SysOperationLog::getTargetType, queryDTO.getTargetType())
                .ge(startTime != null, SysOperationLog::getCreateTime, startTime)
                .lt(endTime != null, SysOperationLog::getCreateTime, endTime)
                .orderByDesc(SysOperationLog::getCreateTime)
                .orderByDesc(SysOperationLog::getId);
        return wrapper;
    }

    private LambdaQueryWrapper<SysLoginLog> buildLoginLogWrapper(LoginLogQueryDTO queryDTO) {
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getUsername()), SysLoginLog::getUsername, queryDTO.getUsername())
                .like(StringUtils.hasText(queryDTO.getIp()), SysLoginLog::getIp, queryDTO.getIp())
                .eq(queryDTO.getSuccessFlag() != null, SysLoginLog::getSuccessFlag, queryDTO.getSuccessFlag())
                .ge(startTime != null, SysLoginLog::getLoginTime, startTime)
                .lt(endTime != null, SysLoginLog::getLoginTime, endTime)
                .orderByDesc(SysLoginLog::getLoginTime)
                .orderByDesc(SysLoginLog::getId);
        return wrapper;
    }

    private boolean hasOperationLogFilter(OperationLogQueryDTO queryDTO) {
        return StringUtils.hasText(queryDTO.getUsername()) || StringUtils.hasText(queryDTO.getModule())
                || StringUtils.hasText(queryDTO.getAction()) || StringUtils.hasText(queryDTO.getTargetType())
                || queryDTO.getStartDate() != null || queryDTO.getEndDate() != null;
    }

    private boolean hasLoginLogFilter(LoginLogQueryDTO queryDTO) {
        return StringUtils.hasText(queryDTO.getUsername()) || StringUtils.hasText(queryDTO.getIp())
                || queryDTO.getSuccessFlag() != null
                || queryDTO.getStartDate() != null || queryDTO.getEndDate() != null;
    }

    private String describeOperationLogFilter(OperationLogQueryDTO queryDTO) {
        StringBuilder sb = new StringBuilder();
        appendCond(sb, "用户", queryDTO.getUsername());
        appendCond(sb, "模块", queryDTO.getModule());
        appendCond(sb, "动作", queryDTO.getAction());
        appendCond(sb, "目标类型", queryDTO.getTargetType());
        appendCond(sb, "开始", queryDTO.getStartDate());
        appendCond(sb, "结束", queryDTO.getEndDate());
        return sb.toString();
    }

    private String describeLoginLogFilter(LoginLogQueryDTO queryDTO) {
        StringBuilder sb = new StringBuilder();
        appendCond(sb, "用户", queryDTO.getUsername());
        appendCond(sb, "IP", queryDTO.getIp());
        if (queryDTO.getSuccessFlag() != null) {
            appendCond(sb, "结果", queryDTO.getSuccessFlag() == 1 ? "成功" : "失败");
        }
        appendCond(sb, "开始", queryDTO.getStartDate());
        appendCond(sb, "结束", queryDTO.getEndDate());
        return sb.toString();
    }

    private void appendCond(StringBuilder sb, String label, Object value) {
        if (value == null || (value instanceof String str && !StringUtils.hasText(str))) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('，');
        }
        sb.append(label).append('=').append(value);
    }

    /**
     * 删除动作本身写一条操作日志留痕（D78：谁删了哪张表多少条）。
     * 直接编程式落库（不走 @AuditLog 切面——删除条数切面拿不到）。
     */
    private void recordDeletion(String targetTable, int count, String mode) {
        try {
            SysOperationLog log = new SysOperationLog();
            log.setModule("审计日志");
            log.setAction("删除日志");
            log.setTargetType(targetTable);
            log.setTargetId("");
            log.setDetail("删除" + targetTable + " " + count + " 条（" + mode + "）");
            log.setCreateTime(LocalDateTime.now());

            LoginResponse.UserInfoVO currentUser = authzService.currentUser();
            if (currentUser != null) {
                log.setUserId(currentUser.getId());
                log.setUsername(currentUser.getUsername());
            }
            sysOperationLogMapper.insert(log);
        } catch (Exception ex) {
            // 留痕失败不阻断删除主流程（与 AuditLogAspect 吞异常口径一致）
            org.slf4j.LoggerFactory.getLogger(AuditService.class)
                    .warn("删除日志留痕失败, table={}, count={}, error={}", targetTable, count, ex.getMessage());
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void requireSuperAdmin() {
        authzService.requireSuperAdmin("仅超级管理员可访问审计日志模块");
    }
}
