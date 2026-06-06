package com.kele.core.buz.doc.model.vo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GET /api/doc/folders/{id}/acl 响应 VO。
 * <p>
 * 字段：ownerId/ownerName（READ+ 都返回）；isOrgPublic+orgPublicPermission；entries（每个 entry 含 grantedBy/createdAt，MANAGE+ 才返）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocFileFolderAclListVO {
    private Long ownerId;
    private String ownerName;
    private Boolean isOrgPublic;
    private String orgPublicPermission;  // 仅 isOrgPublic=true 时有意义
    private List<AclEntryVO> entries;
}
