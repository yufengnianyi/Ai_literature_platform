package com.example.demo_01.ai.model;

import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class DashScopeChatRequestFactory {

    public ChatRequest request(boolean enableThinking, int thinkingBudget, ChatMessage... messages) {
        return request(enableThinking, thinkingBudget, Arrays.asList(messages));
    }

    public ChatRequest request(boolean enableThinking, Integer thinkingBudget, List<ChatMessage> messages) {
        QwenChatRequestParameters.Builder parameters = QwenChatRequestParameters.builder()
                .enableThinking(enableThinking);
        if (enableThinking && thinkingBudget != null && thinkingBudget > 0) {
            parameters.thinkingBudget(thinkingBudget);
        }
        return ChatRequest.builder()
                .messages(messages)
                .parameters(parameters.build())
                .build();
    }
}
