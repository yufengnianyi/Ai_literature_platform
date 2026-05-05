package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class QueryAnalyzerService {

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReviewPromptCanonicalizer reviewPromptCanonicalizer;

    public QueryAnalysis analyze(String question) {
        log.info("Analyzing question: {}", truncate(question, 100));
        ChatResponse response = chatModel.chat(
                SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_QUERY_ANALYZER_SYSTEM)),
                UserMessage.from(question)
        );
        AiMessage aiMessage = response.aiMessage();
        String raw = (aiMessage != null && aiMessage.text() != null) ? aiMessage.text() : "{}";
        try {
            QueryAnalysis analysis = objectMapper.readValue(extractJson(raw), QueryAnalysis.class);
            analysis = reviewPromptCanonicalizer.canonicalize(question, analysis);
            log.info("Query analysis complete: {} sub-questions, {} entities, {} concepts",
                    analysis.subQuestions().size(),
                    analysis.keyEntities().size(),
                    analysis.keyConcepts().size());
            return analysis;
        } catch (Exception e) {
            log.warn("Failed to parse query analysis, using fallback: {}", e.getMessage());
            return reviewPromptCanonicalizer.canonicalize(
                    question,
                    new QueryAnalysis(question, List.of(question), List.of(), List.of())
            );
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('{');
            int last = trimmed.lastIndexOf('}');
            if (first >= 0 && last > first) return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
