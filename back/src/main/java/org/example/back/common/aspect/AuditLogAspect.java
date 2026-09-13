package org.example.back.common.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.example.back.common.annotation.AuditLog;
import org.example.back.common.util.ClientIpUtil;
import org.example.back.entity.SysOperationLog;
import org.example.back.entity.SysUser;
import org.example.back.mapper.SysOperationLogMapper;
import org.example.back.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 操作审计切面：拦截带 @AuditLog 的方法并记录操作日志。
 * D76/ADR-0009：detail 表达式（SpEL）生成人话摘要落库定格；
 * before_data = 请求参数快照，after_data = 返回结果快照（均为写入时 JSON 定格，截断防爆表）。
 */
@Slf4j
@Aspect
@Component
public class AuditLogAspect {

    private static final int DETAIL_MAX_LENGTH = 500;
    private static final int SNAPSHOT_MAX_LENGTH = 4000;

    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer PARAM_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final Map<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>();

    /** 快照脱敏：password/pwd/secret/token 等敏感键的值一律替换为 ******（如重置密码 DTO） */
    private static final Pattern SENSITIVE_KEY_PATTERN =
            Pattern.compile("(?i)\"(\\w*(?:password|pwd|secret|token)\\w*)\"\\s*:\\s*\"[^\"]*\"");

    @Autowired
    private SysOperationLogMapper sysOperationLogMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterReturning(pointcut = "@annotation(auditLog)", returning = "result")
    public void recordOperationLog(JoinPoint joinPoint, AuditLog auditLog, Object result) {
        try {
            HttpServletRequest request = currentRequest();

            SysOperationLog logEntity = new SysOperationLog();
            logEntity.setModule(auditLog.module());
            logEntity.setAction(auditLog.action());
            logEntity.setTargetType(auditLog.targetType());
            logEntity.setTargetId(resolveTargetId(joinPoint.getArgs()));
            logEntity.setDetail(resolveDetail(auditLog, joinPoint, result));
            logEntity.setBeforeData(toJsonSnapshot(filterArgs(joinPoint.getArgs())));
            logEntity.setAfterData(toJsonSnapshot(result));
            logEntity.setRequestUri(request == null ? "" : request.getRequestURI());
            logEntity.setIp(ClientIpUtil.getClientIp(request));
            logEntity.setCreateTime(LocalDateTime.now());

            fillOperator(logEntity);
            sysOperationLogMapper.insert(logEntity);
        } catch (Exception ex) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String methodName = signature.getDeclaringTypeName() + "#" + signature.getMethod().getName();
            log.warn("审计日志记录失败, method={}, error={}", methodName, ex.getMessage());
        }
    }

    /**
     * 求值 detail SpEL 表达式：变量 = 方法参数名 + #result。
     * 表达式为空或求值失败均返回 null（前端兜底显示 模块+动作）。
     */
    private String resolveDetail(AuditLog auditLog, JoinPoint joinPoint, Object result) {
        String detailExpr = auditLog.detail();
        if (detailExpr == null || detailExpr.isBlank()) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            EvaluationContext context = new StandardEvaluationContext();
            String[] paramNames = PARAM_NAME_DISCOVERER.getParameterNames(signature.getMethod());
            Object[] args = joinPoint.getArgs();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            context.setVariable("result", result);

            Expression expression = EXPRESSION_CACHE.computeIfAbsent(detailExpr, SPEL_PARSER::parseExpression);
            Object value = expression.getValue(context);
            return value == null ? null : truncate(String.valueOf(value), DETAIL_MAX_LENGTH);
        } catch (Exception ex) {
            log.warn("审计日志 detail 表达式求值失败, expr={}, error={}", detailExpr, ex.getMessage());
            return null;
        }
    }

    /**
     * 过滤不可序列化的请求参数（servlet 对象/绑定结果/文件），保留业务入参。
     * 单参数直接序列化该参数，多参数序列化为数组。
     */
    private Object filterArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        List<Object> businessArgs = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof ServletRequest || arg instanceof ServletResponse
                    || arg instanceof BindingResult || arg instanceof MultipartFile) {
                continue;
            }
            businessArgs.add(arg);
        }
        if (businessArgs.isEmpty()) {
            return null;
        }
        return businessArgs.size() == 1 ? businessArgs.get(0) : businessArgs;
    }

    private String toJsonSnapshot(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            json = SENSITIVE_KEY_PATTERN.matcher(json).replaceAll("\"$1\":\"******\"");
            return truncate(json, SNAPSHOT_MAX_LENGTH);
        } catch (Exception ex) {
            log.warn("审计日志快照序列化失败, type={}, error={}", value.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void fillOperator(SysOperationLog logEntity) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            logEntity.setUsername("anonymous");
            return;
        }
        Long userId = Long.valueOf(String.valueOf(loginId));
        logEntity.setUserId(userId);

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            logEntity.setUsername("uid:" + userId);
            return;
        }
        logEntity.setUsername(user.getUsername());
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }

    private String resolveTargetId(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        for (Object arg : args) {
            if (arg instanceof Long || arg instanceof Integer) {
                return String.valueOf(arg);
            }
        }
        for (Object arg : args) {
            if (arg instanceof String str && !str.isBlank()) {
                return str;
            }
        }
        return "";
    }
}
