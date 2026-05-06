package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.service.ReviewPromptCanonicalizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPromptCanonicalizerTest {

    @Test
    void shouldKeepRetrievalLayerScientificAndDisplayLayerInUserLanguage() {
        String rawPrompt = """
                疫霉属植物病原体基因功能系统回顾
                你的任务是：从提供的文献中，提取所有参与以下过程的基因：
                - 生长
                - 生殖
                - 致病性

                每个基因需要提取以下字段：
                - gene_name
                - evidence_text
                """;

        QueryAnalysis analysis = new QueryAnalysis(
                rawPrompt,
                List.of("gene_name", "species"),
                List.of("Phytophthora"),
                List.of("growth", "pathogenicity")
        );

        ReviewPromptCanonicalizer canonicalizer = new ReviewPromptCanonicalizer();
        QueryAnalysis canonical = canonicalizer.canonicalize(rawPrompt, analysis);

        assertEquals("zh", canonical.languageCode());
        assertTrue(canonical.mainQuestion().contains("Catalog"));
        assertTrue(canonical.mainQuestion().contains("疫霉"));
        assertFalse(canonical.mainQuestion().contains("gene_name"));
        assertFalse(canonical.mainQuestion().contains("evidence_text"));
        assertFalse(canonical.mainQuestion().contains("你的任务"));

        assertTrue(canonical.displayMainQuestion().contains("疫霉"));
        assertFalse(canonical.subQuestions().isEmpty());
        assertTrue(canonical.subQuestions().stream().anyMatch(item -> item.contains("生长")));
        assertTrue(canonical.subQuestions().stream().anyMatch(item -> item.contains("生殖")));
        assertTrue(canonical.subQuestions().stream().anyMatch(item -> item.contains("致病性")));
        assertTrue(canonical.subQuestions().stream().noneMatch(item -> item.contains("gene_name")));
        assertTrue(canonical.subQuestions().stream().noneMatch(item -> item.contains("evidence_text")));
    }

    @Test
    void shouldNotStripChineseScientificTermsFromRetrievalFields() {
        QueryAnalysis analysis = new QueryAnalysis(
                "哪些基因参与了疫霉菌的生长、生殖和致病过程？",
                List.of(
                        "哪些基因或蛋白与疫霉菌的生长过程相关？",
                        "哪些基因或蛋白与疫霉菌的生殖或发育阶段相关？",
                        "哪些基因或蛋白与疫霉菌的致病性相关？"
                ),
                List.of("疫霉菌", "Phytophthora"),
                List.of("生长", "生殖", "致病", "基因功能"),
                null,
                null,
                null
        );

        ReviewPromptCanonicalizer canonicalizer = new ReviewPromptCanonicalizer();
        QueryAnalysis canonical = canonicalizer.canonicalize(analysis.mainQuestion(), analysis);

        assertEquals("zh", canonical.languageCode());
        assertEquals("哪些基因参与了疫霉菌的生长、生殖和致病过程？", canonical.mainQuestion());
        assertTrue(canonical.subQuestions().stream().anyMatch(item -> item.contains("疫霉菌")));
        assertTrue(canonical.keyEntities().contains("疫霉菌"));
        assertTrue(canonical.keyConcepts().contains("生长"));
    }
}
