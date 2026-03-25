package com.example.demo_01.user;

import com.example.demo_01.user.model.dto.UserAddRequest;
import com.example.demo_01.user.model.dto.UserLoginRequest;
import com.example.demo_01.user.model.dto.UserQueryRequest;
import com.example.demo_01.user.model.dto.UserRegisterRequest;
import com.example.demo_01.user.model.dto.UserUpdateRequest;
import com.example.demo_01.user.model.entity.User;
import com.example.demo_01.user.model.vo.LoginUserVO;
import com.example.demo_01.user.model.vo.UserVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;
import jakarta.servlet.http.HttpServletRequest;

public interface UserService extends IService<User> {

    String userRegister(UserRegisterRequest request);

    LoginUserVO userLogin(UserLoginRequest request, HttpServletRequest httpServletRequest);

    LoginUserVO getCurrentLoginUser(HttpServletRequest request);

    User getLoginUser(HttpServletRequest request);

    boolean userLogout(HttpServletRequest request);

    String addUser(UserAddRequest request);

    UserVO getUserById(String userId);

    boolean updateUser(UserUpdateRequest request);

    boolean deleteUser(String userId);

    Page<UserVO> listUserByPage(UserQueryRequest request);

    LoginUserVO getLoginUserVO(User user);

    UserVO getUserVO(User user);

    boolean isAdmin(User user);
}
