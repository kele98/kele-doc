package com.kele.core.buz.doc.controller;

import com.kele.common.model.ResponseResult;
import com.kele.core.buz.doc.ao.GroupAO;
import com.kele.core.buz.doc.dao.entity.KeleGroup;
import com.kele.core.buz.doc.model.vo.GroupDetailVO;
import com.kele.core.buz.doc.model.vo.GroupListVO;
import com.kele.core.buz.doc.model.vo.GroupMemberVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 群组管理 Controller（仅管理员）。详见 spec §4.1。
 * <p>
 * 业务层 GroupAOImpl 已做 isAdmin() 校验；Controller 不重复校验。
 */
@RestController
@RequestMapping("/api/admin/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupAO groupAO;

    @PostMapping
    public ResponseResult<Long> create(@RequestBody CreateGroupReq req) {
        return ResponseResult.ok(groupAO.createGroup(req.getName(), req.getDescription(), req.getMemberIds()));
    }

    @GetMapping
    public ResponseResult<GroupListVO> list(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String keyword) {
        return ResponseResult.ok(groupAO.listGroups(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ResponseResult<GroupDetailVO> detail(@PathVariable Long id) {
        return ResponseResult.ok(groupAO.getGroupDetail(id));
    }

    @PutMapping("/{id}")
    public ResponseResult<Void> update(@PathVariable Long id, @RequestBody UpdateGroupReq req) {
        groupAO.updateGroup(id, req.getName(), req.getDescription());
        return ResponseResult.ok();
    }

    @DeleteMapping("/{id}")
    public ResponseResult<Void> dissolve(@PathVariable Long id) {
        groupAO.dissolveGroup(id);
        return ResponseResult.ok();
    }

    @PostMapping("/{id}/restore")
    public ResponseResult<Void> restore(@PathVariable Long id) {
        groupAO.restoreGroup(id);
        return ResponseResult.ok();
    }

    @PostMapping("/{id}/members")
    public ResponseResult<Void> addMembers(@PathVariable Long id, @RequestBody AddMembersReq req) {
        groupAO.addMembers(id, req.getUserIds());
        return ResponseResult.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseResult<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        groupAO.removeMember(id, userId);
        return ResponseResult.ok();
    }

    @GetMapping("/{id}/members")
    public ResponseResult<List<GroupMemberVO>> members(@PathVariable Long id) {
        return ResponseResult.ok(groupAO.listMembers(id));
    }

    @GetMapping("/my")
    public ResponseResult<List<KeleGroup>> myGroups() {
        return ResponseResult.ok(groupAO.getMyGroups());
    }

    // ---- inline request DTOs (避免再建 5 个文件) ----
    @lombok.Data public static class CreateGroupReq {
        private String name;
        private String description;
        private java.util.List<Long> memberIds;
    }
    @lombok.Data public static class UpdateGroupReq {
        private String name;
        private String description;
    }
    @lombok.Data public static class AddMembersReq {
        private java.util.List<Long> userIds;
    }
}
