package com.kele.core.other.interceptor;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Lists;
import com.kele.common.enums.ErrorCodeEnum;
import com.kele.common.exception.BusinessException;
import com.kele.core.buz.doc.dao.entity.GroupMember;
import com.kele.core.buz.doc.dao.mapper.GroupMemberMapper;
import com.kele.core.buz.sys.model.bo.UserInfoBO;
import com.kele.core.other.constants.CommonCons;
import com.kele.core.other.context.LoginContext;
import com.kele.core.other.util.web.WebUtil;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * @author wuzhenhong
 * @date 2024/5/14 8:48
 */
public class LoginHandlerInterceptor implements HandlerInterceptor {

    private List<String> whiteUrlList = Lists.newArrayList();
    private List<String> blackUrlList = Lists.newArrayList();
    private AntPathMatcher antPathMatcher = new AntPathMatcher();
    private StringRedisTemplate stringRedisTemplate;
    private GroupMemberMapper groupMemberMapper;

    public LoginHandlerInterceptor(StringRedisTemplate stringRedisTemplate,
                                 GroupMemberMapper groupMemberMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.groupMemberMapper = groupMemberMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws Exception {

        String uri = request.getRequestURI();
        boolean match = whiteUrlList.stream().anyMatch(url -> antPathMatcher.match(url, uri));
        if (match) {
            return true;
        }
        match = blackUrlList.stream().anyMatch(url -> antPathMatcher.match(url, uri));
        if (!match) {
            return true;
        }
        String token = WebUtil.getCookie(request, CommonCons.TOKEN_KEY);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCodeEnum.UN_LOGIN.getCode(), ErrorCodeEnum.UN_LOGIN.getDesc());
        }
        String userJsonInfo = stringRedisTemplate.opsForValue().get(token);
        if (!StringUtils.hasText(userJsonInfo)) {
            throw new BusinessException(ErrorCodeEnum.UN_LOGIN.getCode(), ErrorCodeEnum.UN_LOGIN.getDesc());
        }
        UserInfoBO userInfoBO = JSONUtil.toBean(userJsonInfo, UserInfoBO.class);
        LoginContext.setUserInfo(userInfoBO);

        // v0.7：加载当前用户加入的群组 id 列表（PermissionService 解析 ACL 用）
        List<Long> groupIds = groupMemberMapper.selectList(
                Wrappers.<GroupMember>lambdaQuery().eq(GroupMember::getUserId, LoginContext.getUserId())
        ).stream().map(GroupMember::getGroupId).collect(Collectors.toList());
        LoginContext.setUserGroupIds(groupIds == null ? Collections.emptyList() : groupIds);

        // 续期
        stringRedisTemplate.expire(token, CommonCons.LOGIN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        String key = CommonCons.LOGIN_USER_TOKE_KEY + LoginContext.getUserId();
        stringRedisTemplate.expire(key, CommonCons.LOGIN_TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        LoginContext.remove();
    }


    public void addWhiteUrl(String url) {
        whiteUrlList.add(url);
    }

    public void addBlackUrl(String url) {
        blackUrlList.add(url);
    }
}
