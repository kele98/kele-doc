package com.kele.core.buz.doc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kele.core.buz.doc.dao.entity.GroupMember;

/**
 * v0.13 #11: 群组成员 Service（extends IService 拿到 saveBatch 等批量操作）。
 * 用于 createGroup / addMembers 的批量 INSERT，避免 forEach 单条 round-trip。
 */
public interface IGroupMemberService extends IService<GroupMember> {
}
