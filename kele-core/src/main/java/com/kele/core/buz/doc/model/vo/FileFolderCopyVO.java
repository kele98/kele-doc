package com.kele.core.buz.doc.model.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.NotNull;
import lombok.Data;

/**
 * @author wuzhenhong
 * @date 2024/5/14 19:19
 */
@Data
@ApiModel(value = "文件夹复制VO")
public class FileFolderCopyVO {

    @ApiModelProperty(value = "源文件夹id（要复制的）")
    @NotNull(message = "id必传（源文件夹id）")
    private Long id;

    @ApiModelProperty(value = "目标父文件夹id（复制到哪）")
    @NotNull(message = "folderId必传（目标父文件夹id）")
    private Long folderId;

}
