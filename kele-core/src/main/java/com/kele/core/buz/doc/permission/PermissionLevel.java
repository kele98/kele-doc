package com.kele.core.buz.doc.permission;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 权限等级。详见 spec §5.1。
 */
@Getter
@AllArgsConstructor
public enum PermissionLevel {
    NONE(0),
    READ(1),
    WRITE(2),
    MANAGE(3);

    private final int value;

    /**
     * 是否 ≥ 目标等级。NONE.atLeast(READ) == false；READ.atLeast(NONE) == true。
     */
    public boolean atLeast(PermissionLevel other) {
        return this.value >= other.value;
    }
}
