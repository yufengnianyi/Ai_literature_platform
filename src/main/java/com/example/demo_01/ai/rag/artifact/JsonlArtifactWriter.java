package com.example.demo_01.ai.rag.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JsonlArtifactWriter {

    @Resource
    private ObjectMapper objectMapper;

    public Path write(Path jsonlPath, List<RagChunk> chunks) {
        try {
            Files.createDirectories(jsonlPath.getParent());
            StringBuilder builder = new StringBuilder();
            for (RagChunk chunk : chunks) {
                builder.append(toJson(chunk)).append('\n');
            }
            Files.writeString(jsonlPath, builder.toString(), StandardCharsets.UTF_8);
            return jsonlPath;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write JSONL artifact: " + jsonlPath, e);
        }
    }

    private String toJson(RagChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_id", chunk.documentId().toString());
        payload.put("canonical_key", chunk.canonicalKey());
        payload.put("doi", chunk.doi());
        payload.put("chunk_id", chunk.chunkId());
        payload.put("chunk_index", chunk.chunkIndex());
        payload.put("content_type", chunk.contentType());
        payload.put("section_path", chunk.sectionPath());
        payload.put("paragraph_index", chunk.paragraphIndex());
        payload.put("sentence_start", chunk.sentenceStart());
        payload.put("sentence_end", chunk.sentenceEnd());
        payload.put("title", chunk.title());
        payload.put("text", chunk.text());
        payload.put("source_pdf", chunk.sourcePdf());
        payload.put("source_tei", chunk.sourceTei());
        payload.put("chunk_strategy_version", chunk.chunkStrategyVersion());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chunk payload", e);
        }
    }
}
