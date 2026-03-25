package com.example.demo_01.user.model.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class UserQueryRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private long pageNum = 1;

    private long pageSize = 10;

    private String sortField;

    private String sortOrder;

    private String userAccount;

    private String userName;

    private String userRole;
}
