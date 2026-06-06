package com.kele.core.buz.doc.ao;

import com.kele.core.buz.doc.dao.entity.KeleGroup;
import com.kele.core.buz.doc.model.vo.GroupDetailVO;
import com.kele.core.buz.doc.model.vo.GroupListVO;
import com.kele.core.buz.doc.model.vo.GroupMemberVO;
import java.util.List;

/**
 * 群组管理 AO（仅管理员可调，详见 spec §4.1）。所有方法在实现层做 LoginContext.isAdmin() 校验。
 */
public interface GroupAO {

    /** 创建群组。memberIds 初始成员集合。 */
    Long createGroup(String name, String description, List<Long> memberIds);

    /** 更新群组名/描述。 */
    void updateGroup(Long id, String name, String description);

    /** 解散群组（soft，status=0，可恢复）。同时级联 revoke 该群组所有 ACL 行。 */
    void dissolveGroup(Long id);

    /** 恢复已解散群组。复活该群组所有 revoked_at != NULL 的 ACL 行。 */
    void restoreGroup(Long id);

    /** 加成员。已存在则跳过。 */
    void addMembers(Long groupId, List<Long> userIds);

    /** 移成员。 */
    void removeMember(Long groupId, Long userId);

    /** 列出群组（带分页 + 关键词）。 */
    GroupListVO listGroups(int page, int size, String keyword);

    /** 群组详情（含成员列表）。 */
    GroupDetailVO getGroupDetail(Long id);

    /** 列出群组成员。 */
    List<GroupMemberVO> listMembers(Long id);

    /** 当前用户加入的群组（任意登录用户可用）。 */
    List<KeleGroup> getMyGroups();
}
