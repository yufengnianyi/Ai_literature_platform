package com.example.demo_01.ai.evidence.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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
    private TableJsonlMaterializationService materializationService;

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
        return materializationService.loadOrMaterialize(Path.of(sourceTeiPath));
    }
}
