package com.kele.core.buz.doc.permission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 权限解析引擎 CTE 主路径 stub。详见 spec §5.3。
 * <p>
 * 当前为占位实现，{@link #resolve(Long, Long)} 直接抛 {@link UnsupportedOperationException}。
 * 性能验证（spec §5.3 "EXPLAIN ANALYZE 验证"）通过后会替换为单 SQL CTE 实现。
 * <p>
 * 注：本类默认 <b>不激活</b>（{@code matchIfMissing=false}）。生产走 {@link PermissionService}
 * 的 app-layer walk fallback（{@code application.yml} 里无需配置 {@code kele.doc.permission.fallback}）。
 * 当 {@code kele.doc.permission.cte-primary=true} 时本类被激活并覆盖 fallback（CTE 实现补完后再切）。
 */
@Service
@Primary
@ConditionalOnProperty(name = "kele.doc.permission.cte-primary", havingValue = "true")
public class PermissionServiceCtePrimary extends PermissionService {

    @Override
    public PermissionResult resolve(Long userId, Long folderId) {
        throw new UnsupportedOperationException(
            "CTE primary permission path not yet implemented; " +
            "remove kele.doc.permission.cte-primary=true to use the app-layer walk fallback. " +
            "See spec §5.3 for the planned CTE implementation."
        );
    }
}
