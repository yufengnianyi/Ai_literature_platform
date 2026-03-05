package com.example.demo_01.ai;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 工厂类 根据接口自动创建对象
 @Configuration
public class AiCodeHelperServiceFactory {

    @Resource
    private ChatModel qwenChatModel;

    @Resource(name = "jsonlContentRetriever")
    ContentRetriever jsonlContentRetriever;

    @Bean
    public AiCodeHelperService aiCodeHelperService() {
        // 会话记忆
        // 创建一个会话记忆对象
        // ChatMemory 是单会话实例 而chatMemoryProvider是 多会话实例
        ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);

        // builder 构造器模式实现接口的对象
        AiCodeHelperService aiCodeHelperService = AiServices.builder(AiCodeHelperService.class)
                .chatModel(qwenChatModel)
                //.chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .chatMemory(chatMemory) //会话记忆
                .contentRetriever(jsonlContentRetriever)
                .build();

        return aiCodeHelperService;
    }

}
