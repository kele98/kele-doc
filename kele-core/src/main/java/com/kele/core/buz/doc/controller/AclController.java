package com.kele.core.buz.doc.controller;

import com.kele.common.model.ResponseResult;
import com.kele.core.buz.doc.ao.AclAO;
import com.kele.core.buz.doc.model.vo.DocFileFolderAclListVO;
import com.kele.core.buz.doc.model.vo.GrantAclReqVO;
import com.kele.core.buz.doc.model.vo.TransferOwnerReqVO;
import com.kele.core.other.aspect.authority.Authority;
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
 * ACL 管理 Controller（详见 spec §4.2 + §10.7）。
 * <p>
 * 鉴权走现有 {@code @Authority} 注解（OGNL 而非 SpEL，详见 Authority.java + spec §10.7）：
 * - GET    需 READ  (PermissionService.requireRead)
 * - POST/PUT/DELETE 需 MANAGE (PermissionService.requireManage)
 * - PUT /owner 仅 Owner 可调（admin 旁路由 isAdmin 走旁路）
 */
@RestController
@RequestMapping("/api/doc/folders")
@RequiredArgsConstructor
public class AclController {

    private final AclAO aclAO;

    @GetMapping("/{id}/acl")
    @Authority(expressionArgs = "#folderId", methodName = "requireRead", beanName = "permissionService")
    public ResponseResult<DocFileFolderAclListVO> listAcl(@PathVariable("id") Long folderId) {
        return ResponseResult.ok(aclAO.listFolderAcl(folderId));
    }

    @PostMapping("/{id}/acl")
    @Authority(expressionArgs = "#folderId", methodName = "requireManage", beanName = "permissionService")
    public ResponseResult<Void> grantAcl(@PathVariable("id") Long folderId, @RequestBody GrantAclReqVO req) {
        aclAO.grantAcl(folderId, req.getEntries(), req.isReplace());
        return ResponseResult.ok();
    }

    @DeleteMapping("/{id}/acl/{aclId}")
    @Authority(expressionArgs = "#folderId", methodName = "requireManage", beanName = "permissionService")
    public ResponseResult<Void> revokeAcl(@PathVariable("id") Long folderId, @PathVariable Long aclId) {
        aclAO.revokeAcl(folderId, aclId);
        return ResponseResult.ok();
    }

    @PutMapping("/{id}/acl/{aclId}")
    @Authority(expressionArgs = "#folderId", methodName = "requireManage", beanName = "permissionService")
    public ResponseResult<Void> updateAcl(@PathVariable("id") Long folderId,
                                          @PathVariable Long aclId,
                                          @RequestBody UpdateAclReqVO req) {
        aclAO.updateAcl(folderId, aclId, req.getPermission());
        return ResponseResult.ok();
    }

    @PutMapping("/{id}/owner")
    @Authority(expressionArgs = "#folderId", methodName = "requireManage", beanName = "permissionService")
    public ResponseResult<Void> transferOwner(@PathVariable("id") Long folderId,
                                                @RequestBody TransferOwnerReqVO req) {
        // 业务层 transferOwner 校验"== 当前 owner_id"（CAS）；非 owner 调到这里会先被 requireManage 拒（除非 admin 旁路）
        aclAO.transferOwner(folderId, req.getNewOwnerId());
        return ResponseResult.ok();
    }

    @lombok.Data public static class UpdateAclReqVO {
        private String permission;  // READ / WRITE / MANAGE
    }
}
