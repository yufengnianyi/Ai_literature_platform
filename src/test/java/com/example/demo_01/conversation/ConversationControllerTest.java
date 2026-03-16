package com.example.demo_01.conversation;

import com.example.demo_01.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationController.class)
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private UserService userService;

    @Test
    void shouldCreateConversation() throws Exception {
        ConversationService.ConversationResponse response = new ConversationService.ConversationResponse(
                "c-1", "\u65B0\u4F1A\u8BDD-20260312120000", Instant.parse("2026-03-12T04:00:00Z"), Instant.parse("2026-03-12T04:00:00Z"));
        when(conversationService.createConversation("u-1", new ConversationService.CreateConversationRequest("my title")))
                .thenReturn(response);

        mockMvc.perform(post("/conversations")
                        .header("X-User-Id", "u-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConversationService.CreateConversationRequest("my title"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("c-1"));

        verify(userService).assertUserExists("u-1");
    }

    @Test
    void shouldListConversations() throws Exception {
        when(conversationService.listConversations("u-1")).thenReturn(List.of(
                new ConversationService.ConversationResponse("c-1", "title", Instant.parse("2026-03-12T04:00:00Z"), Instant.parse("2026-03-12T04:00:00Z"))
        ));

        mockMvc.perform(get("/conversations").header("X-User-Id", "u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationId").value("c-1"));
    }

    @Test
    void shouldRenameConversation() throws Exception {
        ConversationService.RenameConversationRequest request = new ConversationService.RenameConversationRequest("new title");
        ConversationService.ConversationResponse response = new ConversationService.ConversationResponse(
                "c-1", "new title", Instant.parse("2026-03-12T04:00:00Z"), Instant.parse("2026-03-12T05:00:00Z"));

        when(conversationService.renameConversation("u-1", "c-1", request)).thenReturn(response);

        mockMvc.perform(patch("/conversations/c-1")
                        .header("X-User-Id", "u-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("new title"));
    }

    @Test
    void shouldDeleteConversation() throws Exception {
        mockMvc.perform(delete("/conversations/c-1").header("X-User-Id", "u-1"))
                .andExpect(status().isOk());

        verify(conversationService).deleteConversation("u-1", "c-1");
    }

    @Test
    void shouldRejectWhenHeaderMissing() throws Exception {
        mockMvc.perform(get("/conversations"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnNotFoundWhenUserMissing() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"))
                .when(userService).assertUserExists("missing-user");

        mockMvc.perform(get("/conversations").header("X-User-Id", "missing-user"))
                .andExpect(status().isNotFound());
    }
}
