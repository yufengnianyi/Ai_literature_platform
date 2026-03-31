package com.example.demo_01.ai.rag.service;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.rag.chunk.TeiChunker;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexEntry;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class RagVectorIngestionService {

    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final int EMBEDDING_BATCH_SIZE = 16;

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private TokenCountEstimator tokenCountEstimator;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private AiPersistenceProperties properties;

    @Resource
    private TeiChunker teiChunker;

    @Resource
    private Bm25IndexService bm25IndexService;

    @Value("${langchain4j.community.dashscope.embedding-model.model-name}")
    private String embeddingModelName;

    public RagVectorIngestionResult ingestChunks(List<RagChunk> chunks) {
        List<TextSegment> segments = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        long estimatedTokensTotal = 0L;
        for (RagChunk chunk : chunks) {
            Metadata metadata = new Metadata()
                    .put("document_id", chunk.documentId().toString())
                    .put("canonical_key", chunk.canonicalKey())
                    .put("chunk_id", chunk.chunkId())
                    .put("chunk_index", chunk.chunkIndex())
                    .put("content_type", chunk.contentType())
                    .put("section_path", chunk.sectionPath())
                    .put("paragraph_index", chunk.paragraphIndex())
                    .put("sentence_start", chunk.sentenceStart())
                    .put("sentence_end", chunk.sentenceEnd())
                    .put("title", chunk.title() == null ? "" : chunk.title())
                    .put("source_pdf", chunk.sourcePdf())
                    .put("source_tei", chunk.sourceTei())
                    .put("chunk_strategy_version", chunk.chunkStrategyVersion());
            if (chunk.doi() != null) {
                metadata.put("doi", chunk.doi());
            }
            String embeddingText = teiChunker.composeEmbeddingText(chunk.title(), chunk.sectionPath(), chunk.text());
            segments.add(TextSegment.from(embeddingText, metadata));
            ids.add(teiChunker.deterministicEmbeddingId(chunk.chunkId()));
            estimatedTokensTotal += safeEstimateTokenCount(embeddingText);
        }
        return ingestSegments(segments, ids, estimatedTokensTotal);
    }

    public RagVectorIngestionResult ingestDocuments(List<Document> documents) {
        List<TextSegment> segments = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        long estimatedTokensTotal = 0L;
        int index = 0;
        for (Document document : documents) {
            Metadata metadata = document.metadata() == null ? new Metadata() : document.metadata().copy();
            String title = metadata.getString("title");
            String section = metadata.getString("section");
            String embeddingText = teiChunker.composeEmbeddingText(title, section, document.text());
            segments.add(TextSegment.from(embeddingText, metadata));
            String key = firstNonBlank(metadata.getString("document_id"), metadata.getString("paper_id"), metadata.getString("file_name"), "legacy")
                    + ":" + firstNonBlank(metadata.getString("chunk_id"), String.valueOf(index));
            ids.add(teiChunker.deterministicEmbeddingId(key));
            estimatedTokensTotal += safeEstimateTokenCount(embeddingText);
            index++;
        }
        return ingestSegments(segments, ids, estimatedTokensTotal);
    }

    public void removeDocument(UUID documentId) {
        jdbcTemplate.update("delete from " + vectorTable() + " where metadata ->> 'document_id' = ?", documentId.toString());
        bm25IndexService.removeByDocumentId(documentId.toString());
    }

    private RagVectorIngestionResult ingestSegments(List<TextSegment> segments, List<String> ids, long estimatedTokensTotal) {
        long embedMs = 0L;
        long persistMs = 0L;
        long providerTokensTotal = 0L;

        for (int start = 0; start < segments.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, segments.size());
            List<TextSegment> batchSegments = segments.subList(start, end);
            List<String> batchIds = ids.subList(start, end);

            Response<List<Embedding>> response;
            Instant embedStart = Instant.now();
            try {
                response = quwenEmbeddingModel.embedAll(batchSegments);
            } catch (Exception ex) {
                String message = "EMBEDDING_ERROR model=" + embeddingModelName
                        + ", range=" + start + "-" + (end - 1)
                        + ", reason=" + rootMessage(ex);
                throw new IllegalStateException(message, ex);
            }
            embedMs += Duration.between(embedStart, Instant.now()).toMillis();
            providerTokensTotal += tokenCount(response.tokenUsage());

            Instant persistStart = Instant.now();
            embeddingStore.addAll(batchIds, response.content(), batchSegments);
            bm25IndexService.index(toBm25IndexEntries(batchIds, batchSegments));
            persistMs += Duration.between(persistStart, Instant.now()).toMillis();
        }
        return new RagVectorIngestionResult(segments.size(), estimatedTokensTotal, providerTokensTotal, embedMs, persistMs);
    }

    private long tokenCount(TokenUsage tokenUsage) {
        if (tokenUsage == null || tokenUsage.totalTokenCount() == null) {
            return 0L;
        }
        return tokenUsage.totalTokenCount();
    }

    private String vectorTable() {
        String table = properties.getRag().getVectorTable();
        if (!SAFE_SQL_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid vector table name: " + table);
        }
        return table;
    }

    private long safeEstimateTokenCount(String text) {
        try {
            return tokenCountEstimator.estimateTokenCountInText(text);
        } catch (Exception ex) {
            // Keep ingestion moving; estimated_tokens_total is advisory only.
            return Math.max(1, text == null ? 0 : (int) Math.ceil(text.length() / 4.0));
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private List<Bm25IndexEntry> toBm25IndexEntries(List<String> ids, List<TextSegment> segments) {
        List<Bm25IndexEntry> entries = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Metadata metadata = segment.metadata() == null ? new Metadata() : segment.metadata().copy();
            entries.add(new Bm25IndexEntry(
                    ids.get(i),
                    metadata.getString("document_id"),
                    metadata.getString("chunk_id"),
                    metadata.getString("title"),
                    metadata.getString("section_path"),
                    segment.text(),
                    metadata
            ));
        }
        return entries;
    }
}
