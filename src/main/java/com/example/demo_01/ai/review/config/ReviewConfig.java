package com.example.demo_01.ai.review.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ReviewConfig {

    @Bean("reviewTaskExecutor")
    public TaskExecutor reviewTaskExecutor(ReviewProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("review-pipeline-");
        executor.setCorePoolSize(properties.getAsyncThreads());
        executor.setMaxPoolSize(Math.max(properties.getAsyncThreads(), 4));
        executor.setQueueCapacity(16);
        executor.initialize();
        return executor;
    }

    @Bean("reviewReportChatModel")
    public ChatModel reviewReportChatModel(
            @Value("${langchain4j.community.dashscope.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.community.dashscope.chat-model.model-name:qwen3-max-2026-01-23}") String modelName,
            @Value("${langchain4j.community.dashscope.chat-model.enable-thinking:false}") boolean enableThinking,
            @Value("${langchain4j.community.dashscope.chat-model.thinking-budget:81920}") Integer thinkingBudget,
            ReviewProperties properties) {
        QwenChatModel model = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(properties.getReport().getMaxTokens())
                .build();
        model.setGenerationParamCustomizer(builder -> {
            builder.enableThinking(enableThinking);
            if (thinkingBudget != null && thinkingBudget > 0) {
                builder.thinkingBudget(thinkingBudget);
            }
        });
        return model;
    }

    @Bean("reviewReportStreamingChatModel")
    public StreamingChatModel reviewReportStreamingChatModel(
            @Value("${langchain4j.community.dashscope.streaming-chat-model.api-key}") String apiKey,
            @Value("${langchain4j.community.dashscope.streaming-chat-model.model-name:qwen3-max-2026-01-23}") String modelName,
            @Value("${langchain4j.community.dashscope.streaming-chat-model.enable-thinking:false}") boolean enableThinking,
            @Value("${langchain4j.community.dashscope.streaming-chat-model.thinking-budget:81920}") Integer thinkingBudget,
            ReviewProperties properties) {
        QwenStreamingChatModel model = QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(properties.getReport().getMaxTokens())
                .build();
        model.setGenerationParamCustomizer(builder -> {
            builder.enableThinking(enableThinking);
            if (thinkingBudget != null && thinkingBudget > 0) {
                builder.thinkingBudget(thinkingBudget);
            }
        });
        return model;
    }
}
