package com.example.demo_01.ai.controller;

import com.example.demo_01.ai.AiCodeHelperService;
import com.example.demo_01.conversation.ConversationService;
import com.example.demo_01.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiController.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiCodeHelperService aiCodeHelperService;

    @MockBean
    private UserService userService;

    @MockBean
    private ConversationService conversationService;

    @Test
    void shouldChatWithUserConversationScopedMemory() throws Exception {
        when(conversationService.normalizeConversationId("conv-1")).thenReturn("conv-1");
        when(aiCodeHelperService.chatWithFlux("u-1::conv-1", "hello")).thenReturn(Flux.just("hi"));

        mockMvc.perform(get("/ai")
                        .header("X-User-Id", "u-1")
                        .param("conversationId", "conv-1")
                        .param("prompt", "hello")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hi")));

        verify(userService).assertUserExists("u-1");
        verify(conversationService).createConversationIfAbsent("u-1", "conv-1");
        verify(aiCodeHelperService).chatWithFlux("u-1::conv-1", "hello");
    }

    @Test
    void shouldReturnBadRequestWhenUserHeaderMissing() throws Exception {
        mockMvc.perform(get("/ai")
                        .param("conversationId", "conv-1")
                        .param("prompt", "hello"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnNotFoundWhenUserMissing() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"))
                .when(userService).assertUserExists("missing-user");

        mockMvc.perform(get("/ai")
                        .header("X-User-Id", "missing-user")
                        .param("conversationId", "conv-1")
                        .param("prompt", "hello"))
                .andExpect(status().isNotFound());
    }
}