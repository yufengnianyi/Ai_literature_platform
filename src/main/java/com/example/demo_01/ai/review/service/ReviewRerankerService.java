package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewRerankerService {

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ReviewProperties reviewProperties;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    private final LlmBatchProcessor batchProcessor = new LlmBatchProcessor();

    public List<RetrievedChunk> rerank(String question, List<RetrievedChunk> candidates) {
        ReviewProperties.Rerank cfg = reviewProperties.getRerank();
        if (candidates.isEmpty()) return List.of();

        // (a) Embedding rerank
        Embedding questionEmbedding = quwenEmbeddingModel.embed(question).content();
        List<ScoredChunk> scored = new ArrayList<>();
        for (RetrievedChunk chunk : candidates) {
            Embedding chunkEmbedding = quwenEmbeddingModel.embed(chunk.text()).content();
            double sim = cosineSimilarity(questionEmbedding.vector(), chunkEmbedding.vector());
            scored.add(new ScoredChunk(chunk, sim));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        List<ScoredChunk> topK = scored.subList(0, Math.min(cfg.getTopK(), scored.size()));
        log.info("Embedding rerank: {} → top {} candidates", candidates.size(), topK.size());

        // (b) LLM relevance screening in batches
        List<RetrievedChunk> topChunks = topK.stream().map(ScoredChunk::chunk).toList();
        List<ChunkRelevanceJudgment> judgments = batchProcessor.processInBatches(
                topChunks,
                cfg.getBatchSize(),
                batch -> screenBatch(question, batch),
                reviewTaskExecutor
        );

        Map<String, ChunkRelevanceJudgment> judgmentMap = judgments.stream()
                .collect(Collectors.toMap(ChunkRelevanceJudgment::chunkId, j -> j, (a, b) -> a));

        Relevance minRelevance = Relevance.valueOf(cfg.getMinRelevance());
        List<RetrievedChunk> included = new ArrayList<>();
        for (ScoredChunk sc : topK) {
            ChunkRelevanceJudgment j = judgmentMap.get(sc.chunk().chunkId());
            if (j != null && meetsThreshold(j.relevance(), minRelevance)) {
                included.add(new RetrievedChunk(
                        sc.chunk().chunkId(), sc.chunk().documentId(), sc.chunk().documentTitle(),
                        sc.chunk().text(), sc.chunk().sectionPath(), sc.score(), sc.chunk().source()));
            }
        }
        log.info("LLM screening: {} judged, {} included (threshold={})",
                judgments.size(), included.size(), minRelevance);
        return included;
    }

    public Map<String, ChunkRelevanceJudgment> getJudgmentMap(String question, List<RetrievedChunk> chunks) {
        List<ChunkRelevanceJudgment> judgments = batchProcessor.processInBatches(
                chunks,
                reviewProperties.getRerank().getBatchSize(),
                batch -> screenBatch(question, batch),
                reviewTaskExecutor
        );
        return judgments.stream()
                .collect(Collectors.toMap(ChunkRelevanceJudgment::chunkId, j -> j, (a, b) -> a));
    }

    private List<ChunkRelevanceJudgment> screenBatch(String question, List<RetrievedChunk> batch) {
        StringBuilder userMsg = new StringBuilder();
        StringBuilder chunksText = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            RetrievedChunk c = batch.get(i);
            chunksText.append(PromptResources.format(
                    PromptCatalog.REVIEW_RERANKER_CHUNK,
                    i + 1,
                    c.chunkId(),
                    safe(c.documentTitle()),
                    truncate(c.text(), 1500)));
        }
        userMsg.append(PromptResources.format(PromptCatalog.REVIEW_RERANKER_USER, question, chunksText));

        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_RERANKER_SYSTEM)),
                    UserMessage.from(userMsg.toString())
            );
            AiMessage ai = response.aiMessage();
            String raw = (ai != null && ai.text() != null) ? ai.text() : "[]";
            return objectMapper.readValue(extractJson(raw),
                    new TypeReference<List<ChunkRelevanceJudgment>>() {});
        } catch (Exception e) {
            log.warn("LLM screening batch failed: {}", e.getMessage());
            return batch.stream()
                    .map(c -> new ChunkRelevanceJudgment(c.chunkId(), Relevance.MEDIUM, "fallback"))
                    .toList();
        }
    }

    private boolean meetsThreshold(Relevance actual, Relevance minimum) {
        if (actual == null) return false;
        return actual.ordinal() <= minimum.ordinal();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
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

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private record ScoredChunk(RetrievedChunk chunk, double score) {}
}
