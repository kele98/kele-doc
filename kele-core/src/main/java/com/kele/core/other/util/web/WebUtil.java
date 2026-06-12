package com.kele.core.other.util.web;

import com.kele.core.other.constants.CommonCons;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * @author wuzhenhong
 * @date 2024/5/14 14:13
 */
public class WebUtil {

    public static String getCookie(HttpServletRequest request, String cookieKey) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return "";
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(cookieKey)) {
                return cookie.getValue();
            }
        }
        return "";
    }

    public static void saveCookie(HttpServletResponse response, String token, Integer loginExpireSeconds) {

        Cookie cookie = new Cookie(CommonCons.TOKEN_KEY, token);
        cookie.setMaxAge(loginExpireSeconds);
        cookie.setPath("/");
        // 防止XSS攻击读取Cookie
        cookie.setHttpOnly(true);
        // v0.13 #6: HTTPS 下加 Secure flag，防止 cookie 在 HTTP 下传输被窃听。
        // 用 RequestContextHolder 取当前请求（saveCookie 总在 controller 同步流程内被调用，
        // Spring MVC 在请求线程内提供该 holder）。request.isSecure() 直连 HTTPS 时为 true；
        // nginx 反代场景需配合 server.forward-headers-strategy=framework 或 RemoteIpValve。
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            cookie.setSecure(attrs.getRequest().isSecure());
        }
        response.addCookie(cookie);
    }

}
