package com.example.demo_01.user;

import com.example.demo_01.annotation.AuthCheck;
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
    public String register(@RequestBody UserRegisterRequest request) {
        return userService.userRegister(request);
    }

    @PostMapping("/login")
    public LoginUserVO login(@RequestBody UserLoginRequest request, HttpServletRequest httpServletRequest) {
        return userService.userLogin(request, httpServletRequest);
    }

    @GetMapping("/get/login")
    public LoginUserVO getLoginUser(HttpServletRequest request) {
        return userService.getCurrentLoginUser(request);
    }

    @PostMapping("/logout")
    public Boolean logout(HttpServletRequest request) {
        return userService.userLogout(request);
    }

    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public String addUser(@RequestBody UserAddRequest request) {
        return userService.addUser(request);
    }

    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public UserVO getUser(@RequestParam("id") String userId) {
        return userService.getUserById(userId);
    }

    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Boolean updateUser(@RequestBody UserUpdateRequest request) {
        return userService.updateUser(request);
    }

    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Boolean deleteUser(@RequestBody UserDeleteRequest request) {
        return userService.deleteUser(request == null ? null : request.getUserId());
    }

    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Page<UserVO> listUserByPage(@RequestBody(required = false) UserQueryRequest request) {
        return userService.listUserByPage(request);
    }
}
