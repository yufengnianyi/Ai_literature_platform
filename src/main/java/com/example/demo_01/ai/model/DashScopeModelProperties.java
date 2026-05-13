package com.example.demo_01.ai.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "langchain4j.community.dashscope")
public class DashScopeModelProperties {

    private QwenModel chatModel = new QwenModel();

    private QwenModel streamingChatModel = new QwenModel();

    @Data
    public static class QwenModel {

        private String apiKey;

        private String modelName;

        private Boolean enableThinking = false;

        private Integer thinkingBudget = 81920;
    }
}
