package com.kele.core;

import com.kele.core.buz.sys.dao.entity.SysUserInfo;
import com.kele.core.buz.sys.dao.mapper.SysUserInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动期校验：sys_user_info.id=1 必须存在且 role='ADMIN'，否则拒绝启动。
 * 详见 spec §6.3。
 */
@Slf4j
@Component
@Order(0)
@ConditionalOnProperty(name = "kele.doc.startup.admin-check.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class KeleDocApplicationRunner implements ApplicationRunner {

    private final SysUserInfoMapper sysUserInfoMapper;

    @Override
    public void run(ApplicationArguments args) {
        SysUserInfo admin = sysUserInfoMapper.selectById(1L);
        if (admin == null || !"ADMIN".equals(admin.getRole())) {
            throw new IllegalStateException(
                "[Kele-Doc] 启动失败：sys_user_info.id=1 用户必须存在且 role='ADMIN'。" +
                "当前: " + (admin == null ? "NULL" : "id=" + admin.getId() + ", role=" + admin.getRole()) +
                "。请参考 docs/kele-doc-sharing-design.md §6 修复。"
            );
        }
        log.info("[Kele-Doc] 启动校验通过：admin user id=1 role=ADMIN");
    }
}
