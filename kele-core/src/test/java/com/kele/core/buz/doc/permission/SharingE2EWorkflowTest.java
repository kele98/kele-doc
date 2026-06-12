package com.kele.core.buz.doc.permission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 共享流程端到端测试。覆盖 spec §4.2 全流程：
 * <ol>
 *   <li>owner 分享 → 接受方 resolve 拿到正确级别</li>
 *   <li>接受方按级别鉴权（READ / WRITE / MANAGE）</li>
 *   <li>升级/降级权限即时生效</li>
 *   <li>撤销后再次鉴权失败（屏蔽存在性 → 404）</li>
 *   <li>组授权按成员资格生效</li>
 *   <li>权限继承父级</li>
 *   <li>owner 优先于任何 ACL</li>
 *   <li>无效 ACL（已撤销）不参与匹配</li>
 * </ol>
 *
 * <p>本测试走 PermissionService app-layer fallback（spec §5.2），mock 住所有 mapper。
 * 不需要真实 MySQL；smoke test（Task 34）跑真实库验证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("共享流程 E2E（PermissionService 路径）")
class SharingE2EWorkflowTest {

    @Mock private DocFileFolderMapper folderMapper;
    @Mock private DocFileFolderAclMapper aclMapper;
    @Mock private GroupMemberMapper groupMemberMapper;
    @InjectMocks private PermissionService permissionService;

    // ---------- 内存伪 DB ----------

    private final Map<Long, DocFileFolder> folders = new HashMap<>();
    private final Map<Long, DocFileFolderAcl> acls = new HashMap<>();
    private final AtomicLong aclSeq = new AtomicLong(0);
    private final List<GroupMember> groupMembers = new ArrayList<>();
    private final AtomicLong lastQueriedFolderId = new AtomicLong(-1);

    // ---------- 工具方法 ----------

    private DocFileFolder createFolder(Long id, Long parentId, Long ownerId) {
        DocFileFolder f = new DocFileFolder();
        f.setId(id);
        f.setParentId(parentId);
        f.setOwnerId(ownerId);
        folders.put(id, f);
        return f;
    }

    private DocFileFolderAcl grantAcl(Long folderId, String type, Long principalId,
                                      String permission, Long grantedBy) {
        DocFileFolderAcl a = new DocFileFolderAcl();
        a.setId(aclSeq.incrementAndGet());
        a.setFolderId(folderId);
        a.setPrincipalType(type);
        a.setPrincipalId(principalId);
        a.setPermission(permission);
        a.setGrantedBy(grantedBy);
        a.setCreatedAt(LocalDateTime.now());
        // 有效记录
        acls.put(a.getId(), a);
        return a;
    }

    private void revokeAcl(Long aclId) {
        DocFileFolderAcl a = acls.get(aclId);
        if (a != null) a.setRevokedAt(LocalDateTime.now());
    }

    private void addToGroup(Long userId, Long groupId) {
        GroupMember gm = new GroupMember();
        gm.setUserId(userId);
        gm.setGroupId(groupId);
        groupMembers.add(gm);
    }

    private void wireMocks() {
        lenient().when(folderMapper.selectById(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            lastQueriedFolderId.set(id);
            return folders.get(id);
        });
        lenient().when(aclMapper.selectList(any())).thenAnswer(inv -> {
            // 模拟 DB 的 WHERE folder_id = cursor 过滤
            return effectiveAcls(lastQueriedFolderId.get());
        });
        lenient().when(groupMemberMapper.selectList(any())).thenAnswer(inv -> {
            List<GroupMember> out = new ArrayList<>();
            for (GroupMember gm : groupMembers) out.add(gm);
            return out;
        });
    }

    private List<DocFileFolderAcl> effectiveAcls(Long folderId) {
        List<DocFileFolderAcl> out = new ArrayList<>();
        for (DocFileFolderAcl a : acls.values()) {
            if (a.getRevokedAt() == null && folderId.equals(a.getFolderId())) {
                out.add(a);
            }
        }
        return out;
    }

    private List<DocFileFolderAcl> effectiveAclsForAll() {
        List<DocFileFolderAcl> out = new ArrayList<>();
        for (DocFileFolderAcl a : acls.values()) {
            if (a.getRevokedAt() == null) out.add(a);
        }
        return out;
    }

    private void setLoginUser(Long userId) {
        UserInfoBO bo = new UserInfoBO();
        bo.setId(userId);
        bo.setAccount("u" + userId);
        bo.setRole("USER");
        LoginContext.setUserInfo(bo);
        LoginContext.setUserGroupIds(extractGroupIdsFor(userId));
    }

    private List<Long> extractGroupIdsFor(Long userId) {
        List<Long> ids = new ArrayList<>();
        for (GroupMember gm : groupMembers) {
            if (gm.getUserId().equals(userId)) ids.add(gm.getGroupId());
        }
        return ids;
    }

    @AfterEach
    void tearDown() {
        LoginContext.remove();
        folders.clear();
        acls.clear();
        groupMembers.clear();
        aclSeq.set(0);
        lastQueriedFolderId.set(-1);
    }

    // ====================================================================
    // §4.2 流程 1: owner 分享 + 接受方鉴权
    // ====================================================================

    @Nested
    @DisplayName("owner 分享 + 接受方鉴权")
    class GrantAndEnforce {

        @Test
        @DisplayName("grantee READ → requireRead 通过 / requireWrite 拒绝")
        void granteeReadEnforced() {
            wireMocks();
            createFolder(1L, null, 100L);          // folder 1, owner=100
            grantAcl(1L, "USER", 200L, "READ", 100L); // owner 100 分享给 200 READ
            setLoginUser(200L);

            assertDoesNotThrow(() -> permissionService.requireRead(1L));
            assertThrows(BusinessException.class, () -> permissionService.requireWrite(1L));
            assertThrows(BusinessException.class, () -> permissionService.requireManage(1L));
        }

        @Test
        @DisplayName("grantee WRITE → requireRead / requireWrite 通过 / requireManage 拒绝")
        void granteeWriteEnforced() {
            wireMocks();
            createFolder(1L, null, 100L);
            grantAcl(1L, "USER", 200L, "WRITE", 100L);
            setLoginUser(200L);

            assertDoesNotThrow(() -> permissionService.requireRead(1L));
            assertDoesNotThrow(() -> permissionService.requireWrite(1L));
            assertThrows(BusinessException.class, () -> permissionService.requireManage(1L));
        }

        @Test
        @DisplayName("grantee MANAGE → 三档全通过")
        void granteeManageEnforced() {
            wireMocks();
            createFolder(1L, null, 100L);
            grantAcl(1L, "USER", 200L, "MANAGE", 100L);
            setLoginUser(200L);

            assertDoesNotThrow(() -> permissionService.requireRead(1L));
            assertDoesNotThrow(() -> permissionService.requireWrite(1L));
            assertDoesNotThrow(() -> permissionService.requireManage(1L));
        }

        @Test
        @DisplayName("未授权用户 requireRead 屏蔽存在性（404 而非 403，spec §10.6）")
        void unauthorizedBlockedAsNotFound() {
            wireMocks();
            createFolder(1L, null, 100L);
            grantAcl(1L, "USER", 200L, "READ", 100L);
            setLoginUser(300L); // 300 未被授权

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> permissionService.requireRead(1L));
            // 业务码应是 RESOURCE_NOT_VISIBLE（404 语义）而非 PERMISSION_DENIED（403 语义）
            // v0.7 修：getCode() 返回 Integer，Integer.parseInt 只能吃 String——直接比较
            assertEquals(com.kele.common.enums.ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
                    ex.getCode());
        }
    }

    // ====================================================================
    // §4.2 流程 2: 升级/降级/撤销
    // ====================================================================

    @Nested
    @DisplayName("升级 / 降级 / 撤销")
    class MutateAndReEnforce {

        @Test
        @DisplayName("READ → 升 MANAGE → 立即可 manage")
        void upgradeFromReadToManage() {
            wireMocks();
            createFolder(1L, null, 100L);
            DocFileFolderAcl acl = grantAcl(1L, "USER", 200L, "READ", 100L);
            setLoginUser(200L);

            assertThrows(BusinessException.class, () -> permissionService.requireManage(1L));

            // 升 MANAGE
            acl.setPermission("MANAGE");
            assertDoesNotThrow(() -> permissionService.requireManage(1L));
        }

        @Test
        @DisplayName("MANAGE → 撤销 → 不再可读")
        void revokeBlocksAllAccess() {
            wireMocks();
            createFolder(1L, null, 100L);
            DocFileFolderAcl acl = grantAcl(1L, "USER", 200L, "MANAGE", 100L);
            setLoginUser(200L);

            assertDoesNotThrow(() -> permissionService.requireRead(1L));
            // 重设 mock：撤销后 selectList 不再返这条
            revokeAcl(acl.getId());
            assertThrows(BusinessException.class, () -> permissionService.requireRead(1L));
        }

        @Test
        @DisplayName("用户拥有两条 ACL，取最高级（spec §5.2 betterOf）")
        void multipleAclsTakesMax() {
            wireMocks();
            createFolder(1L, null, 100L);
            // 同一个 user 200 在 folder 1 有 READ（直接）+ WRITE（继承）—— 我们的简化为直接
            grantAcl(1L, "USER", 200L, "READ", 100L);
            grantAcl(1L, "USER", 200L, "WRITE", 100L);
            setLoginUser(200L);

            assertEquals(PermissionLevel.WRITE, permissionService.resolve(200L, 1L).getLevel());
        }
    }

    // ====================================================================
    // §4.3 流程: 组授权按成员资格
    // ====================================================================

    @Nested
    @DisplayName("组授权（spec §4.3）")
    class GroupSharing {

        @Test
        @DisplayName("组成员命中组授权（同节点 GROUP ACL → source=DIRECT）")
        void groupMemberInheritsGroupAcl() {
            wireMocks();
            createFolder(1L, null, 100L);
            grantAcl(1L, "GROUP", 555L, "READ", 100L);
            addToGroup(200L, 555L);
            setLoginUser(200L);

            // 同节点 GROUP ACL：isDirect=true → source=DIRECT（location-based 语义）
            assertEquals(PermissionResult.Source.DIRECT,
                    permissionService.resolve(200L, 1L).getSource());
            assertEquals(PermissionLevel.READ,
                    permissionService.resolve(200L, 1L).getLevel());
        }

        @Test
        @DisplayName("非组员不命中组授权")
        void nonMemberDoesNotInherit() {
            wireMocks();
            createFolder(1L, null, 100L);
            grantAcl(1L, "GROUP", 555L, "MANAGE", 100L);
            // user 200 不在 group 555
            setLoginUser(200L);

            assertEquals(PermissionLevel.NONE,
                    permissionService.resolve(200L, 1L).getLevel());
        }

        @Test
        @DisplayName("直接 USER ACL 优先于组 ACL（DIRECT source）")
        void directUserAclBeatsGroupAcl() {
            wireMocks();
            createFolder(1L, null, 100L);
            grantAcl(1L, "USER", 200L, "READ", 100L);  // 直接给 200 READ
            grantAcl(1L, "GROUP", 555L, "WRITE", 100L); // 200 所在组 555 WRITE
            addToGroup(200L, 555L);
            setLoginUser(200L);

            PermissionResult r = permissionService.resolve(200L, 1L);
            assertEquals(PermissionLevel.WRITE, r.getLevel()); // 取高等级
            assertEquals(PermissionResult.Source.DIRECT, r.getSource()); // 但 source 走 DIRECT
        }
    }

    // ====================================================================
    // §5.2 流程: 权限继承
    // ====================================================================

    @Nested
    @DisplayName("权限继承（spec §5.2）")
    class Inheritance {

        @Test
        @DisplayName("A(1) <- B(2) <- C(3)；A 上 READ，子 B/C 均继承")
        void inheritableFromParent() {
            wireMocks();
            createFolder(1L, null, 100L); // A, owner=100
            createFolder(2L, 1L, 100L);   // B
            createFolder(3L, 2L, 100L);   // C
            grantAcl(1L, "USER", 200L, "READ", 100L);
            setLoginUser(200L);

            assertEquals(PermissionResult.Source.INHERITED,
                    permissionService.resolve(200L, 2L).getSource());
            assertEquals(PermissionResult.Source.INHERITED,
                    permissionService.resolve(200L, 3L).getSource());
        }

        @Test
        @DisplayName("中途截断：父 owner_id 是 user，递归到 owner 节点直接返 OWNER")
        void inheritanceStopsAtOwner() {
            wireMocks();
            createFolder(1L, null, 999L);   // A, owner=999
            createFolder(2L, 1L, 100L);     // B, owner=100
            setLoginUser(100L);

            // user 100 是 B 的 owner
            PermissionResult r = permissionService.resolve(100L, 2L);
            assertEquals(PermissionResult.Source.OWNER, r.getSource());
            assertEquals(2L, r.getSourceNodeId());
        }
    }

    // ====================================================================
    // §10.6 流程: 屏蔽存在性
    // ====================================================================

    @Nested
    @DisplayName("资源屏蔽存在性（spec §10.6）")
    class ExistenceObfuscation {

        @Test
        @DisplayName("无权限 + 文件夹不存在 → 同样的 404 错误（防探测）")
        void existenceAndAccessIndistinguishable() {
            wireMocks();
            // 不创建 folder 1
            setLoginUser(200L);

            assertThrows(BusinessException.class, () -> permissionService.requireRead(1L));
        }

        @Test
        @DisplayName("有权限 → 正常返回")
        void withAccessPasses() {
            wireMocks();
            createFolder(1L, null, 100L);
            grantAcl(1L, "USER", 200L, "READ", 100L);
            setLoginUser(200L);

            assertDoesNotThrow(() -> permissionService.requireRead(1L));
        }
    }

    // ====================================================================
    // §4.2 流程: owner 优先于任何 ACL
    // ====================================================================

    @Nested
    @DisplayName("owner 优先于 ACL")
    class OwnerTakesPrecedence {

        @Test
        @DisplayName("owner 命中的同时有更低级 ACL，仍返 OWNER（MANAGE）")
        void ownerWinsOverLowerAcl() {
            wireMocks();
            // user 100 是 folder 1 的 owner，但本层有给 100 的 READ ACL
            // —— owner 优先，直接返 OWNER@1
            createFolder(1L, null, 100L);
            grantAcl(1L, "USER", 100L, "READ", 100L);
            setLoginUser(100L);

            PermissionResult r = permissionService.resolve(100L, 1L);
            assertEquals(PermissionResult.Source.OWNER, r.getSource());
            assertEquals(PermissionLevel.MANAGE, r.getLevel());
        }

        @Test
        @DisplayName("owner 跨级：A 是 owner，B 是其子，仍能命中 A 上的 OWNER")
        void ownerTransitively() {
            wireMocks();
            createFolder(1L, null, 100L);
            createFolder(2L, 1L, 999L); // 2 的 owner 不是 100
            setLoginUser(100L);

            // 查 2 时向上找到 1，命中 OWNER@1
            PermissionResult r = permissionService.resolve(100L, 2L);
            assertEquals(PermissionResult.Source.OWNER, r.getSource());
            assertEquals(1L, r.getSourceNodeId());
            assertTrue(r.getLevel().atLeast(PermissionLevel.MANAGE));
        }
    }

    // ====================================================================
    // §3.1 流程: 已撤销 ACL 不参与匹配
    // ====================================================================

    @Test
    @DisplayName("撤销的 ACL 不参与匹配（即使仍存于表中）")
    void revokedAclNotMatched() {
        wireMocks();
        createFolder(1L, null, 100L);
        DocFileFolderAcl acl = grantAcl(1L, "USER", 200L, "MANAGE", 100L);
        setLoginUser(200L);

        // 撤销后 selectList 不再返该条
        revokeAcl(acl.getId());
        assertThrows(BusinessException.class, () -> permissionService.requireRead(1L));
    }
}
