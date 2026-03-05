package com.example.demo_01;

import com.example.demo_01.ai.AiCodeHelper;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class AiChatTest {

    @Resource
    private AiCodeHelper aiCodeHelper;

    @Test
    public void test() {
        String s = aiCodeHelper.chat("请给我介绍一下RLK进化相关的知识");
        System.out.println(s);
    }

    @Test
    public void test2() {
        UserMessage userMessage = UserMessage.from(
                TextContent.from("描述这个图片"),
                ImageContent.from("src/main/resources/static/img/image.png")
        );
        aiCodeHelper.chatMessage(userMessage);
    }
}
