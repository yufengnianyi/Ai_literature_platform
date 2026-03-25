package com.example.demo_01.user;

import com.example.demo_01.user.mapper.UserMapper;
import com.example.demo_01.user.model.entity.User;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;

@Configuration
@Slf4j
public class AdminUserInitializer {

    @Resource
    private InitAdminProperties initAdminProperties;

    @Resource
    private UserMapper userMapper;

    @Resource
    private BCryptPasswordEncoder passwordEncoder;

    @Bean
    @ConditionalOnProperty(prefix = "app.user.init-admin", name = "enabled", havingValue = "true")
    ApplicationRunner initAdminUserRunner() {
        return args -> {
            if (isBlank(initAdminProperties.getUserAccount()) || isBlank(initAdminProperties.getUserPassword())) {
                log.warn("Admin bootstrap is enabled but account/password are missing, skipping initialization");
                return;
            }
            User existing = userMapper.selectOneByMap(Map.of("user_account", initAdminProperties.getUserAccount()));
            if (existing != null) {
                return;
            }

            OffsetDateTime now = OffsetDateTime.now();
            User admin = new User();
            admin.setUserId(UUID.randomUUID().toString());
            admin.setUsername(initAdminProperties.getUserAccount());
            admin.setUserAccount(initAdminProperties.getUserAccount());
            admin.setUserPassword(passwordEncoder.encode(initAdminProperties.getUserPassword()));
            admin.setUserName(isBlank(initAdminProperties.getUserName()) ? "Administrator" : initAdminProperties.getUserName().trim());
            admin.setUserRole("admin");
            admin.setEditTime(now);
            admin.setCreatedAt(now);
            admin.setUpdatedAt(now);
            admin.setIsDelete(0);
            userMapper.insertUser(admin);
            log.info("Initialized admin user {}", admin.getUserAccount());
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
