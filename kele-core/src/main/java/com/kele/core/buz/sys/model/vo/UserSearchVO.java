package com.kele.core.buz.sys.model.vo;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户搜索响应 VO（详见 spec §4.1.1 /api/users/search）。
 * <p>
 * 公共搜索仅返前 4 个字段；管理员列表返回全部 7 个字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSearchVO {
    private Long id;
    private String account;
    private String nickname;  // 对应 sys_user_info.user_name
    private String avatar;
    private String role;
    private Integer status;
    private LocalDateTime createAt;

    public UserSearchVO(Long id, String account, String nickname, String avatar) {
        this.id = id;
        this.account = account;
        this.nickname = nickname;
        this.avatar = avatar;
    }
}
