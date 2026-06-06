package com.kele.core.buz.doc.ao.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.ao.DocCollectFolderAO;
import com.kele.core.other.context.LoginContext;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.model.vo.DocCollectReqVO;
import com.kele.core.buz.doc.model.vo.DocFileResVO;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.buz.doc.permission.PermissionService;
import com.kele.core.other.enums.DelStatusEnum;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * v0.11 + v0.12 重构版。详见 spec §6.2 该文件 2 处条目。
 */
@Service
public class DocCollectFolderAOImpl implements DocCollectFolderAO {

    private static final String NO_PERMISSION_MSG = "无权操作该文件！";
    private static final String FILE_NOT_EXIST_MSG = "文件不存在！";

    @Autowired
    private IDocFileFolderService docFileFolderService;

    @Autowired
    private PermissionService permissionService;

    /**
     * v0.11 B1: 删 creatorId 硬编码过滤；v0.7 §7.1 排除回收站
     */
    @Override
    public List<DocFileResVO> getCollectFileList(String name) {
        Long userId = LoginContext.getUserId();
        return docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
            .eq(DocFileFolder::getCollected, true)
            .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
            .and(w -> w
                // 我 owner 的可见
                .eq(DocFileFolder::getOwnerId, userId)
                // 或我通过 ACL 可见（USER / ORG / GROUP 命中）
                .or().in(DocFileFolder::getId, Wrappers.<com.kele.core.buz.doc.dao.entity.DocFileFolderAcl>lambdaQuery()
                    .select(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getFolderId)
                    .isNull(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getRevokedAt)
                    .and(a -> a.eq(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getPrincipalType, "USER")
                        .eq(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getPrincipalId, userId)
                        .or().eq(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getPrincipalType, "ORG")
                        .or().in(com.kele.core.buz.doc.dao.entity.DocFileFolderAcl::getPrincipalId,
                            LoginContext.getUserGroupIds()))))
            .like(StringUtils.hasText(name), DocFileFolder::getName, name)
            // 排除当前用户软删的（v0.7）
            .notIn(DocFileFolder::getId, Wrappers.<com.kele.core.buz.doc.dao.entity.DocRecycle>lambdaQuery()
                .eq(com.kele.core.buz.doc.dao.entity.DocRecycle::getUserId, userId)
                .select(com.kele.core.buz.doc.dao.entity.DocRecycle::getFolderId)))
            .stream().map(fileFolder -> {
                DocFileResVO resVO = new DocFileResVO();
                resVO.setId(fileFolder.getId());
                resVO.setName(fileFolder.getName());
                resVO.setType(fileFolder.getFileType());
                resVO.setImg(fileFolder.getImg());
                resVO.setCollected(fileFolder.getCollected());
                resVO.setCreateAt(fileFolder.getCreateAt());
                resVO.setUpdateAt(fileFolder.getUpdateAt());
                return resVO;
            }).collect(Collectors.toList());
    }

    @Override
    public void cancelCollect(DocCollectReqVO reqVO) {
        this.checkFilePermission(reqVO.getId());
        DocFileFolder update = new DocFileFolder();
        update.setId(reqVO.getId());
        update.setCollected(false);
        docFileFolderService.updateById(update);
    }

    @Override
    public void collect(DocCollectReqVO reqVO) {
        this.checkFilePermission(reqVO.getId());
        DocFileFolder update = new DocFileFolder();
        update.setId(reqVO.getId());
        update.setCollected(true);
        docFileFolderService.updateById(update);
    }

    /**
     * v0.11 B1: 删 creatorId 硬编码，改 permissionService.requireRead
     */
    private void checkFilePermission(Long fileId) {
        // requireRead 内部已做存在性 + READ 权限双重校验（PermissionService.resolve）
        permissionService.requireRead(fileId);
    }
}
