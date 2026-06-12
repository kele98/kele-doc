package com.kele.core.buz.sys.controller;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.common.model.ResponseResult;
import com.kele.core.buz.sys.ao.SysUserInfoAO;
import com.kele.core.buz.sys.dao.entity.SysUserInfo;
import com.kele.core.buz.sys.dao.mapper.SysUserInfoMapper;
import com.kele.core.buz.sys.model.vo.UserInfoUpdateVO;
import com.kele.core.buz.sys.model.vo.UserInfoVO;
import com.kele.core.buz.sys.model.vo.UserListVO;
import com.kele.core.buz.sys.model.vo.UserLoginVO;
import com.kele.core.buz.sys.model.vo.UserPwdModifyVO;
import com.kele.core.buz.sys.model.vo.UserRegisterVO;
import com.kele.core.buz.sys.model.vo.UserSearchVO;
import com.kele.core.buz.sys.service.ISysUserInfoService;
import com.kele.core.other.context.LoginContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 系统-用户信息 前端控制器
 * </p>
 *
 * @author author
 * @since 2024-05-13
 */
@RestController
@RequestMapping("/api")
public class SysUserInfoController {

    @Autowired
    private SysUserInfoAO sysUserInfoAO;

    @Autowired
    private SysUserInfoMapper sysUserInfoMapper;

    @Autowired
    private ISysUserInfoService sysUserInfoService;

    private void requireAdmin() {
        if (!LoginContext.isAdmin()) {
            throw new BusinessException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), "仅管理员可访问");
        }
    }

    @PostMapping("/register")
    public ResponseResult<Void> register(@RequestBody @Validated UserRegisterVO userRegisterVO) {
        sysUserInfoAO.register(userRegisterVO);
        return ResponseResult.ok();
    }

    @PostMapping("/login")
    public ResponseResult<Void> login(@RequestBody @Validated UserLoginVO userLoginVO, HttpServletResponse response) {
        sysUserInfoAO.login(userLoginVO, response);
        return ResponseResult.ok();
    }

    @GetMapping("/logout")
    public ResponseResult<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sysUserInfoAO.logout(request, response);
        return ResponseResult.ok();
    }

    @GetMapping("/getUserInfo")
    public ResponseResult<UserInfoVO> getUserInfo() {
        UserInfoVO userInfoVO = sysUserInfoAO.getUserInfo();
        return ResponseResult.ok(userInfoVO);
    }

    @PostMapping("/updateUserInfo")
    public ResponseResult<Void> updateUserInfo(@RequestBody UserInfoUpdateVO userInfoUpdateVO) {
        sysUserInfoAO.updateUserInfo(userInfoUpdateVO);
        return ResponseResult.ok();
    }

    @PostMapping("/changePassword")
    public ResponseResult<Void> changePassword(@RequestBody UserPwdModifyVO userPwdModifyVO) {
        sysUserInfoAO.changePassword(userPwdModifyVO);
        return ResponseResult.ok();
    }

    /**
     * 管理员用户列表（含角色、状态，分页）。
     */
    @GetMapping("/admin/users")
    public ResponseResult<UserListVO> adminUserList(
            @RequestParam(value = "keyword", defaultValue = "") String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        requireAdmin();
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        if (page < 1) page = 1;
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserInfo> wrapper =
                Wrappers.<SysUserInfo>lambdaQuery().ne(SysUserInfo::getStatus, -1);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysUserInfo::getAccount, keyword)
                              .or().like(SysUserInfo::getUserName, keyword));
        }
        Long total = sysUserInfoMapper.selectCount(wrapper);
        List<UserSearchVO> records = sysUserInfoMapper.selectList(
                    wrapper.orderByDesc(SysUserInfo::getId)
                           .last("LIMIT " + size + " OFFSET " + (page - 1) * size)
            ).stream().map(u -> new UserSearchVO(
                    u.getId(), u.getAccount(), u.getUserName(), u.getAvatar(),
                    u.getRole(), u.getStatus(), u.getCreateAt()))
            .collect(Collectors.toList());
        return ResponseResult.ok(new UserListVO(records, total.intValue(), page, size));
    }

    /**
     * 管理员：修改用户状态（启用/禁用）。
     */
    @PostMapping("/admin/users/{id}/status")
    public ResponseResult<Void> updateUserStatus(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Integer> body) {
        requireAdmin();
        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            return ResponseResult.fail("status 必须为 0（正常）或 1（禁用）");
        }
        SysUserInfo target = sysUserInfoService.getById(id);
        if (target == null) {
            return ResponseResult.fail("用户不存在");
        }
        // v0.13 #9: 用 id==1L 保护初始 admin，不依赖账号名（admin 改名失效 / 别人改名 admin 被永久保护）
        if (target.getId() != null && target.getId() == 1L) {
            return ResponseResult.fail("不允许操作超级管理员");
        }
        SysUserInfo update = new SysUserInfo();
        update.setId(id);
        update.setStatus(status);
        sysUserInfoService.updateById(update);
        return ResponseResult.ok();
    }

    /**
     * 管理员：修改用户角色。
     */
    @PostMapping("/admin/users/{id}/role")
    public ResponseResult<Void> updateUserRole(
            @PathVariable("id") Long id,
            @RequestBody Map<String, String> body) {
        requireAdmin();
        String role = body.get("role");
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            return ResponseResult.fail("role 必须为 USER 或 ADMIN");
        }
        SysUserInfo target = sysUserInfoService.getById(id);
        if (target == null) {
            return ResponseResult.fail("用户不存在");
        }
        // v0.13 #9: 用 id==1L 保护初始 admin，不依赖账号名（admin 改名失效 / 别人改名 admin 被永久保护）
        if (target.getId() != null && target.getId() == 1L) {
            return ResponseResult.fail("不允许操作超级管理员");
        }
        SysUserInfo update = new SysUserInfo();
        update.setId(id);
        update.setRole(role);
        sysUserInfoService.updateById(update);
        return ResponseResult.ok();
    }

    /**
     * v0.7 §4.1.1：用户搜索。任意登录用户可用（ShareDialog 用）。
     * - 按 username / nickname 模糊匹配
     * - 排除已注销 (status=-1)
     * - limit 限 ≤ 50
     * - 仅返 id / account / nickname / avatar（不返敏感字段）
     */
    @GetMapping("/users/search")
    public ResponseResult<List<UserSearchVO>> searchUsers(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
        if (limit == null || limit <= 0) limit = 20;
        if (limit > 50) limit = 50;
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserInfo> searchWrapper =
                Wrappers.<SysUserInfo>lambdaQuery().ne(SysUserInfo::getStatus, -1);
        if (StringUtils.hasText(keyword)) {
            searchWrapper.and(w -> w.like(SysUserInfo::getAccount, keyword)
                                     .or().like(SysUserInfo::getUserName, keyword));
        }
        List<UserSearchVO> results = sysUserInfoMapper.selectList(
                    searchWrapper.last("LIMIT " + limit)
            ).stream().map(u -> new UserSearchVO(
                    u.getId(), u.getAccount(), u.getUserName(), u.getAvatar()))
            .collect(Collectors.toList());
        return ResponseResult.ok(results);
    }

}
