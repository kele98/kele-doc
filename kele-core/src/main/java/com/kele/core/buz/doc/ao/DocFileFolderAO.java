package com.kele.core.buz.doc.ao;

import com.kele.core.buz.doc.model.vo.DocSynthFileFolderResVO;
import com.kele.core.buz.doc.model.vo.DocFileAndFolderResVO;
import com.kele.core.buz.doc.model.vo.DocFileFolderResVO;
import com.kele.core.buz.doc.model.vo.FileFolderCopyVO;
import com.kele.core.buz.doc.model.vo.FileFolderCreateVO;
import com.kele.core.buz.doc.model.vo.FileFolderDelVO;
import com.kele.core.buz.doc.model.vo.FileFolderMoveVO;
import com.kele.core.buz.doc.model.vo.FileFolderQueryVO;
import com.kele.core.buz.doc.model.vo.FileFolderUpdateVO;
import java.util.List;

/**
 * @author wuzhenhong
 * @date 2024/5/14 19:35
 */
public interface DocFileFolderAO {

    List<DocFileFolderResVO> getFolderTree(Long folderId);

    List<DocFileFolderResVO> getFolderTree(Long folderId, Integer format);

    DocFileAndFolderResVO getFolderAndFileList(FileFolderQueryVO queryVO);

    DocFileAndFolderResVO searchFolderAndFile(FileFolderQueryVO queryVO);

    DocFileFolderResVO createFolder(FileFolderCreateVO createVO);

    void updateFolder(FileFolderUpdateVO updateVO);

    void deleteFolder(FileFolderDelVO delVO);

    void moveFolder(FileFolderMoveVO moveVO);

    List<DocFileFolderResVO> getFolderPath(Long folderId);

    void copyFolder(FileFolderCopyVO copyVO);

    /**
     * @Deprecated
     * replaced by
     * {@link #getFolderTree(Long, Integer)}
     * 使用懒加载的方式
     * @return
     */
    @Deprecated
    List<DocSynthFileFolderResVO> getAllFolderTree();

    /**
     * v0.7 BUG C fix：列出当前用户"可访问的 folder"全集（owner OR 通过 ACL 授权），
     * 不限 parent_id。前端用 isOwner 标志在 "分享给我的" section 区分 owned / shared。
     *
     * <p>用途：解决被分享 folder 在父级 chain 上无 ACL 时的"孤儿授权"问题——
     * 比如 admin 分享 folder 7 给 test 但 folder 6（folder 7 的 parent）没分享，
     * test 无法从 root tree 找到入口，此端点能直接列出所有可访问 folder 让 test "看到入口"。
     */
    List<DocFileFolderResVO> getAccessibleFolders();
}
