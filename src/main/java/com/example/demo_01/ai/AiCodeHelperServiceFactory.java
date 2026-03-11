package com.example.demo_01.ai;

import com.example.demo_01.ai.tools.InterviewQuestionTool;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Stream;

// 工厂类 根据接口自动创建对象
@Configuration
public class AiCodeHelperServiceFactory {

    @Resource
    private ChatModel MyqwenChatModel;

    @Resource(name = "jsonlContentRetriever")
    ContentRetriever jsonlContentRetriever;

    // qwenModel 依赖自带的实现对象
    @Resource
    private StreamingChatModel streamingChatModel;

    @Bean
    public AiCodeHelperService aiCodeHelperService(QwenStreamingChatModel qwenStreamingChatModel) {
        // 会话记忆
        // 创建一个会话记忆对象
        // ChatMemory 是单会话实例 而chatMemoryProvider是 多会话实例
        //ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);

        // builder 构造器模式实现接口的对象
        AiCodeHelperService aiCodeHelperService = AiServices.builder(AiCodeHelperService.class)
                .chatModel(MyqwenChatModel)
                .streamingChatModel(qwenStreamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                //.chatMemory(chatMemory) //会话记忆
                //.contentRetriever(jsonlContentRetriever)
                // 调用工具
                .tools(new InterviewQuestionTool())
                .build();

        return aiCodeHelperService;
    }

}
