package com.kele.core.buz.doc.ao.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.ao.GroupAO;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.entity.GroupMember;
import com.kele.core.buz.doc.dao.entity.KeleGroup;
import com.kele.core.buz.sys.dao.entity.SysUserInfo;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.dao.mapper.GroupMemberMapper;
import com.kele.core.buz.doc.dao.mapper.KeleGroupMapper;
import com.kele.core.buz.sys.dao.mapper.SysUserInfoMapper;
import com.kele.core.buz.doc.model.vo.GroupDetailVO;
import com.kele.core.buz.doc.model.vo.GroupListVO;
import com.kele.core.buz.doc.model.vo.GroupMemberVO;
import com.kele.core.other.aspect.lock.ConcurrentLock;
import com.kele.core.other.context.LoginContext;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

/**
 * 群组管理 AO 实现（详见 spec §4.1）。所有方法做 {@code LoginContext.isAdmin()} 校验。
 * <p>
 * 关键联动：dissolveGroup 时级联 {@code doc_file_folder_acl}（principal_type=GROUP, principal_id=groupId）所有有效行 revoked_at=now()。
 * restoreGroup 时复活上述行（revoked_at=NULL）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupAOImpl implements GroupAO {

    private final KeleGroupMapper keleGroupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final DocFileFolderAclMapper docFileFolderAclMapper;
    private final SysUserInfoMapper sysUserInfoMapper;
    private final TransactionTemplate transactionTemplate;

    private void requireAdmin() {
        if (!LoginContext.isAdmin()) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "仅管理员可操作群组");
        }
    }

    @Override
    public Long createGroup(String name, String description, List<Long> memberIds) {
        requireAdmin();
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "群组名不能为空");
        }
        if (keleGroupMapper.selectCount(Wrappers.<KeleGroup>lambdaQuery()
                .eq(KeleGroup::getName, name).eq(KeleGroup::getStatus, 1)) > 0) {
            throw new BusinessException(ErrorCodeEnum.GROUP_NAME_DUPLICATE.getCode(), "群组名已存在");
        }
        Long creatorId = LoginContext.getUserId();
        LocalDateTime now = LocalDateTime.now();
        Long[] groupIdHolder = new Long[1];
        transactionTemplate.execute(status -> {
            KeleGroup group = new KeleGroup()
                .setName(name.trim())
                .setDescription(description)
                .setCreatedBy(creatorId)
                .setCreatedAt(now)
                .setStatus(1);
            keleGroupMapper.insert(group);
            groupIdHolder[0] = group.getId();
            if (!CollectionUtils.isEmpty(memberIds)) {
                List<GroupMember> rows = memberIds.stream().distinct()
                    .filter(uid -> !uid.equals(creatorId))
                    .map(uid -> new GroupMember()
                        .setGroupId(group.getId())
                        .setUserId(uid)
                        .setJoinedAt(now))
                    .collect(Collectors.toList());
                if (!rows.isEmpty()) {
                    rows.forEach(groupMemberMapper::insert);
                }
            }
            // 创建者自动成为成员
            GroupMember creatorMember = new GroupMember()
                .setGroupId(group.getId())
                .setUserId(creatorId)
                .setJoinedAt(now);
            groupMemberMapper.insert(creatorMember);
            return null;
        });
        return groupIdHolder[0];
    }

    @Override
    public void updateGroup(Long id, String name, String description) {
        requireAdmin();
        KeleGroup existing = keleGroupMapper.selectById(id);
        if (existing == null || existing.getStatus() == 0) {
            throw new BusinessException(ErrorCodeEnum.GROUP_NOT_FOUND.getCode(), "群组不存在或已解散");
        }
        if (name != null && !name.trim().isEmpty() && !name.equals(existing.getName())) {
            if (keleGroupMapper.selectCount(Wrappers.<KeleGroup>lambdaQuery()
                    .eq(KeleGroup::getName, name)
                    .ne(KeleGroup::getId, id)
                    .eq(KeleGroup::getStatus, 1)) > 0) {
                throw new BusinessException(ErrorCodeEnum.GROUP_NAME_DUPLICATE.getCode(), "群组名已存在");
            }
            existing.setName(name.trim());
        }
        existing.setDescription(description);
        keleGroupMapper.updateById(existing);
    }

    /**
     * 解散群组 + 级联 revoke 该群组所有 ACL 行（revoked_at=now()）。单事务。
     */
    @Override
    @ConcurrentLock(key = "kele.core.buz.doc.ao.impl.GroupAOImpl.dissolveGroup(${id})")
    public void dissolveGroup(Long id) {
        requireAdmin();
        KeleGroup existing = keleGroupMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCodeEnum.GROUP_NOT_FOUND.getCode(), "群组不存在");
        }
        if (existing.getStatus() == 0) {
            throw new BusinessException(ErrorCodeEnum.GROUP_DISMISSED.getCode(), "群组已解散");
        }
        if (groupMemberMapper.selectCount(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, id)) > 0) {
            throw new BusinessException(ErrorCodeEnum.GROUP_HAS_MEMBERS.getCode(), "请先清空群组成员");
        }
        transactionTemplate.execute(status -> {
            // 软撤销
            existing.setStatus(0);
            keleGroupMapper.updateById(existing);
            // 级联 revoke 该群组所有 ACL 行（保留原 granted_by/created_at/permission；仅撤销）
            docFileFolderAclMapper.update(null, Wrappers.<DocFileFolderAcl>lambdaUpdate()
                .eq(DocFileFolderAcl::getPrincipalType, "GROUP")
                .eq(DocFileFolderAcl::getPrincipalId, id)
                .isNull(DocFileFolderAcl::getRevokedAt)
                .set(DocFileFolderAcl::getRevokedAt, LocalDateTime.now()));
            return null;
        });
    }

    /**
     * 恢复已解散群组 + 复活之前 revoked_at != NULL 的 ACL 行。
     */
    @Override
    @ConcurrentLock(key = "kele.core.buz.doc.ao.impl.GroupAOImpl.restoreGroup(${id})")
    public void restoreGroup(Long id) {
        requireAdmin();
        KeleGroup existing = keleGroupMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCodeEnum.GROUP_NOT_FOUND.getCode(), "群组不存在");
        }
        if (existing.getStatus() == 1) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "群组未解散，无需恢复");
        }
        transactionTemplate.execute(status -> {
            // 恢复
            existing.setStatus(1);
            keleGroupMapper.updateById(existing);
            // 复活该群组之前被 revoke 的 ACL 行（保留 created_at，仅清 revoked_at）
            // 注：必须精确定位"v0.8 之前因该群组被解散而 revoke 的行"——通过 granted_by + 状态变更顺序难以判别，
            //      简化策略：复活"principal_type=GROUP AND principal_id=id AND revoked_at != NULL"全部行；
            //      这会复活包括"后加 ACL 又被手动 revoke"的情况，但 v1 不区分；如有冲突则人工处理。
            docFileFolderAclMapper.update(null, Wrappers.<DocFileFolderAcl>lambdaUpdate()
                .eq(DocFileFolderAcl::getPrincipalType, "GROUP")
                .eq(DocFileFolderAcl::getPrincipalId, id)
                .isNotNull(DocFileFolderAcl::getRevokedAt)
                .set(DocFileFolderAcl::getRevokedAt, null));
            return null;
        });
    }

    @Override
    public void addMembers(Long groupId, List<Long> userIds) {
        requireAdmin();
        if (keleGroupMapper.selectById(groupId) == null) {
            throw new BusinessException(ErrorCodeEnum.GROUP_NOT_FOUND.getCode(), "群组不存在");
        }
        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }
        // 已存在的不重复插入
        List<Long> existing = groupMemberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, groupId)
                .in(GroupMember::getUserId, userIds))
            .stream().map(GroupMember::getUserId).collect(Collectors.toList());
        List<Long> toAdd = userIds.stream().distinct().filter(uid -> !existing.contains(uid))
            .collect(Collectors.toList());
        if (toAdd.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<GroupMember> rows = toAdd.stream()
            .map(uid -> new GroupMember().setGroupId(groupId).setUserId(uid).setJoinedAt(now))
            .collect(Collectors.toList());
        rows.forEach(groupMemberMapper::insert);
    }

    @Override
    public void removeMember(Long groupId, Long userId) {
        requireAdmin();
        groupMemberMapper.delete(Wrappers.<GroupMember>lambdaQuery()
            .eq(GroupMember::getGroupId, groupId)
            .eq(GroupMember::getUserId, userId));
    }

    @Override
    public GroupListVO listGroups(int page, int size, String keyword) {
        requireAdmin();
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        if (page < 1) page = 1;
        LambdaQueryWrapper<KeleGroup> wrapper = Wrappers.<KeleGroup>lambdaQuery()
            .eq(KeleGroup::getStatus, 1)
            .like(keyword != null && !keyword.isEmpty(), KeleGroup::getName, keyword)
            .orderByDesc(KeleGroup::getCreatedAt);
        List<KeleGroup> records = keleGroupMapper.selectList(wrapper);
        Long total = keleGroupMapper.selectCount(wrapper);
        return new GroupListVO(records, total, page, size);
    }

    @Override
    public GroupDetailVO getGroupDetail(Long id) {
        requireAdmin();
        KeleGroup group = keleGroupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException(ErrorCodeEnum.GROUP_NOT_FOUND.getCode(), "群组不存在");
        }
        List<GroupMember> members = groupMemberMapper.selectList(
            Wrappers.<GroupMember>lambdaQuery().eq(GroupMember::getGroupId, id)
        );
        List<GroupMemberVO> memberVOs;
        if (members.isEmpty()) {
            memberVOs = Collections.emptyList();
        } else {
            List<Long> userIds = members.stream().map(GroupMember::getUserId).collect(Collectors.toList());
            Map<Long, SysUserInfo> userMap = sysUserInfoMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUserInfo::getId, u -> u, (a, b) -> a));
            memberVOs = members.stream().map(m -> {
                SysUserInfo u = userMap.get(m.getUserId());
                return new GroupMemberVO(
                    m.getUserId(),
                    u != null ? u.getAccount() : null,
                    u != null ? u.getUserName() : null,
                    u != null ? u.getAvatar() : null,
                    m.getJoinedAt() == null ? null : m.getJoinedAt().toString());
            }).collect(Collectors.toList());
        }
        return new GroupDetailVO(group, memberVOs);
    }

    @Override
    public List<GroupMemberVO> listMembers(Long id) {
        requireAdmin();
        List<GroupMember> members = groupMemberMapper.selectList(
            Wrappers.<GroupMember>lambdaQuery().eq(GroupMember::getGroupId, id)
        );
        if (members.isEmpty()) return Collections.emptyList();
        List<Long> userIds = members.stream().map(GroupMember::getUserId).collect(Collectors.toList());
        Map<Long, SysUserInfo> userMap = sysUserInfoMapper.selectBatchIds(userIds).stream()
            .collect(Collectors.toMap(SysUserInfo::getId, u -> u, (a, b) -> a));
        return members.stream().map(m -> {
            SysUserInfo u = userMap.get(m.getUserId());
            return new GroupMemberVO(
                m.getUserId(),
                u != null ? u.getAccount() : null,
                u != null ? u.getUserName() : null,
                u != null ? u.getAvatar() : null,
                m.getJoinedAt() == null ? null : m.getJoinedAt().toString());
        }).collect(Collectors.toList());
    }

    @Override
    public List<KeleGroup> getMyGroups() {
        // 任意登录用户可用——不调 requireAdmin()
        Long userId = LoginContext.getUserId();
        if (userId == null) return Collections.emptyList();
        // 成员关系
        List<Long> groupIds = groupMemberMapper.selectList(
                Wrappers.<GroupMember>lambdaQuery().eq(GroupMember::getUserId, userId)
            ).stream().map(GroupMember::getGroupId).collect(Collectors.toList());
        // 也包含自己创建的群组（兼容旧数据：创建时未自动加入）
        Set<Long> idSet = new java.util.HashSet<>(groupIds);
        keleGroupMapper.selectList(
            Wrappers.<KeleGroup>lambdaQuery()
                .eq(KeleGroup::getCreatedBy, userId)
                .eq(KeleGroup::getStatus, 1)
        ).forEach(g -> idSet.add(g.getId()));
        if (idSet.isEmpty()) return Collections.emptyList();
        return keleGroupMapper.selectList(
            Wrappers.<KeleGroup>lambdaQuery()
                .in(KeleGroup::getId, idSet)
                .eq(KeleGroup::getStatus, 1)
                .orderByDesc(KeleGroup::getCreatedAt)
        );
    }
}
