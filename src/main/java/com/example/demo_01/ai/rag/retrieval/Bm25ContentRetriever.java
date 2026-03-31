package com.example.demo_01.ai.rag.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class Bm25ContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(Bm25ContentRetriever.class);
    private static final String[] FIELDS = {"title", "section_path", "text"};
    private static final Map<String, Float> BOOSTS = Map.of(
            "title", 3.0f,
            "section_path", 2.0f,
            "text", 1.0f
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Bm25IndexService bm25IndexService;
    private final ObjectMapper objectMapper;
    private final int maxResults;

    public Bm25ContentRetriever(Bm25IndexService bm25IndexService, ObjectMapper objectMapper, int maxResults) {
        this.bm25IndexService = bm25IndexService;
        this.objectMapper = objectMapper;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        try {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(FIELDS, new StandardAnalyzer(), BOOSTS);
            org.apache.lucene.search.Query luceneQuery = parser.parse(MultiFieldQueryParser.escape(query.text()));
            return bm25IndexService.search(luceneQuery, maxResults).stream()
                    .map(hit -> {
                        Metadata metadata = readMetadata(hit.document().get("metadata_json"));
                        TextSegment segment = TextSegment.from(hit.document().get("text"), metadata);
                        return Content.from(segment, Map.of(ContentMetadata.SCORE, hit.score()));
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("BM25 retrieval failed for query '{}': {}", query.text(), e.getMessage());
            return List.of();
        }
    }

    private Metadata readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new Metadata();
        }
        try {
            return new Metadata(objectMapper.readValue(metadataJson, MAP_TYPE));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize BM25 metadata", e);
        }
    }
}
