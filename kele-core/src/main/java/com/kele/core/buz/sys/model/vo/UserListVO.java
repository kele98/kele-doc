package com.kele.core.buz.sys.model.vo;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户列表响应 VO（带分页）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserListVO {
    private List<UserSearchVO> records;
    private int total;
    private int page;
    private int size;
}
