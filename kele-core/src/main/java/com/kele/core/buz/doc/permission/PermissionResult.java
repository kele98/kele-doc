package com.kele.core.buz.doc.permission;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 权限解析结果。详见 spec §5.1。
 * <p>
 * source：权限来源枚举（OWNER / DIRECT / GROUP / INHERITED / ORG / NONE）。
 * sourceNodeId：来源节点 id（暂未在响应里返——v0.7 BLOCKER #3 修订时删除，留待审计/UX 需求再扩）。
 */
@Getter
@AllArgsConstructor
public class PermissionResult {

    public enum Source {
        OWNER,    // 创建者=Owner，命中即最高
        DIRECT,   // 本层 USER/GROUP/ORG ACL 直接命中
        GROUP,    // 本层 GROUP ACL 命中
        INHERITED, // 父级继承
        ORG,      // 组织内全员公开
        NONE      // 无任何权限
    }

    private final PermissionLevel level;
    private final Source source;
    private final Long sourceNodeId;

    public static PermissionResult none() {
        return new PermissionResult(PermissionLevel.NONE, Source.NONE, null);
    }

    public static PermissionResult owner(Long nodeId) {
        return new PermissionResult(PermissionLevel.MANAGE, Source.OWNER, nodeId);
    }

    public static PermissionResult acl(PermissionLevel level, Source source, Long nodeId) {
        return new PermissionResult(level, source, nodeId);
    }
}
