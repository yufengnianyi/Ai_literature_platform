package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.service.ReviewPromptCanonicalizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPromptCanonicalizerTest {

    @Test
    void shouldConvertExtractionStylePromptIntoCanonicalReviewQuestion() {
        String rawPrompt = """
                疫霉属植物病原体基因功能系统性回顾
                你是一个专注于植物病原体（特别是疫霉属 Phytophthora）的生物信息抽取专家。
                你的任务是：从提供的文献中，提取所有参与以下过程的基因：
                - 生长（如菌丝生长）
                - 生殖（如孢子囊、游动孢子形成）
                - 致病性（如感染、毒力、寄主互作）
                                
                要求：
                1. 必须严格基于原文证据，禁止编造
                2. 每个基因必须有原文证据支撑
                3. 信息缺失时填写 null，不允许猜测
                                
                每个基因需要提取以下字段：
                - gene_name
                - species
                - biological_process
                - specific_function
                - functional_description
                - evidence_text
                - source
                - confidence
                """;

        QueryAnalysis analysis = new QueryAnalysis(
                rawPrompt,
                List.of("gene_name", "species"),
                List.of("Phytophthora"),
                List.of("growth", "pathogenicity")
        );

        ReviewPromptCanonicalizer canonicalizer = new ReviewPromptCanonicalizer();
        QueryAnalysis canonical = canonicalizer.canonicalize(rawPrompt, analysis);

        assertTrue(canonical.mainQuestion().contains("疫霉属植物病原体"));
        assertTrue(canonical.mainQuestion().contains("生长"));
        assertTrue(canonical.mainQuestion().contains("生殖"));
        assertTrue(canonical.mainQuestion().contains("致病"));
        assertFalse(canonical.mainQuestion().contains("gene_name"));
        assertFalse(canonical.mainQuestion().contains("evidence_text"));
        assertFalse(canonical.mainQuestion().contains("你的任务"));

        assertFalse(canonical.subQuestions().isEmpty());
        assertTrue(canonical.subQuestions().size() >= 3);
        assertTrue(canonical.subQuestions().stream().noneMatch(item -> item.contains("gene_name")));
        assertTrue(canonical.subQuestions().stream().noneMatch(item -> item.contains("evidence_text")));
    }
}
