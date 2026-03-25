package com.example.demo_01.user.model.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class UserDeleteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;
}
