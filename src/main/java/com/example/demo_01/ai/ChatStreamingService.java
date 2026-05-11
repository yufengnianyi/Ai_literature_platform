package com.example.demo_01.ai;

import com.example.demo_01.ai.memory.PersistentChatMemoryStore;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.model.dashscope.QwenChatRequestParameters;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatStreamingService {

    private static final int MAX_RAG_SNIPPETS = 5;

    @Resource(name = "qwenStreamingChatModel")
    private StreamingChatModel streamingChatModel;

    @Resource(name = "ragContentRetriever")
    private ContentRetriever ragContentRetriever;

    @Resource
    private PersistentChatMemoryStore persistentChatMemoryStore;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${langchain4j.community.dashscope.streaming-chat-model.thinking-budget:81920}")
    private Integer defaultThinkingBudget;

    public Flux<ServerSentEvent<String>> stream(String memoryKey, String prompt, boolean enableThinking) {
        return Flux.create(sink -> {
            List<Content> retrieved = retrieve(prompt);
            sink.next(ServerSentEvent.<String>builder()
                    .event("sources")
                    .data(toSourcesJson(retrieved))
                    .build());

            List<ChatMessage> history = persistentChatMemoryStore.getMessages(memoryKey);
            List<ChatMessage> requestMessages = new ArrayList<>();
            requestMessages.add(SystemMessage.from(PromptResources.load(PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM)));
            requestMessages.addAll(history);
            requestMessages.add(UserMessage.from(buildGroundedPrompt(prompt, retrieved)));

            StringBuilder answer = new StringBuilder();
            StringBuilder thinking = new StringBuilder();

            QwenChatRequestParameters.Builder parameters = QwenChatRequestParameters.builder()
                    .enableThinking(enableThinking);
            if (enableThinking && defaultThinkingBudget != null && defaultThinkingBudget > 0) {
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
                    if (!enableThinking || partialThinking == null || partialThinking.text() == null
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

    private List<Content> retrieve(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        try {
            List<Content> contents = ragContentRetriever.retrieve(Query.from(prompt));
            if (contents == null || contents.isEmpty()) {
                return List.of();
            }
            return contents.stream().limit(MAX_RAG_SNIPPETS).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildGroundedPrompt(String prompt, List<Content> retrieved) {
        StringBuilder grounded = new StringBuilder();
        grounded.append(prompt == null ? "" : prompt.trim());
        grounded.append("\n\nRetrieved literature snippets:\n");
        if (retrieved == null || retrieved.isEmpty()) {
            grounded.append("No retrieved snippets were found for this query. ");
            grounded.append("If the question requires literature evidence, state that current retrieval evidence is insufficient.");
            return grounded.toString();
        }
        for (int i = 0; i < retrieved.size(); i++) {
            TextSegment segment = retrieved.get(i).textSegment();
            grounded.append("\n[")
                    .append(i + 1)
                    .append("] ")
                    .append(sourceLabel(segment))
                    .append("\n")
                    .append("section=")
                    .append(value(segment, "section_path"))
                    .append("; chunk=")
                    .append(value(segment, "chunk_id"))
                    .append("; page=")
                    .append(value(segment, "page"))
                    .append("\n")
                    .append(segment.text() == null ? "" : segment.text())
                    .append("\n");
        }
        return grounded.toString();
    }

    private String toSourcesJson(List<Content> retrieved) {
        try {
            List<Map<String, String>> sources = new ArrayList<>();
            if (retrieved != null) {
                for (Content content : retrieved) {
                    TextSegment segment = content.textSegment();
                    Map<String, String> source = new LinkedHashMap<>();
                    source.put("title", sourceLabel(segment));
                    putIfPresent(source, "section", value(segment, "section_path"));
                    putIfPresent(source, "chunk", value(segment, "chunk_id"));
                    putIfPresent(source, "page", value(segment, "page"));
                    putIfPresent(source, "excerpt", excerpt(segment.text()));
                    sources.add(source);
                }
            }
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank() && !"-".equals(value)) {
            target.put(key, value);
        }
    }

    private String sourceLabel(TextSegment segment) {
        String title = value(segment, "title");
        if (!"-".equals(title)) {
            return title;
        }
        String fileName = value(segment, "file_name");
        return "-".equals(fileName) ? "retrieved literature" : fileName;
    }

    private String value(TextSegment segment, String key) {
        if (segment == null || segment.metadata() == null) {
            return "-";
        }
        String value = segment.metadata().getString(key);
        return value == null || value.isBlank() ? "-" : value;
    }

    private String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 360 ? normalized : normalized.substring(0, 357) + "...";
    }
}
