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
}
