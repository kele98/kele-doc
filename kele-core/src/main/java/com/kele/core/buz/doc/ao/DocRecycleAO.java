package com.kele.core.buz.doc.ao;

import com.kele.core.buz.doc.model.vo.DocFileAndFolderResVO;
import com.kele.core.buz.doc.model.vo.DocRecycleReqVO;

/**
 * @author wuzhenhong
 * @date 2024/5/17 16:37
 */
public interface DocRecycleAO {

    DocFileAndFolderResVO getRecycleFolderAndFileList(String name);

    void restore(DocRecycleReqVO reqVO);

    void completelyDelete(DocRecycleReqVO reqVO);

    void emptyRecycle();
}
