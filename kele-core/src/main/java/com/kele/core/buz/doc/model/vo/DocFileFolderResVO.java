package com.kele.core.buz.doc.model.vo;

import lombok.Data;

/**
 * @author wuzhenhong
 * @date 2024/5/14 19:23
 */
@Data
public class DocFileFolderResVO extends DocFileFolderBaseResVO {

    private Long parentId;
    private Boolean leaf;
    private Boolean isOrgPublic;

    /**
     * v0.7 BUG C fix：标识当前用户是否是 owner。true → 我的 folder；false → 别人授权给我。
     * 前端 "分享给我的" section 用 isOwner=false 过滤。
     */
    private Boolean isOwner;
}
