package com.example.demo_01.ai;

import com.example.demo_01.ai.guardrail.SafeInputGuardrail;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.InputGuardrails;
import reactor.core.publisher.Flux;

import java.util.List;

//@AiService
@InputGuardrails(SafeInputGuardrail.class)
public interface AiCodeHelperService {

    // 设置系统提示词
    @SystemMessage(fromResource = "system-prompt.txt")
    String chat(String userMessage);

    @SystemMessage(fromResource = "system-prompt.txt")
    // 设置会话记忆id 分离不同用户之间的会话记忆
    String chatWithMemory(@MemoryId int id, @UserMessage String userMessage);

    @SystemMessage(fromResource = "system-prompt.txt")
    record Report(String title, List<String> suggestinoList) {};
    // 学习报告
    Report chatForReport(String userMessage);


    // 返回带来源的回答
    @SystemMessage(fromResource = "system-prompt.txt")
    Result<String> chatWithSources(String userMessage);


    // 使用mcp网络检索回答
    @SystemMessage(fromResource = "system-prompt.txt")
    Result<String> chatWithTools(String userMessage);


    @SystemMessage(fromResource = "system-prompt.txt")
    // 使用Flux
    Flux<String> chatWithFlux(@MemoryId int id, @UserMessage String userMessage);

}
