package com.kele.core.buz.doc.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群组成员 VO（含用户基本信息）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberVO {
    private Long userId;
    private String account;
    private String nickname;
    private String avatar;
    private String joinedAt;  // ISO string，前端直接展示
}
