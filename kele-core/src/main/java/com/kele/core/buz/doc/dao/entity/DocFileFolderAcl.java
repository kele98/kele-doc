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
 * 文档文件夹 ACL（统一 ACL 表）。详见 spec §3.1。
 * <p>
 * revokedAt 语义：NULL=有效，非空=已撤销。
 * revokeReason：撤销原因（NULL=未撤销或复活后清空），用于 restoreGroup 精确过滤——
 * 只复活 GROUP_DISSOLVE 行，避免误复活 MANUAL/REPLACE 的行（静默权限提升漏洞）。
 * <p>
 * 注：本表不用 @TableLogic（@TableLogic 只支持 Integer/Boolean 字段；
 * revokedAt 是 LocalDateTime 语义"已撤销时间戳"），查询时显式加 isNull(revokedAt) 或依赖 uk_acl_active 唯一索引。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("doc_file_folder_acl")
@ApiModel(value = "DocFileFolderAcl", description = "文档文件夹 ACL")
public class DocFileFolderAcl implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty(value = "id")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty(value = "资源ID")
    private Long folderId;

    @ApiModelProperty(value = "主体类型：USER / GROUP / ORG")
    private String principalType;

    @ApiModelProperty(value = "主体ID：USER=userId, GROUP=groupId, ORG=NULL")
    private Long principalId;

    @ApiModelProperty(value = "权限：READ / WRITE / MANAGE")
    private String permission;

    @ApiModelProperty(value = "授权人ID")
    private Long grantedBy;

    @ApiModelProperty(value = "创建时间")
    private LocalDateTime createdAt;

    @ApiModelProperty(value = "撤销时间，NULL=有效")
    private LocalDateTime revokedAt;

    @ApiModelProperty(value = "撤销原因：GROUP_DISSOLVE/MANUAL/REPLACE，NULL=未撤销或复活后清空")
    private String revokeReason;
}
