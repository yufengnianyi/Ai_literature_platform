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
            @Value("${langchain4j.community.dashscope.chat-model.model-name:qwen-max}") String modelName,
            ReviewProperties properties) {
        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(properties.getReport().getMaxTokens())
                .build();
    }

    @Bean("reviewReportStreamingChatModel")
    public StreamingChatModel reviewReportStreamingChatModel(
            @Value("${langchain4j.community.dashscope.streaming-chat-model.api-key}") String apiKey,
            @Value("${langchain4j.community.dashscope.streaming-chat-model.model-name:qwen-max}") String modelName,
            ReviewProperties properties) {
        return QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(properties.getReport().getMaxTokens())
                .build();
    }
}
