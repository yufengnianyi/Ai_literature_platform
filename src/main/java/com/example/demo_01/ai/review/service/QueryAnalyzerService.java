package com.example.demo_01.ai.review.service;

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

    private static final String SYSTEM_PROMPT = """
            You are a scientific review request normalizer. Given a user's raw prompt,
            recover the underlying scientific review question and decompose it into structured
            components for systematic literature review.
            
            Return JSON only with this exact shape:
            {
              "mainQuestion": "one concise scientific review question",
              "subQuestions": ["sub-question 1", "sub-question 2", ...],
              "keyEntities": ["entity1", "entity2", ...],
              "keyConcepts": ["concept1", "concept2", ...]
            }
            
            Rules:
            - The raw prompt may include formatting instructions, output schema, JSON examples,
              extraction rules, or field definitions. Ignore all of that meta-instruction content.
            - mainQuestion must capture only the underlying scientific objective, not the requested
              output format or extraction schema.
            - subQuestions: 3-5 different angles or aspects of the main question
            - subQuestions must be scientific questions, not field names or reporting instructions
            - keyEntities: specific biological entities (gene names, species, protein families, etc.)
            - keyConcepts: abstract concepts (evolution events, analysis methods, biological processes, etc.)
            - All text should be in the same language as the input question
            - Do not include markdown fences or explanations
            """;

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReviewPromptCanonicalizer reviewPromptCanonicalizer;

    public QueryAnalysis analyze(String question) {
        log.info("Analyzing question: {}", truncate(question, 100));
        ChatResponse response = chatModel.chat(
                SystemMessage.from(SYSTEM_PROMPT),
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
