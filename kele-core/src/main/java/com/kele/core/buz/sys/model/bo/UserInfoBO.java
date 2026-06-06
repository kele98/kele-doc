package com.kele.core.buz.sys.model.bo;

import lombok.Data;

/**
 * @author wuzhenhong
 * @date 2024/5/14 10:17
 */
@Data
public class UserInfoBO {

    /**
     * 用户id
     */
    private Long id;

    /**
     * 账号
     */
    private String account;

    /**
     * 角色，USER=普通用户，ADMIN=管理员
     */
    private String role;
}
