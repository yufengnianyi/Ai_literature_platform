package com.example.demo_01.ai.model;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springdoc.webmvc.core.service.RequestService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.community.dashscope.chat-model")
@Data
public class QwenChatModelConfig {

    private String api_key;
    private String model_name;
    private Boolean enable_thinking = false;
    private Integer thinking_budget = 81920;
    // 从bean中拿到listener对象
    @Resource
    private ChatModelListener chatModelListener;

    @Bean
    public ChatModel myqwenChatModel(RequestService requestBuilder) {
        // 创建一个qwenChatModel对象
        // QwenChatModel是安装LLM依赖中提供的实现接口
        QwenChatModel model = QwenChatModel.builder()
                .apiKey(api_key)
                .modelName(model_name)
                .listeners(List.of(chatModelListener))
                .build();
        model.setGenerationParamCustomizer(builder -> {
            builder.enableThinking(Boolean.TRUE.equals(enable_thinking));
            if (thinking_budget != null && thinking_budget > 0) {
                builder.thinkingBudget(thinking_budget);
            }
        });
        return model;
    }
}
