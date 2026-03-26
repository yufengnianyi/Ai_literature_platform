package com.example.demo_01.user;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.user.constant.UserConstant;
import com.example.demo_01.user.model.dto.UserAddRequest;
import com.example.demo_01.user.model.dto.UserDeleteRequest;
import com.example.demo_01.user.model.dto.UserLoginRequest;
import com.example.demo_01.user.model.dto.UserQueryRequest;
import com.example.demo_01.user.model.dto.UserRegisterRequest;
import com.example.demo_01.user.model.dto.UserUpdateRequest;
import com.example.demo_01.user.model.vo.LoginUserVO;
import com.example.demo_01.user.model.vo.UserVO;
import com.mybatisflex.core.paginate.Page;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @PostMapping("/register")
    public BaseResponse<String> register(@RequestBody UserRegisterRequest request) {
        return ResultUtils.success(userService.userRegister(request));
    }

    @PostMapping("/login")
    public BaseResponse<LoginUserVO> login(@RequestBody UserLoginRequest request, HttpServletRequest httpServletRequest) {
        return ResultUtils.success(userService.userLogin(request, httpServletRequest));
    }

    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        return ResultUtils.success(userService.getCurrentLoginUser(request));
    }

    @PostMapping("/logout")
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        return ResultUtils.success(userService.userLogout(request));
    }

    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<String> addUser(@RequestBody UserAddRequest request) {
        return ResultUtils.success(userService.addUser(request));
    }

    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<UserVO> getUser(@RequestParam("id") String userId) {
        return ResultUtils.success(userService.getUserById(userId));
    }

    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest request) {
        return ResultUtils.success(userService.updateUser(request));
    }

    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestBody UserDeleteRequest request) {
        return ResultUtils.success(userService.deleteUser(request == null ? null : request.getUserId()));
    }

    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserByPage(@RequestBody(required = false) UserQueryRequest request) {
        return ResultUtils.success(userService.listUserByPage(request));
    }
}
