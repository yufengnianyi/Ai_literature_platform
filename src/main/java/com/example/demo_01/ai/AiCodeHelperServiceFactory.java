package com.example.demo_01.ai;

import com.example.demo_01.ai.memory.PersistentChatMemoryStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiCodeHelperServiceFactory {

    @Resource
    private ChatModel myqwenChatModel;

    @Resource(name = "ragContentRetriever")
    private ContentRetriever ragContentRetriever;

    @Resource(name = "qwenStreamingChatModel")
    private StreamingChatModel streamingChatModel;

    @Resource
    private PersistentChatMemoryStore persistentChatMemoryStore;

    @Bean
    public AiCodeHelperService aiCodeHelperService() {
        return AiServices.builder(AiCodeHelperService.class)
                .chatModel(myqwenChatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(persistentChatMemoryStore)
                        .build())
                .contentRetriever(ragContentRetriever)
                .build();
    }
}
