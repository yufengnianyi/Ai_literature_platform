package com.example.demo_01.ai.controller;

import com.example.demo_01.ai.ChatStreamingService;
import com.example.demo_01.ai.memory.UserConversationKey;
import com.example.demo_01.conversation.ConversationService;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.exception.ThrowUtils;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private ChatStreamingService chatStreamingService;

    @Resource
    private UserService userService;

    @Resource
    private ConversationService conversationService;

    @GetMapping
    public Flux<ServerSentEvent<String>> chat(
            HttpServletRequest httpServletRequest,
            @RequestParam(required = false) String conversationId,
            @RequestParam(name = "memory_id", required = false) Integer legacyMemoryId,
            @RequestParam(name = "enableThinking", defaultValue = "false") boolean enableThinking,
            @RequestParam String prompt) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        String normalizedUserId = loginUser.getUserId();

        String resolvedConversationId = resolveConversationId(conversationId, legacyMemoryId);
        conversationService.createConversationIfAbsent(normalizedUserId, resolvedConversationId);
        String memoryKey = UserConversationKey.compose(normalizedUserId, resolvedConversationId);
        return chatStreamingService.stream(memoryKey, prompt, enableThinking);
    }

    private String resolveConversationId(String conversationId, Integer legacyMemoryId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationService.normalizeConversationId(conversationId);
        }
        ThrowUtils.throwIf((conversationId == null || conversationId.isBlank()) && legacyMemoryId == null,
                ErrorCode.PARAMS_ERROR,
                "conversationId or memory_id is required");
        return conversationService.normalizeConversationId("legacy-" + legacyMemoryId);
    }
}
