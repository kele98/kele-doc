package com.kele.core.buz.doc.model.vo;

import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PUT /api/doc/folders/{id}/owner 请求 body。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransferOwnerReqVO {
    @NotNull
    private Long newOwnerId;
}
