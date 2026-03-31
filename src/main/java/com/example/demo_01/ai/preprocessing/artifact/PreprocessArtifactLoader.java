package com.example.demo_01.ai.preprocessing.artifact;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PreprocessArtifactLoader {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private ObjectMapper objectMapper;

    public PreprocessArtifact loadManifest(Path storageDir) {
        Path manifestPath = storageDir.resolve("artifact-manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Preprocess artifact manifest not found: " + manifestPath);
        }
        try {
            return objectMapper.readValue(Files.readString(manifestPath), PreprocessArtifact.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read preprocess artifact manifest: " + manifestPath, e);
        }
    }

    public List<RagChunk> loadChunks(Path storageDir) {
        Path jsonlPath = storageDir.resolve("document.jsonl");
        if (!Files.isRegularFile(jsonlPath)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Chunk artifact not found: " + jsonlPath);
        }
        try {
            List<RagChunk> chunks = new ArrayList<>();
            for (String line : Files.readAllLines(jsonlPath)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = objectMapper.readValue(line, MAP_TYPE);
                chunks.add(new RagChunk(
                        UUID.fromString(String.valueOf(row.get("document_id"))),
                        stringValue(row.get("canonical_key")),
                        stringValue(row.get("doi")),
                        stringValue(row.get("chunk_id")),
                        intValue(row.get("chunk_index")),
                        stringValue(row.get("content_type")),
                        stringValue(row.get("section_path")),
                        intValue(row.get("paragraph_index")),
                        intValue(row.get("sentence_start")),
                        intValue(row.get("sentence_end")),
                        stringValue(row.get("title")),
                        stringValue(row.get("text")),
                        stringValue(row.get("source_pdf")),
                        stringValue(row.get("source_tei")),
                        stringValue(row.get("chunk_strategy_version"))
                ));
            }
            return chunks;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load chunk artifact: " + jsonlPath, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
