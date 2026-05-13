package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.model.DashScopeChatRequestFactory;
import com.example.demo_01.ai.review.config.ReviewProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class ReviewReasoningChatClient {

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ReviewProperties reviewProperties;

    @Resource
    private DashScopeChatRequestFactory chatRequestFactory;

    public ChatResponse chatCore(ChatMessage... messages) {
        ReviewProperties.Reasoning cfg = reviewProperties.getReasoning();
        return chatWithReasoning(cfg.isCoreDeepThinking(), cfg.getCoreThinkingBudget(), messages);
    }

    public ChatResponse chatStandard(ChatMessage... messages) {
        ReviewProperties.Reasoning cfg = reviewProperties.getReasoning();
        return chatWithReasoning(cfg.isStandardDeepThinking(), cfg.getStandardThinkingBudget(), messages);
    }

    private ChatResponse chatWithReasoning(boolean enableThinking, int thinkingBudget, ChatMessage... messages) {
        return chatModel.chat(chatRequestFactory.request(enableThinking, thinkingBudget, messages));
    }
}
