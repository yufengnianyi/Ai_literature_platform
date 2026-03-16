package com.example.demo_01.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @Test
    void shouldCreateUser() throws Exception {
        UserService.UserResponse response = new UserService.UserResponse(
                "u-1", "alice", Instant.parse("2026-03-12T00:00:00Z"), Instant.parse("2026-03-12T00:00:00Z"));
        when(userService.createUser(new UserService.CreateUserRequest("alice"))).thenReturn(response);

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UserService.CreateUserRequest("alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-1"))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void shouldGetUser() throws Exception {
        UserService.UserResponse response = new UserService.UserResponse(
                "u-2", "bob", Instant.parse("2026-03-12T00:00:00Z"), Instant.parse("2026-03-12T00:00:00Z"));
        when(userService.getUser("u-2")).thenReturn(response);

        mockMvc.perform(get("/users/u-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-2"))
                .andExpect(jsonPath("$.username").value("bob"));
    }
}