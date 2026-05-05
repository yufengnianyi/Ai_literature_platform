package com.example.demo_01.ai;

import com.example.demo_01.ai.guardrail.SafeInputGuardrail;
import com.example.demo_01.ai.prompt.PromptCatalog;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.InputGuardrails;
import reactor.core.publisher.Flux;

import java.util.List;

@InputGuardrails(SafeInputGuardrail.class)
public interface AiCodeHelperService {

    @SystemMessage(fromResource = PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)
    String chat(String userMessage);

    @SystemMessage(fromResource = PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)
    String chatWithMemory(@MemoryId String conversationId, @UserMessage String userMessage);

    @SystemMessage(fromResource = PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)
    record Report(String title, List<String> suggestinoList) {
    }

    @SystemMessage(fromResource = PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)
    Report chatForReport(String userMessage);

    @SystemMessage(fromResource = PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)
    Result<String> chatWithSources(String userMessage);

    @SystemMessage(fromResource = PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)
    Result<String> chatWithTools(String userMessage);

    @SystemMessage(fromResource = PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)
    Flux<String> chatWithFlux(@MemoryId String conversationId, @UserMessage String userMessage);
}
