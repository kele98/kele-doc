package com.kele.core.buz.doc.ao.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.other.aspect.lock.ConcurrentLock;
import com.kele.core.buz.doc.ao.AbstractDocFileFolderAO;
import com.kele.core.buz.doc.ao.DocFileFolderAO;
import com.kele.core.buz.doc.dao.entity.DocFileFolderAcl;
import com.kele.core.buz.doc.dao.mapper.DocFileFolderAclMapper;
import com.kele.core.buz.doc.model.vo.DocSynthFileFolderResVO;
import com.kele.core.other.constants.RedissonLockPrefixCons;
import com.kele.core.other.context.LoginContext;
import com.kele.core.buz.doc.dao.entity.DocFileFolder;
import com.kele.core.buz.doc.dao.entity.DocRecycle;
import com.kele.core.buz.doc.model.vo.DocFileAndFolderResVO;
import com.kele.core.buz.doc.model.vo.DocFileFolderResVO;
import com.kele.core.buz.doc.model.vo.DocFileResVO;
import com.kele.core.buz.doc.model.vo.FileFolderCopyVO;
import com.kele.core.buz.doc.model.vo.FileFolderCreateVO;
import com.kele.core.buz.doc.model.vo.FileFolderDelVO;
import com.kele.core.buz.doc.model.vo.FileFolderMoveVO;
import com.kele.core.buz.doc.model.vo.FileFolderQueryVO;
import com.kele.core.buz.doc.model.vo.FileFolderUpdateVO;
import com.kele.core.other.enums.DelStatusEnum;
import com.kele.core.other.enums.FileFolderFormatEnum;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * v0.11 + v0.12 + v0.7 §5.4.a/b 重构版。详见 spec §6.2 该文件全部条目。
 */
@Slf4j
@Service
public class DocFileFolderAOImpl extends AbstractDocFileFolderAO implements DocFileFolderAO {

    @Autowired
    private DocFileFolderAclMapper docFileFolderAclMapper;

    /**
     * v0.11 B1 重构：creatorId → ownerId，加 NOT IN doc_recycle 过滤（scope=mine 语义）。
     * v0.7 §6.2 BUG B fix：在 owner 条件上 OR ACL 命中子查询，让"我被授权的 folder"
     * 也会出现在 tree 里（owner 是 A，B 被 A 通过 ACL 授权给 folder，B 仍能看到）。
     */
    @Override
    public List<DocFileFolderResVO> getFolderTree(Long folderId, Integer format) {

        Long effectiveFolderId = (Objects.isNull(folderId) || folderId <= 0L) ? 0L : folderId;
        Long userId = LoginContext.getUserId();
        String aclSubSql = buildAclSubSql(userId);
        String recycleSubSql = buildRecycleSubSql(userId);
        List<DocFileFolder> fileFolders = docFileFolderService.list(Wrappers.<DocFileFolder>query()
            .lambda()
                .and(wrapper -> wrapper
                    .and(w1 -> w1
                        .eq(DocFileFolder::getParentId, effectiveFolderId)
                        .eq(DocFileFolder::getOwnerId, userId))
                    .or(w2 -> w2
                        .eq(DocFileFolder::getParentId, effectiveFolderId)
                        .inSql(DocFileFolder::getId, aclSubSql)))
            .eq(DocFileFolder::getFormat, format)
            .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
            .notInSql(DocFileFolder::getId, recycleSubSql));

        return fileFolders.stream().map(fileFolder -> {
            DocFileFolderResVO resVO = new DocFileFolderResVO();
            resVO.setId(fileFolder.getId());
            resVO.setName(fileFolder.getName());
            resVO.setLeaf(fileFolder.getFolderCount() <= 0);
            return resVO;
        }).collect(Collectors.toList());
    }

    @Override
    public List<DocFileFolderResVO> getFolderTree(Long folderId) {
        return this.getFolderTree(folderId, FileFolderFormatEnum.FOLDER.getFormat());
    }

    /**
     * v0.11 B1 + v0.7 §7.1 重构：
     * - 删 creatorId 硬编码过滤
     * - 删 isPublic 硬编码 + 替换为 ORG ACL 子查询
     * - 加 doc_recycle NOT IN 过滤
     * - 顶层目录下"我看得到的"全部文件
     */
    @Override
    public DocFileAndFolderResVO getFolderAndFileList(FileFolderQueryVO queryVO) {

        Long folderId = queryVO.getFolderId();
        if (Objects.isNull(folderId) || folderId <= 0L) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "搜索某个文件夹下的文件夹和文件时，folderId必传！");
        }
        DocFileFolder userFolder = docFileFolderService.getById(folderId);
        if (Objects.isNull(userFolder)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(),
                "id为" + folderId + "的文件夹不存在或无权访问");
        }
        // 顶层目录判断保留（影响 filterFileList isTop 参数）
        boolean isTop = userFolder.getParentId().equals(0L);
        List<Long> topFoldersIds;
        if (isTop) {
            topFoldersIds = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery().eq(DocFileFolder::getParentId, 0))
                .stream().map(DocFileFolder::getId).collect(Collectors.toList());
        } else {
            topFoldersIds = new ArrayList<>();
            topFoldersIds.add(userFolder.getId());
        }
        Long userId = LoginContext.getUserId();
        String recycleSubSql = buildRecycleSubSql(userId);
        List<DocFileFolder> fileFolders = docFileFolderService.list(Wrappers.<DocFileFolder>query()
            .orderBy(
                StringUtils.hasText(queryVO.getSortField()) && StringUtils.hasText(queryVO.getSortType())
                , "asc".equalsIgnoreCase(queryVO.getSortType()), StrUtil.toUnderlineCase(queryVO.getSortField()))
            .lambda()
                .eq(DocFileFolder::getParentId, folderId)
            .apply(StringUtils.hasText(queryVO.getFileType()),
                String.format("(format = %s or (format = %s and file_type = '%s'))",
                    FileFolderFormatEnum.FOLDER.getFormat(), FileFolderFormatEnum.FILE.getFormat(),
                    queryVO.getFileType()))
            .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
            .notInSql(DocFileFolder::getId, recycleSubSql));

        List<DocFileFolderResVO> fileFolderBaseResVOList = fileFolders.stream()
            .filter(folder -> FileFolderFormatEnum.FOLDER.getFormat().equals(folder.getFormat()))
            .map(fileFolder -> {
                DocFileFolderResVO resVO = new DocFileFolderResVO();
                resVO.setId(fileFolder.getId());
                resVO.setName(fileFolder.getName());
                return resVO;
            }).collect(Collectors.toList());

        // 批量查每个 folder 的 ACL 主体类型，用于填充 isOrgPublic / isShared
        // Bug #7: isShared 只算 USER/GROUP（不算 ORG），与 getAccessibleFolders 语义对齐
        if (!fileFolderBaseResVOList.isEmpty()) {
            List<Long> folderIds = fileFolderBaseResVOList.stream()
                .map(DocFileFolderResVO::getId).collect(Collectors.toList());
            List<DocFileFolderAcl> acls = docFileFolderAclMapper.selectList(
                Wrappers.<DocFileFolderAcl>lambdaQuery()
                    .in(DocFileFolderAcl::getFolderId, folderIds)
                    .isNull(DocFileFolderAcl::getRevokedAt));
            Map<Long, Set<String>> folderAclTypesMap = new HashMap<>();
            for (DocFileFolderAcl acl : acls) {
                folderAclTypesMap.computeIfAbsent(acl.getFolderId(), k -> new HashSet<>())
                    .add(acl.getPrincipalType());
            }
            fileFolderBaseResVOList.forEach(vo -> {
                Set<String> types = folderAclTypesMap.getOrDefault(vo.getId(), Collections.emptySet());
                vo.setIsOrgPublic(types.contains("ORG"));
                vo.setIsShared(types.stream().anyMatch(t -> "USER".equals(t) || "GROUP".equals(t)));
            });
        }

        List<DocFileResVO> fileList = this.filterFileList(fileFolders, isTop);

        DocFileAndFolderResVO docFileAndFolderResVO = new DocFileAndFolderResVO();
        docFileAndFolderResVO.setFolderList(fileFolderBaseResVOList);
        docFileAndFolderResVO.setFileList(fileList);

        return docFileAndFolderResVO;
    }

    /** v0.11 B1: 删 creatorId，改 scope=shared 或 all（按 name 模糊匹配 + 跨 owner 共享） */
    @Override
    public DocFileAndFolderResVO searchFolderAndFile(FileFolderQueryVO queryVO) {

        String name = queryVO.getName();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "搜索关键词必填！");
        }
        Long userId = LoginContext.getUserId();
        String aclSubSql = buildAclSubSql(userId);
        List<DocFileFolder> fileFolders = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
            .like(DocFileFolder::getName, name)
            .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
            .and(w -> w.eq(DocFileFolder::getOwnerId, userId)
                      .or().inSql(DocFileFolder::getId, aclSubSql))
            .and(StringUtils.hasText(queryVO.getFileType()),
                wrapper -> wrapper
                    .eq(DocFileFolder::getFormat, FileFolderFormatEnum.FOLDER.getFormat())
                    .or()
                    .eq(DocFileFolder::getFormat, FileFolderFormatEnum.FILE.getFormat())
                    .eq(DocFileFolder::getFileType, queryVO.getFileType())));

        List<DocFileFolderResVO> fileFolderBaseResVOList = fileFolders.stream()
            .filter(folder -> FileFolderFormatEnum.FOLDER.getFormat().equals(folder.getFormat()))
            .map(fileFolder -> {
                DocFileFolderResVO resVO = new DocFileFolderResVO();
                resVO.setId(fileFolder.getId());
                resVO.setName(fileFolder.getName());
                return resVO;
            }).collect(Collectors.toList());

        List<DocFileResVO> fileList = this.filterFileList(fileFolders, false);

        DocFileAndFolderResVO docFileAndFolderResVO = new DocFileAndFolderResVO();
        docFileAndFolderResVO.setFolderList(fileFolderBaseResVOList);
        docFileAndFolderResVO.setFileList(fileList);

        return docFileAndFolderResVO;
    }

    /**
     * v0.11 B1: 加 setOwnerId；v0.7 §5.4.a/b 改父.isPublic 校验为 isRoot() + PermissionService
     */
    @Override
    public DocFileFolderResVO createFolder(FileFolderCreateVO createVO) {

        Long parentFolderId = createVO.getParentFolderId();
        if (Objects.isNull(parentFolderId) || parentFolderId <= 0L) {
            // 根文件夹
            parentFolderId = 0L;
        } else {
            // 检查父文件夹是否合法合理
            DocFileFolder parent = super.getById(parentFolderId);
            // 父必须是文件夹（不是文件）
            if (FileFolderFormatEnum.FILE.getFormat().equals(parent.getFormat())) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "只允许在文件夹下创建文件！");
            }
            // 校验：创建子文件夹需要父 WRITE（WRITE 用户可在共享文件夹内增删内容）
            permissionService.requireWrite(parentFolderId);
        }
        DocFileFolder docFileFolder = new DocFileFolder();
        docFileFolder.setParentId(parentFolderId);
        docFileFolder.setName(createVO.getName());
        docFileFolder.setFileCount(0);
        docFileFolder.setFolderCount(0);
        docFileFolder.setFormat(FileFolderFormatEnum.FOLDER.getFormat());
        docFileFolder.setFileType("");
        docFileFolder.setVersion(0);
        Long currentUserId = LoginContext.getUserId();
        docFileFolder.setCreatorId(currentUserId);
        docFileFolder.setOwnerId(currentUserId);  // v0.11 B1: 创建者 = Owner
        docFileFolder.setCreateAt(LocalDateTime.now());
        docFileFolder.setUpdateAt(LocalDateTime.now());
        docFileFolder.setStatus(DelStatusEnum.NORMAL.getStatus());

        Long finalParentFolderId = parentFolderId;
        transactionTemplate.execute(status -> {
            docFileFolderService.save(docFileFolder);
            if (finalParentFolderId > 0L) {
                docFileFolderService.updateFolderCount(finalParentFolderId, 1);
            }
            return null;
        });

        DocFileFolderResVO fileFolderResVO = new DocFileFolderResVO();
        fileFolderResVO.setId(docFileFolder.getId());
        fileFolderResVO.setName(docFileFolder.getName());
        fileFolderResVO.setCreateAt(docFileFolder.getCreateAt());
        fileFolderResVO.setUpdateAt(docFileFolder.getUpdateAt());

        return fileFolderResVO;
    }

    @Override
    public void updateFolder(FileFolderUpdateVO updateVO) {
        // 重命名属于修改操作，至少需要 WRITE 权限
        permissionService.requireWrite(updateVO.getId());
        DocFileFolder docFileFolder = super.getById(updateVO.getId());
        if (Objects.isNull(docFileFolder)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "文件夹不存在！");
        }
        String newName = docFileFolder.getName();
        if (Objects.equals(newName.trim(), updateVO.getName())) {
            return;
        }
        DocFileFolder update = new DocFileFolder();
        update.setId(docFileFolder.getId());
        update.setName(updateVO.getName());
        docFileFolderService.updateById(update);
    }

    /**
     * v0.10 + v0.11 B5 重构：
     * - 删 .set(DocFileFolder::getStatus, DelStatusEnum.DEL.getStatus()) 整段（v0.7 §7.1.a status 恒为 1）
     * - 删 setId(docFileFolder.getId()) 那行（v0.8 schema 后 id 是自增 PK，不再是 folderId）
     * - docRecycle.setId → docRecycle.setFolderId
     */
    @Override
    @ConcurrentLock(key = RedissonLockPrefixCons.DEL_FOLDER + "${delVO.id}")
    public void deleteFolder(FileFolderDelVO delVO) {
        permissionService.requireManage(delVO.getId());
        DocFileFolder docFileFolder = super.getById(delVO.getId());

        List<DocFileFolder> childList = new ArrayList<>();
        childList.add(docFileFolder);
        super.getChild(Collections.singletonList(delVO.getId()), childList);
        List<Long> idList = childList.stream().map(DocFileFolder::getId).distinct().collect(Collectors.toList());
        Long userId = LoginContext.getUserId();
        LocalDateTime now = LocalDateTime.now();
        List<DocRecycle> docRecycleList = childList.stream().map(folder -> {
            DocRecycle docRecycle = new DocRecycle();
            // v0.11 B5: 删 setId(folderId)，加 setFolderId(folderId)
            docRecycle.setFolderId(folder.getId());
            docRecycle.setIdList(Collections.singletonList(folder.getId()));
            docRecycle.setName(folder.getName());  // 每个 child 用自己的 name，避免回收站列表空白
            docRecycle.setUserId(userId);
            docRecycle.setDeleterId(userId);
            docRecycle.setOwnerAtDeleteId(folder.getOwnerId());
            docRecycle.setCreateAt(now);
            return docRecycle;
        }).collect(Collectors.toList());
        // 单事务：插入 recycle 记录（status 保持 1 不变）
        transactionTemplate.execute(status -> {
            docRecycleService.saveBatch(docRecycleList);
            super.saveRecycleLevel(docRecycleList);
            Long parentId = docFileFolder.getParentId();
            if (Objects.nonNull(parentId) && parentId > 0L) {
                docFileFolderService.updateFolderCount(parentId, -1);
            }
            return null;
        });
    }

    /**
     * v0.13 #13: 源 WRITE + 目标父 WRITE + 循环引用防护。
     * 移动 = 从源摘走（改源）+ 放入目标（写目标），两侧都需要 WRITE。
     * WRITE ⊂ MANAGE ⊂ OWNER，owner 隐含 WRITE，回归不破。
     */
    @Override
    @ConcurrentLock(key = RedissonLockPrefixCons.MOVE_FOLDER + "${moveVO.id}")
    public void moveFolder(FileFolderMoveVO moveVO) {
        Long id = moveVO.getId();
        Long newFolderId = moveVO.getNewFolderId();
        if (id.equals(newFolderId)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "不能移动自身到自身下");
        }
        // 移动改变文件归属结构，需要源和目标都是 MANAGE 权限
        // WRITE 只允许编辑内容，不允许移动
        permissionService.requireManage(id);
        permissionService.requireManage(newFolderId);

        Map<Long, DocFileFolder> map = super.getByIdList(Arrays.asList(id, newFolderId));
        DocFileFolder parent = map.get(newFolderId);
        if (FileFolderFormatEnum.FILE.getFormat().equals(parent.getFormat())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "只允许迁移到文件夹下");
        }
        // 检测循环引用：目标文件夹是否是源文件夹的子文件夹
        if (this.isDescendantOf(newFolderId, id)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "不能将文件夹移动到其子文件夹下，会导致循环引用");
        }
        DocFileFolder currentFolder = map.get(id);
        if (currentFolder.getParentId().equals(newFolderId)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "已在指定文件夹下，不要重复移入！");
        }
        DocFileFolder update = new DocFileFolder();
        update.setId(id);
        update.setParentId(newFolderId);
        // ACL 行随节点迁移，保留原样
        transactionTemplate.execute(status -> {
            docFileFolderService.updateById(update);
            docFileFolderService.updateFolderCount(newFolderId, 1);
            Long parentId = currentFolder.getParentId();
            if (Objects.nonNull(parentId) && parentId > 0L) {
                docFileFolderService.updateFolderCount(parentId, -1);
            }
            return null;
        });
    }

    /**
     * 检测目标文件夹是否是源文件夹的子文件夹（形成循环引用）
     */
    private boolean isDescendantOf(Long targetFolderId, Long ancestorId) {
        Long currentParentId = targetFolderId;
        Set<Long> visitedIds = Sets.newHashSet();
        while (Objects.nonNull(currentParentId) && currentParentId > 0L) {
            if (visitedIds.contains(currentParentId)) {
                log.warn("业务逻辑缺陷！！！检测到循环引用，当前文件夹ID:{}，祖先文件夹ID:{}", currentParentId, ancestorId);
                return false;
            }
            visitedIds.add(currentParentId);
            if (currentParentId.equals(ancestorId)) {
                return true;
            }
            DocFileFolder folder = docFileFolderService.getById(currentParentId);
            if (Objects.isNull(folder)) {
                return false;
            }
            currentParentId = folder.getParentId();
        }
        return false;
    }

    @Override
    public List<DocFileFolderResVO> getFolderPath(Long folderId) {
        DocFileFolder docFileFolder = this.getById(folderId);
        List<DocFileFolder> paths = Lists.newArrayList();
        paths.add(docFileFolder);
        this.getParentFolders(docFileFolder.getParentId(), paths);
        List<DocFileFolderResVO> resVOS = new ArrayList<>(paths.size());
        for (int i = paths.size() - 1; i >= 0; i--) {
            DocFileFolder folder = paths.get(i);
            DocFileFolderResVO resVO = new DocFileFolderResVO();
            resVO.setId(folder.getId());
            resVO.setName(folder.getName());
            resVOS.add(resVO);
        }
        return resVOS;
    }

    /**
     * v0.13 #12: 完整递归复制子树。
     * - 源 READ + 目标父 WRITE
     * - 源在当前用户回收站 → 拒绝
     * - 副本 owner = 复制者；ACL 不复制（新副本是复制者的私有物）
     * - BFS 遍历子树，先建父再建子，文件走存储层物理复制
     * - 子树节点数 ≤ 500、深度 ≤ 16（防恶意构造）
     */
    @Override
    public void copyFolder(FileFolderCopyVO copyVO) {
        // 源 READ + 目标父 WRITE
        permissionService.requireRead(copyVO.getId());
        permissionService.requireWrite(copyVO.getFolderId());

        DocFileFolder sourceRoot = docFileFolderService.getById(copyVO.getId());
        if (Objects.isNull(sourceRoot)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "源文件夹不存在或无权访问");
        }
        // 源在当前用户回收站 → 拒绝
        Long userId = LoginContext.getUserId();
        boolean sourceInRecycle = docRecycleService.lambdaQuery()
            .eq(DocRecycle::getFolderId, copyVO.getId())
            .eq(DocRecycle::getUserId, userId)
            .count() > 0;
        if (sourceInRecycle) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "源文件夹在回收站中，无法复制");
        }

        DocFileFolder targetParent = docFileFolderService.getById(copyVO.getFolderId());
        if (Objects.isNull(targetParent)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "目标父文件夹不存在或无权访问");
        }

        // 循环引用检测：目标不能是源本身或源的子孙
        if (copyVO.getId().equals(copyVO.getFolderId())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "目标父文件夹不能是源文件夹本身");
        }
        Long cursor = targetParent.getParentId();
        int guard = 0;
        while (cursor != null && cursor != 0L && guard++ < 64) {
            if (cursor.equals(copyVO.getId())) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                    "目标父文件夹是源文件夹的子孙节点，会造成循环引用");
            }
            DocFileFolder p = docFileFolderService.getById(cursor);
            if (p == null) break;
            cursor = p.getParentId();
        }

        // 收集整棵子树（BFS，只含 status=NORMAL 的节点）
        List<DocFileFolder> subtree = new ArrayList<>();
        subtree.add(sourceRoot);
        super.getChild(Collections.singletonList(sourceRoot.getId()), subtree);

        // 子树大小限制
        if (subtree.size() > 500) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                "文件夹内容过多（超过 500 项），无法复制");
        }

        // 深度限制
        Map<Long, Integer> idDepthMap = new HashMap<>();
        idDepthMap.put(sourceRoot.getId(), 0);
        for (DocFileFolder node : subtree) {
            if (node.getId().equals(sourceRoot.getId())) continue;
            Integer parentDepth = idDepthMap.get(node.getParentId());
            if (parentDepth != null) {
                int depth = parentDepth + 1;
                if (depth > 16) {
                    throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                        "文件夹层级过深（超过 16 层），无法复制");
                }
                idDepthMap.put(node.getId(), depth);
            }
        }

        // 名字冲突检测：目标父下同名的最大副本号
        String baseName = sourceRoot.getName();
        int maxSuffix = getMaxCopySuffix(copyVO.getFolderId(), baseName);
        String newName = maxSuffix == 0
            ? baseName + " (副本)"
            : baseName + " (副本" + (maxSuffix + 1) + ")";

        // oldId → newId 映射（用于重建 parent 链）
        Map<Long, Long> oldToNewId = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        // 分离：文件夹 vs 文件
        List<DocFileFolder> newFolders = new ArrayList<>();
        List<DocFileFolder> newFiles = new ArrayList<>();

        for (DocFileFolder src : subtree) {
            DocFileFolder copy = new DocFileFolder();
            copy.setOldId(src.getId());
            copy.setOldVersion(src.getVersion());
            copy.setId(null);  // 自增
            copy.setName(src.getId().equals(sourceRoot.getId()) ? newName : src.getName());
            copy.setFormat(src.getFormat());
            copy.setFileType(src.getFileType());
            copy.setImg(src.getImg());
            copy.setVersion(0);
            copy.setCreatorId(userId);
            copy.setOwnerId(userId);
            copy.setCreateAt(now);
            copy.setUpdateAt(now);
            // 根节点挂到目标父下，子节点在新树内重挂
            copy.setParentId(src.getId().equals(sourceRoot.getId())
                ? copyVO.getFolderId()
                : src.getParentId());
            // 文件：先 DISPLAY，存储复制成功后改 NORMAL
            copy.setStatus(FileFolderFormatEnum.FILE.getFormat().equals(src.getFormat())
                ? DelStatusEnum.DISPLAY.getStatus()
                : DelStatusEnum.NORMAL.getStatus());
            // fileCount / folderCount 由后续 rebuildChildrenCounts 回填
            copy.setFileCount(0);
            copy.setFolderCount(0);

            if (FileFolderFormatEnum.FILE.getFormat().equals(src.getFormat())) {
                newFiles.add(copy);
            } else {
                newFolders.add(copy);
            }
        }

        // 单事务：保存所有新节点（文件夹先于文件，保证 parent 存在）
        transactionTemplate.execute(status -> {
            // 1. 保存文件夹（按 BFS 顺序，subtree 已是 BFS）
            for (DocFileFolder folder : newFolders) {
                docFileFolderService.save(folder);
                oldToNewId.put(folder.getOldId(), folder.getId());
            }
            // 重建文件夹的 parentId 映射 + fileCount/folderCount
            for (DocFileFolder folder : newFolders) {
                Long newParentId = folder.getParentId().equals(copyVO.getFolderId())
                    ? copyVO.getFolderId()
                    : oldToNewId.get(folder.getParentId());
                if (newParentId != null && !newParentId.equals(folder.getParentId())) {
                    docFileFolderService.lambdaUpdate()
                        .set(DocFileFolder::getParentId, newParentId)
                        .eq(DocFileFolder::getId, folder.getId())
                        .update();
                }
            }
            // 2. 保存文件
            for (DocFileFolder file : newFiles) {
                Long newParentId = oldToNewId.get(file.getParentId());
                if (newParentId != null) {
                    file.setParentId(newParentId);
                }
                docFileFolderService.save(file);
                oldToNewId.put(file.getOldId(), file.getId());
            }
            // 3. 回填文件夹的 fileCount / folderCount
            rebuildChildrenCounts(newFolders, newFiles);
            // 4. 更新目标父的 folderCount
            docFileFolderService.updateFolderCount(copyVO.getFolderId(), 1);
            return null;
        });

        // 5. 文件存储复制（事务外，失败只影响存储不影响 DB 结构）
        if (!newFiles.isEmpty()) {
            boolean success = docFileContentStorageService.copy(newFiles, () -> {
                return transactionTemplate.execute(status -> {
                    List<Long> newFileIds = newFiles.stream()
                        .map(DocFileFolder::getId).collect(Collectors.toList());
                    docFileFolderService.lambdaUpdate()
                        .set(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
                        .in(DocFileFolder::getId, newFileIds)
                        .update();
                    docFileFolderService.updateFileCount(copyVO.getFolderId(), newFiles.size());
                    return true;
                });
            });
            if (!success) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "文档内容复制失败！");
            }
        }
    }

    /**
     * 查目标父下同名（含"副本"后缀）的最大编号。
     * 例如目标下已有"报告 (副本)"和"报告 (副本3)"，返回 3。
     */
    private int getMaxCopySuffix(Long parentId, String baseName) {
        List<DocFileFolder> siblings = docFileFolderService.list(Wrappers.<DocFileFolder>lambdaQuery()
            .eq(DocFileFolder::getParentId, parentId)
            .likeRight(DocFileFolder::getName, baseName));
        int max = 0;
        for (DocFileFolder s : siblings) {
            String n = s.getName();
            if (n.equals(baseName) || n.equals(baseName + " (副本)")) {
                max = Math.max(max, 1);
            } else if (n.startsWith(baseName + " (副本") && n.endsWith(")")) {
                try {
                    String num = n.substring((baseName + " (副本").length(), n.length() - 1);
                    max = Math.max(max, Integer.parseInt(num.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return max;
    }

    /**
     * 回填每个新文件夹的 fileCount / folderCount。
     * 调用时所有新节点的 parentId 已修正完毕，直接按 parentId 分组统计。
     */
    private void rebuildChildrenCounts(List<DocFileFolder> newFolders,
                                       List<DocFileFolder> newFiles) {
        Set<Long> folderIds = newFolders.stream()
            .map(DocFileFolder::getId).collect(Collectors.toSet());
        // folderCount: 每个 folder 下有多少子 folder
        Map<Long, Long> subFolderCounts = newFolders.stream()
            .filter(f -> folderIds.contains(f.getParentId()))
            .collect(Collectors.groupingBy(DocFileFolder::getParentId, Collectors.counting()));
        // fileCount: 每个 folder 下有多少文件
        Map<Long, Long> subFileCounts = newFiles.stream()
            .filter(f -> folderIds.contains(f.getParentId()))
            .collect(Collectors.groupingBy(DocFileFolder::getParentId, Collectors.counting()));
        // 批量更新
        for (DocFileFolder folder : newFolders) {
            Long fid = folder.getId();
            long fc = subFolderCounts.getOrDefault(fid, 0L);
            long fic = subFileCounts.getOrDefault(fid, 0L);
            if (fc > 0 || fic > 0) {
                docFileFolderService.lambdaUpdate()
                    .set(DocFileFolder::getFolderCount, (int) fc)
                    .set(DocFileFolder::getFileCount, (int) fic)
                    .eq(DocFileFolder::getId, fid)
                    .update();
            }
        }
    }

    /**
     * @Deprecated
     * v0.11 B1: creatorId → ownerId，加 doc_recycle 过滤
     * v0.7 §6.2 BUG B fix：在 owner 条件上 OR ACL 命中子查询，跟 getFolderTree 同款 pattern。
     */
    @Deprecated
    @Override
    public List<DocSynthFileFolderResVO> getAllFolderTree() {
        // 获取当前登录人 owner 的 + 通过 ACL 授权的文件夹
        Long userId = LoginContext.getUserId();
        String aclSubSql = buildAclSubSql(userId);
        String recycleSubSql = buildRecycleSubSql(userId);
        List<DocFileFolder> docFileFolderList = docFileFolderService.list(Wrappers.<DocFileFolder>query()
            .lambda()
                .and(wrapper -> wrapper
                    .and(w1 -> w1.eq(DocFileFolder::getOwnerId, userId))
                    .or(w2 -> w2.inSql(DocFileFolder::getId, aclSubSql)))
            .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
            .notInSql(DocFileFolder::getId, recycleSubSql));
        if(CollectionUtils.isEmpty(docFileFolderList)) {
            return Collections.emptyList();
        }
        Map<Long, List<DocFileFolder>> parentIdMapFolderMap = docFileFolderList.stream().collect(Collectors.groupingBy(DocFileFolder::getParentId));
        Set<Long> allFolderIds = docFileFolderList.stream().map(DocFileFolder::getId).collect(Collectors.toSet());
        // 分为两类顶层节点：自己的根文件夹（parent_id=0 且 owner_id=userId）和共享文件夹
        List<DocFileFolder> ownedRoots = docFileFolderList.stream()
            .filter(f -> f.getParentId() == 0L && userId.equals(f.getOwnerId()))
            .collect(Collectors.toList());
        List<DocFileFolder> sharedRoots = docFileFolderList.stream()
            .filter(f -> !userId.equals(f.getOwnerId()))
            .filter(f -> f.getParentId() == 0L || !allFolderIds.contains(f.getParentId()))
            .collect(Collectors.toList());

        List<DocSynthFileFolderResVO> result = new ArrayList<>();
        // 自己的根文件夹直接作为顶层
        for (DocFileFolder folder : ownedRoots) {
            result.add(this.buildDocSynthFileFolderResVO(folder, parentIdMapFolderMap));
        }
        // 共享文件夹挂到"被分享的文件夹"虚拟节点下
        if (!sharedRoots.isEmpty()) {
            DocSynthFileFolderResVO sharedNode = new DocSynthFileFolderResVO();
            sharedNode.setId(-1L);
            sharedNode.setName("被分享的文件夹");
            sharedNode.setFolder(true);
            sharedNode.setChildren(sharedRoots.stream()
                .map(folder -> this.buildDocSynthFileFolderResVO(folder, parentIdMapFolderMap))
                .collect(Collectors.toList()));
            result.add(sharedNode);
        }
        return result;
    }

    /**
     * v0.7 BUG C fix：列出当前用户"可访问的 folder"全集（owner OR 通过 ACL 授权），
     * 不限 parent_id。前端用 isOwner 标志在 "分享给我的" section 区分 owned / shared。
     *
     * <p>用途：解决被分享 folder 在父级 chain 上无 ACL 时的"孤儿授权"问题——
     * 比如 admin 分享 folder 7 给 test 但 folder 6（folder 7 的 parent）没分享，
     * test 无法从 root tree 找到入口，此端点能直接列出所有可访问 folder 让 test "看到入口"。
     *
     * <p>v0.13 Bug #7 修复：批量查 ACL 填充 isOrgPublic / isShared 字段。
     * 之前这两个字段恒 null（死字段），前端无法区分 ORG-public 和明确分享。
     */
    @Override
    public List<DocFileFolderResVO> getAccessibleFolders() {
        Long userId = LoginContext.getUserId();
        String aclSubSql = buildAclSubSql(userId);
        String recycleSubSql = buildRecycleSubSql(userId);
        List<DocFileFolder> fileFolders = docFileFolderService.list(Wrappers.<DocFileFolder>query()
            .lambda()
                .eq(DocFileFolder::getStatus, DelStatusEnum.NORMAL.getStatus())
                .and(wrapper -> wrapper
                    .and(w1 -> w1.eq(DocFileFolder::getOwnerId, userId))
                    .or(w2 -> w2.inSql(DocFileFolder::getId, aclSubSql)))
            .notInSql(DocFileFolder::getId, recycleSubSql));
        // Bug #7：批量查每个 folder 的 ACL 主体类型，用于填充 isOrgPublic / isShared
        Set<Long> folderIdSet = fileFolders.stream().map(DocFileFolder::getId).collect(Collectors.toSet());
        Map<Long, Set<String>> folderAclTypesMap = new HashMap<>();
        if (!folderIdSet.isEmpty()) {
            List<DocFileFolderAcl> acls = docFileFolderAclMapper.selectList(
                Wrappers.<DocFileFolderAcl>lambdaQuery()
                    .in(DocFileFolderAcl::getFolderId, folderIdSet)
                    .isNull(DocFileFolderAcl::getRevokedAt));
            for (DocFileFolderAcl acl : acls) {
                folderAclTypesMap.computeIfAbsent(acl.getFolderId(), k -> new HashSet<>())
                    .add(acl.getPrincipalType());
            }
        }
        return fileFolders.stream()
            // 只返 folder 不返 file（"分享给我的" 不该包含 file）
            .filter(f -> FileFolderFormatEnum.FOLDER.getFormat().equals(f.getFormat()))
            .map(f -> {
                DocFileFolderResVO resVO = new DocFileFolderResVO();
                resVO.setId(f.getId());
                resVO.setName(f.getName());
                resVO.setParentId(f.getParentId());
                resVO.setIsOwner(userId.equals(f.getOwnerId()));
                resVO.setLeaf(f.getFolderCount() <= 0);
                // Bug #7：填充共享来源字段
                Set<String> types = folderAclTypesMap.getOrDefault(f.getId(), Collections.emptySet());
                resVO.setIsOrgPublic(types.contains("ORG"));
                resVO.setIsShared(types.stream().anyMatch(t -> "USER".equals(t) || "GROUP".equals(t)));
                return resVO;
            })
            .collect(Collectors.toList());
    }

    private DocSynthFileFolderResVO buildDocSynthFileFolderResVO(DocFileFolder folder,
        Map<Long, List<DocFileFolder>> parentIdMapFolderMap) {

        DocSynthFileFolderResVO resVO = new DocSynthFileFolderResVO();
        resVO.setId(folder.getId());
        resVO.setName(folder.getName());
        resVO.setType(folder.getFileType());
        resVO.setImg(folder.getImg());
        resVO.setFolder(FileFolderFormatEnum.FOLDER.getFormat().equals(folder.getFormat()));

        List<DocFileFolder> children = parentIdMapFolderMap.getOrDefault(folder.getId(), Collections.emptyList());
        resVO.setChildren(children.stream().map(e -> this.buildDocSynthFileFolderResVO(e, parentIdMapFolderMap))
            .collect(Collectors.toList()));
        return resVO;
    }

    private void getParentFolders(Long parentId, List<DocFileFolder> parentFileFolders) {
        if (Objects.isNull(parentId) || parentId <= 0L) {
            return;
        }
        DocFileFolder docFileFolder = docFileFolderService.getById(parentId);
        parentFileFolders.add(docFileFolder);
        this.getParentFolders(docFileFolder.getParentId(), parentFileFolders);
    }

    /**
     * v0.11 B1 MINOR: 删 creatorId 硬编码过滤（顶层目录下"我可见"的所有文件）
     */
    private List<DocFileResVO> filterFileList(List<DocFileFolder> fileFolders, Boolean isTop) {
        Long userId = LoginContext.getUserId();
        Set<Long> collectedIds = getCollectedFolderIds(userId, fileFolders);
        return fileFolders.stream()
                .filter(folder -> FileFolderFormatEnum.FILE.getFormat().equals(folder.getFormat()))
                // v0.11: 删 .filter(!isTop || folder.getCreatorId()...) 硬编码
                .map(fileFolder -> {
                    DocFileResVO resVO = new DocFileResVO();
                    resVO.setId(fileFolder.getId());
                    resVO.setName(fileFolder.getName());
                    resVO.setType(fileFolder.getFileType());
                    resVO.setImg(fileFolder.getImg());
                    resVO.setCollected(collectedIds.contains(fileFolder.getId()));
                    resVO.setCreateAt(fileFolder.getCreateAt());
                    resVO.setUpdateAt(fileFolder.getUpdateAt());
                    return resVO;
                })
                .collect(Collectors.toList());
    }

    private boolean copyFolderOtherDeal(FileFolderCopyVO copyVO, DocFileFolder targetFolder, List<DocFileFolder> childs) {
        return transactionTemplate.<Boolean>execute(status -> {
            Map<Long, Long> oldMapNew = childs.stream()
                .collect(Collectors.toMap(DocFileFolder::getOldId, DocFileFolder::getId, (v1, v2) -> v1));
            List<DocFileFolder> updateList = childs.stream().map(e -> {
                DocFileFolder update = new DocFileFolder();
                update.setId(e.getId());
                if (copyVO.getId().equals(e.getOldId())) {
                    update.setParentId(copyVO.getFolderId());
                } else {
                    update.setParentId(oldMapNew.getOrDefault(e.getParentId(), 0L));
                }
                update.setStatus(DelStatusEnum.NORMAL.getStatus());
                return update;
            }).collect(Collectors.toList());
            boolean updateResult = docFileFolderService.updateBatchById(updateList);
            if(!updateResult) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "复制文件夹失败！原因=》更新文件元数据失败！");
            }
            int updateFileCount = docFileFolderService.updateFolderCount(targetFolder.getId(), 1);
            if(updateFileCount <= 0) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "复制文件夹失败！原因=》更新父文件夹文件数量失败！");
            }
            return updateResult && updateFileCount > 0;
        });
    }

    /** v0.11 B1 辅助方法：当前用户的群组 id 列表（供 exists 子查询用） */
    private List<Long> getMyGroupIds() {
        return LoginContext.getUserGroupIds();
    }

    /**
     * 构建 ACL 子查询 SQL：返回当前用户可访问的 folder_id 集合（含 ACL 直接命中 + 其所有后代）。
     * 用在 inSql() 里，避免 MyBatis-Plus LambdaQueryWrapper.in(column, Wrapper) 不支持子查询的问题。
     *
     * <p>v0.13 #4 + MySQL 8 升级：改用 WITH RECURSIVE CTE，递归展开"直接 ACL 命中 folder 的所有后代"。
     * 旧版只返回直接命中集合，导致"父被共享但子未共享"的 folder 在 tree 里不可见。
     * <p>MySQL 8.0.1+ 支持 WITH RECURSIVE。CTE 名用 acl_cte（accessible 是 MySQL 保留字，不能用作标识符）。
     *
     * <p>package-private for test（{@code DocFileFolderAOImplTest} 直接断言 SQL 含锚点 + 递归两段 + revoked_at 过滤）。
     */
    String buildAclSubSql(Long userId) {
        List<Long> groupIds = this.getMyGroupIds();
        String inClause = groupIds.isEmpty() ? "(-1)"
            : groupIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return "WITH RECURSIVE acl_cte AS ("
            + "  SELECT folder_id FROM doc_file_folder_acl WHERE revoked_at IS NULL "
            + "    AND ((principal_type = 'USER' AND principal_id = " + userId + ") "
            + "         OR principal_type = 'ORG' "
            + "         OR principal_id IN (" + inClause + ")) "
            + "  UNION ALL "
            + "  SELECT f.id FROM doc_file_folder f "
            + "    JOIN acl_cte a ON f.parent_id = a.folder_id "
            + "    WHERE f.status != -1 "
            + ") SELECT folder_id FROM acl_cte";
    }

    /** 构建回收站子查询 SQL：返回当前用户已软删除的 folder_id 集合 */
    private String buildRecycleSubSql(Long userId) {
        return "SELECT folder_id FROM doc_recycle WHERE user_id = " + userId;
    }
}
