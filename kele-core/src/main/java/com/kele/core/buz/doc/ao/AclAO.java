package com.kele.core.buz.doc.ao;

import com.kele.core.buz.doc.model.vo.AclEntryVO;
import com.kele.core.buz.doc.model.vo.DocFileFolderAclListVO;
import java.util.List;

/**
 * ACL 管理 AO。详见 spec §4.2。
 * <p>
 * 鉴权：业务层 PermissionService 在 controller 的 @Authority 注解触发；AO 不重复校验。
 */
public interface AclAO {

    /** 列出本文件夹**本级**显式授权（不含继承）。READ+ 可查。 */
    DocFileFolderAclListVO listFolderAcl(Long folderId);

    /**
     * 批量授权（含 revoke-then-grant 全量替换）。
     * <p>
     * {@code replace=false}（默认）：追加 + 覆盖（每个 entry 单独 upsert）。
     * {@code replace=true}：先撤销该 folder 所有有效 ACL 行（revoked_at=now()），再插入 entries。
     * <p>
     * MANAGE+ 可调。
     */
    void grantAcl(Long folderId, List<AclEntryVO> entries, boolean replace);

    /** 撤销单条 ACL 行。MANAGE+。 */
    void revokeAcl(Long folderId, Long aclId);

    /** 修改单条 ACL 权限。MANAGE+。 */
    void updateAcl(Long folderId, Long aclId, String permission);

    /** 转让 Owner。仅 Owner（admin 旁路）。 */
    void transferOwner(Long folderId, Long newOwnerId);
}
