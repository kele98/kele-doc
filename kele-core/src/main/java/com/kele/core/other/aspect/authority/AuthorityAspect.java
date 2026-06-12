package com.kele.core.other.aspect.authority;

import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.other.context.LoginContext;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.ognl.OgnlException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

/**
 * @author wuzhenhong
 * @date 2024/5/16 16:23
 */
@Slf4j
@Aspect
@Component
public class AuthorityAspect implements ApplicationContextAware {

    private static final Object[] EMPTY_ARRAY = new Object[0];

    private ApplicationContext applicationContext;

    /**
     * {@link Authority}
     */
    @Pointcut("@annotation(com.kele.core.other.aspect.authority.Authority) || @within(com.kele.core.other.aspect.authority.Authority)")
    public void pointCut() {
        // 仅仅是为了设置切点
    }

    @Before("pointCut()")
    public Object before(JoinPoint joinPoint) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        Object[] parameters = joinPoint.getArgs();
        String[] parameterNames = methodSignature.getParameterNames();
        Authority authority = method.getAnnotation(Authority.class);

        // v0.7 落实 spec §10.7: 优先用 expressionArgs（新字段），fallback 到 spelArgs（已 @Deprecated）
        String[] expressionArgs = authority.expressionArgs();
        if (expressionArgs == null || expressionArgs.length == 0) {
            expressionArgs = authority.spelArgs();
        }
        Object[] finalArgs = EMPTY_ARRAY;
        if (Objects.nonNull(expressionArgs) && expressionArgs.length > 0) {
            Map<String, Object> context = new HashMap<>();
            for (int i = 0; i < parameters.length; i++) {
                context.put(parameterNames[i], parameters[i]);
            }
            context.put("userInfoBO", LoginContext.getUserInfo());
            finalArgs = Arrays.stream(expressionArgs).map(args -> {
                    try {
                        // 去掉 OGNL 表达式的 # 前缀（#folderId → folderId），让 2-arg
                        // Ognl.getValue(String, Object) 在 root=contextMap 上走 Map property
                        // access（等价于 context.get("folderId")）。
                        // 不要用 OnglUtils.evaluate：那是 ${...} 模板引擎，对裸 #folderId 不识别，
                        // 会返回空串导致 findMethod 拿到 null 抛 NPE（v0.7 回归）。
                        // mybatis-ognl 的 Ognl.getValue 没有 (String, Map, Object) 这种
                        // standalone OGNL 才有的 context+root 双参数重载。
                        String ognlExpr = args.startsWith("#") ? args.substring(1) : args;
                        return Ognl.getValue(ognlExpr, context);
                    } catch (OgnlException e) {
                        throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "ognl表达式解析失败！", e);
                    }
                })
                .toArray();
        }

        String beanName = authority.beanName();
        Object bean = this.applicationContext.getBean(beanName);
        // v0.7 修复：原 (Class<?>[]) cast 在 Java 数组类型系统下永远抛 ClassCastException
        // 改用 typed generator toArray(IntFunction) 直接生成 Class<?>[] 数组，绕过强制 cast
        Class<?>[] classes = Arrays.stream(finalArgs)
            .map(Object::getClass)
            .toArray(Class<?>[]::new);
        Method method1 = ReflectionUtils.findMethod(bean.getClass(), authority.methodName(), classes);
        // v0.13 #7: assert 在 prod (-ea off) 静默吞掉 null，NPE 被 GlobalExceptionHandler
        // 当成 "system error" 掩盖真实原因。改成显式判空 + 带定位信息的 BusinessException。
        if (method1 == null) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "鉴权配置错误: " + authority.beanName() + "#" + authority.methodName()
                    + " 未找到（参数类型: " + Arrays.toString(classes) + ")");
        }
        return method1.invoke(bean, finalArgs);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
