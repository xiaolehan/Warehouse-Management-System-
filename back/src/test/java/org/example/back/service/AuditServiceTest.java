package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginLogQueryDTO;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.OperationLogQueryDTO;
import org.example.back.entity.SysLoginLog;
import org.example.back.entity.SysOperationLog;
import org.example.back.mapper.SysLoginLogMapper;
import org.example.back.mapper.SysOperationLogMapper;
import org.example.back.mapper.SysUserMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 初始化 MyBatis-Plus lambda 缓存（纯 mock 测试下不会自动加载）
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, SysOperationLog.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, SysLoginLog.class);
    }

    @Mock private SysLoginLogMapper sysLoginLogMapper;
    @Mock private SysOperationLogMapper sysOperationLogMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private AuthzService authzService;

    @InjectMocks private AuditService service;

    private void mockCurrentUser() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(1L);
        user.setUsername("superadmin");
        lenient().when(authzService.currentUser()).thenReturn(user);
    }

    // ---------- D78：单条删除——目标不存在时报 notFound 且不留痕 ----------
    @Test
    void deleteOperationLog_notFound_noTrace() {
        when(sysOperationLogMapper.deleteById(9L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteOperationLog(9L));
        assertTrue(ex.getMessage().contains("操作日志不存在"), "实际: " + ex.getMessage());
        verify(sysOperationLogMapper, never()).insert(any(SysOperationLog.class));
    }

    // ---------- D78：单条删除成功——删除动作本身写操作日志留痕 ----------
    @Test
    void deleteOperationLog_success_recordsTrace() {
        mockCurrentUser();
        when(sysOperationLogMapper.deleteById(9L)).thenReturn(1);

        int count = service.deleteOperationLog(9L);

        assertEquals(1, count);
        ArgumentCaptor<SysOperationLog> cap = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(sysOperationLogMapper).insert(cap.capture());
        SysOperationLog trace = cap.getValue();
        assertEquals("审计日志", trace.getModule());
        assertEquals("删除日志", trace.getAction());
        assertEquals("操作日志", trace.getTargetType());
        assertTrue(trace.getDetail().contains("删除操作日志 1 条"), "实际: " + trace.getDetail());
        assertTrue(trace.getDetail().contains("单条删除"), "实际: " + trace.getDetail());
        assertEquals(1L, trace.getUserId());
        assertEquals("superadmin", trace.getUsername());
    }

    // ---------- D78：批量删除——空选择拒绝 ----------
    @Test
    void deleteOperationLogs_emptyIds_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteOperationLogs(List.of()));
        assertTrue(ex.getMessage().contains("请选择要删除的日志"), "实际: " + ex.getMessage());
        verify(sysOperationLogMapper, never()).deleteBatchIds(any());
    }

    // ---------- D78：按条件删除——无筛选条件拒绝（防误清空全表） ----------
    @Test
    void deleteOperationLogsByQuery_noFilter_rejected() {
        OperationLogQueryDTO query = new OperationLogQueryDTO();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteOperationLogsByQuery(query));
        assertTrue(ex.getMessage().contains("至少一个筛选条件"), "实际: " + ex.getMessage());
        verify(sysOperationLogMapper, never()).delete(any());
    }

    // ---------- D78：按条件删除——带筛选删除并留痕（含筛选描述） ----------
    @Test
    void deleteOperationLogsByQuery_withFilter_deletesAndRecords() {
        mockCurrentUser();
        OperationLogQueryDTO query = new OperationLogQueryDTO();
        query.setUsername("zhang");
        query.setStartDate(LocalDate.of(2026, 9, 1));
        when(sysOperationLogMapper.delete(any())).thenReturn(3);

        int count = service.deleteOperationLogsByQuery(query);

        assertEquals(3, count);
        ArgumentCaptor<SysOperationLog> cap = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(sysOperationLogMapper).insert(cap.capture());
        String detail = cap.getValue().getDetail();
        assertTrue(detail.contains("删除操作日志 3 条"), "实际: " + detail);
        assertTrue(detail.contains("按条件删除"), "实际: " + detail);
        assertTrue(detail.contains("用户=zhang"), "实际: " + detail);
    }

    // ---------- D78：登录日志同样可删并留痕 ----------
    @Test
    void deleteLoginLog_success_recordsTrace() {
        mockCurrentUser();
        when(sysLoginLogMapper.deleteById(5L)).thenReturn(1);

        int count = service.deleteLoginLog(5L);

        assertEquals(1, count);
        ArgumentCaptor<SysOperationLog> cap = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(sysOperationLogMapper).insert(cap.capture());
        assertTrue(cap.getValue().getDetail().contains("删除登录日志 1 条"),
                "实际: " + cap.getValue().getDetail());
    }

    @Test
    void deleteLoginLogsByQuery_noFilter_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteLoginLogsByQuery(new LoginLogQueryDTO()));
        assertTrue(ex.getMessage().contains("至少一个筛选条件"), "实际: " + ex.getMessage());
        verify(sysLoginLogMapper, never()).delete(any());
    }

    // ---------- D79：导出超过上限拒绝（防全量拖表） ----------
    @Test
    void exportOperationLogs_overLimit_rejected() {
        when(sysOperationLogMapper.selectList(any()))
                .thenReturn(Collections.nCopies(20000, new SysOperationLog()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.exportOperationLogs(new OperationLogQueryDTO()));
        assertTrue(ex.getMessage().contains("上限"), "实际: " + ex.getMessage());
    }

    // ---------- D79：导出表头含操作描述，行数据完整（时间/描述/快照） ----------
    @Test
    void exportOperationLogs_writesHeadersAndRows() throws Exception {
        SysOperationLog log = new SysOperationLog();
        log.setCreateTime(LocalDateTime.of(2026, 9, 13, 10, 30, 0));
        log.setUsername("zhang");
        log.setModule("商品资料");
        log.setAction("更新");
        log.setTargetType("商品");
        log.setTargetId("9");
        log.setDetail("更新商品 #9");
        log.setRequestUri("/api/goods/9");
        log.setIp("127.0.0.1");
        log.setBeforeData("{\"goodsName\":\"旧\"}");
        log.setAfterData("{\"code\":200}");
        when(sysOperationLogMapper.selectList(any())).thenReturn(List.of(log));

        byte[] bytes = service.exportOperationLogs(new OperationLogQueryDTO());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertEquals("操作描述", header.getCell(6).getStringCellValue());
            assertEquals("请求参数快照", header.getCell(9).getStringCellValue());

            Row row = sheet.getRow(1);
            assertEquals("2026-09-13 10:30:00", row.getCell(0).getStringCellValue());
            assertEquals("zhang", row.getCell(1).getStringCellValue());
            assertEquals("更新商品 #9", row.getCell(6).getStringCellValue());
            assertEquals("{\"goodsName\":\"旧\"}", row.getCell(9).getStringCellValue());
            assertEquals("{\"code\":200}", row.getCell(10).getStringCellValue());
            assertEquals(2, sheet.getPhysicalNumberOfRows());
        }
    }

}
