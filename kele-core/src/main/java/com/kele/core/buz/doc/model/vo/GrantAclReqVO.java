package com.kele.core.buz.doc.model.vo;

import java.util.List;
import javax.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/doc/folders/{id}/acl 请求 body。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GrantAclReqVO {
    @NotEmpty
    private List<AclEntryVO> entries;
    /** false（默认）= 追加 + 覆盖；true = 全量替换。详见 spec §4.4。 */
    private boolean replace;
}
