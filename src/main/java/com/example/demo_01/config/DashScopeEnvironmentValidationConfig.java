package com.example.demo_01.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

@Configuration
@Profile({"local", "prod"})
public class DashScopeEnvironmentValidationConfig {

    private static final String PLACEHOLDER_API_KEY = "demo-key";

    @Bean
    ApplicationRunner dashScopeEnvironmentValidator(
            @Value("${langchain4j.community.dashscope.chat-model.api-key:}") String chatApiKey,
            @Value("${langchain4j.community.dashscope.embedding-model.api-key:}") String embeddingApiKey,
            @Value("${langchain4j.community.dashscope.streaming-chat-model.api-key:}") String streamingApiKey) {
        return args -> {
            validateDashScopeApiKey("chat-model", chatApiKey);
            validateDashScopeApiKey("embedding-model", embeddingApiKey);
            validateDashScopeApiKey("streaming-chat-model", streamingApiKey);
        };
    }

    private static void validateDashScopeApiKey(String modelType, String apiKey) {
        if (!StringUtils.hasText(apiKey) || PLACEHOLDER_API_KEY.equals(apiKey.trim())) {
            throw new IllegalStateException(
                    "DashScope API key for "
                            + modelType
                            + " is missing or still using the placeholder value. "
                            + "Set DASHSCOPE_API_KEY to a valid key before starting the application.");
        }
    }
}
