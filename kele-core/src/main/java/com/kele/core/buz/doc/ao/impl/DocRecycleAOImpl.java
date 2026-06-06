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
import org.springframework.transaction.annotation.Transactional;
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
        // 检查父级是否被删除，如果被删除，找到其上未被删除的父级，挂在下面
        Long parentId = docFileFolder.getParentId();
        while(Objects.nonNull(parentId) && parentId != 0L) {
            DocFileFolder parent = docFileFolderService.getById(parentId);
            if (Objects.isNull(parent)) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                    String.format("id为%s的父文件夹不存在", parentId));
            }
            if(DelStatusEnum.NORMAL.getStatus().equals(parent.getStatus())) {
                break;
            }
            parentId = parent.getParentId();
        }
        Long finalParentId = parentId;
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
     * v0.11 m7 重构：getRecycleById(recycleId) 而非 folderId
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
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
        transactionTemplate.execute(status -> {
            // 硬删 ACL
            docFileFolderAclService.delete(Wrappers.<com.kele.core.buz.doc.dao.entity.DocFileFolderAcl>lambdaQuery()
                .eq(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getFolderId, folderId));
            // 硬删 doc_file_folder
            docFileFolderService.removeById(folderId);
            // 删 recycle 记录
            docRecycleService.removeById(recycleId);
            // 删关联层级
            docRelationLevelService.remove(Wrappers.<DocRelationLevel>lambdaQuery()
                .eq(DocRelationLevel::getParentId, folderId));
            return null;
        });
    }

    /**
     * v0.12 MAJOR: 整方法重构。详见 spec §7 表格"清空回收站"行。
     * 收集当前用户所有 recycle 记录 → 单事务批量硬删 folder + ACL + content + recycle + relation_level
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
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
        // 2. 单事务批量硬删
        transactionTemplate.execute(status -> {
            // 删 doc_file_folder_acl
            docFileFolderAclService.delete(Wrappers.<com.kele.core.buz.doc.dao.entity.DocFileFolderAcl>lambdaQuery()
                .in(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getFolderId, folderIds));
            // 删 doc_file_folder（含子文件夹；调用方若有需要可单 SQL 删除子树）
            docFileFolderService.remove(Wrappers.<DocFileFolder>lambdaQuery()
                .in(DocFileFolder::getId, folderIds));
            // 删 recycle 行（仅当前用户）
            docRecycleService.remove(Wrappers.<DocRecycle>lambdaQuery()
                .eq(DocRecycle::getUserId, userId));
            // 删 relation_level
            docRelationLevelService.remove(Wrappers.<DocRelationLevel>lambdaQuery()
                .in(DocRelationLevel::getParentId, folderIds));
            return null;
        });
    }
}
