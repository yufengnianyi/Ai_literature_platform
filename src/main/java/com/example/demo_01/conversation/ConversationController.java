package com.example.demo_01.conversation;

import com.example.demo_01.user.UserService;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private static final String USER_ID_HEADER = "X-User-Id";

    @Resource
    private ConversationService conversationService;

    @Resource
    private UserService userService;

    @PostMapping
    public ConversationService.ConversationResponse create(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestBody(required = false) ConversationService.CreateConversationRequest request) {
        String normalizedUserId = normalizeUserId(userId);
        userService.assertUserExists(normalizedUserId);
        return conversationService.createConversation(normalizedUserId, request);
    }

    @GetMapping
    public List<ConversationService.ConversationResponse> list(
            @RequestHeader(USER_ID_HEADER) String userId) {
        String normalizedUserId = normalizeUserId(userId);
        userService.assertUserExists(normalizedUserId);
        return conversationService.listConversations(normalizedUserId);
    }

    @PatchMapping("/{conversationId}")
    public ConversationService.ConversationResponse rename(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId,
            @RequestBody ConversationService.RenameConversationRequest request) {
        String normalizedUserId = normalizeUserId(userId);
        userService.assertUserExists(normalizedUserId);
        return conversationService.renameConversation(normalizedUserId, conversationId, request);
    }

    @DeleteMapping("/{conversationId}")
    public void delete(
            @RequestHeader(USER_ID_HEADER) String userId,
            @PathVariable String conversationId) {
        String normalizedUserId = normalizeUserId(userId);
        userService.assertUserExists(normalizedUserId);
        conversationService.deleteConversation(normalizedUserId, conversationId);
    }

    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Id header is required");
        }
        return userId.trim();
    }
}
