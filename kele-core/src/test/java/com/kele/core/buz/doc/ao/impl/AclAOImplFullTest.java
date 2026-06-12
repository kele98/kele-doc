package com.kele.core.buz.doc.ao.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.ao.AclAO;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderMapper;
import com.kele.core.buz.doc.model.vo.AclEntryVO;
import com.kele.core.buz.doc.model.vo.DocFileFolderAclListVO;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.buz.sys.dao.entity.SysUserInfo;
import com.kele.core.buz.sys.dao.mapper.SysUserInfoMapper;
import com.kele.core.buz.sys.model.bo.UserInfoBO;
import com.kele.core.other.context.LoginContext;
import com.kele.core.other.enums.DelStatusEnum;
import com.kele.core.other.enums.FileFolderFormatEnum;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

/**
 * AclAO 全流程集成测试。连真实 MySQL，@Transactional + @Rollback 自动回滚。
 *
 * <p>覆盖：
 * - grantAcl: USER/GROUP/ORG 授权 + upsert + replace 模式 + 校验
 * - revokeAcl: 撤销 + 已撤销幂等 + 不存在抛错 + 跨 folder 拒绝
 * - updateAcl: 升级/降级 + 已撤销不可改 + 非法值
 * - listFolderAcl: 列出本级 ACL + owner 信息 + ORG 公开 + principalName 回填
 * - transferOwner: owner 转让 + admin 强转 + 非 owner 拒绝 + 转让后权限变化
 * - 权限即时生效: grant/revoke/update 后 requireXxx 行为立即变化
 * - 权限矩阵: READ/WRITE/MANAGE 各能做什么
 * - enforceLastManage: owner 存在时跳过 / 孤儿 folder 保护
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("AclAO 全流程集成测试")
class AclAOImplFullTest {

    @Autowired private AclAO aclAO;
    @Autowired private IDocFileFolderService folderService;
    @Autowired private DocFileFolderMapper folderMapper;
    @Autowired private DocFileFolderAclMapper aclMapper;
    @Autowired private SysUserInfoMapper sysUserInfoMapper;

    private Long ownerUserId;
    private Long otherUserId;
    private Long thirdUserId;

    @BeforeEach
    void setUp() {
        ownerUserId = createUser("it-acl-owner-" + nano());
        otherUserId = createUser("it-acl-other-" + nano());
        thirdUserId = createUser("it-acl-third-" + nano());
    }

    @AfterEach
    void tearDown() {
        LoginContext.remove();
    }

    // ====================================================================
    // grantAcl
    // ====================================================================

    @Test
    @DisplayName("grantAcl: 给用户授权 READ → listFolderAcl 可见")
    void grantUserRead_visible() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertEquals(1, list.getEntries().size());
        AclEntryVO entry = list.getEntries().get(0);
        assertEquals("USER", entry.getPrincipalType());
        assertEquals(otherUserId, entry.getPrincipalId());
        assertEquals("READ", entry.getPermission());
    }

    @Test
    @DisplayName("grantAcl: 对同一用户 upsert：READ → WRITE → 权限更新")
    void grantUserUpsert_permissionUpgraded() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "WRITE", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertEquals(1, list.getEntries().size());
        assertEquals("WRITE", list.getEntries().get(0).getPermission());
    }

    @Test
    @DisplayName("grantAcl: 批量授权多个用户 → 全部可见")
    void grantBatch_multipleUsers() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        aclAO.grantAcl(folder.getId(), Arrays.asList(
            new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null),
            new AclEntryVO(null, "USER", thirdUserId, "WRITE", null, null, null)
        ), false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertEquals(2, list.getEntries().size());
        List<String> permissions = list.getEntries().stream()
            .filter(e -> e.getPrincipalType().equals("USER"))
            .map(AclEntryVO::getPermission).sorted().collect(Collectors.toList());
        assertEquals(Arrays.asList("READ", "WRITE"), permissions);
    }

    @Test
    @DisplayName("grantAcl: replace=true → 先撤销旧 ACL 再插入新的")
    void grantReplace_revokesOldAndInsertsNew() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", thirdUserId, "WRITE", null, null, null)),
            true);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertEquals(1, list.getEntries().size());
        assertEquals(thirdUserId, list.getEntries().get(0).getPrincipalId());
        assertEquals("WRITE", list.getEntries().get(0).getPermission());
    }

    @Test
    @DisplayName("grantAcl: ORG 公开授权 → isOrgPublic=true")
    void grantOrgPublic_visible() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "ORG", null, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertTrue(list.getIsOrgPublic());
        assertEquals("READ", list.getOrgPublicPermission());
    }

    @Test
    @DisplayName("grantAcl: ORG 授权只允许 READ → WRITE 抛错")
    void grantOrgNonRead_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "ORG", null, "WRITE", null, null, null)),
                false));
    }

    @Test
    @DisplayName("grantAcl: 非法主体类型 → 抛错")
    void grantInvalidPrincipalType_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "UNKNOWN", 1L, "READ", null, null, null)),
                false));
    }

    @Test
    @DisplayName("grantAcl: USER/GROUP 不传 principalId → 抛错")
    void grantUserWithoutPrincipalId_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", null, "READ", null, null, null)),
                false));
    }

    @Test
    @DisplayName("grantAcl: 非法权限值 → 抛错")
    void grantInvalidPermission_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "EXECUTE", null, null, null)),
                false));
    }

    @Test
    @DisplayName("grantAcl: 非 MANAGE 用户授权 → 拒绝")
    void grantByNonManage_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        loginAs(otherUserId, "USER");
        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", thirdUserId, "READ", null, null, null)),
                false));
    }

    // ====================================================================
    // revokeAcl
    // ====================================================================

    @Test
    @DisplayName("revokeAcl: 撤销 ACL → listFolderAcl 不再可见")
    void revokeAcl_notVisible() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO listBefore = aclAO.listFolderAcl(folder.getId());
        Long aclId = listBefore.getEntries().get(0).getId();

        aclAO.revokeAcl(folder.getId(), aclId);

        DocFileFolderAclListVO listAfter = aclAO.listFolderAcl(folder.getId());
        assertEquals(0, listAfter.getEntries().size());
    }

    @Test
    @DisplayName("revokeAcl: 已撤销的 ACL 再次撤销 → 幂等（不抛错）")
    void revokeAlreadyRevoked_idempotent() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();

        aclAO.revokeAcl(folder.getId(), aclId);
        assertDoesNotThrow(() -> aclAO.revokeAcl(folder.getId(), aclId));
    }

    @Test
    @DisplayName("revokeAcl: 撤销不存在的 ACL → 抛错")
    void revokeNonexistentAcl_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertThrows(BusinessException.class, () -> aclAO.revokeAcl(folder.getId(), -999L));
    }

    @Test
    @DisplayName("revokeAcl: 撤销别的 folder 的 ACL → 抛错（folderId 不匹配）")
    void revokeWrongFolder_throws() {
        DocFileFolder folder1 = createFolder(ownerUserId);
        DocFileFolder folder2 = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder1.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder1.getId());
        Long aclId = list.getEntries().get(0).getId();

        assertThrows(BusinessException.class, () -> aclAO.revokeAcl(folder2.getId(), aclId));
    }

    // ====================================================================
    // updateAcl
    // ====================================================================

    @Test
    @DisplayName("updateAcl: 升级 READ → MANAGE")
    void upgradePermission_readToManage() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();

        aclAO.updateAcl(folder.getId(), aclId, "MANAGE");

        DocFileFolderAclListVO after = aclAO.listFolderAcl(folder.getId());
        assertEquals("MANAGE", after.getEntries().get(0).getPermission());
    }

    @Test
    @DisplayName("updateAcl: 降级 MANAGE → READ")
    void downgradePermission_manageToRead() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "MANAGE", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();

        aclAO.updateAcl(folder.getId(), aclId, "READ");

        DocFileFolderAclListVO after = aclAO.listFolderAcl(folder.getId());
        assertEquals("READ", after.getEntries().get(0).getPermission());
    }

    @Test
    @DisplayName("updateAcl: 已撤销的 ACL 不能修改")
    void updateRevokedAcl_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();
        aclAO.revokeAcl(folder.getId(), aclId);

        assertThrows(BusinessException.class, () -> aclAO.updateAcl(folder.getId(), aclId, "WRITE"));
    }

    @Test
    @DisplayName("updateAcl: 非法权限值 → 抛错")
    void updateInvalidPermission_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();

        assertThrows(BusinessException.class, () -> aclAO.updateAcl(folder.getId(), aclId, "EXECUTE"));
    }

    // ====================================================================
    // listFolderAcl
    // ====================================================================

    @Test
    @DisplayName("listFolderAcl: 无 ACL → 空列表 + owner 信息正确")
    void listEmptyAcl_ownerInfoCorrect() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertEquals(ownerUserId, list.getOwnerId());
        assertFalse(list.getIsOrgPublic());
        assertEquals(0, list.getEntries().size());
    }

    @Test
    @DisplayName("listFolderAcl: ownerName 已回填")
    void listAcl_ownerNamePopulated() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertNotNull(list.getOwnerName());
    }

    @Test
    @DisplayName("listFolderAcl: READ 用户可以 listAcl")
    void listAcl_byReadUser_passes() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        loginAs(otherUserId, "USER");
        assertDoesNotThrow(() -> aclAO.listFolderAcl(folder.getId()));
    }

    @Test
    @DisplayName("listFolderAcl: 无权限用户 listAcl → 拒绝")
    void listAcl_byUnauthorized_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(otherUserId, "USER");

        assertThrows(BusinessException.class, () -> aclAO.listFolderAcl(folder.getId()));
    }

    @Test
    @DisplayName("listFolderAcl: entries 中 principalName 已回填")
    void listAcl_principalNamePopulated() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertEquals(1, list.getEntries().size());
        assertNotNull(list.getEntries().get(0).getPrincipalName());
        assertFalse(list.getEntries().get(0).getPrincipalName().isEmpty());
    }

    @Test
    @DisplayName("listFolderAcl: 同时有 USER + ORG → entries 包含两种 + isOrgPublic=true")
    void listAcl_mixedUserAndOrg() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(), Arrays.asList(
            new AclEntryVO(null, "USER", otherUserId, "WRITE", null, null, null),
            new AclEntryVO(null, "ORG", null, "READ", null, null, null)
        ), false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        assertTrue(list.getIsOrgPublic());
        assertEquals(2, list.getEntries().size());
        List<String> types = list.getEntries().stream()
            .map(AclEntryVO::getPrincipalType).sorted().collect(Collectors.toList());
        assertEquals(Arrays.asList("ORG", "USER"), types);
    }

    // ====================================================================
    // transferOwner
    // ====================================================================

    @Test
    @DisplayName("transferOwner: owner 成功转让")
    void transferOwner_success() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        aclAO.transferOwner(folder.getId(), otherUserId);

        DocFileFolder updated = folderService.getById(folder.getId());
        assertEquals(otherUserId, updated.getOwnerId());
    }

    @Test
    @DisplayName("transferOwner: 转让给自己 → 抛错")
    void transferToSelf_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertThrows(BusinessException.class,
            () -> aclAO.transferOwner(folder.getId(), ownerUserId));
    }

    @Test
    @DisplayName("transferOwner: 目标用户不存在 → 抛错")
    void transferToNonexistentUser_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertThrows(BusinessException.class,
            () -> aclAO.transferOwner(folder.getId(), -999L));
    }

    @Test
    @DisplayName("transferOwner: 非 owner 即使有 MANAGE ACL 也不可转让")
    void nonOwnerWithManage_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "MANAGE", null, null, null)),
            false);

        loginAs(otherUserId, "USER");
        assertThrows(BusinessException.class,
            () -> aclAO.transferOwner(folder.getId(), thirdUserId));
    }

    @Test
    @DisplayName("transferOwner: admin 可强制转让")
    void adminForceTransfer_success() {
        DocFileFolder folder = createFolder(otherUserId);
        loginAs(ownerUserId, "ADMIN");

        aclAO.transferOwner(folder.getId(), thirdUserId);

        DocFileFolder updated = folderService.getById(folder.getId());
        assertEquals(thirdUserId, updated.getOwnerId());
    }

    @Test
    @DisplayName("transferOwner: 转让后新 owner 可以 MANAGE")
    void afterTransfer_newOwnerCanManage() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.transferOwner(folder.getId(), otherUserId);

        loginAs(otherUserId, "ADMIN");
        assertDoesNotThrow(() ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", thirdUserId, "READ", null, null, null)),
                false));
    }

    @Test
    @DisplayName("transferOwner: 转让后原 owner 失去 MANAGE（除非有 ACL）")
    void afterTransfer_oldOwnerLosesManage() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.transferOwner(folder.getId(), otherUserId);

        loginAs(ownerUserId, "USER");
        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", thirdUserId, "READ", null, null, null)),
                false));
    }

    // ====================================================================
    // 权限即时生效
    // ====================================================================

    @Test
    @DisplayName("权限即时生效: grant WRITE → requireWrite 通过（通过 listAcl 验证）")
    void grantWrite_requireWritePasses() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "WRITE", null, null, null)),
            false);

        loginAs(otherUserId, "USER");
        assertDoesNotThrow(() -> aclAO.listFolderAcl(folder.getId()));
    }

    @Test
    @DisplayName("权限即时生效: revoke 后 → requireRead 拒绝")
    void revokeAcl_requireReadThrows() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();
        aclAO.revokeAcl(folder.getId(), aclId);

        loginAs(otherUserId, "USER");
        assertThrows(BusinessException.class, () -> aclAO.listFolderAcl(folder.getId()));
    }

    @Test
    @DisplayName("权限即时生效: 降级 MANAGE → WRITE 后 → 不能 grant")
    void downgradeFromManage_cannotGrant() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "MANAGE", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();
        aclAO.updateAcl(folder.getId(), aclId, "WRITE");

        loginAs(otherUserId, "USER");
        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", thirdUserId, "READ", null, null, null)),
                false));
    }

    @Test
    @DisplayName("权限即时生效: 升级 READ → MANAGE 后 → 可以 grant")
    void upgradeToManage_canGrant() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();
        aclAO.updateAcl(folder.getId(), aclId, "MANAGE");

        loginAs(otherUserId, "USER");
        assertDoesNotThrow(() ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", thirdUserId, "READ", null, null, null)),
                false));
    }

    // ====================================================================
    // 权限矩阵
    // ====================================================================

    @Test
    @DisplayName("权限矩阵: READ 可以 listAcl 但不能 grant")
    void readUserCanListButNotModify() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
            false);

        loginAs(otherUserId, "USER");
        assertDoesNotThrow(() -> aclAO.listFolderAcl(folder.getId()));
        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", thirdUserId, "READ", null, null, null)),
                false));
    }

    @Test
    @DisplayName("权限矩阵: WRITE 可以 listAcl 但不能 grant")
    void writeUserCanListButNotModify() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "WRITE", null, null, null)),
            false);

        loginAs(otherUserId, "USER");
        assertDoesNotThrow(() -> aclAO.listFolderAcl(folder.getId()));
        assertThrows(BusinessException.class, () ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", thirdUserId, "READ", null, null, null)),
                false));
    }

    @Test
    @DisplayName("权限矩阵: MANAGE 可以 grant/revoke/update（owner 场景）")
    void manageUserCanDoAll() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertDoesNotThrow(() ->
            aclAO.grantAcl(folder.getId(),
                Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "READ", null, null, null)),
                false));

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();

        assertDoesNotThrow(() -> aclAO.updateAcl(folder.getId(), aclId, "WRITE"));
        assertDoesNotThrow(() -> aclAO.revokeAcl(folder.getId(), aclId));
    }

    // ====================================================================
    // enforceLastManage
    // ====================================================================

    @Test
    @DisplayName("enforceLastManage: 有 owner 的 folder 可以撤销所有 ACL")
    void folderWithOwner_canRevokeAllAcls() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");
        aclAO.grantAcl(folder.getId(),
            Collections.singletonList(new AclEntryVO(null, "USER", otherUserId, "MANAGE", null, null, null)),
            false);

        DocFileFolderAclListVO list = aclAO.listFolderAcl(folder.getId());
        Long aclId = list.getEntries().get(0).getId();

        assertDoesNotThrow(() -> aclAO.revokeAcl(folder.getId(), aclId));
    }

    // enforceLastManage 的孤儿 folder（owner_id=null）分支在真实 DB 下不可测：
    // owner_id 列有 NOT NULL 约束。该分支是防御性代码，由 mock 单测覆盖。

    // ====================================================================
    // 工具方法
    // ====================================================================

    private Long createUser(String account) {
        SysUserInfo u = new SysUserInfo();
        u.setAccount(account);
        u.setPassword("test-password");
        u.setUserName(account.replace("it-acl-", "IT"));
        u.setCreateAt(LocalDateTime.now());
        u.setUpdateAt(LocalDateTime.now());
        u.setStatus(0);
        u.setRole("USER");
        sysUserInfoMapper.insert(u);
        return u.getId();
    }

    private DocFileFolder createFolder(Long ownerId) {
        DocFileFolder f = new DocFileFolder();
        f.setParentId(0L);
        f.setName("IT-acl-folder-" + nano());
        f.setFormat(FileFolderFormatEnum.FOLDER.getFormat());
        f.setFileType("");
        f.setFileCount(0);
        f.setFolderCount(0);
        f.setStatus(DelStatusEnum.NORMAL.getStatus());
        f.setOwnerId(ownerId);
        f.setCreatorId(ownerId);
        f.setCreateAt(LocalDateTime.now());
        f.setUpdateAt(LocalDateTime.now());
        folderService.save(f);
        return f;
    }

    private void loginAs(Long userId, String role) {
        UserInfoBO bo = new UserInfoBO();
        bo.setId(userId);
        bo.setAccount("u" + userId);
        bo.setRole(role);
        LoginContext.setUserInfo(bo);
    }

    private static long nano() {
        return System.nanoTime();
    }
}
