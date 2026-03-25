package com.example.demo_01.user.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

@Data
public class UserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;

    private String userAccount;

    private String userName;

    private String userAvatar;

    private String userProfile;

    private String userRole;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
