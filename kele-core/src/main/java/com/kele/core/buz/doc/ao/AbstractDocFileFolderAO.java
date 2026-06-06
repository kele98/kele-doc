package com.kele.core.buz.doc.ao;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.dao.entity.DocRelationLevel;
import com.kele.core.buz.doc.model.vo.DocFileResVO;
import com.kele.core.buz.doc.permission.PermissionService;
import com.kele.core.buz.doc.service.IDocFileContentStorageService;
import com.kele.core.buz.doc.service.IDocRelationLevelService;
import com.kele.core.other.context.LoginContext;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocRecycle;
import com.kele.core.buz.doc.service.IDocFileFolderService;
import com.kele.core.buz.doc.service.IDocRecycleService;
import com.kele.core.other.enums.DelStatusEnum;
import com.kele.core.other.enums.FileFolderFormatEnum;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

/**
 * @author wuzhenhong
 * @date 2024/5/16 10:53
 */
public abstract class AbstractDocFileFolderAO {

    @Autowired
    protected IDocFileFolderService docFileFolderService;

    @Autowired
    protected IDocRecycleService docRecycleService;

    @Autowired
    protected IDocRelationLevelService docRelationLevelService;

    @Autowired
    protected IDocFileContentStorageService docFileContentStorageService;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected PermissionService permissionService;

    /**
     * 读单条文件夹。v0.11 B1：删 creatorId 硬编码，改走 PermissionService.requireRead。
     * READ 不满足 → 抛 RESOURCE_NOT_VISIBLE（屏蔽存在性，防探测攻击）。
     */
    protected DocFileFolder getById(Long id) {
        DocFileFolder docFileFolder = docFileFolderService.getById(id);
        if (Objects.isNull(docFileFolder) || !DelStatusEnum.NORMAL.getStatus().equals(docFileFolder.getStatus())) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
                String.format("id为%s的文件夹不存在或无权访问", id));
        }
        permissionService.requireRead(id);
        return docFileFolder;
    }

    /**
     * 批量读多 ID 的文件夹。v0.11 B1 + bonus 修复：
     * 1) 删 creatorId 硬编码过滤，改 PermissionService.requireRead per item
     * 2) 修 line 83 错误消息用 noExitsIdList（此时为空）改为 forbidList
     */
    protected List<DocFileFolder> selectByIdList(List<Long> idList) {
        List<DocFileFolder> docFileFolderList = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
            .in(DocFileFolder::getId, idList)
            .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus()));
        if (CollectionUtils.isEmpty(docFileFolderList)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
                String.format("id为%s的文件夹不存在或无权访问", idList));
        }
        Set<Long> idSet = docFileFolderList.stream().map(DocFileFolder::getId).collect(Collectors.toSet());
        List<Long> noExitsIdList = idList.stream().filter(id -> !idSet.contains(id)).collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(noExitsIdList)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
                String.format("id为%s的文件夹不存在或无权访问", noExitsIdList));
        }
        // PermissionService.requireRead per item（v0.11 B1）
        List<DocFileFolder> forbidList = new java.util.ArrayList<>();
        for (DocFileFolder f : docFileFolderList) {
            try {
                permissionService.requireRead(f.getId());
            } catch (BusinessException e) {
                forbidList.add(f);
            }
        }
        if (!CollectionUtils.isEmpty(forbidList)) {
            String forbidIds = forbidList.stream().map(f -> f.getId().toString()).collect(Collectors.joining(","));
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(),
                String.format("id为%s的资源禁止访问", forbidIds));
        }
        return docFileFolderList;
    }

    protected Map<Long, DocFileFolder> getByIdList(List<Long> idList) {
        return this.selectByIdList(idList).stream()
            .collect(Collectors.toMap(DocFileFolder::getId, Function.identity(), (v1, v2) -> v1));
    }

    protected void getChild(List<Long> parentIdList, List<DocFileFolder> child) {
        if (CollectionUtils.isEmpty(parentIdList)) {
            return;
        }
        List<DocFileFolder> fileFolders = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
            .in(DocFileFolder::getParentId, parentIdList)
            .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus()));
        child.addAll(fileFolders);
        List<Long> idList = fileFolders.stream().map(DocFileFolder::getId).distinct().collect(Collectors.toList());
        this.getChild(idList, child);
    }

    /**
     * 写 doc_relation_level 关联层级。
     * v0.11 m7：line 113 getId → getFolderId（v0.8 schema 升级后 id 是回收记录自增 PK，不再是 folderId）。
     */
    protected void saveRecycleLevel(List<DocRecycle> docRecycleList) {
        List<DocRelationLevel> levels = docRecycleList.stream().flatMap(docRecycle -> {
            List<Long> relationIdList = Optional.ofNullable(docRecycle.getIdList())
                .orElse(Collections.emptyList());
            return relationIdList.stream().map(relationId -> {
                DocRelationLevel level = new DocRelationLevel();
                level.setId(null);
                level.setParentId(docRecycle.getFolderId());
                level.setSonId(relationId);
                level.setUserId(docRecycle.getUserId());
                return level;
            });
        }).collect(Collectors.toList());
        if(!CollectionUtils.isEmpty(levels)) {
            docRelationLevelService.saveBatch(levels);
        }
    }

    protected List<DocFileResVO> filterFileList(List<DocFileFolder> fileFolders) {
        return fileFolders.stream()
                .filter(folder -> FileFolderFormatEnum.FILE.getFormat().equals(folder.getFormat()))
                .map(fileFolder -> {
                    DocFileResVO resVO = new DocFileResVO();
                    resVO.setId(fileFolder.getId());
                    resVO.setName(fileFolder.getName());
                    resVO.setType(fileFolder.getFileType());
                    resVO.setImg(fileFolder.getImg());
                    resVO.setCollected(fileFolder.getCollected());
                    resVO.setCreateAt(fileFolder.getCreateAt());
                    resVO.setUpdateAt(fileFolder.getUpdateAt());
                    return resVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * v0.11 m7 + v0.12：getRecycleById 重构——入参 recycleId，**先查 recycle 记录再查 folder**。
     * 调用方（如 DocRecycleAOImpl.restore:93 / completelyDelete:141）需传 recycleId 而非 folderId。
     * <p>
     * v0.7 §7.1.a 修正：操作权限校验用 {@code docRecycle.user_id}（软删人），不是原始 {@code creatorId}。
     * 因为新模型下非 Owner 的 MANAGE 用户也能软删别人的文件，creatorId 跟软删人已不是同一人。
     * <p>
     * v0.7 §7 修正：删除 {@code status==NORMAL} 检查——新模型 status 恒为 1（软删走 doc_recycle 表），
     * 该检查恒为 true 会导致任何 restore 调用都报"该文件未被删除"。
     */
    protected DocFileFolder getRecycleById(Long recycleId) {
        DocRecycle docRecycle = docRecycleService.getById(recycleId);
        if (Objects.isNull(docRecycle)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
                "回收记录不存在或已清空");
        }
        // v0.7 §7.1.a：用 recycle.user_id 校验，不是 creatorId
        Long userId = LoginContext.getUserId();
        if (!docRecycle.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "禁止访问");
        }
        DocFileFolder docFileFolder = docFileFolderService.getById(docRecycle.getFolderId());
        if (Objects.isNull(docFileFolder)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "该文件不存在或已被删除！");
        }
        // v0.7 §7：删 status==NORMAL 检查（status 恒为 1，恒为 true 会导致永远报错）
        return docFileFolder;
    }
}
