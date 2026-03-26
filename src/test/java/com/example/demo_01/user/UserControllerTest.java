package com.example.demo_01.user;

import com.example.demo_01.aop.AuthInterceptor;
import com.example.demo_01.exception.GlobalExceptionHandler;
import com.example.demo_01.user.mapper.UserMapper;
import com.example.demo_01.user.model.dto.UserDeleteRequest;
import com.example.demo_01.user.model.dto.UserLoginRequest;
import com.example.demo_01.user.model.dto.UserQueryRequest;
import com.example.demo_01.user.model.dto.UserRegisterRequest;
import com.example.demo_01.user.model.entity.User;
import com.example.demo_01.user.model.vo.LoginUserVO;
import com.example.demo_01.user.model.vo.UserVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.paginate.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({AuthInterceptor.class, GlobalExceptionHandler.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private UserMapper userMapper;

    @Test
    void shouldRegisterUser() throws Exception {
        UserRegisterRequest request = new UserRegisterRequest();
        request.setUserAccount("alice01");
        request.setUserPassword("password01");
        request.setCheckPassword("password01");
        request.setUserName("Alice");

        when(userService.userRegister(any(UserRegisterRequest.class))).thenReturn("u-1");

                mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("u-1"));
    }

    @Test
    void shouldLoginUser() throws Exception {
        UserLoginRequest request = new UserLoginRequest();
        request.setUserAccount("alice01");
        request.setUserPassword("password01");

        LoginUserVO loginUserVO = new LoginUserVO();
        loginUserVO.setUserId("u-1");
        loginUserVO.setUserAccount("alice01");
        loginUserVO.setUserName("Alice");
        loginUserVO.setUserRole("user");
        when(userService.userLogin(any(UserLoginRequest.class), any())).thenReturn(loginUserVO);

                mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("u-1"))
                .andExpect(jsonPath("$.data.userAccount").value("alice01"));
    }

    @Test
    void shouldGetCurrentLoginUser() throws Exception {
        LoginUserVO loginUserVO = new LoginUserVO();
        loginUserVO.setUserId("u-1");
        loginUserVO.setUserAccount("alice01");
        loginUserVO.setUserName("Alice");
        loginUserVO.setUserRole("user");
        when(userService.getCurrentLoginUser(any())).thenReturn(loginUserVO);

        mockMvc.perform(get("/user/get/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("u-1"));
    }

    @Test
    void shouldListUsersByPageForAdmin() throws Exception {
        User admin = new User();
        admin.setUserId("admin-1");
        admin.setUserRole("admin");
        when(userService.getLoginUser(any())).thenReturn(admin);

        UserVO userVO = new UserVO();
        userVO.setUserId("u-1");
        userVO.setUserAccount("alice01");
        userVO.setUserName("Alice");
        userVO.setUserRole("user");

        Page<UserVO> page = new Page<>(1, 10);
        page.setRecords(java.util.List.of(userVO));
        page.setTotalRow(1L);
        page.setTotalPage(1L);
        when(userService.listUserByPage(any(UserQueryRequest.class))).thenReturn(page);

        mockMvc.perform(post("/user/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserQueryRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].userId").value("u-1"))
                .andExpect(jsonPath("$.data.totalRow").value(1));
    }

    @Test
    void shouldDeleteUserForAdmin() throws Exception {
        User admin = new User();
        admin.setUserId("admin-1");
        admin.setUserRole("admin");
        when(userService.getLoginUser(any())).thenReturn(admin);
        when(userService.deleteUser("u-2")).thenReturn(true);

        UserDeleteRequest request = new UserDeleteRequest();
        request.setUserId("u-2");

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(userService).deleteUser("u-2");
    }
}
