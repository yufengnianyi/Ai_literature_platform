package com.example.demo_01.user.mapper;

import com.example.demo_01.user.model.entity.User;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Insert("""
            insert into app_user (
                user_id,
                username,
                user_account,
                user_password,
                user_name,
                user_avatar,
                user_profile,
                user_role,
                edit_time,
                created_at,
                updated_at,
                is_delete
            ) values (
                #{userId},
                #{username},
                #{userAccount},
                #{userPassword},
                #{userName},
                #{userAvatar},
                #{userProfile},
                #{userRole},
                #{editTime},
                #{createdAt},
                #{updatedAt},
                #{isDelete}
            )
            """)
    int insertUser(User user);

    @Update("""
            update app_user
            set username = #{username},
                user_account = #{userAccount},
                user_name = #{userName},
                user_avatar = #{userAvatar},
                user_profile = #{userProfile},
                user_role = #{userRole},
                edit_time = #{editTime},
                updated_at = #{updatedAt}
            where user_id = #{userId}
            """)
    int updateUserProfile(User user);
}
