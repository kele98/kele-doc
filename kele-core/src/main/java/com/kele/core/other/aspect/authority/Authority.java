package com.kele.core.other.aspect.authority;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限控制。详见 spec §10.7。
 * <p>
 * 底层是 OGNL（{@link OnglUtils#evaluate}），不是 SpEL。历史命名 {@link #spelArgs()} 误导性但保留作
 * {@code @Deprecated} 别名——新代码请用 {@link #expressionArgs()}。
 *
 * @author wuzhenhong
 * @date 2024/5/16 16:23
 * @deprecated v0.13 #15: 声明式鉴权已迁移到 AO 层 inline requireXxx。
 *             原因：{@code @Authority} 只支持粗粒度（requireManage/requireRead），
 *             无法表达 owner-only 等细粒度（见 transferOwner 先例）。
 *             新代码请直接调用 {@code permissionService.requireXxx()}。
 *             本注解 + AuthorityAspect 保留到下一大版本再删。
 */
@Deprecated
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Authority {

    /**
     * @deprecated 字段名误导（实际引擎是 OGNL 不是 SpEL）。新代码请用 {@link #expressionArgs()}。
     */
    @Deprecated
    String[] spelArgs() default {};

    /**
     * OGNL 表达式参数列表（语义中性字段名，详见 spec §10.7）。
     * 形如 {@code "#folderId"}，引用方法参数名（OGNL 默认从 MethodArgs 上下文取值）。
     */
    String[] expressionArgs() default {};

    /**
     * 权限校验实现的方法名。在 {@link #beanName()} 指定的 bean 上反射调用。
     */
    String methodName();

    /**
     * 注入到 beanFactory 的权限实现类（通常是 {@code permissionService}）。
     */
    String beanName();
}
