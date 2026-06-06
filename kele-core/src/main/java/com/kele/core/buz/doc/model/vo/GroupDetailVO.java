package com.kele.core.buz.doc.model.vo;

import com.kele.core.buz.doc.dao.entity.KeleGroup;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群组详情 VO（含成员列表）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupDetailVO {
    private KeleGroup group;
    private List<GroupMemberVO> members;
}
