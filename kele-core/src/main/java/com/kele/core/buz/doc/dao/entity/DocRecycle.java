package com.kele.core.buz.doc.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 文档-回收站
 * </p>
 *
 * @author author
 * @since 2024-05-17
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@ApiModel(value="DocRecycle对象", description="文档-回收站")
public class DocRecycle implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "被回收的文件夹ID（v0.8 新增；旧实现里 id 字段 = folderId，现在拆开）")
    private Long folderId;

    @ApiModelProperty(value = "文件名")
    private String name;

    @ApiModelProperty(value = "回收人ID（= deleterId，向后兼容）")
    private Long userId;

    @ApiModelProperty(value = "删除者ID（v0.13 双视角：区分 deleter 和 owner）")
    private Long deleterId;

    @ApiModelProperty(value = "删除时的 folder ownerId 快照（v0.13 双视角）")
    private Long ownerAtDeleteId;

    @ApiModelProperty(value = "回收时间")
    private LocalDateTime createAt;

    @TableField(exist = false)
    @ApiModelProperty(value = "回收层级的id")
    private List<Long> idList;
}
