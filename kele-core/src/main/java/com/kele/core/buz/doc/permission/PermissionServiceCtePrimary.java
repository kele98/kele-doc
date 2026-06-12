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
 * <b>v0.13 #16 行为</b>：单一三态开关 {@code kele.doc.permission.engine}：
 * <ul>
 *   <li>{@code app-walk}（默认）：{@link PermissionService} 激活，本类不激活</li>
 *   <li>{@code cte}：本类激活并 {@link Primary @Primary} 覆盖 fallback（CTE 实现补完后再切）</li>
 * </ul>
 */
@Service
@Primary
@ConditionalOnProperty(name = "kele.doc.permission.engine", havingValue = "cte")
public class PermissionServiceCtePrimary extends PermissionService {

    @Override
    public PermissionResult resolve(Long userId, Long folderId) {
        throw new UnsupportedOperationException(
            "CTE primary permission path not yet implemented; " +
            "set kele.doc.permission.engine=app-walk (or remove it) to use the app-layer walk. " +
            "See spec §5.3 for the planned CTE implementation."
        );
    }
}
