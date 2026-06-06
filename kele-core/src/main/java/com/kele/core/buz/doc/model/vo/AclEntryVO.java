package com.kele.core.buz.doc.model.vo;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ACL 列表中的一项（用户/群组/全员）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AclEntryVO {
    private Long id;  // 列表里有，grant 请求里没有

    @NotBlank
    private String principalType;  // USER / GROUP / ORG

    /** ORG 类型时此字段为 null。 */
    private Long principalId;

    @NotBlank
    private String permission;  // READ / WRITE / MANAGE

    private String principalName;  // 显示用，list 时填充
    private Long grantedBy;
    private String createdAt;
}
