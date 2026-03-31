package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.kg.retrieval.GraphHybridRetriever;
import com.example.demo_01.ai.kg.retrieval.Neo4jGraphContentRetriever;
import com.example.demo_01.ai.rag.retrieval.Bm25ContentRetriever;
import com.example.demo_01.ai.rag.retrieval.Bm25IndexService;
import com.example.demo_01.ai.rag.retrieval.EmbeddingStoreTextRepository;
import com.example.demo_01.ai.rag.retrieval.HybridContentRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagRetrieverConfig {

    @Resource
    private EmbeddingModel quwenEmbeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private RagRetrievalProperties ragRetrievalProperties;

    @Bean
    @DependsOnDatabaseInitialization
    public Bm25IndexService bm25IndexService(EmbeddingStoreTextRepository embeddingStoreTextRepository,
                                             ObjectMapper objectMapper) {
        Bm25IndexService service = new Bm25IndexService(embeddingStoreTextRepository, ragRetrievalProperties, objectMapper);
        service.bootstrapIfNeeded();
        return service;
    }

    @Bean("ragContentRetriever")
    @DependsOnDatabaseInitialization
    public ContentRetriever ragContentRetriever(Bm25IndexService bm25IndexService,
                                                ObjectMapper objectMapper,
                                                ObjectProvider<Neo4jGraphContentRetriever> graphContentRetrieverProvider) {
        ContentRetriever denseRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(quwenEmbeddingModel)
                .maxResults(ragRetrievalProperties.getDenseMaxResults())
                .minScore(ragRetrievalProperties.getDenseMinScore())
                .build();
        ContentRetriever bm25Retriever = new Bm25ContentRetriever(
                bm25IndexService,
                objectMapper,
                ragRetrievalProperties.getBm25MaxResults()
        );
        ContentRetriever textRetriever = new HybridContentRetriever(
                denseRetriever,
                bm25Retriever,
                ragRetrievalProperties.getRrfK(),
                ragRetrievalProperties.getFusedMaxResults()
        );
        ContentRetriever graphRetriever = graphContentRetrieverProvider.getIfAvailable();
        if (graphRetriever == null) {
            graphRetriever = query -> java.util.List.of();
        }
        return new GraphHybridRetriever(
                textRetriever,
                graphRetriever,
                ragRetrievalProperties.getRrfK(),
                ragRetrievalProperties.getFusedMaxResults()
        );
    }
}
