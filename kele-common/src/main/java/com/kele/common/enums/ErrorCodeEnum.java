package com.kele.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author wuzhenhong
 * @date 2024/5/14 10:06
 */
@Getter
public enum ErrorCodeEnum {

    SUCCESS(0, "成功"),
    UN_LOGIN(401, "未登录"),
    NOT_FOUND(404, "资源未找到"),
    ERROR(500, "失败"),

    // ====== v0.12 新增：共享/权限/群组相关 ======
    PERMISSION_DENIED(403, "权限不足"),
    RESOURCE_NOT_VISIBLE(404, "资源不存在或无权访问"),
    GROUP_NAME_DUPLICATE(409, "群组名已存在"),
    USER_ALREADY_IN_GROUP(409, "用户已在群组中"),
    ACL_DUPLICATE(409, "该主体已被授权"),
    CANNOT_REMOVE_LAST_MANAGE(409, "至少需要保留一个管理者"),
    GROUP_HAS_MEMBERS(409, "请先清空群组成员"),
    GROUP_NOT_FOUND(404, "群组不存在"),
    GROUP_DISMISSED(409, "群组已解散"),
    USER_NOT_FOUND(404, "用户不存在"),
    ORG_PUBLIC_PERMISSION_INVALID(400, "组织内公开仅支持 READ 权限"),
    INVALID_ACL_PRINCIPAL_TYPE(400, "非法的主体类型");

    public Integer code;
    private String desc;

    // 显式构造器（避免 Lombok @AllArgsConstructor 在某些 JDK/IDE 组合下不生成的问题）
    ErrorCodeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
