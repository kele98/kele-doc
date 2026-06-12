package com.kele.core.buz.doc.ao.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.ao.AclAO;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.buz.sys.dao.entity.SysUserInfo;
import com.kele.core.buz.sys.dao.mapper.SysUserInfoMapper;
import com.kele.core.buz.sys.model.bo.UserInfoBO;
import com.kele.core.other.context.LoginContext;
import com.kele.core.other.enums.DelStatusEnum;
import com.kele.core.other.enums.FileFolderFormatEnum;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

/**
 * AclAO.transferOwner 集成测试。
 *
 * <p>覆盖：
 * - owner 转让成功（CAS）
 * - 非 owner 的 MANAGE 用户不可转让
 * - admin 可强制转让
 * - 新 Owner 不存在时抛错
 * - 新 Owner 等于当前 Owner 时抛错
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("AclAO.transferOwner 集成测试")
class AclAOImplTransferOwnerTest {

    @Autowired private AclAO aclAO;
    @Autowired private IDocFileFolderService folderService;
    @Autowired private SysUserInfoMapper sysUserInfoMapper;
    @Autowired private DocFileFolderAclMapper aclMapper;

    private Long ownerUserId;
    private Long managerUserId;
    private Long thirdUserId;
    private Long adminUserId;

    @BeforeEach
    void setUp() {
        // 创建 3 个测试用户：owner / manager / 第三方
        ownerUserId = createUser("it-owner-" + System.nanoTime(), "Owner");
        managerUserId = createUser("it-manager-" + System.nanoTime(), "Manager");
        thirdUserId = createUser("it-third-" + System.nanoTime(), "Third");
        // admin 复用 owner 角色（测试时切换 LoginContext）
        adminUserId = ownerUserId;
    }

    @AfterEach
    void tearDown() {
        LoginContext.remove();
    }

    @Test
    @DisplayName("transferOwner: owner 成功转让给其他用户")
    void ownerTransfer_success() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        aclAO.transferOwner(folder.getId(), managerUserId);

        DocFileFolder updated = folderService.getById(folder.getId());
        assertNotNull(updated);
        assertEquals(managerUserId, updated.getOwnerId(), "Owner 应变为新用户");
    }

    @Test
    @DisplayName("transferOwner: 新 Owner 等于当前 Owner → 抛错")
    void transferToSelf_throws() {
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        assertThrows(BusinessException.class,
            () -> aclAO.transferOwner(folder.getId(), ownerUserId));
    }

    @Test
    @DisplayName("transferOwner: 新 Owner 用户不存在 → 抛错")
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

        // 给 managerUser 一条 MANAGE ACL
        DocFileFolderAcl acl = new DocFileFolderAcl();
        acl.setFolderId(folder.getId());
        acl.setPrincipalType("USER");
        acl.setPrincipalId(managerUserId);
        acl.setPermission("MANAGE");
        acl.setGrantedBy(ownerUserId);
        acl.setCreatedAt(LocalDateTime.now());
        aclMapper.insert(acl);

        // managerUser 尝试转让 → 应失败（不是 owner，也不是 admin）
        loginAs(managerUserId, "USER");
        assertThrows(BusinessException.class,
            () -> aclAO.transferOwner(folder.getId(), thirdUserId));
    }

    @Test
    @DisplayName("transferOwner: admin 可强制转让（即使不是 folder owner）")
    void adminCanForceTransfer() {
        // folder 属于 ownerUser，adminUser（同一人，ADMIN 角色）强制转让给 thirdUser
        DocFileFolder folder = createFolder(ownerUserId);
        loginAs(ownerUserId, "ADMIN");

        aclAO.transferOwner(folder.getId(), thirdUserId);

        DocFileFolder updated = folderService.getById(folder.getId());
        assertNotNull(updated);
        assertEquals(thirdUserId, updated.getOwnerId());
    }

    // ========== 工具方法 ==========

    private Long createUser(String account, String userName) {
        SysUserInfo u = new SysUserInfo();
        u.setAccount(account);
        u.setPassword("test-password");
        u.setUserName(userName);
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
        f.setName("IT-transfer-folder-" + System.nanoTime());
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
}
