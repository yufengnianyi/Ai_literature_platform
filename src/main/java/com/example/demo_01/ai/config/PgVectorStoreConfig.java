package com.example.demo_01.ai.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class PgVectorStoreConfig {

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            DataSource dataSource,
            AiPersistenceProperties properties,
            @Value("${spring.flyway.placeholders.embeddingDimension}") int flywayEmbeddingDimension,
            @Value("${spring.flyway.placeholders.vectorTable}") String flywayVectorTable) {
        if (properties.getRag().getEmbeddingDimension() != flywayEmbeddingDimension) {
            throw new IllegalStateException("app.ai.rag.embedding-dimension must match spring.flyway.placeholders.embeddingDimension");
        }
        if (!properties.getRag().getVectorTable().equals(flywayVectorTable)) {
            throw new IllegalStateException("app.ai.rag.vector-table must match spring.flyway.placeholders.vectorTable");
        }
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(properties.getRag().getVectorTable())
                .dimension(properties.getRag().getEmbeddingDimension())
                .createTable(false)
                .build();
    }

    @Bean
    public TokenCountEstimator tokenCountEstimator(
            @Value("${langchain4j.community.dashscope.embedding-model.model-name}") String modelName) {
        return new HeuristicTokenCountEstimator(modelName);
    }
}
