package com.kele.core.buz.doc.permission;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderMapper;
import com.kele.core.buz.doc.dao.mapper.GroupMemberMapper;
import com.kele.core.other.context.LoginContext;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

/**
 * 权限解析引擎。详见 spec §5.2 (app-layer walk) + §5.3 (CTE primary)。
 * <p>
 * <b>v0.7 行为</b>：本类是默认激活的 app-layer walk fallback（{@code matchIfMissing=true}）。
 * 当 {@code kele.doc.permission.cte-primary=true} 时本类被
 * {@link PermissionServiceCtePrimary} 覆盖。CTE 实现补完后再切。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kele.doc.permission.fallback", havingValue = "false", matchIfMissing = true)
public class PermissionService {

    @Autowired private DocFileFolderMapper docFileFolderMapper;
    @Autowired private DocFileFolderAclMapper docFileFolderAclMapper;
    @Autowired private GroupMemberMapper groupMemberMapper;

    /**
     * 解析"用户 U 对资源 R 的有效权限"。详见 spec §5.2 算法不变量：
     * 先走完所有祖先链，收集全部候选，最后按 source 优先级 + permission 大小 选 best。
     * 不早退（避免祖先 Owner 检查被本层 ACL 早退跳过）。
     */
    public PermissionResult resolve(Long userId, Long folderId) {
        if (userId == null || folderId == null) {
            return PermissionResult.none();
        }
        List<Long> groupIds = groupMemberMapper.selectList(
                Wrappers.<com.kele.core.buz.doc.dao.entity.GroupMember>lambdaQuery()
                        .eq(com.kele.core.buz.doc.dao.entity.GroupMember::getUserId, userId)
        ).stream().map(com.kele.core.buz.doc.dao.entity.GroupMember::getGroupId).collect(java.util.stream.Collectors.toList());
        List<Long> safeGroupIds = CollectionUtils.isEmpty(groupIds) ? Collections.emptyList() : groupIds;

        PermissionResult best = PermissionResult.none();
        Long cursor = folderId;
        while (cursor != null) {
            DocFileFolder folder = docFileFolderMapper.selectById(cursor);
            if (folder == null) {
                break;
            }

            // 1. Owner 隐式 MANAGE（最高优先级）
            if (userId.equals(folder.getOwnerId())) {
                return PermissionResult.owner(cursor);
            }

            // 2. 本层 ACL 命中
            List<DocFileFolderAcl> acls = docFileFolderAclMapper.selectList(
                    Wrappers.<DocFileFolderAcl>lambdaQuery()
                            .eq(DocFileFolderAcl::getFolderId, cursor)
                            .isNull(DocFileFolderAcl::getRevokedAt)
            );
            for (DocFileFolderAcl acl : acls) {
                if (!match(acl, userId, safeGroupIds)) {
                    continue;
                }
                PermissionLevel level = PermissionLevel.valueOf(acl.getPermission());
                boolean isDirect = cursor.equals(folderId);
                PermissionResult.Source source;
                if (isDirect) {
                    source = PermissionResult.Source.DIRECT;
                } else if ("GROUP".equals(acl.getPrincipalType())) {
                    source = PermissionResult.Source.GROUP;
                } else {
                    source = PermissionResult.Source.INHERITED;
                }
                PermissionResult candidate = PermissionResult.acl(level, source, cursor);
                if (betterOf(best, candidate) == candidate) {
                    best = candidate;
                }
                // 命中 MANAGE + DIRECT 可早退（其他更高级别不存在）
                if (best.getLevel() == PermissionLevel.MANAGE
                        && best.getSource() == PermissionResult.Source.DIRECT) {
                    return best;
                }
            }

            cursor = folder.getParentId();
        }
        return best;
    }

    private boolean match(DocFileFolderAcl acl, Long userId, List<Long> groupIds) {
        if ("USER".equals(acl.getPrincipalType())) {
            return userId.equals(acl.getPrincipalId());
        }
        if ("GROUP".equals(acl.getPrincipalType())) {
            return acl.getPrincipalId() != null && groupIds.contains(acl.getPrincipalId());
        }
        return "ORG".equals(acl.getPrincipalType());
    }

    private PermissionResult betterOf(PermissionResult a, PermissionResult b) {
        int rankA = sourceRank(a.getSource());
        int rankB = sourceRank(b.getSource());
        if (rankA != rankB) {
            return rankA > rankB ? a : b;
        }
        return a.getLevel().getValue() >= b.getLevel().getValue() ? a : b;
    }

    private int sourceRank(PermissionResult.Source s) {
        switch (s) {
            case OWNER: return 4;
            case DIRECT: return 3;
            case GROUP: return 2;
            case INHERITED: return 1;
            default: return 0;
        }
    }

    /** 鉴权并抛错——MANAGE 级别以上 */
    public void requireManage(Long folderId) {
        Long userId = LoginContext.getUserId();
        PermissionResult r = resolve(userId, folderId);
        if (!r.getLevel().atLeast(PermissionLevel.MANAGE)) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "权限不足");
        }
    }

    public void requireWrite(Long folderId) {
        Long userId = LoginContext.getUserId();
        PermissionResult r = resolve(userId, folderId);
        if (!r.getLevel().atLeast(PermissionLevel.WRITE)) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "权限不足");
        }
    }

    public void requireRead(Long folderId) {
        Long userId = LoginContext.getUserId();
        PermissionResult r = resolve(userId, folderId);
        if (!r.getLevel().atLeast(PermissionLevel.READ)) {
            // 屏蔽存在性：返回 404 而非 403（防探测攻击）
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "资源不存在或无权访问");
        }
    }
}
