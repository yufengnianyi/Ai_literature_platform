package com.example.demo_01.ai.controller;

import com.example.demo_01.ai.AiCodeHelperService;
import com.example.demo_01.ai.markdown.MarkdownChunkBuffer;
import com.example.demo_01.ai.memory.UserConversationKey;
import com.example.demo_01.conversation.ConversationService;
import com.example.demo_01.user.UserService;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final String USER_ID_HEADER = "X-User-Id";

    @Resource
    private AiCodeHelperService aiCodeHelperService;

    @Resource
    private UserService userService;

    @Resource
    private ConversationService conversationService;

    @GetMapping
    public Flux<ServerSentEvent<String>> chat(
            @RequestHeader(USER_ID_HEADER) String userId,
            @RequestParam(required = false) String conversationId,
            @RequestParam(name = "memory_id", required = false) Integer legacyMemoryId,
            @RequestParam String prompt) {
        String normalizedUserId = resolveUserId(userId);
        userService.assertUserExists(normalizedUserId);

        String resolvedConversationId = resolveConversationId(conversationId, legacyMemoryId);
        conversationService.createConversationIfAbsent(normalizedUserId, resolvedConversationId);
        String memoryKey = UserConversationKey.compose(normalizedUserId, resolvedConversationId);
        MarkdownChunkBuffer chunkBuffer = new MarkdownChunkBuffer();

        Flux<ServerSentEvent<String>> messageEvents = aiCodeHelperService.chatWithFlux(memoryKey, prompt)
                .concatMapIterable(chunkBuffer::append)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(chunk)
                        .build());

        Flux<ServerSentEvent<String>> completionEvents = Flux.defer(() -> Flux.concat(
                Flux.fromIterable(chunkBuffer.flushRemaining())
                        .map(chunk -> ServerSentEvent.<String>builder()
                                .event("message")
                                .data(chunk)
                                .build()),
                Flux.just(ServerSentEvent.<String>builder()
                        .event("complete")
                        .build())
        ));

        return messageEvents.concatWith(completionEvents);
    }

    private String resolveUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Id header is required");
        }
        return userId.trim();
    }

    private String resolveConversationId(String conversationId, Integer legacyMemoryId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationService.normalizeConversationId(conversationId);
        }
        if (legacyMemoryId != null) {
            return conversationService.normalizeConversationId("legacy-" + legacyMemoryId);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId or memory_id is required");
    }
}
