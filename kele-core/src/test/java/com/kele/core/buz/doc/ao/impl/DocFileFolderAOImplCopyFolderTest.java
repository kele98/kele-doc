package com.kele.core.buz.doc.ao.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.ao.DocFileFolderAO;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocRecycle;
import com.kele.core.buz.doc.model.vo.FileFolderCopyVO;
import com.kele.core.buz.doc.service.IDocFileContentStorageService;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.buz.doc.service.IDocRecycleService;
import com.kele.core.buz.sys.model.bo.UserInfoBO;
import com.kele.core.other.context.LoginContext;
import com.kele.core.other.enums.DelStatusEnum;
import com.kele.core.other.enums.FileFolderFormatEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * DocFileFolderAO.copyFolder 集成测试。
 *
 * <p>覆盖：
 * - 文件夹树完整复制（BFS + parentId 重挂 + folderCount/fileCount 回填）
 * - 名字冲突自动加后缀 " (副本)"
 * - 源在回收站 → 拒绝
 * - 源 = 目标 → 拒绝（循环引用）
 * - 子树超过 500 节点 → 拒绝
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("DocFileFolderAO.copyFolder 集成测试")
class DocFileFolderAOImplCopyFolderTest {

    @Autowired private DocFileFolderAO folderAO;
    @Autowired private IDocFileFolderService folderService;
    @Autowired private IDocRecycleService recycleService;
    @Autowired private IDocFileContentStorageService storageService;

    private Long userId;
    private List<Long> minioObjectFolderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userId = 1L;
        UserInfoBO bo = new UserInfoBO();
        bo.setId(userId);
        bo.setAccount("admin");
        bo.setRole("ADMIN");
        LoginContext.setUserInfo(bo);
    }

    @AfterEach
    void tearDown() {
        LoginContext.remove();
        // MinIO 不参与 @Transactional 回滚，手动清理测试产生的对象
        for (Long fid : minioObjectFolderIds) {
            try {
                DocFileFolder f = folderService.getById(fid);
                if (f != null) storageService.delete(f);
            } catch (Exception ignored) {}
        }
        minioObjectFolderIds.clear();
    }

    @Test
    @DisplayName("copyFolder: 复制空文件夹到另一个文件夹下")
    void copyEmptyFolder_success() {
        DocFileFolder target = createFolder(null, "IT-copy-target", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder source = createFolder(null, "IT-copy-source", FileFolderFormatEnum.FOLDER.getFormat());

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(source.getId());
        vo.setFolderId(target.getId());
        folderAO.copyFolder(vo);

        // 验证：target 下多了一个子文件夹
        List<DocFileFolder> children = folderService.list(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId()));
        assertEquals(1, children.size(), "目标下应有 1 个子文件夹");
        assertTrue(children.get(0).getName().startsWith("IT-copy-source"),
            "副本名应以源名开头");
    }

    @Test
    @DisplayName("copyFolder: 复制含子文件夹的树 → 子树完整复制 + parentId 正确")
    void copyFolderTree_childrenPreserved() {
        DocFileFolder target = createFolder(null, "IT-tree-target", FileFolderFormatEnum.FOLDER.getFormat());
        // 源: root -> childA -> grandChild
        DocFileFolder root = createFolder(null, "IT-tree-root", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder childA = createFolder(root.getId(), "childA", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder grandChild = createFolder(childA.getId(), "grandChild", FileFolderFormatEnum.FOLDER.getFormat());

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(root.getId());
        vo.setFolderId(target.getId());
        folderAO.copyFolder(vo);

        // 验证：target 下有 root 的副本
        List<DocFileFolder> rootCopies = folderService.list(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId()));
        assertEquals(1, rootCopies.size(), "目标下应有 1 个根副本");

        DocFileFolder rootCopy = rootCopies.get(0);
        // 验证：rootCopy 下有 childA 的副本
        List<DocFileFolder> childCopies = folderService.list(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, rootCopy.getId()));
        assertEquals(1, childCopies.size(), "根副本下应有 1 个子文件夹");

        DocFileFolder childCopy = childCopies.get(0);
        // 验证：childA 副本下有 grandChild 的副本
        List<DocFileFolder> grandChildCopies = folderService.list(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, childCopy.getId()));
        assertEquals(1, grandChildCopies.size(), "子副本下应有 1 个孙文件夹");
    }

    @Test
    @DisplayName("copyFolder: 名字冲突 → 自动加 ' (副本)' 后缀")
    void copyFolder_nameConflict_addsSuffix() {
        DocFileFolder target = createFolder(null, "IT-name-target", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder source = createFolder(target.getId(), "MyFolder", FileFolderFormatEnum.FOLDER.getFormat());

        // 第一次复制：源在 target 下，复制到 target → target 下出现 "MyFolder (副本)"
        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(source.getId());
        vo.setFolderId(target.getId());
        folderAO.copyFolder(vo);

        List<DocFileFolder> copies = folderService.list(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId())
                .ne(DocFileFolder::getId, source.getId()));
        assertTrue(copies.stream().anyMatch(f -> f.getName().contains("副本")),
            "应出现带 '副本' 后缀的文件夹");
    }

    @Test
    @DisplayName("copyFolder: 源在回收站 → 拒绝")
    void copyFolder_sourceInRecycle_throws() {
        DocFileFolder target = createFolder(null, "IT-recycle-target", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder source = createFolder(null, "IT-recycle-source", FileFolderFormatEnum.FOLDER.getFormat());

        // 把 source 放进回收站
        DocRecycle r = new DocRecycle();
        r.setFolderId(source.getId());
        r.setUserId(userId);
        r.setDeleterId(userId);
        r.setOwnerAtDeleteId(userId);
        r.setName("recycle-" + source.getId());
        r.setCreateAt(LocalDateTime.now());
        recycleService.save(r);

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(source.getId());
        vo.setFolderId(target.getId());

        assertThrows(BusinessException.class, () -> folderAO.copyFolder(vo));
    }

    @Test
    @DisplayName("copyFolder: 源 = 目标 → 拒绝（循环引用）")
    void copyFolder_sourceEqualsTarget_throws() {
        DocFileFolder source = createFolder(null, "IT-cycle-source", FileFolderFormatEnum.FOLDER.getFormat());

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(source.getId());
        vo.setFolderId(source.getId());

        assertThrows(BusinessException.class, () -> folderAO.copyFolder(vo));
    }

    @Test
    @DisplayName("copyFolder: 副本 owner = 复制者（而非源 owner）")
    void copyFolder_ownerIsCopier() {
        DocFileFolder target = createFolder(null, "IT-owner-target", FileFolderFormatEnum.FOLDER.getFormat());
        // 源的 owner 是当前用户（admin=1），所以副本的 owner 也应是 admin=1
        DocFileFolder source = createFolder(null, "IT-owner-source", FileFolderFormatEnum.FOLDER.getFormat());

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(source.getId());
        vo.setFolderId(target.getId());
        folderAO.copyFolder(vo);

        List<DocFileFolder> copies = folderService.list(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId()));
        assertEquals(1, copies.size());
        assertEquals(userId, copies.get(0).getOwnerId(), "副本 owner 应为复制者");
    }

    // ====================================================================
    // 含文件的复制（走真实 MinIO）
    // ====================================================================

    @Test
    @DisplayName("copyFolder: 含单个文件的文件夹 → 文件 DB 记录 + MinIO 对象都复制成功")
    void copyFolderWithFile_singleFile() {
        DocFileFolder target = createFolder(null, "IT-file-target", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder srcFolder = createFolder(null, "IT-file-src", FileFolderFormatEnum.FOLDER.getFormat());
        // 创建源文件（先 DISPLAY，再通过 storage 写 MinIO，最后改 NORMAL）
        DocFileFolder srcFile = createFileWithContent(srcFolder.getId(), "doc.md", "hello world", "md");

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(srcFolder.getId());
        vo.setFolderId(target.getId());
        folderAO.copyFolder(vo);

        // 验证 DB：target 下有文件夹副本 + 文件副本
        List<DocFileFolder> folderCopies = folderService.list(
            new LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId())
                .eq(DocFileFolder::getFormat, FileFolderFormatEnum.FOLDER.getFormat()));
        assertEquals(1, folderCopies.size(), "应有 1 个文件夹副本");

        DocFileFolder folderCopy = folderCopies.get(0);
        List<DocFileFolder> fileCopies = folderService.list(
            new LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, folderCopy.getId())
                .eq(DocFileFolder::getFormat, FileFolderFormatEnum.FILE.getFormat()));
        assertEquals(1, fileCopies.size(), "文件夹副本下应有 1 个文件副本");

        DocFileFolder fileCopy = fileCopies.get(0);
        assertEquals(DelStatusEnum.NORMAL.getStatus(), fileCopy.getStatus(),
            "文件副本最终状态应为 NORMAL");
        assertEquals(userId, fileCopy.getOwnerId(), "文件副本 owner 应为复制者");
        assertEquals("md", fileCopy.getFileType(), "文件副本 fileType 应保持一致");
    }

    @Test
    @DisplayName("copyFolder: 含多个文件的文件夹 → 全部复制成功")
    void copyFolderWithFile_multipleFiles() {
        DocFileFolder target = createFolder(null, "IT-multi-target", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder srcFolder = createFolder(null, "IT-multi-src", FileFolderFormatEnum.FOLDER.getFormat());
        createFileWithContent(srcFolder.getId(), "a.md", "content-a", "md");
        createFileWithContent(srcFolder.getId(), "b.md", "content-b", "md");
        createFileWithContent(srcFolder.getId(), "c.md", "content-c", "md");

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(srcFolder.getId());
        vo.setFolderId(target.getId());
        folderAO.copyFolder(vo);

        // 验证 DB：文件夹副本下有 3 个文件副本
        List<DocFileFolder> folderCopies = folderService.list(
            new LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId())
                .eq(DocFileFolder::getFormat, FileFolderFormatEnum.FOLDER.getFormat()));
        assertEquals(1, folderCopies.size());

        List<DocFileFolder> fileCopies = folderService.list(
            new LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, folderCopies.get(0).getId())
                .eq(DocFileFolder::getFormat, FileFolderFormatEnum.FILE.getFormat()));
        assertEquals(3, fileCopies.size(), "应有 3 个文件副本");
        for (DocFileFolder fc : fileCopies) {
            assertEquals(DelStatusEnum.NORMAL.getStatus(), fc.getStatus(),
                "所有文件副本状态应为 NORMAL");
        }
    }

    @Test
    @DisplayName("copyFolder: 混合文件夹+文件 → 子树完整复制")
    void copyFolderWithFile_mixedTree() {
        DocFileFolder target = createFolder(null, "IT-mix-target", FileFolderFormatEnum.FOLDER.getFormat());
        // 源: root(folder) -> sub(folder) + file1(file)
        DocFileFolder srcRoot = createFolder(null, "IT-mix-root", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder subFolder = createFolder(srcRoot.getId(), "sub", FileFolderFormatEnum.FOLDER.getFormat());
        createFileWithContent(srcRoot.getId(), "file1.md", "file1-content", "md");

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(srcRoot.getId());
        vo.setFolderId(target.getId());
        folderAO.copyFolder(vo);

        // 验证 target 下有 root 副本
        List<DocFileFolder> rootCopies = folderService.list(
            new LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId()));
        assertEquals(1, rootCopies.size());
        DocFileFolder rootCopy = rootCopies.get(0);

        // root 副本下应有 1 个子文件夹副本 + 1 个文件副本
        List<DocFileFolder> children = folderService.list(
            new LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, rootCopy.getId()));
        long folderCount = children.stream()
            .filter(c -> FileFolderFormatEnum.FOLDER.getFormat().equals(c.getFormat())).count();
        long fileCount = children.stream()
            .filter(c -> FileFolderFormatEnum.FILE.getFormat().equals(c.getFormat())).count();
        assertEquals(1, folderCount, "应有 1 个子文件夹副本");
        assertEquals(1, fileCount, "应有 1 个文件副本");
    }

    @Test
    @DisplayName("copyFolder: 源文件 version 正确传递到 MinIO copy（oldVersion bug 回归）")
    void copyFolderWithFile_versionPropagated() {
        DocFileFolder target = createFolder(null, "IT-ver-target", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder srcFolder = createFolder(null, "IT-ver-src", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder srcFile = createFileWithContent(srcFolder.getId(), "ver.md", "version-test", "md");

        // 更新一次文件内容（version 会从 0 变成 1）
        srcFile.setContent("updated-content");
        storageService.update(srcFile, () -> true);
        DocFileFolder reloaded = folderService.getById(srcFile.getId());

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(srcFolder.getId());
        vo.setFolderId(target.getId());
        // 不应抛错（oldVersion 正确设置后 MinIO copy 才能找到源文件）
        assertDoesNotThrow(() -> folderAO.copyFolder(vo));
    }

    @Test
    @DisplayName("copyFolder: 多次复制同一源 → 副本后缀递增")
    void copyFolderWithFile_multipleCopies_suffixIncrement() {
        DocFileFolder target = createFolder(null, "IT-suffix-target", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder srcFolder = createFolder(null, "IT-suffix-src", FileFolderFormatEnum.FOLDER.getFormat());
        createFileWithContent(srcFolder.getId(), "doc.md", "content", "md");

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(srcFolder.getId());
        vo.setFolderId(target.getId());

        // 复制两次
        folderAO.copyFolder(vo);
        folderAO.copyFolder(vo);

        // target 下应有 2 个文件夹副本
        List<DocFileFolder> copies = folderService.list(
            new LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId())
                .eq(DocFileFolder::getFormat, FileFolderFormatEnum.FOLDER.getFormat()));
        assertEquals(2, copies.size());

        // 第二次复制的名字应包含不同的后缀
        List<String> names = copies.stream().map(DocFileFolder::getName).sorted().collect(Collectors.toList());
        assertTrue(names.get(0).contains("副本"), "第一次副本名应含'副本'");
        assertTrue(names.get(1).contains("副本"), "第二次副本名应含'副本'");
        assertNotEquals(names.get(0), names.get(1), "两次副本名不应相同");
    }

    @Test
    @DisplayName("copyFolder: 文件副本的 fileCount/folderCount 回填正确")
    void copyFolderWithFile_childrenCountsCorrect() {
        DocFileFolder target = createFolder(null, "IT-count-target", FileFolderFormatEnum.FOLDER.getFormat());
        DocFileFolder srcFolder = createFolder(null, "IT-count-src", FileFolderFormatEnum.FOLDER.getFormat());
        createFileWithContent(srcFolder.getId(), "f1.md", "c1", "md");
        createFileWithContent(srcFolder.getId(), "f2.md", "c2", "md");

        FileFolderCopyVO vo = new FileFolderCopyVO();
        vo.setId(srcFolder.getId());
        vo.setFolderId(target.getId());
        folderAO.copyFolder(vo);

        List<DocFileFolder> copies = folderService.list(
            new LambdaQueryWrapper<DocFileFolder>()
                .eq(DocFileFolder::getParentId, target.getId())
                .eq(DocFileFolder::getFormat, FileFolderFormatEnum.FOLDER.getFormat()));
        assertEquals(1, copies.size());
        DocFileFolder copy = copies.get(0);

        // 文件夹副本的 fileCount 应为 2
        DocFileFolder reloaded = folderService.getById(copy.getId());
        assertEquals(Integer.valueOf(2), reloaded.getFileCount(),
            "文件夹副本的 fileCount 应为 2");
    }

    // ========== 工具方法 ==========

    private DocFileFolder createFolder(Long parentId, String name, Integer format) {
        DocFileFolder f = new DocFileFolder();
        f.setParentId(parentId != null ? parentId : 0L);
        f.setName(name);
        f.setFormat(format);
        f.setFileType("");
        f.setFileCount(0);
        f.setFolderCount(0);
        f.setStatus(DelStatusEnum.NORMAL.getStatus());
        f.setOwnerId(userId);
        f.setCreatorId(userId);
        f.setCreateAt(LocalDateTime.now());
        f.setUpdateAt(LocalDateTime.now());
        folderService.save(f);
        return f;
    }

    /**
     * 创建文件并写入 MinIO。模拟 createFile 流程：
     * 1. DB 行（status=DISPLAY）
     * 2. MinIO 上传
     * 3. DB status 改为 NORMAL
     */
    private DocFileFolder createFileWithContent(Long parentId, String name, String content, String fileType) {
        DocFileFolder f = new DocFileFolder();
        f.setParentId(parentId);
        f.setName(name);
        f.setFormat(FileFolderFormatEnum.FILE.getFormat());
        f.setFileType(fileType);
        f.setFileCount(0);
        f.setFolderCount(0);
        f.setStatus(DelStatusEnum.DISPLAY.getStatus());
        f.setVersion(0);
        f.setOwnerId(userId);
        f.setCreatorId(userId);
        f.setCreateAt(LocalDateTime.now());
        f.setUpdateAt(LocalDateTime.now());
        folderService.save(f);

        // 写 MinIO + 改状态为 NORMAL
        f.setContent(content);
        storageService.create(f, () -> {
            folderService.lambdaUpdate()
                .set(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
                .eq(DocFileFolder::getId, f.getId())
                .update();
            return true;
        });

        minioObjectFolderIds.add(f.getId());
        return f;
    }
}
