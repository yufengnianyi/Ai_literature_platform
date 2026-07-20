package com.example.demo_01.ai;

import com.example.demo_01.ai.rag.RagChatProperties;
import com.example.demo_01.ai.rag.evaluation.service.QwenRerankClient;
import com.example.demo_01.ai.rag.evaluation.service.QwenRerankClient.RerankDocument;
import com.example.demo_01.ai.rag.evaluation.service.QwenRerankClient.RerankResult;
import com.example.demo_01.ai.rag.evaluation.service.QwenRerankClient.RerankScore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs hybrid retrieval for the interactive chat endpoint and turns the retrieved
 * chunks into (1) a context block injected into the LLM prompt and (2) a JSON
 * {@code sources} payload consumed by the frontend.
 */
@Service
public class ChatRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(ChatRetrievalService.class);

    private static final String EMPTY_SOURCES_JSON = "[]";

    @Resource(name = "ragContentRetriever")
    private ContentRetriever ragContentRetriever;

    @Resource
    private RagChatProperties ragChatProperties;

    @Resource
    private QwenRerankClient qwenRerankClient;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * @param contextBlock human-readable numbered context injected into the prompt
     * @param sourcesJson  JSON array serialized for the frontend {@code sources} event
     * @param hasContext   whether any usable context was retrieved
     */
    public record RetrievedContext(String contextBlock, String sourcesJson, boolean hasContext) {
    }

    public RetrievedContext retrieve(String prompt) {
        if (!ragChatProperties.isEnabled() || prompt == null || prompt.isBlank()) {
            return empty();
        }

        List<Content> contents;
        try {
            contents = ragContentRetriever.retrieve(Query.from(prompt.trim()));
        } catch (Exception e) {
            log.warn("Chat RAG retrieval failed, falling back to plain chat: {}", e.getMessage());
            return empty();
        }

        if (contents == null || contents.isEmpty()) {
            return empty();
        }

        contents = rerank(prompt.trim(), contents);

        int limit = Math.min(ragChatProperties.getMaxContextChunks(), contents.size());
        StringBuilder contextBlock = new StringBuilder();
        List<Map<String, String>> sources = new ArrayList<>();
        int index = 0;

        for (Content content : contents) {
            if (index >= limit || contextBlock.length() >= ragChatProperties.getMaxContextChars()) {
                break;
            }
            TextSegment segment = content.textSegment();
            if (segment == null) {
                continue;
            }
            index++;

            String title = metadataOrDefault(segment, "title", "未知文献");
            String section = metadataOrDefault(segment, "section_path", null);
            String chunkId = metadataOrDefault(segment, "chunk_id", null);
            String excerpt = truncate(segment.text(), ragChatProperties.getMaxExcerptChars());

            appendContextEntry(contextBlock, index, title, section, excerpt);
            sources.add(buildSource(title, section, chunkId, excerpt));
        }

        if (sources.isEmpty()) {
            return empty();
        }

        return new RetrievedContext(contextBlock.toString(), toJson(sources), true);
    }

    /**
     * Reorders the fused candidates with the Qwen cross-encoder rerank model and
     * drops chunks below the configured relevance threshold. Falls back to the
     * original fused order when rerank is disabled, unusable, or filters everything.
     */
    private List<Content> rerank(String query, List<Content> candidates) {
        RagChatProperties.Rerank cfg = ragChatProperties.getRerank();
        if (!cfg.isEnabled() || candidates.size() <= 1) {
            return candidates;
        }

        List<RerankDocument> documents = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            TextSegment segment = candidates.get(i).textSegment();
            String text = segment == null ? "" : truncate(segment.text(), cfg.getInputMaxChars());
            documents.add(new RerankDocument(String.valueOf(i), text));
        }

        Map<String, Double> scoreByIndex;
        try {
            RerankResult result = qwenRerankClient.rerank(query, documents, cfg.getModel());
            scoreByIndex = new LinkedHashMap<>();
            for (RerankScore score : result.scores()) {
                scoreByIndex.put(score.id(), score.score());
            }
        } catch (Exception e) {
            log.warn("Chat RAG rerank failed, keeping fused order: {}", e.getMessage());
            return candidates;
        }

        if (scoreByIndex.isEmpty()) {
            return candidates;
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(
                (Integer i) -> scoreByIndex.getOrDefault(String.valueOf(i), 0.0)).reversed());

        List<Content> reranked = new ArrayList<>();
        for (Integer i : order) {
            if (scoreByIndex.getOrDefault(String.valueOf(i), 0.0) >= cfg.getMinScore()) {
                reranked.add(candidates.get(i));
            }
        }

        return reranked.isEmpty() ? candidates : reranked;
    }

    private void appendContextEntry(StringBuilder builder, int index, String title, String section, String excerpt) {
        builder.append('[').append(index).append("] 标题: ").append(title);
        if (section != null) {
            builder.append(" | 章节: ").append(section);
        }
        builder.append('\n').append(excerpt).append("\n\n");
    }

    private Map<String, String> buildSource(String title, String section, String chunkId, String excerpt) {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("title", title);
        if (section != null) {
            source.put("section", section);
        }
        if (chunkId != null) {
            source.put("chunk", chunkId);
        }
        if (excerpt != null && !excerpt.isBlank()) {
            source.put("excerpt", excerpt);
        }
        return source;
    }

    private String metadataOrDefault(TextSegment segment, String key, String defaultValue) {
        if (segment.metadata() == null) {
            return defaultValue;
        }
        String value = segment.metadata().getString(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 1)).strip() + "…";
    }

    private String toJson(List<Map<String, String>> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize chat RAG sources: {}", e.getMessage());
            return EMPTY_SOURCES_JSON;
        }
    }

    private RetrievedContext empty() {
        return new RetrievedContext("", EMPTY_SOURCES_JSON, false);
    }
}
