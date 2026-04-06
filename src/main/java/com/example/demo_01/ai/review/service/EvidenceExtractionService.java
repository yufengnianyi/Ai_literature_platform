package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class EvidenceExtractionService {

    private static final String SYSTEM_PROMPT = """
            You are a scientific evidence extractor. Given a research question and literature chunks,
            extract structured evidence from each chunk that is relevant to the question.
            
            Return JSON only as an array:
            [{
              "chunkId": "...",
              "documentId": "...",
              "documentTitle": "...",
              "claim": "the key claim or assertion",
              "finding": "specific finding or result",
              "methodology": "method used (if mentioned)",
              "entities": ["entity1", "entity2"],
              "evidenceType": "EXPERIMENTAL|COMPUTATIONAL|REVIEW",
              "confidence": 0.0-1.0,
              "originalText": "verbatim quote from chunk supporting the claim",
              "subQuestion": "the most relevant sub-question this evidence addresses"
            }]
            
            Rules:
            - Extract ALL relevant evidence items from each chunk (may be 0 or multiple per chunk)
            - originalText must be copied verbatim from the chunk
            - confidence reflects how strong/clear the evidence is
            - Match each evidence to the most relevant sub-question
            - If a chunk contains no relevant evidence, skip it (do not include it)
            - Do not include markdown fences or explanations
            """;

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReviewProperties reviewProperties;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    private final LlmBatchProcessor batchProcessor = new LlmBatchProcessor();

    public List<ExtractedEvidence> extract(String mainQuestion,
                                           List<String> subQuestions,
                                           List<RetrievedChunk> chunks) {
        log.info("Extracting evidence from {} chunks", chunks.size());
        List<ExtractedEvidence> allEvidence = batchProcessor.processInBatches(
                chunks,
                reviewProperties.getExtraction().getBatchSize(),
                batch -> extractBatch(mainQuestion, subQuestions, batch),
                reviewTaskExecutor
        );
        log.info("Evidence extraction complete: {} evidence items from {} chunks",
                allEvidence.size(), chunks.size());
        return allEvidence;
    }

    private List<ExtractedEvidence> extractBatch(String mainQuestion,
                                                  List<String> subQuestions,
                                                  List<RetrievedChunk> batch) {
        StringBuilder userMsg = new StringBuilder();
        userMsg.append("Main question: ").append(mainQuestion).append("\n\n");
        userMsg.append("Sub-questions:\n");
        for (int i = 0; i < subQuestions.size(); i++) {
            userMsg.append(i + 1).append(". ").append(subQuestions.get(i)).append("\n");
        }
        userMsg.append("\nLiterature chunks:\n\n");
        for (int i = 0; i < batch.size(); i++) {
            RetrievedChunk c = batch.get(i);
            userMsg.append("--- Chunk ").append(i + 1)
                    .append(" [id=").append(c.chunkId())
                    .append(", doc_id=").append(c.documentId())
                    .append(", source=").append(safe(c.documentTitle()))
                    .append("] ---\n")
                    .append(c.text())
                    .append("\n\n");
        }

        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(userMsg.toString())
            );
            AiMessage ai = response.aiMessage();
            String raw = (ai != null && ai.text() != null) ? ai.text() : "[]";
            return objectMapper.readValue(extractJson(raw),
                    new TypeReference<List<ExtractedEvidence>>() {});
        } catch (Exception e) {
            log.warn("Evidence extraction batch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "[]";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('[');
            int last = trimmed.lastIndexOf(']');
            if (first >= 0 && last > first) return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private String safe(String s) { return s == null ? "" : s; }
}
