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
        docFileFolder.setCollected(false);
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
     * v0.7 §5.4.b 重构：源 Owner-only + 目标父 Owner-only + 循环引用防护
     * 只有 owner 能移动文件夹，MANAGE 权限仅允许内部操作（创建/删除子项）。
     * 若需转移归属，应使用"转让所有权"功能。
     */
    @Override
    @ConcurrentLock(key = RedissonLockPrefixCons.MOVE_FOLDER + "${moveVO.id}")
    public void moveFolder(FileFolderMoveVO moveVO) {
        Long id = moveVO.getId();
        Long newFolderId = moveVO.getNewFolderId();
        if (id.equals(newFolderId)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "不能移动自身到自身下");
        }
        // 只有 owner 才能移动文件夹
        Long userId = LoginContext.getUserId();
        DocFileFolder sourceFolder = docFileFolderService.getById(id);
        if (Objects.isNull(sourceFolder) || !userId.equals(sourceFolder.getOwnerId())) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "只有所有者才能移动文件夹");
        }
        // 目标文件夹也必须是自己的
        DocFileFolder targetFolder = docFileFolderService.getById(newFolderId);
        if (Objects.isNull(targetFolder) || !userId.equals(targetFolder.getOwnerId())) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "只能移动到自己的文件夹下");
        }

        Map<Long, DocFileFolder> map = super.getByIdList(Arrays.asList(id, newFolderId));
        DocFileFolder parent = map.get(newFolderId);
        if (Objects.isNull(parent)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), String.format("id为%s的目标文件夹不存在", newFolderId));
        }
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
        // v0.7 §5.4.b: ACL 行随节点迁移，保留原样
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
     * v0.7 §5.4.a 重构（v1 only copies single node, NOT subtree）：
     * - 源 READ + 目标父 MANAGE
     * - 源在当前用户回收站 → 拒绝
     * - 副本 owner = 复制者；ACL 起始为空
     * - 不递归复制子树
     */
    @Override
    public void copyFolder(FileFolderCopyVO copyVO) {
        // 源 READ + 目标父 WRITE
        permissionService.requireRead(copyVO.getId());
        permissionService.requireWrite(copyVO.getFolderId());

        DocFileFolder docFileFolder = docFileFolderService.getById(copyVO.getId());
        if (Objects.isNull(docFileFolder)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "源文件夹不存在或无权访问");
        }
        // 源在当前用户回收站 → 拒绝（v0.8 新增）
        Long userId = LoginContext.getUserId();
        boolean sourceInRecycle = docRecycleService.lambdaQuery()
            .eq(DocRecycle::getFolderId, copyVO.getId())
            .eq(DocRecycle::getUserId, userId)
            .count() > 0;
        if (sourceInRecycle) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "源文件夹在回收站中，无法复制");
        }

        // v0.7 修正：目标父文件夹存在性显式校验（requireManage 隐式已查但给更清晰的错误信息）
        DocFileFolder targetParent = docFileFolderService.getById(copyVO.getFolderId());
        if (Objects.isNull(targetParent)) {
            throw new BusinessException(ErrorCodeEnum.RESOURCE_NOT_VISIBLE.getCode(), "目标父文件夹不存在或无权访问");
        }

        // v0.7 修正：自循环检测——目标不能是源本身或源的子孙节点
        // （否则复制会把源挂到自己的子树下面，造成无限递归和 parent 链死循环）
        if (copyVO.getId().equals(copyVO.getFolderId())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "目标父文件夹不能是源文件夹本身");
        }
        // 沿目标父的 parent 链向上走，看是否能找到源 id
        Long cursor = targetParent.getParentId();
        int guard = 0;
        while (cursor != null && cursor != 0L && guard++ < 64) {
            if (cursor.equals(copyVO.getId())) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                    "目标父文件夹是源文件夹的子孙节点，会造成循环引用");
            }
            DocFileFolder parent = docFileFolderService.getById(cursor);
            if (parent == null) break;  // 父链断裂（脏数据），放过
            cursor = parent.getParentId();
        }

        // v1: 仅复制当前节点本身（新 id），不递归子树
        // 用 1-元素数组持有返回值（lambda 内不能赋给外层局部变量）
        final Long[] newFolderIdHolder = new Long[1];
        transactionTemplate.execute(status -> {
            DocFileFolder copy = new DocFileFolder();
            copy.setParentId(copyVO.getFolderId());
            copy.setName(docFileFolder.getName() + " (副本)");
            copy.setFileCount(0);
            copy.setFolderCount(0);
            copy.setFormat(docFileFolder.getFormat());
            copy.setFileType("");
            copy.setCollected(false);
            copy.setVersion(0);
            copy.setCreatorId(userId);
            copy.setOwnerId(userId);  // v0.7：复制者 = 新 Owner
            copy.setCreateAt(LocalDateTime.now());
            copy.setUpdateAt(LocalDateTime.now());
            copy.setStatus(DelStatusEnum.NORMAL.getStatus());
            docFileFolderService.save(copy);
            newFolderIdHolder[0] = copy.getId();
            docFileFolderService.updateFolderCount(copyVO.getFolderId(), 1);
            return null;
        });
        Long newFolderId = newFolderIdHolder[0];
        // 注意：v1 不复制子树，不复制内容，不复制 ACL（v0.7 §5.4.a）
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
        List<DocFileFolder> topFolders = parentIdMapFolderMap.get(0L);
        if(CollectionUtils.isEmpty(topFolders)) {
            return Collections.emptyList();
        }
        return topFolders.stream().map(folder -> this.buildDocSynthFileFolderResVO(folder, parentIdMapFolderMap))
            .collect(Collectors.toList());
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
        return fileFolders.stream()
                .filter(folder -> FileFolderFormatEnum.FILE.getFormat().equals(folder.getFormat()))
                // v0.11: 删 .filter(!isTop || folder.getCreatorId()...) 硬编码
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
     * 构建 ACL 子查询 SQL：返回当前用户通过 ACL 可访问的 folder_id 集合。
     * 用在 inSql() 里，避免 MyBatis-Plus LambdaQueryWrapper.in(column, Wrapper) 不支持子查询的问题。
     *
     * <p>package-private for test（{@code DocFileFolderAOImplTest} 直接断言 SQL 三分支齐全 + revoked_at 过滤）。
     */
    String buildAclSubSql(Long userId) {
        List<Long> groupIds = this.getMyGroupIds();
        String inClause = groupIds.isEmpty() ? "(-1)"
            : groupIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return "SELECT folder_id FROM doc_file_folder_acl WHERE revoked_at IS NULL "
            + "AND ((principal_type = 'USER' AND principal_id = " + userId + ") "
            + "OR principal_type = 'ORG' "
            + "OR principal_id IN (" + inClause + "))";
    }

    /** 构建回收站子查询 SQL：返回当前用户已软删除的 folder_id 集合 */
    private String buildRecycleSubSql(Long userId) {
        return "SELECT folder_id FROM doc_recycle WHERE user_id = " + userId;
    }
}
