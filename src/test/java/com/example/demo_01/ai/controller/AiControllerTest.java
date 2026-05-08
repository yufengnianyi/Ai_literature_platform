package com.example.demo_01.ai.controller;

import com.example.demo_01.ai.AiCodeHelperService;
import com.example.demo_01.ai.ChatStreamingService;
import com.example.demo_01.conversation.ConversationService;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.exception.GlobalExceptionHandler;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.mapper.UserMapper;
import com.example.demo_01.user.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiController.class)
@org.springframework.context.annotation.Import(GlobalExceptionHandler.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiCodeHelperService aiCodeHelperService;

    @MockBean
    private ChatStreamingService chatStreamingService;

    @MockBean
    private UserService userService;

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private UserMapper userMapper;

    @Test
    void shouldChatWithUserConversationScopedMemory() throws Exception {
        User loginUser = new User();
        loginUser.setUserId("u-1");
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        when(conversationService.normalizeConversationId("conv-1")).thenReturn("conv-1");
        when(aiCodeHelperService.chatWithFlux("u-1::conv-1", "hello")).thenReturn(Flux.just("hi"));

        mockMvc.perform(get("/ai")
                        .param("conversationId", "conv-1")
                        .param("prompt", "hello")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("event:message"),
                        org.hamcrest.Matchers.containsString("data:hi"),
                        org.hamcrest.Matchers.containsString("event:complete")
                )));

        verify(conversationService).createConversationIfAbsent("u-1", "conv-1");
        verify(aiCodeHelperService).chatWithFlux("u-1::conv-1", "hello");
    }

    @Test
    void shouldReturnUnauthorizedWhenNotLoggedIn() throws Exception {
        doThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "login required"))
                .when(userService).getLoginUser(any());

        mockMvc.perform(get("/ai")
                        .param("conversationId", "conv-1")
                        .param("prompt", "hello"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"code":40100,"data":null,"message":"login required"}
                        """));
    }
}
