package com.kele.core.buz.doc.model.vo;

import com.kele.core.buz.doc.dao.entity.KeleGroup;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群组列表响应 VO（带分页）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupListVO {
    private List<KeleGroup> records;
    private Long total;
    private int page;
    private int size;
}
