package com.kele.core.buz.doc.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 群组成员。group_id + user_id 复合主键（PRIMARY KEY），MyBatis-Plus 通过 @TableId 标注一个字段做主键。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("group_member")
@ApiModel(value = "GroupMember", description = "群组成员")
public class GroupMember implements Serializable {

    private static final long serialVersionUID = 1L;

    @com.baomidou.mybatisplus.annotation.TableId(value = "group_id")
    private Long groupId;

    private Long userId;

    private LocalDateTime joinedAt;
}
