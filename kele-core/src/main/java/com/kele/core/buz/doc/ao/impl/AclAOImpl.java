package com.kele.core.buz.doc.ao.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.ao.AclAO;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.entity.GroupMember;
import com.kele.core.buz.doc.dao.entity.KeleGroup;
import com.kele.core.buz.sys.dao.entity.SysUserInfo;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderMapper;
import com.kele.core.buz.doc.dao.mapper.GroupMemberMapper;
import com.kele.core.buz.doc.dao.mapper.KeleGroupMapper;
import com.kele.core.buz.sys.dao.mapper.SysUserInfoMapper;
import com.kele.core.buz.doc.model.vo.AclEntryVO;
import com.kele.core.buz.doc.model.vo.DocFileFolderAclListVO;
import com.kele.core.other.context.LoginContext;
import com.kele.core.buz.doc.permission.AclRevokeReason;
import com.kele.core.buz.doc.permission.PermissionLevel;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

/**
 * ACL 管理 AO 实现（详见 spec §4.2 + §5.5）。
 * <p>
 * 鉴权：controller 的 @Authority 注解（OGNL 调 permissionService.requireManage/requireRead）
 *       在 controller 入口已做；本类不重复校验。
 * <p>
 * 关键策略：
 * - 撤销最后一条 MANAGE 时抛 CANNOT_REMOVE_LAST_MANAGE（v0.7 §5.5）
 * - replace=true 时先撤销整 folder 有效 ACL 再 insert
 * - transferOwner 用 CAS（owner_id=:id AND owner_id=:oldOwner 条件 UPDATE）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AclAOImpl implements AclAO {

    private final DocFileFolderAclMapper docFileFolderAclMapper;
    private final DocFileFolderMapper docFileFolderMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final KeleGroupMapper keleGroupMapper;
    private final SysUserInfoMapper sysUserInfoMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public DocFileFolderAclListVO listFolderAcl(Long folderId) {
        DocFileFolder folder = docFileFolderMapper.selectById(folderId);
        if (folder == null) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "文件夹不存在");
        }
        // owner 字段对所有 READ+ 都返回
        SysUserInfo owner = sysUserInfoMapper.selectById(folder.getOwnerId());

        // 列出本级有效 ACL
        List<DocFileFolderAcl> acls = docFileFolderAclMapper.selectList(
            Wrappers.<DocFileFolderAcl>lambdaQuery()
                .eq(DocFileFolderAcl::getFolderId, folderId)
                .isNull(DocFileFolderAcl::getRevokedAt)
        );
        List<AclEntryVO> entries = new ArrayList<>();
        boolean isOrgPublic = false;
        String orgPublicPermission = null;
        if (!acls.isEmpty()) {
            // 收集 USER 和 GROUP 的 principal id
            Set<Long> userIds = new HashSet<>();
            Set<Long> groupIds = new HashSet<>();
            for (DocFileFolderAcl acl : acls) {
                if ("USER".equals(acl.getPrincipalType())) {
                    userIds.add(acl.getPrincipalId());
                } else if ("GROUP".equals(acl.getPrincipalType())) {
                    groupIds.add(acl.getPrincipalId());
                }
            }
            // USER：userName 为 null 时 fallback 到 account
            Map<Long, String> userNameMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : sysUserInfoMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(
                        SysUserInfo::getId,
                        u -> u.getUserName() != null ? u.getUserName() : u.getAccount(),
                        (a, b) -> a));
            // GROUP：查实际群组名
            Map<Long, String> groupNameMap = groupIds.isEmpty()
                ? Collections.emptyMap()
                : keleGroupMapper.selectBatchIds(groupIds).stream()
                    .filter(g -> g.getName() != null)
                    .collect(Collectors.toMap(KeleGroup::getId, KeleGroup::getName, (a, b) -> a));

            for (DocFileFolderAcl acl : acls) {
                if ("ORG".equals(acl.getPrincipalType())) {
                    isOrgPublic = true;
                    orgPublicPermission = acl.getPermission();
                    // ORG 仍写一个 entry 让前端可见（permission 字段会原样回）
                    entries.add(new AclEntryVO(acl.getId(), "ORG", null, acl.getPermission(),
                        "全员", acl.getGrantedBy(), acl.getCreatedAt() == null ? null : acl.getCreatedAt().toString()));
                } else {
                    String name = "USER".equals(acl.getPrincipalType())
                        ? userNameMap.getOrDefault(acl.getPrincipalId(), "")
                        : groupNameMap.getOrDefault(acl.getPrincipalId(), "");
                    entries.add(new AclEntryVO(acl.getId(), acl.getPrincipalType(), acl.getPrincipalId(),
                        acl.getPermission(), name, acl.getGrantedBy(),
                        acl.getCreatedAt() == null ? null : acl.getCreatedAt().toString()));
                }
            }
        }

        return new DocFileFolderAclListVO(
            folder.getOwnerId(),
            owner != null ? owner.getUserName() : null,
            isOrgPublic,
            orgPublicPermission,
            entries);
    }

    @Override
    public void grantAcl(Long folderId, List<AclEntryVO> entries, boolean replace) {
        if (CollectionUtils.isEmpty(entries)) return;
        Long grantorId = LoginContext.getUserId();
        LocalDateTime now = LocalDateTime.now();
        transactionTemplate.execute(status -> {
            if (replace) {
                // 全量替换：先撤销本 folder 全部有效 ACL
                // 写 revoke_reason='REPLACE'，避免后续 restoreGroup 时被误复活
                docFileFolderAclMapper.update(null, Wrappers.<DocFileFolderAcl>lambdaUpdate()
                    .eq(DocFileFolderAcl::getFolderId, folderId)
                    .isNull(DocFileFolderAcl::getRevokedAt)
                    .set(DocFileFolderAcl::getRevokedAt, now)
                    .set(DocFileFolderAcl::getRevokeReason, AclRevokeReason.REPLACE));
            }
            for (AclEntryVO e : entries) {
                validateEntry(e);
                // 查现有有效行
                DocFileFolderAcl existing = docFileFolderAclMapper.selectOne(Wrappers.<DocFileFolderAcl>lambdaQuery()
                    .eq(DocFileFolderAcl::getFolderId, folderId)
                    .eq(DocFileFolderAcl::getPrincipalType, e.getPrincipalType())
                    .eq(DocFileFolderAcl::getPrincipalId,
                        "ORG".equals(e.getPrincipalType()) ? null : e.getPrincipalId())
                    .isNull(DocFileFolderAcl::getRevokedAt));
                if (existing != null) {
                    existing.setPermission(e.getPermission());
                    docFileFolderAclMapper.updateById(existing);
                } else {
                    DocFileFolderAcl row = new DocFileFolderAcl()
                        .setFolderId(folderId)
                        .setPrincipalType(e.getPrincipalType())
                        .setPrincipalId("ORG".equals(e.getPrincipalType()) ? null : e.getPrincipalId())
                        .setPermission(e.getPermission())
                        .setGrantedBy(grantorId)
                        .setCreatedAt(now);
                    try {
                        docFileFolderAclMapper.insert(row);
                    } catch (DuplicateKeyException dup) {
                        // v0.7 修复：并发场景下两请求同时 selectOne 都没查到现有行 → 两条 INSERT
                        // 撞 uk_acl_active 唯一索引 → 翻译为 409 ACL_DUPLICATE（而非 500）
                        // 抛 BusinessException 自然让事务回滚
                        throw new BusinessException(ErrorCodeEnum.ACL_DUPLICATE.getCode(),
                            "该主体已被授权（并发冲突）");
                    }
                }
            }
            // 撤销最后一条 MANAGE 校验
            enforceLastManage(folderId);
            return null;
        });
    }

    @Override
    public void revokeAcl(Long folderId, Long aclId) {
        DocFileFolderAcl acl = docFileFolderAclMapper.selectById(aclId);
        if (acl == null || !acl.getFolderId().equals(folderId)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "ACL 行不存在");
        }
        if (acl.getRevokedAt() != null) {
            return; // 已撤销
        }
        transactionTemplate.execute(status -> {
            acl.setRevokedAt(LocalDateTime.now());
            acl.setRevokeReason(AclRevokeReason.MANUAL);
            docFileFolderAclMapper.updateById(acl);
            enforceLastManage(folderId);
            return null;
        });
    }

    @Override
    public void updateAcl(Long folderId, Long aclId, String permission) {
        if (!PermissionLevel.READ.name().equals(permission)
            && !PermissionLevel.WRITE.name().equals(permission)
            && !PermissionLevel.MANAGE.name().equals(permission)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "非法权限值");
        }
        DocFileFolderAcl acl = docFileFolderAclMapper.selectById(aclId);
        if (acl == null || !acl.getFolderId().equals(folderId)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "ACL 行不存在");
        }
        if (acl.getRevokedAt() != null) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "已撤销的 ACL 不能修改");
        }
        transactionTemplate.execute(status -> {
            acl.setPermission(permission);
            docFileFolderAclMapper.updateById(acl);
            enforceLastManage(folderId);
            return null;
        });
    }

    @Override
    public void transferOwner(Long folderId, Long newOwnerId) {
        // v0.7 §4.2：转让 Owner 仅 Owner 可调（admin 旁路）
        // 之前用 @Authority(requireManage) 允许任何 MANAGE 用户转让——非 Owner 的 MANAGE 用户
        // 可偷家把 Owner 转给自己。spec §4.2 要求严格 owner-only。
        DocFileFolder folder = docFileFolderMapper.selectById(folderId);
        if (folder == null) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "文件夹不存在");
        }
        Long callerId = LoginContext.getUserId();
        boolean isOwner = folder.getOwnerId() != null && folder.getOwnerId().equals(callerId);
        boolean isAdmin = LoginContext.isAdmin();
        if (!isOwner && !isAdmin) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(),
                "仅 Owner 或 admin 可转让所有权");
        }
        if (newOwnerId == null || newOwnerId.equals(folder.getOwnerId())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "新 Owner 不能为空或等于当前 Owner");
        }
        SysUserInfo newOwner = sysUserInfoMapper.selectById(newOwnerId);
        if (newOwner == null || newOwner.getStatus() == -1) {
            throw new BusinessException(ErrorCodeEnum.USER_NOT_FOUND.getCode(), "新 Owner 用户不存在或已注销");
        }
        // CAS：条件 owner_id = 当前
        int updated = docFileFolderMapper.update(null, Wrappers.<DocFileFolder>lambdaUpdate()
            .set(DocFileFolder::getOwnerId, newOwnerId)
            .eq(DocFileFolder::getId, folderId)
            .eq(DocFileFolder::getOwnerId, folder.getOwnerId()));
        if (updated == 0) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "转让失败：Owner 已被其他操作修改");
        }
    }

    // ---- 辅助 ----

    private void validateEntry(AclEntryVO e) {
        if (e.getPrincipalType() == null
            || !("USER".equals(e.getPrincipalType()) || "GROUP".equals(e.getPrincipalType()) || "ORG".equals(e.getPrincipalType()))) {
            throw new BusinessException(ErrorCodeEnum.INVALID_ACL_PRINCIPAL_TYPE.getCode(), "非法主体类型");
        }
        if (!PermissionLevel.READ.name().equals(e.getPermission())
            && !PermissionLevel.WRITE.name().equals(e.getPermission())
            && !PermissionLevel.MANAGE.name().equals(e.getPermission())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "非法权限值");
        }
        if (e.getPrincipalType().equals("ORG")) {
            if (!PermissionLevel.READ.name().equals(e.getPermission())) {
                throw new BusinessException(ErrorCodeEnum.ORG_PUBLIC_PERMISSION_INVALID.getCode(), "组织内公开仅支持 READ 权限");
            }
        } else {
            if (e.getPrincipalId() == null) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "USER/GROUP 主体必须传 principalId");
            }
        }
    }

    /**
     * 撤销/修改后校验"至少保留一条 MANAGE 有效行"。该 folder 本身 owner 永远算 MANAGE。
     * <p>
     * v0.7 修正：原逻辑只查 ACL 表里 USER/GROUP 的 MANAGE 行数，userCount==0 就抛错。
     * 但 owner 本身也算 MANAGE（命中 OWNER 直接返 MANAGE），
     * 刚创建的文件夹没有 ACL MANAGE 行时撤销唯一一条 READ/WRITE 会被误报。
     * 修正：owner 存在时直接 return（owner 本身就是 MANAGE 兜底）。
     */
    private void enforceLastManage(Long folderId) {
        DocFileFolder folder = docFileFolderMapper.selectById(folderId);
        if (folder == null) return;  // 已删
        // owner 永远算 MANAGE——folder 有 owner 就直接放过（无需 ACL 行兜底）
        if (folder.getOwnerId() != null) {
            return;
        }
        // owner 为 null 的孤儿 folder 才检查 ACL：至少要 1 条有效 USER/GROUP MANAGE
        Long userCount = docFileFolderAclMapper.selectCount(Wrappers.<DocFileFolderAcl>lambdaQuery()
            .eq(DocFileFolderAcl::getFolderId, folderId)
            .eq(DocFileFolderAcl::getPermission, "MANAGE")
            .isNull(DocFileFolderAcl::getRevokedAt)
            .in(DocFileFolderAcl::getPrincipalType, "USER", "GROUP"));
        if (userCount == 0) {
            throw new BusinessException(ErrorCodeEnum.CANNOT_REMOVE_LAST_MANAGE.getCode(), "至少需要保留一个管理者");
        }
    }
}
