package com.kele.core.buz.doc.ao.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.core.buz.doc.ao.DocCollectFolderAO;
import com.kele.core.buz.doc.dao.entity.DocCollectFolder;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.model.vo.DocCollectReqVO;
import com.kele.core.buz.doc.model.vo.DocFileResVO;
import com.kele.core.buz.doc.permission.PermissionService;
import com.kele.core.buz.doc.service.IDocCollectFolderService;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.other.context.LoginContext;
import com.kele.core.other.enums.DelStatusEnum;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 收藏功能：per-user 关联表实现。
 * <p>
 * 废弃原 DocFileFolder.collected 全局布尔标志，
 * 改用 doc_collect_folder 表记录 (user_id, folder_id) 的 per-user 收藏关系。
 */
@Service
public class DocCollectFolderAOImpl implements DocCollectFolderAO {

    @Autowired
    private IDocCollectFolderService docCollectFolderService;

    @Autowired
    private IDocFileFolderService docFileFolderService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private DocFileFolderAOImpl docFileFolderAO;

    @Override
    public List<DocFileResVO> getCollectFileList(String name) {
        Long userId = LoginContext.getUserId();

        // 1. 查出当前用户收藏的所有 folder_id
        List<Long> collectedFolderIds = docCollectFolderService.list(
                Wrappers.<DocCollectFolder>lambdaQuery()
                        .eq(DocCollectFolder::getUserId, userId))
                .stream().map(DocCollectFolder::getFolderId).collect(Collectors.toList());

        if (collectedFolderIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 过滤可见性：admin 旁路，普通用户走 owner + ACL
        String recycleSubSql = "SELECT folder_id FROM doc_recycle WHERE user_id = " + userId;
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocFileFolder> query =
                Wrappers.<DocFileFolder>lambdaQuery()
                .in(DocFileFolder::getId, collectedFolderIds)
                .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
                .like(StringUtils.hasText(name), DocFileFolder::getName, name)
                .notInSql(DocFileFolder::getId, recycleSubSql);

        if (!LoginContext.isAdmin()) {
            String aclSubSql = docFileFolderAO.buildAclSubSql(userId);
            query.and(w -> w
                    .eq(DocFileFolder::getOwnerId, userId)
                    .or().inSql(DocFileFolder::getId, aclSubSql));
        }

        return docFileFolderService.list(query).stream().map(fileFolder -> {
            DocFileResVO resVO = new DocFileResVO();
            resVO.setId(fileFolder.getId());
            resVO.setName(fileFolder.getName());
            resVO.setType(fileFolder.getFileType());
            resVO.setImg(fileFolder.getImg());
            resVO.setCollected(true);
            resVO.setCreateAt(fileFolder.getCreateAt());
            resVO.setUpdateAt(fileFolder.getUpdateAt());
            return resVO;
        }).collect(Collectors.toList());
    }

    @Override
    public void collect(DocCollectReqVO reqVO) {
        this.checkFilePermission(reqVO.getId());
        Long userId = LoginContext.getUserId();

        // 幂等：已收藏则跳过
        long exists = docCollectFolderService.count(
                Wrappers.<DocCollectFolder>lambdaQuery()
                        .eq(DocCollectFolder::getUserId, userId)
                        .eq(DocCollectFolder::getFolderId, reqVO.getId()));
        if (exists > 0) {
            return;
        }

        DocCollectFolder collect = new DocCollectFolder();
        collect.setUserId(userId);
        collect.setFolderId(reqVO.getId());
        collect.setCreateAt(LocalDateTime.now());
        docCollectFolderService.save(collect);
    }

    @Override
    public void cancelCollect(DocCollectReqVO reqVO) {
        this.checkFilePermission(reqVO.getId());
        Long userId = LoginContext.getUserId();

        docCollectFolderService.remove(
                Wrappers.<DocCollectFolder>lambdaQuery()
                        .eq(DocCollectFolder::getUserId, userId)
                        .eq(DocCollectFolder::getFolderId, reqVO.getId()));
    }

    private void checkFilePermission(Long fileId) {
        permissionService.requireRead(fileId);
    }
}
