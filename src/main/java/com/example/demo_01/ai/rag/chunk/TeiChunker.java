package com.example.demo_01.ai.rag.chunk;

import com.example.demo_01.ai.preprocessing.PreprocessingProperties;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import dev.langchain4j.model.TokenCountEstimator;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TeiChunker {

    @Resource
    private PreprocessingProperties properties;

    @Resource
    private TokenCountEstimator tokenCountEstimator;

    public List<RagChunk> chunk(UUID documentId,
                                String canonicalKey,
                                ParsedTeiDocument parsed,
                                Path sourcePdf,
                                Path sourceTei) {
        Map<String, List<ChunkUnit>> grouped = new LinkedHashMap<>();
        for (ChunkUnit unit : parsed.chunkUnits()) {
            String key = unit.contentType() + "||" + unit.sectionPath();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(unit);
        }

        List<RagChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (List<ChunkUnit> units : grouped.values()) {
            chunkIndex = appendGroupChunks(chunks, chunkIndex, documentId, canonicalKey, parsed.metadata(), units, sourcePdf, sourceTei);
        }
        return chunks;
    }

    private int appendGroupChunks(List<RagChunk> out,
                                  int startingIndex,
                                  UUID documentId,
                                  String canonicalKey,
                                  RagDocumentMetadata metadata,
                                  List<ChunkUnit> units,
                                  Path sourcePdf,
                                  Path sourceTei) {
        List<ChunkUnit> buffer = new ArrayList<>();
        int chunkIndex = startingIndex;
        for (ChunkUnit unit : units) {
            for (ChunkUnit candidateUnit : splitOversizedUnit(metadata, unit)) {
                if (buffer.isEmpty()) {
                    buffer.add(candidateUnit);
                    continue;
                }
                int candidateTokens = estimateTokens(metadata, buffer, candidateUnit);
                if (candidateTokens <= properties.getChunking().getTargetTokens()) {
                    buffer.add(candidateUnit);
                    continue;
                }
                int currentTokens = estimateTokens(metadata, buffer);
                if (candidateTokens <= properties.getChunking().getMaxTokens()
                        && currentTokens < properties.getChunking().getTargetTokens() / 2) {
                    buffer.add(candidateUnit);
                    continue;
                }
                chunkIndex = flushChunk(out, chunkIndex, documentId, canonicalKey, metadata, buffer, sourcePdf, sourceTei);
                buffer = seedNextBuffer(metadata, buffer, candidateUnit);
            }
        }
        if (!buffer.isEmpty()) {
            chunkIndex = flushChunk(out, chunkIndex, documentId, canonicalKey, metadata, buffer, sourcePdf, sourceTei);
        }
        return chunkIndex;
    }

    private List<ChunkUnit> seedNextBuffer(RagDocumentMetadata metadata, List<ChunkUnit> previous, ChunkUnit next) {
        List<ChunkUnit> buffer = new ArrayList<>();
        int overlap = properties.getChunking().getOverlapSentences();
        if (overlap > 0 && !previous.isEmpty()) {
            int start = Math.max(0, previous.size() - overlap);
            buffer.addAll(previous.subList(start, previous.size()));
        }
        while (!buffer.isEmpty() && estimateTokens(metadata, buffer, next) > properties.getChunking().getMaxTokens()) {
            buffer.remove(0);
        }
        buffer.add(next);
        return buffer;
    }

    private int flushChunk(List<RagChunk> out,
                           int currentChunkIndex,
                           UUID documentId,
                           String canonicalKey,
                           RagDocumentMetadata metadata,
                           List<ChunkUnit> buffer,
                           Path sourcePdf,
                           Path sourceTei) {
        String text = joinText(buffer);
        if (text.isBlank()) {
            return currentChunkIndex;
        }
        int nextIndex = currentChunkIndex + 1;
        ChunkUnit first = buffer.get(0);
        ChunkUnit last = buffer.get(buffer.size() - 1);
        out.add(new RagChunk(
                documentId,
                canonicalKey,
                metadata.doiNormalized(),
                documentId + ":" + nextIndex,
                nextIndex,
                first.contentType(),
                first.sectionPath(),
                first.paragraphIndex(),
                first.sentenceIndex(),
                last.sentenceIndex(),
                metadata.title(),
                text,
                sourcePdf.toAbsolutePath().toString(),
                sourceTei.toAbsolutePath().toString(),
                properties.getChunking().getStrategyVersion()));
        return nextIndex;
    }

    private List<ChunkUnit> splitOversizedUnit(RagDocumentMetadata metadata, ChunkUnit unit) {
        if (estimateTokens(metadata, List.of(unit)) <= properties.getChunking().getMaxTokens()) {
            return List.of(unit);
        }
        String[] tokens = unit.text().split("\\s+");
        List<ChunkUnit> parts = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            String candidate = builder.isEmpty() ? token : builder + " " + token;
            ChunkUnit candidateUnit = new ChunkUnit(unit.contentType(), unit.sectionPath(), unit.paragraphIndex(), unit.sentenceIndex(), candidate);
            if (!builder.isEmpty()
                    && estimateTokens(metadata, List.of(candidateUnit)) > properties.getChunking().getMaxTokens()) {
                parts.add(new ChunkUnit(unit.contentType(), unit.sectionPath(), unit.paragraphIndex(), unit.sentenceIndex(), builder.toString()));
                builder = new StringBuilder(token);
            } else {
                builder = new StringBuilder(candidate);
            }
        }
        if (!builder.isEmpty()) {
            parts.add(new ChunkUnit(unit.contentType(), unit.sectionPath(), unit.paragraphIndex(), unit.sentenceIndex(), builder.toString()));
        }
        return parts.isEmpty() ? List.of(unit) : parts;
    }

    private int estimateTokens(RagDocumentMetadata metadata, List<ChunkUnit> units, ChunkUnit next) {
        List<ChunkUnit> candidate = new ArrayList<>(units);
        candidate.add(next);
        return estimateTokens(metadata, candidate);
    }

    private int estimateTokens(RagDocumentMetadata metadata, List<ChunkUnit> units) {
        return tokenCountEstimator.estimateTokenCountInText(composeEmbeddingText(metadata.title(), units.get(0).sectionPath(), joinText(units)));
    }

    public String composeEmbeddingText(String title, String sectionPath, String text) {
        StringBuilder builder = new StringBuilder();
        if (title != null && !title.isBlank()) {
            builder.append("Paper: ").append(title).append('\n');
        }
        if (sectionPath != null && !sectionPath.isBlank()) {
            builder.append("Section: ").append(sectionPath).append('\n');
        }
        builder.append(text);
        return builder.toString();
    }

    private String joinText(List<ChunkUnit> units) {
        StringBuilder builder = new StringBuilder();
        for (ChunkUnit unit : units) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(unit.text());
        }
        return builder.toString().trim();
    }

    public String deterministicEmbeddingId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}

