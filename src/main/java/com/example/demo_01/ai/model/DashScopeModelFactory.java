package com.example.demo_01.ai.model;

import com.alibaba.dashscope.aigc.generation.GenerationParam;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DashScopeModelFactory {

    @Resource
    private ChatModelListener chatModelListener;

    public ChatModel chatModel(DashScopeModelProperties.QwenModel properties) {
        return chatModel(properties, null);
    }

    public ChatModel chatModel(DashScopeModelProperties.QwenModel properties, Integer maxTokens) {
        QwenChatModel.QwenChatModelBuilder builder = QwenChatModel.builder()
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName())
                .listeners(List.of(chatModelListener));
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        QwenChatModel model = builder.build();
        model.setGenerationParamCustomizer(generation -> applyThinking(properties, generation));
        return model;
    }

    public StreamingChatModel streamingChatModel(DashScopeModelProperties.QwenModel properties, Integer maxTokens) {
        QwenStreamingChatModel.QwenStreamingChatModelBuilder builder = QwenStreamingChatModel.builder()
                .apiKey(properties.getApiKey())
                .modelName(properties.getModelName());
        if (maxTokens != null && maxTokens > 0) {
            builder.maxTokens(maxTokens);
        }
        QwenStreamingChatModel model = builder.build();
        model.setGenerationParamCustomizer(generation -> applyThinking(properties, generation));
        return model;
    }

    private void applyThinking(DashScopeModelProperties.QwenModel properties,
                               GenerationParam.GenerationParamBuilder<?, ?> generation) {
        generation.enableThinking(Boolean.TRUE.equals(properties.getEnableThinking()));
        Integer thinkingBudget = properties.getThinkingBudget();
        if (thinkingBudget != null && thinkingBudget > 0) {
            generation.thinkingBudget(thinkingBudget);
        }
    }
}
