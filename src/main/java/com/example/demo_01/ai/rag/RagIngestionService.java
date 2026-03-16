package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.config.RagBootstrapMode;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.IngestionResult;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);
    private static final Pattern SAFE_SQL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final String DATASET_KEY = "jsonl-docs";

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private JsonlLoader jsonlLoader;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private AiPersistenceProperties properties;

    public void bootstrapEmbeddings() {
        ingest(properties.getRag().getBootstrapMode());
    }

    public void ingest(RagBootstrapMode mode) {
        RagBootstrapMode effectiveMode = mode == null ? RagBootstrapMode.REBUILD : mode;
        if (effectiveMode == RagBootstrapMode.SKIP) {
            log.info("Skipping RAG ingestion because mode is SKIP");
            return;
        }

        Path docsPath = Path.of(properties.getRag().getDocsPath());
        String datasetHash = calculateDatasetHash(docsPath);

        if (effectiveMode == RagBootstrapMode.REBUILD) {
            rebuild(datasetHash);
            return;
        }

        long rowCount = countEmbeddings();
        if (rowCount == 0L) {
            ingestDocuments(datasetHash);
            return;
        }

        String existingHash = currentDatasetHash();
        if (existingHash == null) {
            log.info("Embedding table already contains data, recording dataset hash without re-ingesting");
            updateDatasetHash(datasetHash);
            return;
        }

        if (!Objects.equals(existingHash, datasetHash)) {
            log.warn("Embedding dataset changed but mode is IF_EMPTY. Use REBUILD to refresh persisted embeddings.");
            return;
        }

        log.info("Skipping RAG ingestion because persisted embeddings are already up to date");
    }

    public RagIngestionStatus status() {
        long rowCount = countEmbeddings();
        IngestionStateRow state = currentDatasetState();
        if (state == null) {
            return new RagIngestionStatus(rowCount, null, null);
        }
        return new RagIngestionStatus(rowCount, state.datasetHash(), state.updatedAt());
    }

    private void rebuild(String datasetHash) {
        log.info("Rebuilding persisted embeddings in table {}", properties.getRag().getVectorTable());
        jdbcTemplate.execute("truncate table " + vectorTable());
        jdbcTemplate.update("""
                delete from rag_ingestion_state
                where dataset_key = ?
                """, DATASET_KEY);
        ingestDocuments(datasetHash);
    }

    private void ingestDocuments(String datasetHash) {
        Path docsPath = Path.of(properties.getRag().getDocsPath());
        List<Document> documents = jsonlLoader.loadDirectory(docsPath);
        if (documents.isEmpty()) {
            log.warn("No JSONL documents found under {}", docsPath);
            return;
        }

        DocumentByParagraphSplitter splitter = new DocumentByParagraphSplitter(1200, 200);
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .textSegmentTransformer(segment -> {
                    String title = segment.metadata().getString("title");
                    String section = segment.metadata().getString("section");
                    StringBuilder builder = new StringBuilder();
                    if (title != null && !title.isBlank()) {
                        builder.append("Paper: ").append(title).append('\n');
                    }
                    if (section != null && !section.isBlank()) {
                        builder.append("Section: ").append(section).append('\n');
                    }
                    builder.append(segment.text());
                    return TextSegment.from(builder.toString(), segment.metadata());
                })
                .embeddingModel(quwenEmbeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        IngestionResult result = ingestor.ingest(documents);
        updateDatasetHash(datasetHash);
        log.info("Persisted {} JSONL chunks to pgvector. Token usage: {}", documents.size(), result.tokenUsage());
    }

    private long countEmbeddings() {
        Long rowCount = jdbcTemplate.queryForObject("select count(*) from " + vectorTable(), Long.class);
        return rowCount == null ? 0L : rowCount;
    }

    private String currentDatasetHash() {
        IngestionStateRow row = currentDatasetState();
        return row == null ? null : row.datasetHash();
    }

    private IngestionStateRow currentDatasetState() {
        List<IngestionStateRow> rows = jdbcTemplate.query("""
                select dataset_hash, updated_at
                from rag_ingestion_state
                where dataset_key = ?
                """, (rs, rowNum) -> {
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            Instant updatedAtInstant = updatedAt == null ? null : updatedAt.toInstant();
            return new IngestionStateRow(rs.getString("dataset_hash"), updatedAtInstant);
        }, DATASET_KEY);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private void updateDatasetHash(String datasetHash) {
        jdbcTemplate.update("""
                insert into rag_ingestion_state (dataset_key, dataset_hash, updated_at)
                values (?, ?, ?)
                on conflict (dataset_key) do update
                set dataset_hash = excluded.dataset_hash,
                    updated_at = excluded.updated_at
                """, DATASET_KEY, datasetHash, Timestamp.from(Instant.now()));
    }

    private String calculateDatasetHash(Path docsPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (!Files.exists(docsPath)) {
                throw new IllegalStateException("RAG docs path does not exist: " + docsPath);
            }
            try (Stream<Path> pathStream = Files.walk(docsPath)) {
                pathStream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(path -> updateDigest(digest, docsPath, path));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing SHA-256 support", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void updateDigest(MessageDigest digest, Path root, Path file) {
        digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
        try (InputStream inputStream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String vectorTable() {
        String table = properties.getRag().getVectorTable();
        if (!SAFE_SQL_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid vector table name: " + table);
        }
        return table;
    }

    public record RagIngestionStatus(long rowCount, String datasetHash, Instant updatedAt) {
    }

    private record IngestionStateRow(String datasetHash, Instant updatedAt) {
    }
}