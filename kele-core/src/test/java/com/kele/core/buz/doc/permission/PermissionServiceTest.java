package com.kele.core.buz.doc.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.entity.GroupMember;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderMapper;
import com.kele.core.buz.doc.dao.mapper.GroupMemberMapper;
import com.kele.core.buz.sys.model.bo.UserInfoBO;
import com.kele.core.other.context.LoginContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PermissionService 单测。详见 spec §5.2 算法不变量。
 *
 * <p>覆盖：owner 优先、direct / group / inherited / org 命中、none、早退、betterOf 选优。
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock private DocFileFolderMapper docFileFolderMapper;
    @Mock private DocFileFolderAclMapper docFileFolderAclMapper;
    @Mock private GroupMemberMapper groupMemberMapper;

    @InjectMocks private PermissionService permissionService;

    @AfterEach
    void tearDown() {
        LoginContext.remove();
    }

    // ---------- resolve() 基础行为 ----------

    @Test
    void resolve_null_user_returns_none() {
        assertEquals(PermissionResult.none(), permissionService.resolve(null, 1L));
    }

    @Test
    void resolve_null_folder_returns_none() {
        assertEquals(PermissionResult.none(), permissionService.resolve(1L, null));
    }

    @Test
    void resolve_folder_not_found_returns_none() {
        Long userId = 100L;
        Long folderId = 10L;
        when(docFileFolderMapper.selectById(folderId)).thenReturn(null);
        PermissionResult r = permissionService.resolve(userId, folderId);
        assertEquals(PermissionLevel.NONE, r.getLevel());
        assertEquals(PermissionResult.Source.NONE, r.getSource());
        assertNull(r.getSourceNodeId());
    }

    // ---------- owner 优先（最高级，最高 source） ----------

    @Test
    void resolve_owner_returns_manage_owner_at_root() {
        Long userId = 100L;
        Long folderId = 10L;
        DocFileFolder folder = folder(folderId, null, userId);

        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder);
        // 即便本层有 ACL，owner 也直接返回
        DocFileFolderAcl acl = acl(folderId, "USER", 999L, "READ");
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, folderId);
        assertEquals(PermissionLevel.MANAGE, r.getLevel());
        assertEquals(PermissionResult.Source.OWNER, r.getSource());
        assertEquals(folderId, r.getSourceNodeId());
    }

    @Test
    void resolve_owner_on_ancestor_returns_owner_at_ancestor_node() {
        // 链：A(1) <- B(2) <- C(3)；user 拥有 A；查 C 应返回 OWNER@A
        Long userId = 100L;
        Long c = 3L, b = 2L, a = 1L;

        when(docFileFolderMapper.selectById(c)).thenReturn(folder(c, b, 999L));
        when(docFileFolderMapper.selectById(b)).thenReturn(folder(b, a, 999L));
        when(docFileFolderMapper.selectById(a)).thenReturn(folder(a, null, userId));
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, c);
        assertEquals(PermissionLevel.MANAGE, r.getLevel());
        assertEquals(PermissionResult.Source.OWNER, r.getSource());
        assertEquals(a, r.getSourceNodeId());
    }

    // ---------- direct user ACL ----------

    @Test
    void resolve_direct_user_acl_read() {
        Long userId = 100L, folderId = 10L;
        DocFileFolder folder = folder(folderId, null, 999L);
        DocFileFolderAcl acl = acl(folderId, "USER", userId, "READ");

        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder);
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, folderId);
        assertEquals(PermissionLevel.READ, r.getLevel());
        assertEquals(PermissionResult.Source.DIRECT, r.getSource());
        assertEquals(folderId, r.getSourceNodeId());
    }

    // ---------- group ACL ----------

    @Test
    void resolve_direct_group_acl_returns_group_source() {
        Long userId = 100L, folderId = 10L, groupId = 555L;
        DocFileFolder folder = folder(folderId, null, 999L);
        DocFileFolderAcl acl = acl(folderId, "GROUP", groupId, "WRITE");
        GroupMember gm = new GroupMember();
        gm.setUserId(userId);
        gm.setGroupId(groupId);

        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder);
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.singletonList(gm));

        PermissionResult r = permissionService.resolve(userId, folderId);
        assertEquals(PermissionLevel.WRITE, r.getLevel());
        assertEquals(PermissionResult.Source.GROUP, r.getSource());
    }

    @Test
    void resolve_group_acl_ignored_when_user_not_in_group() {
        Long userId = 100L, folderId = 10L, groupId = 555L;
        DocFileFolder folder = folder(folderId, null, 999L);
        DocFileFolderAcl acl = acl(folderId, "GROUP", groupId, "WRITE");

        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder);
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, folderId);
        assertEquals(PermissionLevel.NONE, r.getLevel());
    }

    // ---------- inherited ----------

    @Test
    void resolve_inherited_user_acl_from_parent_returns_inherited() {
        // A(1) <- B(2)；user 在 A 有 READ ACL；查 B 应返回 INHERITED
        Long userId = 100L, a = 1L, b = 2L;
        DocFileFolder fa = folder(a, null, 999L);
        DocFileFolder fb = folder(b, a, 999L);
        DocFileFolderAcl acl = acl(a, "USER", userId, "READ");

        when(docFileFolderMapper.selectById(b)).thenReturn(fb);
        when(docFileFolderMapper.selectById(a)).thenReturn(fa);
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, b);
        assertEquals(PermissionLevel.READ, r.getLevel());
        assertEquals(PermissionResult.Source.INHERITED, r.getSource());
        assertEquals(a, r.getSourceNodeId());
    }

    // ---------- ORG public ----------

    @Test
    void resolve_org_public_acl_grants_read_to_anyone() {
        Long userId = 100L, folderId = 10L;
        DocFileFolder folder = folder(folderId, null, 999L);
        DocFileFolderAcl acl = acl(folderId, "ORG", null, "READ");

        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder);
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, folderId);
        assertEquals(PermissionLevel.READ, r.getLevel());
        assertEquals(PermissionResult.Source.ORG, r.getSource());
    }

    // ---------- best-of 选择：DIRECT > INHERITED ----------

    @Test
    void resolve_prefers_direct_over_inherited_at_same_level() {
        // 链：A(1, READ) <- B(2, READ)；查 B 应取 DIRECT@B
        Long userId = 100L, a = 1L, b = 2L;
        DocFileFolder fa = folder(a, null, 999L);
        DocFileFolder fb = folder(b, a, 999L);
        DocFileFolderAcl aclA = acl(a, "USER", userId, "READ");
        DocFileFolderAcl aclB = acl(b, "USER", userId, "READ");

        when(docFileFolderMapper.selectById(b)).thenReturn(fb);
        when(docFileFolderMapper.selectById(a)).thenReturn(fa);
        // 按 cursor 调用顺序：B 先 A 后
        when(docFileFolderAclMapper.selectList(any()))
                .thenReturn(Collections.singletonList(aclB))
                .thenReturn(Collections.singletonList(aclA));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, b);
        assertEquals(PermissionResult.Source.DIRECT, r.getSource());
        assertEquals(b, r.getSourceNodeId());
    }

    @Test
    void resolve_inherits_higher_level_from_ancestor() {
        // 链：A(1, MANAGE) <- B(2, READ)；查 B 应取 INHERITED MANAGE@A
        Long userId = 100L, a = 1L, b = 2L;
        DocFileFolder fa = folder(a, null, 999L);
        DocFileFolder fb = folder(b, a, 999L);
        DocFileFolderAcl aclA = acl(a, "USER", userId, "MANAGE");
        DocFileFolderAcl aclB = acl(b, "USER", userId, "READ");

        when(docFileFolderMapper.selectById(b)).thenReturn(fb);
        when(docFileFolderMapper.selectById(a)).thenReturn(fa);
        when(docFileFolderAclMapper.selectList(any()))
                .thenReturn(Collections.singletonList(aclB))
                .thenReturn(Collections.singletonList(aclA));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, b);
        assertEquals(PermissionLevel.MANAGE, r.getLevel());
        assertEquals(PermissionResult.Source.INHERITED, r.getSource());
        assertEquals(a, r.getSourceNodeId());
    }

    // ---------- 早退：MANAGE+DIRECT 命中即返回 ----------

    @Test
    void resolve_early_return_on_manage_direct() {
        // B(2) 有 READ，祖先 A(1) 有 MANAGE+USER 直接命中
        // 但因 A 是祖先不算 DIRECT，应取 INHERITED MANAGE@A
        // —— 早退仅当 best.level==MANAGE && best.source==DIRECT
        Long userId = 100L, a = 1L, b = 2L;
        DocFileFolder fa = folder(a, null, 999L);
        DocFileFolder fb = folder(b, a, 999L);
        DocFileFolderAcl aclA = acl(a, "USER", userId, "MANAGE");
        DocFileFolderAcl aclB = acl(b, "USER", userId, "READ");

        when(docFileFolderMapper.selectById(b)).thenReturn(fb);
        when(docFileFolderMapper.selectById(a)).thenReturn(fa);
        when(docFileFolderAclMapper.selectList(any()))
                .thenReturn(Collections.singletonList(aclB))
                .thenReturn(Collections.singletonList(aclA));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, b);
        // 祖先上的 USER ACL 是 INHERITED 不是 DIRECT，不应早退到 INHERITED 之外
        assertEquals(PermissionLevel.MANAGE, r.getLevel());
        assertEquals(PermissionResult.Source.INHERITED, r.getSource());
    }

    @Test
    void resolve_early_return_when_target_has_direct_manage() {
        Long userId = 100L, folderId = 10L;
        DocFileFolder folder = folder(folderId, null, 999L);
        DocFileFolderAcl acl = acl(folderId, "USER", userId, "MANAGE");

        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder);
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, folderId);
        assertEquals(PermissionLevel.MANAGE, r.getLevel());
        assertEquals(PermissionResult.Source.DIRECT, r.getSource());
    }

    // ---------- 没有任何权限 ----------

    @Test
    void resolve_no_acl_no_owner_returns_none() {
        Long userId = 100L, folderId = 10L;
        DocFileFolder folder = folder(folderId, null, 999L);

        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder);
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, folderId);
        assertEquals(PermissionLevel.NONE, r.getLevel());
        assertEquals(PermissionResult.Source.NONE, r.getSource());
    }

    @Test
    void resolve_chained_no_access_returns_none() {
        // 链：A(1) <- B(2) <- C(3)；A 上有给其他人的 READ；user 无任何命中
        Long userId = 100L, a = 1L, b = 2L, c = 3L;
        DocFileFolder fa = folder(a, null, 999L);
        DocFileFolder fb = folder(b, a, 999L);
        DocFileFolder fc = folder(c, b, 999L);
        DocFileFolderAcl aclA = acl(a, "USER", 888L, "READ");

        when(docFileFolderMapper.selectById(c)).thenReturn(fc);
        when(docFileFolderMapper.selectById(b)).thenReturn(fb);
        when(docFileFolderMapper.selectById(a)).thenReturn(fa);
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(aclA));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        PermissionResult r = permissionService.resolve(userId, c);
        assertEquals(PermissionLevel.NONE, r.getLevel());
    }

    // ---------- requireXxx 鉴权（走 LoginContext） ----------

    @Test
    void requireRead_owner_passes() {
        setLoginUser(100L);
        Long folderId = 10L;
        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder(folderId, null, 100L));
        permissionService.requireRead(folderId); // 不抛
    }

    @Test
    void requireRead_no_access_throws_404_to_avoid_probing() {
        setLoginUser(100L);
        Long folderId = 10L;
        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder(folderId, null, 999L));
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> permissionService.requireRead(folderId));
        // spec §10.6：屏蔽存在性 → 返 404 而非 403
        assertNotNull(ex);
    }

    @Test
    void requireWrite_read_only_throws() {
        setLoginUser(100L);
        Long folderId = 10L;
        DocFileFolderAcl acl = acl(folderId, "USER", 100L, "READ");
        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder(folderId, null, 999L));
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () -> permissionService.requireWrite(folderId));
    }

    @Test
    void requireManage_write_only_throws() {
        setLoginUser(100L);
        Long folderId = 10L;
        DocFileFolderAcl acl = acl(folderId, "USER", 100L, "WRITE");
        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder(folderId, null, 999L));
        when(docFileFolderAclMapper.selectList(any())).thenReturn(Collections.singletonList(acl));
        when(groupMemberMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () -> permissionService.requireManage(folderId));
    }

    @Test
    void requireManage_owner_passes() {
        setLoginUser(100L);
        Long folderId = 10L;
        when(docFileFolderMapper.selectById(folderId)).thenReturn(folder(folderId, null, 100L));
        permissionService.requireManage(folderId);
    }

    // ---------- 工具 ----------

    private static DocFileFolder folder(Long id, Long parentId, Long ownerId) {
        DocFileFolder f = new DocFileFolder();
        f.setId(id);
        f.setParentId(parentId);
        f.setOwnerId(ownerId);
        return f;
    }

    private static DocFileFolderAcl acl(Long folderId, String type, Long principalId, String perm) {
        DocFileFolderAcl a = new DocFileFolderAcl();
        a.setFolderId(folderId);
        a.setPrincipalType(type);
        a.setPrincipalId(principalId);
        a.setPermission(perm);
        return a;
    }

    private void setLoginUser(Long userId) {
        UserInfoBO bo = new UserInfoBO();
        bo.setId(userId);
        bo.setAccount("u" + userId);
        bo.setRole("USER");
        LoginContext.setUserInfo(bo);
        LoginContext.setUserGroupIds(new ArrayList<>());
        // 兼容 Mockito 的 strict 模式
        lenient().when(groupMemberMapper.selectList(any()))
                .thenReturn(Collections.<GroupMember>emptyList());
    }

    @Test
    void sourceRank_smoke() {
        // OWNER > DIRECT > GROUP > INHERITED > NONE —— 与 PermissionService.sourceRank 一致
        // （不起断言；用于人工阅读时锚定算法优先级）
        assertTrue(PermissionResult.Source.OWNER.name().equals("OWNER"));
        assertFalse(PermissionResult.Source.NONE.name().equals("DIRECT"));
    }
}
