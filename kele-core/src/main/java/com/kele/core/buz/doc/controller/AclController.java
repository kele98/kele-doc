package com.kele.core.buz.doc.controller;

import com.kele.common.model.ResponseResult;
import com.kele.core.buz.doc.ao.AclAO;
import com.kele.core.buz.doc.model.vo.DocFileFolderAclListVO;
import com.kele.core.buz.doc.model.vo.GrantAclReqVO;
import com.kele.core.buz.doc.model.vo.TransferOwnerReqVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ACL 管理 Controller（详见 spec §4.2）。
 * <p>
 * 鉴权已统一到 AO 层 inline requireXxx（v0.13 #15），与项目其他 AO 一致。
 * 旧 @Authority 注解已标 @Deprecated，本 controller 不再使用。
 */
@RestController
@RequestMapping("/api/doc/folders")
@RequiredArgsConstructor
public class AclController {

    private final AclAO aclAO;

    @GetMapping("/{id}/acl")
    public ResponseResult<DocFileFolderAclListVO> listAcl(@PathVariable("id") Long folderId) {
        return ResponseResult.ok(aclAO.listFolderAcl(folderId));
    }

    @PostMapping("/{id}/acl")
    public ResponseResult<Void> grantAcl(@PathVariable("id") Long folderId, @RequestBody GrantAclReqVO req) {
        aclAO.grantAcl(folderId, req.getEntries(), req.isReplace());
        return ResponseResult.ok();
    }

    @DeleteMapping("/{id}/acl/{aclId}")
    public ResponseResult<Void> revokeAcl(@PathVariable("id") Long folderId, @PathVariable Long aclId) {
        aclAO.revokeAcl(folderId, aclId);
        return ResponseResult.ok();
    }

    @PutMapping("/{id}/acl/{aclId}")
    public ResponseResult<Void> updateAcl(@PathVariable("id") Long folderId,
                                          @PathVariable Long aclId,
                                          @RequestBody UpdateAclReqVO req) {
        aclAO.updateAcl(folderId, aclId, req.getPermission());
        return ResponseResult.ok();
    }

    @PutMapping("/{id}/owner")
    public ResponseResult<Void> transferOwner(@PathVariable("id") Long folderId,
                                                @RequestBody TransferOwnerReqVO req) {
        // 鉴权已迁移到 AclAO.transferOwner 内部：requireManage 兜底 + owner-only 细校
        aclAO.transferOwner(folderId, req.getNewOwnerId());
        return ResponseResult.ok();
    }

    @lombok.Data public static class UpdateAclReqVO {
        private String permission;  // READ / WRITE / MANAGE
    }
}
