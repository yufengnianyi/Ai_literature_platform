package com.example.demo_01.user;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.user.init-admin")
public class InitAdminProperties {

    private boolean enabled;

    private String userAccount;

    private String userPassword;

    private String userName;
}
