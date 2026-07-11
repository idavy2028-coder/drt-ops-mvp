package com.idavy.drtops.auth;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final UserAccountRepository users;
    private final AuthConfiguration config;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public BootstrapAdminInitializer(
            UserAccountRepository users,
            AuthConfiguration config,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.users = users;
        this.config = config;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() != 0) {
            return;
        }
        if (!StringUtils.hasText(config.getBootstrapAdminUsername())
                || !StringUtils.hasText(config.getBootstrapAdminPassword())) {
            LOG.warn("用户库为空，但未配置初始系统管理员账号，跳过创建");
            return;
        }
        UserAccount administrator = UserAccount.create(
                config.getBootstrapAdminUsername().trim(),
                "初始系统管理员",
                passwordEncoder.encode(config.getBootstrapAdminPassword()));
        administrator.assignRoles(Set.of(RoleCode.SYSTEM_ADMIN));
        try {
            users.saveAndFlush(administrator);
            LOG.warn("已创建初始系统管理员账号 {}，请首次登录后立即重置密码", administrator.getUsername());
        } catch (DataIntegrityViolationException exception) {
            LOG.info("初始系统管理员已由并发启动实例创建，跳过重复创建");
        }
    }
}
