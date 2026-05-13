package com.example.demo_01.ai.model;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DashScopeModelProperties.class)
public class QwenChatModelConfig {

    @Bean
    public ChatModel myqwenChatModel(DashScopeModelProperties properties,
                                     DashScopeModelFactory modelFactory) {
        return modelFactory.chatModel(properties.getChatModel());
    }
}
