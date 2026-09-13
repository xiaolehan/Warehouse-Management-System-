package org.example.back.common.annotation;

import java.lang.annotation.*;

/**
 * 操作审计注解
 * 标记关键业务接口，由审计切面统一落库。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /**
     * 业务模块，例如：用户管理、进货管理。
     */
    String module();

    /**
     * 操作动作，例如：删除、作废并红冲、重置密码。
     */
    String action();

    /**
     * 目标类型，例如：用户、进货单。
     */
    String targetType() default "";

    /**
     * 操作描述（人话摘要），SpEL 表达式，写入时求值并落库定格（D76/ADR-0009）。
     * 可用变量：方法参数名（如 #id、#dto）+ #result（方法返回值，通常是 Result&lt;VO&gt;，取数用 #result.data?.xxx）。
     * 建议用安全导航符 ?. 防空指针；求值失败时兜底为 null（前端回退显示 模块+动作）。
     * 示例："'确认出库 销售单 ' + #result.data?.orderNo + '（' + #result.data?.goodsName + ' × ' + #result.data?.quantity + '）'"
     */
    String detail() default "";
}
