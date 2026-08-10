package com.example.demo_01.ai.evidence.table;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.TableBackfillResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Slf4j
@Service
public class TableJsonlMaterializationService {

    public static final String TABLES_FILE = "tables.jsonl";

    @Resource
    private TeiTableParser teiTableParser;

    @Resource
    private ObjectMapper objectMapper;

    public Path materialize(Path teiPath) {
        if (teiPath == null || !Files.isRegularFile(teiPath)) {
            return null;
        }
        Path tablesPath = teiPath.resolveSibling(TABLES_FILE);
        try {
            String teiXml = Files.readString(teiPath, StandardCharsets.UTF_8);
            List<ParsedTable> tables = teiTableParser.parseAll(teiXml);
            writeJsonl(tablesPath, tables);
            return tablesPath;
        } catch (Exception e) {
            log.warn("Failed to materialize {} from {}: {}", TABLES_FILE, teiPath, e.getMessage());
            return null;
        }
    }

    public List<ParsedTable> loadOrMaterialize(Path teiPath) {
        if (teiPath == null) {
            return List.of();
        }
        Path tablesPath = teiPath.resolveSibling(TABLES_FILE);
        if (Files.isRegularFile(tablesPath)) {
            try {
                return readJsonl(tablesPath);
            } catch (Exception e) {
                log.warn("Failed to read cached {}: {}; re-parsing TEI", tablesPath, e.getMessage());
            }
        }
        Path materialized = materialize(teiPath);
        if (materialized == null || !Files.isRegularFile(materialized)) {
            return List.of();
        }
        try {
            return readJsonl(materialized);
        } catch (Exception e) {
            log.warn("Failed to read materialized {}: {}", materialized, e.getMessage());
            return List.of();
        }
    }

    public TableBackfillResponse backfill(Path artifactRoot, int maxDocuments) {
        if (artifactRoot == null || !Files.isDirectory(artifactRoot)) {
            throw new IllegalArgumentException("Artifact root does not exist: " + artifactRoot);
        }
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger materialized = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (Stream<Path> paths = Files.walk(artifactRoot)) {
            paths.filter(path -> path.getFileName() != null)
                    .filter(path -> "document.tei.xml".equals(path.getFileName().toString()))
                    .limit(maxDocuments <= 0 ? Long.MAX_VALUE : maxDocuments)
                    .forEach(path -> {
                        scanned.incrementAndGet();
                        Path result = materialize(path);
                        if (result == null) {
                            failed.incrementAndGet();
                        } else {
                            materialized.incrementAndGet();
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan artifact root: " + artifactRoot, e);
        }
        return new TableBackfillResponse(scanned.get(), materialized.get(), failed.get());
    }

    private List<ParsedTable> readJsonl(Path tablesPath) throws IOException {
        return Files.readAllLines(tablesPath, StandardCharsets.UTF_8).stream()
                .filter(line -> line != null && !line.isBlank())
                .map(this::readTable)
                .toList();
    }

    private ParsedTable readTable(String line) {
        try {
            return objectMapper.readValue(line, ParsedTable.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse table JSONL", e);
        }
    }

    private void writeJsonl(Path tablesPath, List<ParsedTable> tables) throws IOException {
        Files.createDirectories(tablesPath.getParent());
        StringBuilder builder = new StringBuilder();
        for (ParsedTable table : tables) {
            builder.append(objectMapper.writeValueAsString(table)).append('\n');
        }
        Path temp = Files.createTempFile(tablesPath.getParent(), "tables-", ".tmp");
        try {
            Files.writeString(temp, builder.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, tablesPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicFailure) {
                Files.move(temp, tablesPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
