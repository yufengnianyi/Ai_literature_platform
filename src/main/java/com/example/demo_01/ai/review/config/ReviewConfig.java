package com.example.demo_01.ai.review.config;

import com.example.demo_01.ai.model.DashScopeModelFactory;
import com.example.demo_01.ai.model.DashScopeModelProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
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
    public ChatModel reviewReportChatModel(DashScopeModelProperties modelProperties,
                                           DashScopeModelFactory modelFactory,
                                           ReviewProperties properties) {
        return modelFactory.chatModel(modelProperties.getChatModel(), properties.getReport().getMaxTokens());
    }

    @Bean("reviewReportStreamingChatModel")
    public StreamingChatModel reviewReportStreamingChatModel(DashScopeModelProperties modelProperties,
                                                             DashScopeModelFactory modelFactory,
                                                             ReviewProperties properties) {
        return modelFactory.streamingChatModel(modelProperties.getStreamingChatModel(), properties.getReport().getMaxTokens());
    }
}
