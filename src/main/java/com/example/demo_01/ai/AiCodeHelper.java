package com.example.demo_01.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AiCodeHelper类
 * 这是一个智能编程助手类的定义，用于提供编程相关的帮助功能
 */

@Slf4j
@Service
public class AiCodeHelper {

    @Resource
    private ChatModel qwenChatModel;

    private static final String SYSTEM_MESSAGE = """
            """;

    /**
     * chat方法
     * 这是一个用于与AI进行对话的方法，接收一个字符串参数message，返回一个字符串
     * @param message
     * @return
     */
    public String chat(String message) {
        SystemMessage systemMessage = SystemMessage.from(SYSTEM_MESSAGE);
        UserMessage userMessage = UserMessage.from(message);
        ChatResponse chatResponse = qwenChatModel.chat(systemMessage,userMessage);
        AiMessage aiMessage = chatResponse.aiMessage();
        log.info("AI回复：" + aiMessage.toString());
        return aiMessage.text();
    }


    // LangChain4j 提供的message
    public String chatMessage(UserMessage message) {
        ChatResponse chatResponse = qwenChatModel.chat(message);
        AiMessage aiMessage = chatResponse.aiMessage();
        log.info("AI回复：" + aiMessage.toString());
        return aiMessage.text();
    }
}
