package com.example.demo_01.user.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

@Table("app_user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(value = "user_id", keyType = KeyType.None)
    private String userId;

    @Column("username")
    private String username;

    @Column("user_account")
    private String userAccount;

    @Column("user_password")
    private String userPassword;

    @Column("user_name")
    private String userName;

    @Column("user_avatar")
    private String userAvatar;

    @Column("user_profile")
    private String userProfile;

    @Column("user_role")
    private String userRole;

    @Column(value = "edit_time")
    private OffsetDateTime editTime;

    @Column(value = "created_at")
    private OffsetDateTime createdAt;

    @Column(value = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(value = "is_delete", isLogicDelete = true)
    private Integer isDelete;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUserAccount() {
        return userAccount;
    }

    public void setUserAccount(String userAccount) {
        this.userAccount = userAccount;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public void setUserPassword(String userPassword) {
        this.userPassword = userPassword;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserAvatar() {
        return userAvatar;
    }

    public void setUserAvatar(String userAvatar) {
        this.userAvatar = userAvatar;
    }

    public String getUserProfile() {
        return userProfile;
    }

    public void setUserProfile(String userProfile) {
        this.userProfile = userProfile;
    }

    public String getUserRole() {
        return userRole;
    }

    public void setUserRole(String userRole) {
        this.userRole = userRole;
    }

    public OffsetDateTime getEditTime() {
        return editTime;
    }

    public void setEditTime(OffsetDateTime editTime) {
        this.editTime = editTime;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getIsDelete() {
        return isDelete;
    }

    public void setIsDelete(Integer isDelete) {
        this.isDelete = isDelete;
    }
}
