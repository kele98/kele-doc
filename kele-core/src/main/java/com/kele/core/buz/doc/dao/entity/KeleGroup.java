package com.kele.core.buz.doc.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 群组。表名 `group` 是 MySQL 保留字，必须加反引号。
 * 类名 KeleGroup 避免与 java.lang.Object#getClass 反射冲突。
 * @TableName("`group`") 必须显式反引号——MyBatis-Plus 3.5.3.1 默认生成的 SQL 不会加反引号，否则 1064 syntax error。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("`group`")
@ApiModel(value = "KeleGroup", description = "群组")
public class KeleGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "群组名")
    private String name;

    @ApiModelProperty(value = "群组描述")
    private String description;

    @ApiModelProperty(value = "创建人ID")
    private Long createdBy;

    @ApiModelProperty(value = "创建时间")
    private LocalDateTime createdAt;

    @ApiModelProperty(value = "1=正常，0=已解散")
    private Integer status;
}
