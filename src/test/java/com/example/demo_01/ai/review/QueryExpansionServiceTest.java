package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryExpansionServiceTest {

    @Test
    void expandShouldProduceConciseEnglishQueriesForBroadPhytophthoraGeneReview() {
        QueryExpansionService service = new QueryExpansionService();
        service.loadVocabulary();

        QueryAnalysis analysis = new QueryAnalysis(
                "哪些基因参与了疫霉菌的生长、生殖和致病过程？",
                List.of(
                        "哪些基因或蛋白与疫霉菌的生长过程相关？",
                        "哪些基因或蛋白与疫霉菌的生殖或发育阶段相关？",
                        "哪些基因或蛋白与疫霉菌的致病性或效应子调控相关？"
                ),
                List.of("疫霉菌", "Phytophthora"),
                List.of("生长", "生殖", "致病", "菌丝生长", "孢子形成", "基因调控", "基因功能")
        );

        List<String> queries = service.expand(analysis);

        assertTrue(queries.contains("phytophthora growth"));
        assertTrue(queries.contains("phytophthora growth gene"));
        assertTrue(queries.contains("phytophthora reproduction"));
        assertTrue(queries.contains("phytophthora reproduction gene"));
        assertTrue(queries.contains("phytophthora pathogenicity"));
        assertTrue(queries.contains("phytophthora pathogenicity gene"));
    }

    @Test
    void expandShouldTranslateLitchiiAndZoosporeTermsFromVocabulary() {
        QueryExpansionService service = new QueryExpansionService();
        service.loadVocabulary();

        QueryAnalysis analysis = new QueryAnalysis(
                "整理荔枝疫霉菌中与游动孢子发育相关的基因",
                List.of("哪些基因与荔枝疫霉菌的游动孢子发育相关？"),
                List.of("荔枝疫霉菌"),
                List.of("游动孢子发育", "基因功能")
        );

        List<String> queries = service.expand(analysis);

        assertTrue(queries.contains("phytophthora litchii"));
        assertTrue(queries.contains("zoospore development"));
        assertTrue(queries.contains("phytophthora litchii zoospore development"));
        assertTrue(queries.contains("phytophthora litchii zoospore development gene"));
    }
}
