package com.example.demo_01.ai.evidence.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily materializes parsed tables into a {@code tables.jsonl} sidecar next to the document's
 * {@code document.tei.xml}, and serves them on demand.
 *
 * <p>First access for a document parses the TEI, writes {@code tables.jsonl} (one
 * {@link ParsedTable} per line), and caches the result in memory. Subsequent accesses read the
 * cached / on-disk artifact. The sidecar is auditable and lets human reviewers inspect exactly
 * what table text the extractor saw, without re-embedding anything into the vector store.</p>
 */
@Slf4j
@Component
public class TableJsonlStore {

    static final String TABLES_FILE = "tables.jsonl";

    @Resource
    private TeiTableParser teiTableParser;

    @Resource
    private ObjectMapper objectMapper;

    private final Map<String, List<ParsedTable>> cache = new ConcurrentHashMap<>();

    /**
     * Load all tables for the document whose TEI lives at {@code sourceTeiPath}.
     * Returns an empty list when the TEI path is missing or unreadable.
     */
    public List<ParsedTable> load(String sourceTeiPath) {
        if (sourceTeiPath == null || sourceTeiPath.isBlank()) {
            return List.of();
        }
        return cache.computeIfAbsent(sourceTeiPath, this::loadFromDisk);
    }

    private List<ParsedTable> loadFromDisk(String sourceTeiPath) {
        Path teiPath = Path.of(sourceTeiPath);
        Path tablesPath = teiPath.resolveSibling(TABLES_FILE);
        try {
            if (Files.isRegularFile(tablesPath)) {
                return readJsonl(tablesPath);
            }
        } catch (Exception e) {
            log.warn("Failed to read cached {}: {}; re-parsing TEI", tablesPath, e.getMessage());
        }
        if (!Files.isRegularFile(teiPath)) {
            log.warn("TEI file not found for table extraction: {}", teiPath);
            return List.of();
        }
        try {
            String teiXml = Files.readString(teiPath, StandardCharsets.UTF_8);
            List<ParsedTable> tables = teiTableParser.parseAll(teiXml);
            writeJsonl(tablesPath, tables);
            return tables;
        } catch (Exception e) {
            log.warn("Failed to parse tables from {}: {}", teiPath, e.getMessage());
            return List.of();
        }
    }

    private List<ParsedTable> readJsonl(Path tablesPath) throws IOException {
        List<ParsedTable> tables = new ArrayList<>();
        for (String line : Files.readAllLines(tablesPath, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) {
                continue;
            }
            tables.add(objectMapper.readValue(line, ParsedTable.class));
        }
        return List.copyOf(tables);
    }

    private void writeJsonl(Path tablesPath, List<ParsedTable> tables) {
        try {
            Files.createDirectories(tablesPath.getParent());
            StringBuilder builder = new StringBuilder();
            for (ParsedTable table : tables) {
                builder.append(objectMapper.writeValueAsString(table)).append('\n');
            }
            Path temp = Files.createTempFile(tablesPath.getParent(), "tables-", ".tmp");
            Files.writeString(temp, builder.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, tablesPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicFailure) {
                Files.move(temp, tablesPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Failed to materialize {}: {}", tablesPath, e.getMessage());
        }
    }
}
