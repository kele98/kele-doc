package com.kele.core.buz.doc.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kele.core.buz.doc.dao.entity.GroupMember;
import com.kele.core.buz.doc.dao.mapper.GroupMemberMapper;
import com.kele.core.buz.doc.service.IGroupMemberService;
import org.springframework.stereotype.Service;

/**
 * v0.13 #11: 群组成员 ServiceImpl，提供 saveBatch 批量插入。
 */
@Service
public class GroupMemberServiceImpl extends ServiceImpl<GroupMemberMapper, GroupMember>
        implements IGroupMemberService {
}
