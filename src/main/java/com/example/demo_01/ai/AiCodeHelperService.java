package com.example.demo_01.ai;

import com.example.demo_01.ai.guardrail.SafeInputGuardrail;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.InputGuardrails;
import reactor.core.publisher.Flux;

import java.util.List;

@InputGuardrails(SafeInputGuardrail.class)
public interface AiCodeHelperService {

    @SystemMessage(fromResource = "system-prompt.txt")
    String chat(String userMessage);

    @SystemMessage(fromResource = "system-prompt.txt")
    String chatWithMemory(@MemoryId String conversationId, @UserMessage String userMessage);

    @SystemMessage(fromResource = "system-prompt.txt")
    record Report(String title, List<String> suggestinoList) {
    }

    @SystemMessage(fromResource = "system-prompt.txt")
    Report chatForReport(String userMessage);

    @SystemMessage(fromResource = "system-prompt.txt")
    Result<String> chatWithSources(String userMessage);

    @SystemMessage(fromResource = "system-prompt.txt")
    Result<String> chatWithTools(String userMessage);

    @SystemMessage(fromResource = "system-prompt.txt")
    Flux<String> chatWithFlux(@MemoryId String conversationId, @UserMessage String userMessage);
}