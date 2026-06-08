package com.kele.core.buz.doc.ao.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.other.aspect.lock.ConcurrentLock;
import com.kele.core.buz.doc.ao.AbstractDocFileFolderAO;
import com.kele.core.buz.doc.ao.DocRecycleAO;
import com.kele.core.buz.doc.dao.entity.DocRelationLevel;
import com.kele.core.other.constants.RedissonLockPrefixCons;
import com.kele.core.other.context.LoginContext;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocRecycle;
import com.kele.core.buz.doc.model.vo.DocFileAndFolderResVO;
import com.kele.core.buz.doc.model.vo.DocFileFolderResVO;
import com.kele.core.buz.doc.model.vo.DocFileResVO;
import com.kele.core.buz.doc.model.vo.DocRecycleReqVO;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.service.IDocFileContentStorageService;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.buz.doc.service.IDocRecycleService;
import com.kele.core.buz.doc.service.IDocRelationLevelService;
import com.kele.core.other.enums.DelStatusEnum;
import com.kele.core.other.enums.FileFolderFormatEnum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * v0.11 + v0.12 重构版。详见 spec §6.2 + §7.1.a。
 */
@Slf4j
@Service
public class DocRecycleAOImpl extends AbstractDocFileFolderAO implements DocRecycleAO {

    @Autowired
    private IDocRecycleService docRecycleService;

    @Autowired
    private IDocFileFolderService docFileFolderService;

    @Autowired
    private IDocRelationLevelService docRelationLevelService;

    @Autowired
    private IDocFileContentStorageService docFileContentStorageService;

    @Autowired
    private DocFileFolderAclMapper docFileFolderAclService;

    /**
     * v0.11 m7: getId → getFolderId
     */
    @Override
    public DocFileAndFolderResVO getRecycleFolderAndFileList(String name) {

        Long userId = LoginContext.getUserId();
        List<DocRecycle> docRecycleList = docRecycleService.list(Wrappers.<DocRecycle>lambdaQuery()
            .eq(DocRecycle::getUserId, userId)
            .like(StringUtils.hasText(name), DocRecycle::getName, name));
        DocFileAndFolderResVO docFileAndFolderResVO = new DocFileAndFolderResVO();
        docFileAndFolderResVO.setFileList(Collections.emptyList());
        docFileAndFolderResVO.setFolderList(Collections.emptyList());
        if (CollectionUtils.isEmpty(docRecycleList)) {
            return docFileAndFolderResVO;
        }
        // v0.11 m7: DocRecycle::getId → DocRecycle::getFolderId
        List<Long> folderIdList = docRecycleList.stream().map(DocRecycle::getFolderId).distinct()
            .collect(Collectors.toList());
        List<DocFileFolder> folderList = docFileFolderService.listByIds(folderIdList);

        List<DocFileFolderResVO> fileFolderBaseResVOList = folderList.stream()
            .filter(folder -> FileFolderFormatEnum.FOLDER.getFormat().equals(folder.getFormat()))
            .map(fileFolder -> {
                DocFileFolderResVO resVO = new DocFileFolderResVO();
                resVO.setId(fileFolder.getId());
                resVO.setName(fileFolder.getName());
                return resVO;
            }).collect(Collectors.toList());

        List<DocFileResVO> fileList = this.filterFileList(folderList);
        docFileAndFolderResVO.setFolderList(fileFolderBaseResVOList);
        docFileAndFolderResVO.setFileList(fileList);
        return docFileAndFolderResVO;
    }

    /**
     * v0.11 m7 + v0.12 重构：
     * - line 87: getParentId, id → getParentId, docRecycle.getFolderId() (reqVO.getId() 是 recycle 记录 id)
     * - line 116: 加 userId 防御性过滤
     * - line 122: getId, id → getId, docRecycle.getFolderId()
     * - 配合 Task 15：getRecycleById(id) 接受 recycleId
     */
    @Override
    @ConcurrentLock(key = RedissonLockPrefixCons.RESTORE_FOLDER + "${reqVO.id}")
    public void restore(DocRecycleReqVO reqVO) {
        Long folderId = reqVO.getId();  // 前端传的是 folderId（文件/文件夹的业务 id）
        Long userId = LoginContext.getUserId();
        // 按 folderId + userId 查当前用户的 recycle 记录
        DocRecycle docRecycleRecord = docRecycleService.getOne(Wrappers.<DocRecycle>lambdaQuery()
            .eq(DocRecycle::getFolderId, folderId)
            .eq(DocRecycle::getUserId, userId));
        if (Objects.isNull(docRecycleRecord)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "该文件夹已被恢复请刷新列表！");
        }
        Long recycleId = docRecycleRecord.getId();
        List<DocRelationLevel> levelList = docRelationLevelService.lambdaQuery()
            .eq(DocRelationLevel::getParentId, folderId)
            .list();
        if(CollectionUtils.isEmpty(levelList)) {
            log.error("folderId为{}的文件数据恢复异常", folderId);
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "数据恢复异常，请联系管理员！");
        }
        DocFileFolder docFileFolder = docFileFolderService.getById(folderId);
        if (Objects.isNull(docFileFolder)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "该文件不存在或已被删除！");
        }
        // v0.13: 修复 walk 永不 reparent 的 bug。
        // v0.7 模型下 doc_file_folder.status 恒 NORMAL（软删除搬到 doc_recycle），
        // 旧 walk 第一次就 break，finalParentId 永远 = 原 parentId，reparent update 永不执行。
        // 改为按 doc_recycle 判：当前用户回收站里的祖先才需要 walk 跳过。
        // 走到根（0L）= 全部祖先都在回收站，挂到根（合法，下游 requireWrite 跳过 0）。
        Long parentId = docFileFolder.getParentId();
        while (Objects.nonNull(parentId) && parentId != 0L) {
            DocFileFolder parent = docFileFolderService.getById(parentId);
            if (Objects.isNull(parent)) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                    String.format("id为%s的父文件夹不存在", parentId));
            }
            boolean parentInMyRecycle = docRecycleService.lambdaQuery()
                .eq(DocRecycle::getFolderId, parentId)
                .eq(DocRecycle::getUserId, userId)
                .exists();
            if (!parentInMyRecycle) {
                break;  // 当前用户可见的最近祖先
            }
            parentId = parent.getParentId();
        }
        Long finalParentId = parentId;
        // 修复 Bug #4：恢复操作要求用户对最终父级有 WRITE 权限（防御 transferOwner/ACL 撤销等场景）
        // finalParentId 为 0/null 表示恢复到顶级根，无需校验
        if (Objects.nonNull(finalParentId) && finalParentId > 0L) {
            permissionService.requireWrite(finalParentId);
        }
        List<Long> childIds = levelList.stream().map(DocRelationLevel::getSonId).collect(Collectors.toList());
        List<Long> relationLevelIds = levelList.stream().map(DocRelationLevel::getId).collect(Collectors.toList());
        transactionTemplate.execute(status -> {
            // 删 recycle 记录（即"恢复"：文件 status 恒为 1，删掉 recycle 标记即可）
            docRecycleService.remove(Wrappers.<DocRecycle>lambdaQuery()
                .eq(DocRecycle::getUserId, userId)
                .eq(DocRecycle::getId, recycleId));
            docRelationLevelService.removeBatchByIds(relationLevelIds);
            Integer format = docFileFolder.getFormat();
            if(!Objects.equals(finalParentId, docFileFolder.getParentId())) {
                // v0.12: getId, id → getId, docRecycle.getFolderId()
                docFileFolderService.update(Wrappers.<DocFileFolder>lambdaUpdate()
                    .set(DocFileFolder::getParentId, finalParentId)
                    .eq(DocFileFolder::getId, folderId));
            }
            if(Objects.nonNull(finalParentId) && finalParentId > 0L) {
                if(FileFolderFormatEnum.FOLDER.getFormat().equals(format)) {
                    docFileFolderService.updateFolderCount(finalParentId, 1);
                }
                if(FileFolderFormatEnum.FILE.getFormat().equals(format)) {
                    docFileFolderService.updateFileCount(finalParentId, 1);
                }
            }
            return null;
        });
    }

    /**
     * v0.11 m7 + v0.12 + Bug #2 修复：
     * - 递归收集整个子树（folderId 自身 + 所有后代）
     * - 单事务批量硬删 folder + ACL + recycle + relation_level（全部覆盖整个子树）
     * - 事务提交后 best-effort 批量清理存储内容（仅 FILE 类型，覆盖整个子树）
     * - 避免父文件夹被硬删后子节点成为 DB 孤儿 + 存储孤儿
     */
    @Override
    public void completelyDelete(DocRecycleReqVO reqVO) {
        Long folderId = reqVO.getId();  // 前端传的是 folderId
        Long userId = LoginContext.getUserId();
        DocRecycle docRecycleRecord = docRecycleService.getOne(Wrappers.<DocRecycle>lambdaQuery()
            .eq(DocRecycle::getFolderId, folderId)
            .eq(DocRecycle::getUserId, userId));
        if (Objects.isNull(docRecycleRecord)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "回收记录不存在或已清空");
        }
        Long recycleId = docRecycleRecord.getId();
        // 先快照根 folder（事务提交后 DB 行就没了）
        DocFileFolder rootFolder = docFileFolderService.getById(folderId);
        if (Objects.isNull(rootFolder)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "该文件不存在或已被删除！");
        }
        // Bug #2：递归收集整个子树，避免父被硬删后子节点成为 DB / 存储 orphan
        List<DocFileFolder> subtree = new ArrayList<>();
        subtree.add(rootFolder);
        super.getChild(Collections.singletonList(folderId), subtree);
        List<Long> subtreeIds = subtree.stream().map(DocFileFolder::getId).collect(Collectors.toList());
        // 仅 FILE 类型有 storage content 需要清理
        List<DocFileFolder> filesToClean = subtree.stream()
            .filter(f -> FileFolderFormatEnum.FILE.getFormat().equals(f.getFormat()))
            .collect(Collectors.toList());
        transactionTemplate.execute(status -> {
            // 硬删 ACL（覆盖整个子树）
            docFileFolderAclService.delete(Wrappers.<com.kele.core.buz.doc.dao.entity.DocFileFolderAcl>lambdaQuery()
                .in(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getFolderId, subtreeIds));
            // 硬删 doc_file_folder（覆盖整个子树）
            docFileFolderService.remove(Wrappers.<DocFileFolder>lambdaQuery()
                .in(DocFileFolder::getId, subtreeIds));
            // 删 recycle 记录（覆盖整个子树；deleteFolder 时为每个 child 都创建了 recycle 记录）
            docRecycleService.remove(Wrappers.<DocRecycle>lambdaQuery()
                .eq(DocRecycle::getUserId, userId)
                .in(DocRecycle::getFolderId, subtreeIds));
            // 删关联层级（覆盖整个子树）
            docRelationLevelService.remove(Wrappers.<DocRelationLevel>lambdaQuery()
                .in(DocRelationLevel::getParentId, subtreeIds));
            return null;
        });
        // 事务提交后 best-effort 批量清理存储内容（失败仅记日志，不影响用户）
        filesToClean.forEach(f -> {
            try {
                docFileContentStorageService.delete(f);
            } catch (Exception e) {
                log.error("清理存储内容失败 folderId={}", f.getId(), e);
            }
        });
    }

    /**
     * v0.12 MAJOR: 整方法重构。详见 spec §7 表格"清空回收站"行。
     * v0.13 Bug #1 修复：把每个 recycle.folder_id 展开成子树后再删，避免"用户曾单独恢复过子节点
     * （recycle 行已删，但 parent_id 仍指向父）→ 父被清空时子节点成 DB / 存储孤儿"。
     * 设计取舍与 Bug #2 一致：父被永久删除时，子就算被恢复过也跟着走（合理，父没了子没有挂载点）。
     */
    @Override
    public void emptyRecycle() {
        Long userId = LoginContext.getUserId();
        // 1. 收集所有 folder_id
        List<DocRecycle> docRecycleList = docRecycleService.lambdaQuery()
            .eq(DocRecycle::getUserId, userId).list();
        if (CollectionUtils.isEmpty(docRecycleList)) {
            return;
        }
        List<Long> folderIds = docRecycleList.stream()
            .map(DocRecycle::getFolderId).collect(Collectors.toList());
        // 2. 把每个 recycle.folder_id 展开成子树，避免子节点成为孤儿
        List<DocFileFolder> roots = docFileFolderService.listByIds(folderIds);
        List<DocFileFolder> allSubtree = new ArrayList<>();
        for (DocFileFolder root : roots) {
            // 防御：recycle 行存在但 DB 行已被并行删的边缘情况
            if (root == null) continue;
            List<DocFileFolder> sub = new ArrayList<>();
            sub.add(root);
            super.getChild(Collections.singletonList(root.getId()), sub);
            allSubtree.addAll(sub);
        }
        List<Long> allSubtreeIds = allSubtree.stream()
            .map(DocFileFolder::getId).distinct().collect(Collectors.toList());
        // 仅 FILE 类型有 storage content 需要清理
        List<DocFileFolder> filesToClean = allSubtree.stream()
            .filter(f -> FileFolderFormatEnum.FILE.getFormat().equals(f.getFormat()))
            .collect(Collectors.toList());
        // 3. 单事务批量硬删（覆盖整个子树）
        transactionTemplate.execute(status -> {
            // 删 doc_file_folder_acl
            docFileFolderAclService.delete(Wrappers.<com.kele.core.buz.doc.dao.entity.DocFileFolderAcl>lambdaQuery()
                .in(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getFolderId, allSubtreeIds));
            // 删 doc_file_folder（含子文件夹）
            docFileFolderService.remove(Wrappers.<DocFileFolder>lambdaQuery()
                .in(DocFileFolder::getId, allSubtreeIds));
            // 删 recycle 行（仅当前用户；可能包含子树里的 recycle 行——比如用户曾恢复过子节点后又被清空）
            docRecycleService.remove(Wrappers.<DocRecycle>lambdaQuery()
                .eq(DocRecycle::getUserId, userId)
                .in(DocRecycle::getFolderId, allSubtreeIds));
            // 删 relation_level
            docRelationLevelService.remove(Wrappers.<DocRelationLevel>lambdaQuery()
                .in(DocRelationLevel::getParentId, allSubtreeIds));
            return null;
        });
        // 4. 事务提交后 best-effort 批量清理存储内容（失败仅记日志）
        filesToClean.forEach(f -> {
            try {
                docFileContentStorageService.delete(f);
            } catch (Exception e) {
                log.error("清理存储内容失败 folderId={}", f.getId(), e);
            }
        });
    }
}
