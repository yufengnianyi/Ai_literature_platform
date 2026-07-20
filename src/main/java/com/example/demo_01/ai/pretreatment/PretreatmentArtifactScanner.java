package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactDocument;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.ArtifactScan;
import com.example.demo_01.ai.pretreatment.PretreatmentModels.SkippedArtifact;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class PretreatmentArtifactScanner {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private ObjectMapper objectMapper;

    public ArtifactScan scan(Path artifactRoot, int maxDocuments) {
        if (!Files.isDirectory(artifactRoot)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Artifact root does not exist: " + artifactRoot);
        }
        List<ArtifactDocument> documents = new ArrayList<>();
        List<SkippedArtifact> skipped = new ArrayList<>();
        try (Stream<Path> stream = Files.list(artifactRoot)) {
            List<Path> dirs = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path dir : dirs) {
                if (maxDocuments > 0 && documents.size() >= maxDocuments) {
                    break;
                }
                Path manifestPath = dir.resolve("artifact-manifest.json");
                Path jsonlPath = dir.resolve("document.jsonl");
                UUID documentId = parseUuid(dir.getFileName().toString());
                if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(jsonlPath)) {
                    skipped.add(new SkippedArtifact(documentId, dir.toString(), "Missing artifact-manifest.json or document.jsonl"));
                    continue;
                }
                try {
                    PreprocessArtifact manifest = objectMapper.readValue(Files.readString(manifestPath), PreprocessArtifact.class);
                    documents.add(new ArtifactDocument(manifest.documentId(), dir.toString(), manifest, loadChunks(jsonlPath)));
                } catch (Exception ex) {
                    skipped.add(new SkippedArtifact(documentId, dir.toString(), "Failed to load artifact: " + rootMessage(ex)));
                }
            }
            return new ArtifactScan(documents, skipped);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan artifact root: " + artifactRoot, e);
        }
    }

    private List<RagChunk> loadChunks(Path jsonlPath) throws IOException {
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

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
