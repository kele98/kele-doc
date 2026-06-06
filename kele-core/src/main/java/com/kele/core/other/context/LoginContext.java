package com.kele.core.other.context;

import com.kele.core.buz.sys.model.bo.UserInfoBO;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 登录上下文 ThreadLocal。详见 spec §5.3 + §14（v0.6 B3 新增 isAdmin/userGroupIds）。
 */
public class LoginContext {

    private static final ThreadLocal<UserInfoBO> USER_INFO = new ThreadLocal<>();
    private static final ThreadLocal<List<Long>> USER_GROUP_IDS = new ThreadLocal<>();

    public static void setUserInfo(UserInfoBO userInfoBO) {
        USER_INFO.set(userInfoBO);
    }

    public static UserInfoBO getUserInfo() {
        return USER_INFO.get();
    }

    public static Long getUserId() {
        UserInfoBO userInfoBO = LoginContext.getUserInfo();
        return Objects.isNull(userInfoBO) ? null : userInfoBO.getId();
    }

    public static String getAccount() {
        UserInfoBO userInfoBO = LoginContext.getUserInfo();
        return Objects.isNull(userInfoBO) ? null : userInfoBO.getAccount();
    }

    /** v0.6 B3 新增：role=='ADMIN' 判定 */
    public static boolean isAdmin() {
        UserInfoBO userInfoBO = LoginContext.getUserInfo();
        return userInfoBO != null && "ADMIN".equals(userInfoBO.getRole());
    }

    /** v0.7 新增：当前用户加入的群组 id 列表（由 LoginHandlerInterceptor 注入） */
    public static void setUserGroupIds(List<Long> groupIds) {
        USER_GROUP_IDS.set(groupIds == null ? Collections.emptyList() : groupIds);
    }

    public static List<Long> getUserGroupIds() {
        List<Long> ids = USER_GROUP_IDS.get();
        return ids == null ? Collections.emptyList() : ids;
    }

    public static void remove() {
        USER_INFO.remove();
        USER_GROUP_IDS.remove();
    }
}
