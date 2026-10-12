package org.example.back.common.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.example.back.common.annotation.AuditLog;
import org.example.back.entity.SysOperationLog;
import org.example.back.entity.SysUser;
import org.example.back.mapper.SysOperationLogMapper;
import org.example.back.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D76/ADR-0009：detail SpEL 生成 / before-after 快照（脱敏+截断）。
 * 无登录上下文时 StpUtil 返回 null → username 落 "anonymous"，不影响本组断言。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogAspectTest {

    /** 带注解的哑方法载体：param names 依赖 -parameters 编译（pom 已开） */
    @SuppressWarnings("unused")
    static class DummyOps {
        @AuditLog(module = "商品资料", action = "更新", targetType = "商品",
                detail = "'更新商品 #' + #id + '，备注：' + #remark")
        public Object update(Long id, String remark) { return null; }

        @AuditLog(module = "用户管理", action = "重置密码", targetType = "用户")
        public Object resetPwd(Long id, Map<String, String> dto) { return null; }

        @AuditLog(module = "商品资料", action = "导入", detail = "'导入：' + #missing.deep")
        public Object badExpr(String name) { return null; }

        @AuditLog(module = "商品资料", action = "更新", detail = "#remark")
        public Object echo(Long id, String remark) { return null; }
    }

    @Mock private SysOperationLogMapper sysOperationLogMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private AuditLogAspect aspect;

    private ProceedingJoinPoint mockProceedJoinPoint(Method method, Object[] args) {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        // signature 仅在 detail 表达式非空时参与求值（空 detail 短路），故 lenient
        lenient().when(signature.getMethod()).thenReturn(method);
        lenient().when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        stubProceed(pjp, null);
        return pjp;
    }

    /** proceed() 声明 Throwable，直接 when() 需方法声明 throws——测试助手内部吞掉保持无 checked 异常签名。 */
    private void stubProceed(ProceedingJoinPoint pjp, Object result) {
        try {
            lenient().when(pjp.proceed()).thenReturn(result);
        } catch (Throwable ignored) {
            // 不会发生：mock 的 proceed 不会真的抛
        }
    }

    private AuditLog auditOf(Method method) {
        return method.getAnnotation(AuditLog.class);
    }

    private SysOperationLog captureInsert(ProceedingJoinPoint pjp, AuditLog auditLog, Object result) {
        // 单测无 SaTokenContext，静态打桩为未登录（操作者落 "anonymous"）
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(null);
            stubProceed(pjp, result);
            try {
                aspect.recordOperationLog(pjp, auditLog);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        ArgumentCaptor<SysOperationLog> cap = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(sysOperationLogMapper).insert(cap.capture());
        return cap.getValue();
    }

    // ---------- D76：detail 由 SpEL 按方法参数名求值并落库；targetId 取首个 Long 入参 ----------
    @Test
    void detail_generatedFromSpelArgs() throws Exception {
        Method m = DummyOps.class.getMethod("update", Long.class, String.class);
        ProceedingJoinPoint jp = mockProceedJoinPoint(m, new Object[]{9L, "改价"});

        SysOperationLog log = captureInsert(jp, auditOf(m), null);

        assertEquals("更新商品 #9，备注：改价", log.getDetail());
        assertEquals("9", log.getTargetId());
        assertNull(log.getAfterData(), "返回 null 时 after_data 应为 null");
        assertTrue(log.getBeforeData().contains("改价"), "实际: " + log.getBeforeData());
    }

    // ---------- D76：无 detail 表达式（历史写法）→ detail 为 null，前端兜底 模块+动作 ----------
    @Test
    void detail_blank_returnsNull() throws Exception {
        Method m = DummyOps.class.getMethod("resetPwd", Long.class, Map.class);
        ProceedingJoinPoint jp = mockProceedJoinPoint(m, new Object[]{7L, Map.of("k", "v")});

        SysOperationLog log = captureInsert(jp, auditOf(m), null);

        assertNull(log.getDetail());
    }

    // ---------- D76：表达式求值失败兜底 null，不阻断日志落库 ----------
    @Test
    void detail_expressionFailure_fallsBackNull() throws Exception {
        Method m = DummyOps.class.getMethod("badExpr", String.class);
        ProceedingJoinPoint jp = mockProceedJoinPoint(m, new Object[]{"x"});

        SysOperationLog log = captureInsert(jp, auditOf(m), null);

        assertNull(log.getDetail());
        assertEquals("商品资料", log.getModule());
    }

    // ---------- D76：detail 截断 500 / before_data 截断 4000 ----------
    @Test
    void detailAndSnapshot_truncated() throws Exception {
        Method m = DummyOps.class.getMethod("echo", Long.class, String.class);
        String big = "a".repeat(5000);
        ProceedingJoinPoint jp = mockProceedJoinPoint(m, new Object[]{1L, big});

        SysOperationLog log = captureInsert(jp, auditOf(m), null);

        assertEquals(500, log.getDetail().length());
        assertEquals(4000, log.getBeforeData().length());
    }

    // ---------- D76：快照脱敏——password 键值一律 ******（重置密码 DTO 场景） ----------
    @Test
    void snapshot_redactsSensitiveKeys() throws Exception {
        Method m = DummyOps.class.getMethod("resetPwd", Long.class, Map.class);
        ProceedingJoinPoint jp = mockProceedJoinPoint(m, new Object[]{7L, Map.of("newPassword", "abc123", "username", "u1")});

        SysOperationLog log = captureInsert(jp, auditOf(m), null);

        assertTrue(log.getBeforeData().contains("\"newPassword\":\"******\""),
                "实际: " + log.getBeforeData());
        assertFalse(log.getBeforeData().contains("abc123"), "明文密码泄露: " + log.getBeforeData());
        assertTrue(log.getBeforeData().contains("u1"), "非敏感字段应保留: " + log.getBeforeData());
    }

    // ---------- D76：servlet/文件参数不入快照，单个业务参数直接序列化 ----------
    @Test
    void snapshot_skipsNonSerializableArgs() throws Exception {
        Method m = DummyOps.class.getMethod("echo", Long.class, String.class);
        MultipartFile file = mock(MultipartFile.class);
        ProceedingJoinPoint jp = mockProceedJoinPoint(m, new Object[]{file, "仅业务参数"});

        SysOperationLog log = captureInsert(jp, auditOf(m), null);

        assertEquals("\"仅业务参数\"", log.getBeforeData());
    }

    // ---------- D76：after_data = 返回结果快照 ----------
    @Test
    void afterData_fromResult() throws Exception {
        Method m = DummyOps.class.getMethod("update", Long.class, String.class);
        ProceedingJoinPoint jp = mockProceedJoinPoint(m, new Object[]{9L, "改价"});

        SysOperationLog log = captureInsert(jp, auditOf(m), Map.of("code", 200, "msg", "ok"));

        assertTrue(log.getAfterData().contains("\"code\":200"), "实际: " + log.getAfterData());
    }

    // ---------- code-review P1：操作者在 proceed() 前定格——方法体内注销自身会话（本人改密场景）不记 anonymous ----------
    @Test
    void operator_capturedBeforeProceed() throws Throwable {
        Method m = DummyOps.class.getMethod("update", Long.class, String.class);
        ProceedingJoinPoint jp = mockProceedJoinPoint(m, new Object[]{9L, "改价"});
        SysUser op = new SysUser();
        op.setId(9L);
        op.setUsername("op9");
        when(sysUserMapper.selectById(9L)).thenReturn(op);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            // 第一次读（proceed 前）= 9；proceed 内改密成功注销会话后（若 AfterReturning 再读）= null
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(9L, (Object) null);
            stubProceed(jp, null);
            aspect.recordOperationLog(jp, auditOf(m));
        }

        ArgumentCaptor<SysOperationLog> cap = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(sysOperationLogMapper).insert(cap.capture());
        SysOperationLog log0 = cap.getValue();
        assertEquals("op9", log0.getUsername(), "改密注销会话后操作者仍应定格为登录用户");
        assertEquals(9L, log0.getUserId());
    }
}
