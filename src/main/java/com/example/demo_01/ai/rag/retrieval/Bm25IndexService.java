package com.example.demo_01.ai.rag.retrieval;

import com.example.demo_01.ai.rag.RagRetrievalProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Bm25IndexService {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexService.class);

    private final EmbeddingStoreTextRepository embeddingStoreTextRepository;
    private final ObjectMapper objectMapper;
    private final Path indexPath;

    public Bm25IndexService(EmbeddingStoreTextRepository embeddingStoreTextRepository,
                            RagRetrievalProperties retrievalProperties,
                            ObjectMapper objectMapper) {
        this.embeddingStoreTextRepository = embeddingStoreTextRepository;
        this.objectMapper = objectMapper;
        this.indexPath = Path.of(retrievalProperties.getBm25IndexPath()).toAbsolutePath().normalize();
    }

    public synchronized void bootstrapIfNeeded() {
        try {
            Files.createDirectories(indexPath);
            long rowCount = embeddingStoreTextRepository.countRows();
            try (Directory directory = openDirectory()) {
                if (!DirectoryReader.indexExists(directory)) {
                    rebuildIndex();
                    return;
                }
                long indexedCount = countIndexedDocuments(directory);
                if (indexedCount != rowCount) {
                    rebuildIndex();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to bootstrap BM25 index at " + indexPath, e);
        }
    }

    public synchronized void rebuildIndex() {
        List<Bm25IndexEntry> entries = embeddingStoreTextRepository.fetchAll();
        try (Directory directory = openDirectory();
             IndexWriter writer = new IndexWriter(directory, writerConfig(IndexWriterConfig.OpenMode.CREATE))) {
            for (Bm25IndexEntry entry : entries) {
                writer.addDocument(toDocument(entry));
            }
            writer.commit();
            log.info("BM25 index rebuilt at {} with {} documents", indexPath, entries.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rebuild BM25 index at " + indexPath, e);
        }
    }

    public synchronized void index(List<Bm25IndexEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(indexPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create BM25 index directory: " + indexPath, e);
        }
        try (Directory directory = openDirectory();
             IndexWriter writer = new IndexWriter(directory, writerConfig(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
            for (Bm25IndexEntry entry : entries) {
                writer.updateDocument(new Term("id", entry.id()), toDocument(entry));
            }
            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update BM25 index", e);
        }
    }

    public synchronized void removeByDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return;
        }
        try (Directory directory = openDirectory()) {
            if (!DirectoryReader.indexExists(directory)) {
                return;
            }
            try (IndexWriter writer = new IndexWriter(directory, writerConfig(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
                writer.deleteDocuments(new Term("document_id", documentId));
                writer.commit();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete BM25 index entries for document " + documentId, e);
        }
    }

    public synchronized List<Bm25SearchHit> search(org.apache.lucene.search.Query query, int maxResults) {
        if (query == null || maxResults <= 0) {
            return List.of();
        }
        try (Directory directory = openDirectory()) {
            if (!DirectoryReader.indexExists(directory)) {
                return List.of();
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                if (reader.numDocs() == 0) {
                    return List.of();
                }
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                TopDocs topDocs = searcher.search(query, maxResults);
                List<Bm25SearchHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    hits.add(new Bm25SearchHit(searcher.doc(scoreDoc.doc), scoreDoc.score));
                }
                return hits;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search BM25 index", e);
        }
    }

    public Path indexPath() {
        return indexPath;
    }

    public synchronized long countIndexedDocuments() {
        try (Directory directory = openDirectory()) {
            return countIndexedDocuments(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to count BM25 index documents", e);
        }
    }

    private long countIndexedDocuments(Directory directory) throws IOException {
        if (!DirectoryReader.indexExists(directory)) {
            return 0L;
        }
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }

    private Directory openDirectory() throws IOException {
        return FSDirectory.open(indexPath);
    }

    private IndexWriterConfig writerConfig(IndexWriterConfig.OpenMode openMode) {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(openMode);
        config.setSimilarity(new BM25Similarity());
        return config;
    }

    private Document toDocument(Bm25IndexEntry entry) {
        Document document = new Document();
        document.add(new StringField("id", safe(entry.id()), Field.Store.YES));
        document.add(new StringField("document_id", safe(entry.documentId()), Field.Store.YES));
        document.add(new StringField("chunk_id", safe(entry.chunkId()), Field.Store.YES));
        document.add(new TextField("title", safe(entry.title()), Field.Store.YES));
        document.add(new TextField("section_path", safe(entry.sectionPath()), Field.Store.YES));
        document.add(new TextField("text", safe(entry.text()), Field.Store.YES));
        document.add(new StoredField("metadata_json", writeMetadata(entry.metadata())));
        return document;
    }

    private String writeMetadata(Metadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Collections.emptyMap() : metadata.toMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize BM25 metadata", e);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record Bm25SearchHit(Document document, float score) {
    }
}
