package com.kele.core.other.aspect.authority;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kele.core.buz.doc.permission.PermissionService;
import com.kele.core.other.context.LoginContext;
import java.lang.reflect.Method;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

/**
 * AuthorityAspect 单元测试。v0.7 回归：{@code @Authority(expressionArgs = "#folderId", ...)}
 * 应当把方法参数解析为真实值（Long folderId）并反射调用 permissionService.requireManage。
 *
 * <p>v0.7 bug 复现路径：{@code OnglUtils.evaluate("#folderId", ctx)} 在 OnglUtils 是
 * {@code ${...}} 模板引擎的语义下不识别裸 OGNL 表达式，返回空串，{@code findMethod}
 * 拿到 null 后第 85 行 {@code method1.invoke(...)} 抛 NPE。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorityAspect 裸 OGNL 表达式求值")
class AuthorityAspectTest {

    @Mock private ApplicationContext applicationContext;
    @Mock private JoinPoint joinPoint;
    @Mock private MethodSignature methodSignature;

    private AuthorityAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AuthorityAspect();
        aspect.setApplicationContext(applicationContext);
    }

    @AfterEach
    void tearDown() {
        LoginContext.remove();
    }

    @Test
    void resolves_bare_ognl_folderId_and_invokes_bean_method_with_Long() throws Throwable {
        Method targetMethod = Target.class.getMethod("withFolderId", Long.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(targetMethod);
        when(joinPoint.getArgs()).thenReturn(new Object[]{3L});
        when(methodSignature.getParameterNames()).thenReturn(new String[]{"folderId"});

        PermissionService mockService = mock(PermissionService.class);
        when(applicationContext.getBean("permissionService")).thenReturn(mockService);

        assertDoesNotThrow(() -> aspect.before(joinPoint));
        verify(mockService).requireManage(3L);
    }

    /**
     * 测试目标方法。仅用于反射读取 {@link Authority} 元注解，不会被实际调用。
     */
    static class Target {
        @Authority(
            expressionArgs = "#folderId",
            methodName = "requireManage",
            beanName = "permissionService")
        public void withFolderId(Long folderId) {
            // no-op
        }
    }
}
