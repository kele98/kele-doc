package com.kele.core.buz.doc.controller;


import com.kele.common.model.ResponseResult;
import com.kele.core.buz.doc.model.vo.DocSynthFileFolderResVO;
import com.kele.core.buz.doc.ao.DocFileFolderAO;
import com.kele.core.buz.doc.model.vo.DocFileAndFolderResVO;
import com.kele.core.buz.doc.model.vo.DocFileFolderResVO;
import com.kele.core.buz.doc.model.vo.FileFolderCopyVO;
import com.kele.core.buz.doc.model.vo.FileFolderCreateVO;
import com.kele.core.buz.doc.model.vo.FileFolderDelVO;
import com.kele.core.buz.doc.model.vo.FileFolderMoveVO;
import com.kele.core.buz.doc.model.vo.FileFolderQueryVO;
import com.kele.core.buz.doc.model.vo.FileFolderUpdateVO;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 文档-文件夹 前端控制器
 * </p>
 *
 * @author author
 * @since 2024-05-13
 */
@RestController
@RequestMapping("/api")
public class DocFileFolderController {

    @Autowired
    private DocFileFolderAO docFileFolderAO;

    @GetMapping(value = "/getFolderTree")
    public ResponseResult<List<DocFileFolderResVO>> getFolderTree(@RequestParam Long folderId) {
        List<DocFileFolderResVO> resVOS = docFileFolderAO.getFolderTree(folderId);
        return ResponseResult.ok(resVOS);
    }

    @GetMapping(value = "/getAllFolderTree")
    public ResponseResult<List<DocSynthFileFolderResVO>> getAllFolderTree() {
        List<DocSynthFileFolderResVO> resVOS = docFileFolderAO.getAllFolderTree();
        return ResponseResult.ok(resVOS);
    }

    @GetMapping(value = "/getFolderPath")
    public ResponseResult<List<DocFileFolderResVO>> getFolderPath(@RequestParam Long folderId) {
        List<DocFileFolderResVO> resVOS = docFileFolderAO.getFolderPath(folderId);
        return ResponseResult.ok(resVOS);
    }

    @GetMapping(value = "/getFolderAndFileList")
    public ResponseResult<DocFileAndFolderResVO> getFolderAndFileList(FileFolderQueryVO queryVO) {
        DocFileAndFolderResVO resVO = docFileFolderAO.getFolderAndFileList(queryVO);
        return ResponseResult.ok(resVO);
    }

    @PostMapping(value = "/searchFolderAndFile")
    public ResponseResult<DocFileAndFolderResVO> searchFolderAndFile(@RequestBody FileFolderQueryVO queryVO) {
        DocFileAndFolderResVO resVO = docFileFolderAO.searchFolderAndFile(queryVO);
        return ResponseResult.ok(resVO);
    }

    @PostMapping(value = "/createFolder")
    public ResponseResult<DocFileFolderResVO> createFolder(@RequestBody @Validated FileFolderCreateVO createVO) {
        DocFileFolderResVO resVO = docFileFolderAO.createFolder(createVO);
        return ResponseResult.ok(resVO);
    }

    @PostMapping(value = "/updateFolder")
    public ResponseResult<Void> updateFolder(@RequestBody @Validated FileFolderUpdateVO updateVO) {
        docFileFolderAO.updateFolder(updateVO);
        return ResponseResult.ok();
    }

    @PostMapping(value = "/deleteFolder")
    public ResponseResult<Void> deleteFolder(@RequestBody @Validated FileFolderDelVO delVO) {
        docFileFolderAO.deleteFolder(delVO);
        return ResponseResult.ok();
    }

    @PostMapping(value = "/moveFolder")
    public ResponseResult<Void> moveFolder(@RequestBody @Validated FileFolderMoveVO moveVO) {
        docFileFolderAO.moveFolder(moveVO);
        return ResponseResult.ok();
    }

    @PostMapping(value = "/copyFolder")
    public ResponseResult<Void> copyFolder(@RequestBody @Validated FileFolderCopyVO copyVO) {
        docFileFolderAO.copyFolder(copyVO);
        return ResponseResult.ok();
    }

    /**
     * v0.7 BUG C fix：列出当前登录用户"可访问的 folder"全集（owner OR 通过 ACL 授权），
     * 不限 parent_id。前端 "分享给我的" section 用 isOwner=false 过滤。
     *
     * <p>举例：test 登录，admin 把 folder 7 (parent=6) 和 folder 6 (parent=0) 都分享给 test。
     * 从 root tree 进不去（folder 6 的 ACL 行没生效或被旧版代码忽略了），
     * 此端点直接返所有可访问的 folder，UI 单独一个 section 展示，
     * 用户能直接点进去。
     */
    @GetMapping(value = "/folders/accessible")
    public ResponseResult<List<DocFileFolderResVO>> getAccessibleFolders() {
        return ResponseResult.ok(docFileFolderAO.getAccessibleFolders());
    }
}
