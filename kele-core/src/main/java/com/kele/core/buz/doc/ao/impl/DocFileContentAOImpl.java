package com.kele.core.buz.doc.ao.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.other.aspect.lock.ConcurrentLock;
import com.kele.core.buz.doc.ao.AbstractDocFileFolderAO;
import com.kele.core.buz.doc.ao.DocFileContentAO;
import com.kele.core.other.context.LoginContext;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocRecycle;
import com.kele.core.buz.doc.model.vo.DocFileContentResVO;
import com.kele.core.buz.doc.model.vo.DocFileCopyReqVO;
import com.kele.core.buz.doc.model.vo.DocFileCreateReqVO;
import com.kele.core.buz.doc.model.vo.DocFileDelReqVO;
import com.kele.core.buz.doc.model.vo.DocFileMoveReqVO;
import com.kele.core.buz.doc.model.vo.DocFileUpdateReqVO;
import com.kele.core.other.enums.DelStatusEnum;
import com.kele.core.other.enums.FileFolderFormatEnum;
import com.kele.core.buz.sys.service.ISysFileUploadService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * v0.11 + v0.12 重构版。详见 spec §6.2 该文件 5 处条目。
 */
@Slf4j
@Service
public class DocFileContentAOImpl extends AbstractDocFileFolderAO implements DocFileContentAO {

    @Autowired
    private ISysFileUploadService sysFileUploadService;

    @Override
    @Deprecated
    public DocFileContentResVO getFileContent(Long id) {
        permissionService.requireRead(id);
        DocFileFolder docFileFolder = super.getById(id);
        return docFileContentStorageService.getFileContent(docFileFolder);
    }

    /**
     * v0.11 B1: 加 setOwnerId
     */
    @Override
    public DocFileContentResVO createFile(DocFileCreateReqVO createReqVO) {

        DocFileFolder parent = super.getById(createReqVO.getFolderId());
        if (Objects.isNull(parent)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "父文件夹不存在或无权访问");
        }
        if (FileFolderFormatEnum.FILE.getFormat().equals(parent.getFormat())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "只能在文件夹下创建文件");
        }
        // 创建文件需对父 WRITE（v0.7 §5.4.a 隐含）
        permissionService.requireWrite(createReqVO.getFolderId());
        DocFileFolder fileFolder = new DocFileFolder();
        fileFolder.setParentId(createReqVO.getFolderId());
        fileFolder.setName(createReqVO.getName());
        fileFolder.setFileCount(0);
        fileFolder.setFolderCount(0);
        fileFolder.setFormat(FileFolderFormatEnum.FILE.getFormat());
        fileFolder.setFileType(createReqVO.getType());
        fileFolder.setImg(null);
        fileFolder.setVersion(0);
        Long currentUserId = LoginContext.getUserId();
        fileFolder.setCreatorId(currentUserId);
        fileFolder.setOwnerId(currentUserId);  // v0.11 B1: 创建者 = Owner
        fileFolder.setCreateAt(LocalDateTime.now());
        fileFolder.setUpdateAt(LocalDateTime.now());
        // 先置为无效状态
        fileFolder.setStatus(DelStatusEnum.DISPLAY.getStatus());
        boolean saveSuccess = docFileFolderService.save(fileFolder);
        if(!saveSuccess) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "文档保存失败！原因=》文件元数据保存失败！");
        }

        boolean success = docFileContentStorageService.create(fileFolder, () ->
            transactionTemplate.execute(status -> {
                boolean statusUpdate = docFileFolderService.lambdaUpdate()
                    .set(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
                    .eq(DocFileFolder::getId, fileFolder.getId())
                    .update();
                if (!statusUpdate) {
                    throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                        "文档保存失败！原因=》将文件置为生效状态时失败！");
                }
                int fileCountUpdate = docFileFolderService.updateFileCount(createReqVO.getFolderId(), 1);
                if (fileCountUpdate <= 0) {
                    throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                        "文档保存失败！原因=》更新父文件夹下文件数量时失败！");
                }
                return true;
            })
        );
        if (!success) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "保存文档内容失败！");
        }
        DocFileContentResVO resVO = new DocFileContentResVO();
        resVO.setId(fileFolder.getId());
        resVO.setName(fileFolder.getName());
        resVO.setFolderId(fileFolder.getParentId());
        resVO.setType(fileFolder.getFileType());
        return resVO;
    }

    @Override
    @ConcurrentLock(key = "com.kele.core.doc.ao.impl.DocFileContentAOImpl.updateFile(${updateReqVO.id})")
    public void updateFile(DocFileUpdateReqVO updateReqVO) {
        Long fileId = updateReqVO.getId();
        permissionService.requireWrite(fileId);
        DocFileFolder fileFolder = super.getById(fileId);
        DocFileFolder updateFolder = new DocFileFolder();
        updateFolder.setId(fileFolder.getId());
        updateFolder.setName(updateReqVO.getName());
        updateFolder.setImg(updateReqVO.getImg());
        updateFolder.setCreatorId(fileFolder.getCreatorId());
        updateFolder.setUpdateAt(LocalDateTime.now());
        updateFolder.setContent(updateReqVO.getContent());
        updateFolder.setVersion(fileFolder.getVersion());
        boolean success = docFileContentStorageService.update(updateFolder, () -> {
                boolean updateResult = docFileFolderService.updateById(updateFolder);
                if (!updateResult) {
                    throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                        "文档内容更新失败！原因=》更新文件元数据失败！");
                }
                return true;
            }
        );
        if(!success) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "文档内容更新失败！");
        }
        try {
            String oldImg = fileFolder.getImg();
            String newImg = updateReqVO.getImg();
            if (StringUtils.hasText(oldImg)
                && StringUtils.hasText(newImg)
                && !oldImg.equals(newImg)) {
                sysFileUploadService.delete(oldImg);
            }
        } catch (Exception e) {
            log.error("删除老的封面图片失败！", e);
        }
    }

    /**
     * v0.13 #13: 源 WRITE + 目标父 WRITE。
     * 移动 = 从源摘走（改源）+ 放入目标（写目标），两侧都需要 WRITE。
     */
    @Override
    public void moveFile(DocFileMoveReqVO reqVO) {

        Long newFolderId = reqVO.getNewFolderId();

        // 移动改变文件归属结构，需要源文件和目标文件夹都是 MANAGE 权限
        for (Long id : reqVO.getIds()) {
            permissionService.requireManage(id);
        }
        permissionService.requireManage(newFolderId);

        DocFileFolder targetFolder = super.getById(newFolderId);
        if (FileFolderFormatEnum.FILE.getFormat().equals(targetFolder.getFormat())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "只能迁移到文件夹下！");
        }

        List<Long> idList = reqVO.getIds();
        List<DocFileFolder> fileFolders = super.selectByIdList(idList);  // 已被 Task 15 改造为走 PermissionService
        List<String> checkFileNames = fileFolders.stream().filter(e -> e.getParentId().equals(newFolderId))
            .map(DocFileFolder::getName)
            .collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(checkFileNames)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), String.format("要移动的【%s】已在指定文件夹下，不要重复移入！", checkFileNames
                .stream().collect(Collectors.joining(","))));
        }
        Map<Long, Integer> parentIdMapSizeMap = fileFolders.stream()
            .filter(e -> Objects.nonNull(e.getParentId()) && e.getParentId() > 0L)
            .collect(Collectors.groupingBy(DocFileFolder::getParentId,
                Collectors.summingInt(x -> 1)));
        List<DocFileFolder> updateOldFolderList = parentIdMapSizeMap.entrySet().stream().map(entry -> {
            DocFileFolder update = new DocFileFolder();
            update.setId(entry.getKey());
            update.setFileCount(-entry.getValue());
            return update;
        }).collect(Collectors.toList());
        List<DocFileFolder> updateList = idList.stream().map(id -> {
            DocFileFolder update = new DocFileFolder();
            update.setId(id);
            update.setParentId(reqVO.getNewFolderId());
            return update;
        }).collect(Collectors.toList());
        transactionTemplate.execute(status -> {
            if (!CollectionUtils.isEmpty(updateOldFolderList)) {
                docFileFolderService.batchDeltaUpdate(updateOldFolderList);
            }
            docFileFolderService.updateFileCount(reqVO.getNewFolderId(), updateList.size());
            docFileFolderService.updateBatchById(updateList);
            return null;
        });
    }

    /**
     * v0.11 B1: 源 READ + 目标父 MANAGE（v0.7 §5.4.a 文件级）
     */
    @Override
    public void copyFile(DocFileCopyReqVO reqVO) {

        Long newFolderId = reqVO.getNewFolderId();
        DocFileFolder parent = super.getById(newFolderId);
        if (Objects.isNull(parent)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "目标文件夹不存在！");
        }
        if (FileFolderFormatEnum.FILE.getFormat().equals(parent.getFormat())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "只能复制到文件夹下！");
        }
        // 源文件 READ 校验
        for (Long id : reqVO.getIds()) {
            permissionService.requireRead(id);
        }
        // 目标父 WRITE 校验（WRITE 用户可在文件夹内创建/复制文件）
        permissionService.requireWrite(newFolderId);

        List<Long> originIdList = reqVO.getIds();
        List<DocFileFolder> fileFolders = super.selectByIdList(originIdList);
        if (CollectionUtils.isEmpty(fileFolders)) {
            return;
        }
        LocalDateTime currentLdt = LocalDateTime.now();
        Long userId = LoginContext.getUserId();
        fileFolders.forEach(e -> {
            e.setOldId(e.getId());
            e.setId(null);
            e.setCreateAt(currentLdt);
            e.setUpdateAt(currentLdt);
            e.setParentId(reqVO.getNewFolderId());
            e.setOldVersion(e.getVersion());
            e.setVersion(0);
            e.setCreatorId(userId);
            e.setOwnerId(userId);  // v0.7: 复制者 = 新 Owner
            e.setStatus(DelStatusEnum.DISPLAY.getStatus());
        });
        boolean saveSuccess = docFileFolderService.saveBatch(fileFolders);
        if(!saveSuccess) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "文档复制失败！");
        }
        boolean success = docFileContentStorageService.copy(fileFolders, () -> {
            return transactionTemplate.execute(status -> {
                boolean updateStatusResult = docFileFolderService.lambdaUpdate()
                    .set(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
                    .in(DocFileFolder::getId, fileFolders.stream().map(DocFileFolder::getId)
                        .collect(Collectors.toList()))
                    .update();
                if(!updateStatusResult) {
                    throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "文档内容复制失败！原因=》更新文件元数据状态失败！");
                }
                int updateFileCount = docFileFolderService.updateFileCount(reqVO.getNewFolderId(), fileFolders.size());
                if(updateFileCount <= 0) {
                    throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "文档内容复制失败！原因=》更新父文件夹文件数量失败！");
                }
                return true;
            });
        });
        if(!success) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "文档复制失败！");
        }
    }

    /**
     * v0.10 + v0.11 B5 重构：
     * - 删 .set(DocFileFolder::getStatus, DelStatusEnum.DEL.getStatus()) 整段（v0.7 §7.1.a status 恒为 1）
     * - docRecycle.setId(e.getId()) → docRecycle.setFolderId(e.getId())
     */
    @Override
    public void deleteFile(DocFileDelReqVO reqVO) {

        List<Long> idList = reqVO.getIds();
        for (Long id : idList) {
            permissionService.requireManage(id);
        }
        List<DocFileFolder> fileFolders = super.selectByIdList(idList);
        Map<Long, Integer> parentIdMapSizeMap = fileFolders.stream()
            .filter(e -> Objects.nonNull(e.getParentId()) && e.getParentId() > 0L)
            .collect(Collectors.groupingBy(DocFileFolder::getParentId,
                Collectors.summingInt(x -> 1)));
        List<DocFileFolder> updateOldFolderList = parentIdMapSizeMap.entrySet().stream().map(entry -> {
            DocFileFolder update = new DocFileFolder();
            update.setId(entry.getKey());
            update.setFileCount(-entry.getValue());
            return update;
        }).collect(Collectors.toList());
        LocalDateTime recycleTime = LocalDateTime.now();
        Long userId = LoginContext.getUserId();
        List<DocRecycle> docRecycleList = fileFolders.stream().map(e -> {
            DocRecycle docRecycle = new DocRecycle();
            docRecycle.setFolderId(e.getId());  // v0.11 B5: setId → setFolderId
            docRecycle.setIdList(Collections.singletonList(e.getId()));
            docRecycle.setName(e.getName());
            docRecycle.setUserId(userId);
            docRecycle.setDeleterId(userId);
            docRecycle.setOwnerAtDeleteId(e.getOwnerId());
            docRecycle.setCreateAt(recycleTime);
            return docRecycle;
        }).collect(Collectors.toList());
        transactionTemplate.execute(status -> {
            // v0.10 B5: 删整段 .set(DocFileFolder::getStatus, DelStatusEnum.DEL.getStatus())
            // status 保持 1 不变
            docRecycleService.saveBatch(docRecycleList);
            super.saveRecycleLevel(docRecycleList);
            if (!CollectionUtils.isEmpty(updateOldFolderList)) {
                docFileFolderService.batchDeltaUpdate(updateOldFolderList);
            }
            return null;
        });
    }

    @Override
    public void downloadFileContent(Long id, HttpServletResponse response) {
        permissionService.requireRead(id);
        DocFileFolder docFileFolder = super.getById(id);
        docFileContentStorageService.downloadFileContent(docFileFolder, response);
    }

    /**
     * v0.12 MINOR: 加 requireRead 鉴权
     */
    @Override
    public DocFileContentResVO getFileBaseInfo(Long id) {
        permissionService.requireRead(id);  // v0.12: 加鉴权
        DocFileFolder docFileFolder = docFileFolderService.getById(id);
        DocFileContentResVO resVO = new DocFileContentResVO();
        resVO.setId(id);
        resVO.setName(docFileFolder.getName());
        resVO.setUpdateAt(docFileFolder.getUpdateAt());
        resVO.setCreateAt(docFileFolder.getCreateAt());
        return resVO;
    }
}
