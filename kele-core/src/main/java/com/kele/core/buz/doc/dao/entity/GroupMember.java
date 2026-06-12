package com.kele.core.buz.doc.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 群组成员。group_id + user_id 复合主键（PRIMARY KEY）。
 * <p>v0.13 #2: 删掉 @TableId 注解——MyBatis-Plus 把 @TableId 当单字段主键处理，
 * 但本表是复合主键，标 @TableId 在 group_id 上是误用：selectById/updateById/deleteById
 * 会按 group_id 单字段查/改/删，可能漏掉多行。
 * 现在两个字段都是普通字段，业务代码用 Wrappers.lambdaQuery() 手动拼复合主键条件（已是这样）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("group_member")
@ApiModel(value = "GroupMember", description = "群组成员")
public class GroupMember implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long groupId;

    private Long userId;

    private LocalDateTime joinedAt;
}
