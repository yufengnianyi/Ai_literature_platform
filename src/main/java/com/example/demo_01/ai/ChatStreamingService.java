package com.example.demo_01.ai;

import com.example.demo_01.ai.memory.PersistentChatMemoryStore;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatStreamingService {

    @Resource(name = "qwenStreamingChatModel")
    private StreamingChatModel streamingChatModel;

    @Resource
    private PersistentChatMemoryStore persistentChatMemoryStore;

    @Value("${langchain4j.community.dashscope.streaming-chat-model.thinking-budget:81920}")
    private Integer defaultThinkingBudget;

    public Flux<ServerSentEvent<String>> stream(String memoryKey, String prompt, boolean deepThinking) {
        return Flux.create(sink -> {
            List<ChatMessage> history = persistentChatMemoryStore.getMessages(memoryKey);
            List<ChatMessage> requestMessages = new ArrayList<>();
            requestMessages.add(SystemMessage.from(PromptResources.load(PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)));
            requestMessages.addAll(history);
            requestMessages.add(UserMessage.from(prompt));

            StringBuilder answer = new StringBuilder();
            StringBuilder thinking = new StringBuilder();

            QwenChatRequestParameters.Builder parameters = QwenChatRequestParameters.builder()
                    .enableThinking(deepThinking);
            if (deepThinking && defaultThinkingBudget != null && defaultThinkingBudget > 0) {
                parameters.thinkingBudget(defaultThinkingBudget);
            }

            ChatRequest request = ChatRequest.builder()
                    .messages(requestMessages)
                    .parameters(parameters.build())
                    .build();

            streamingChatModel.chat(request, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (partialResponse == null || partialResponse.isEmpty()) {
                        return;
                    }
                    answer.append(partialResponse);
                    sink.next(ServerSentEvent.<String>builder()
                            .event("message")
                            .data(partialResponse)
                            .build());
                }

                @Override
                public void onPartialThinking(PartialThinking partialThinking) {
                    if (!deepThinking || partialThinking == null || partialThinking.text() == null
                            || partialThinking.text().isEmpty()) {
                        return;
                    }
                    thinking.append(partialThinking.text());
                    sink.next(ServerSentEvent.<String>builder()
                            .event("thinking")
                            .data(partialThinking.text())
                            .build());
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    String finalAnswer = answer.toString();
                    if ((finalAnswer == null || finalAnswer.isBlank())
                            && completeResponse != null
                            && completeResponse.aiMessage() != null
                            && completeResponse.aiMessage().text() != null) {
                        finalAnswer = completeResponse.aiMessage().text();
                    }
                    List<ChatMessage> updatedMessages = new ArrayList<>(history);
                    updatedMessages.add(UserMessage.from(prompt));
                    AiMessage aiMessage = AiMessage.builder()
                            .text(finalAnswer == null ? "" : finalAnswer)
                            .thinking(thinking.isEmpty() ? null : thinking.toString())
                            .build();
                    updatedMessages.add(aiMessage);
                    persistentChatMemoryStore.updateMessages(memoryKey, updatedMessages);
                    sink.next(ServerSentEvent.<String>builder().event("complete").build());
                    sink.complete();
                }

                @Override
                public void onError(Throwable error) {
                    sink.error(error);
                }
            });
        });
    }
}
