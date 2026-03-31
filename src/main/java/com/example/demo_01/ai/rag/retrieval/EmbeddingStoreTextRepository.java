package com.example.demo_01.ai.rag.retrieval;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Repository
public class EmbeddingStoreTextRepository {

    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final AiPersistenceProperties properties;
    private final ObjectMapper objectMapper;

    public EmbeddingStoreTextRepository(JdbcTemplate jdbcTemplate,
                                        AiPersistenceProperties properties,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public long countRows() {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + vectorTable(), Long.class);
        return count == null ? 0L : count;
    }

    public List<Bm25IndexEntry> fetchAll() {
        return jdbcTemplate.query("""
                select embedding_id::text as embedding_id,
                       coalesce(text, '') as text,
                       metadata::text as metadata_json
                from %s
                """.formatted(vectorTable()), (rs, rowNum) -> {
            Metadata metadata = readMetadata(rs.getString("metadata_json"));
            return new Bm25IndexEntry(
                    rs.getString("embedding_id"),
                    metadata.getString("document_id"),
                    metadata.getString("chunk_id"),
                    metadata.getString("title"),
                    metadata.getString("section_path"),
                    rs.getString("text"),
                    metadata
            );
        });
    }

    private Metadata readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new Metadata();
        }
        try {
            return new Metadata(objectMapper.readValue(metadataJson, MAP_TYPE));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse embedding_store.metadata", e);
        }
    }

    private String vectorTable() {
        String table = properties.getRag().getVectorTable();
        if (!SAFE_SQL_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid vector table name: " + table);
        }
        return table;
    }
}
