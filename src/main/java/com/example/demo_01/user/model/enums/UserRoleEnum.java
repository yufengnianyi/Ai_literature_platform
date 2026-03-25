package com.example.demo_01.user.model.enums;

import com.example.demo_01.user.constant.UserConstant;

public enum UserRoleEnum {
    USER(UserConstant.USER_ROLE, "普通用户"),
    ADMIN(UserConstant.ADMIN_ROLE, "管理员");

    private final String value;
    private final String text;

    UserRoleEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public String getValue() {
        return value;
    }

    public String getText() {
        return text;
    }

    public static UserRoleEnum fromValue(String value) {
        for (UserRoleEnum roleEnum : values()) {
            if (roleEnum.value.equals(value)) {
                return roleEnum;
            }
        }
        return null;
    }
}
