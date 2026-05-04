package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.ExtractedEvidence;
import com.example.demo_01.ai.review.model.ReviewModels.FusedEvidenceGroup;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.TypedEntities;
import com.example.demo_01.ai.review.service.ReportGeneratorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportGeneratorServiceTest {

    @Test
    void generateReportShouldStayGroundedInEvidenceAndUseChineseHeadings() {
        ReportGeneratorService service = new ReportGeneratorService();
        ExtractedEvidence evidence = new ExtractedEvidence(
                "chunk-1",
                "doc-1",
                "A MYB-related transcription factor regulates effector gene expression in an oomycete pathogen",
                "PsMyb37 is essential for virulence.",
                "PsMyb37 regulates effector expression and is required for pathogenicity.",
                "CRISPR/Cas9-mediated knockout",
                new TypedEntities(
                        List.of("Phytophthora sojae"),
                        List.of("PsMyb37"),
                        List.of("pathogenicity"),
                        List.of(),
                        List.of("virulence"),
                        List.of("CRISPR/Cas9"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                ),
                List.of("Phytophthora sojae", "PsMyb37", "pathogenicity"),
                "EXPERIMENTAL",
                0.92,
                "PsMyb37 is essential for the virulence of Phytophthora sojae.",
                "Which genes or proteins are linked to pathogenicity?"
        );
        QueryAnalysis analysis = new QueryAnalysis(
                "Catalog genes and proteins related to Phytophthora pathogenicity.",
                List.of(evidence.subQuestion()),
                List.of("Phytophthora", "PsMyb37"),
                List.of("pathogenicity"),
                "zh",
                "整理一下涉及疫霉菌致病过程的所有基因",
                List.of("哪些基因或蛋白与致病性相关？")
        );

        String report = service.generateReport(
                analysis,
                List.of(new FusedEvidenceGroup(evidence.subQuestion(), "", List.of(), 0, 0, List.of())),
                List.of(evidence)
        );

        assertTrue(report.contains("一、研究主题的概述"));
        assertTrue(report.contains("PsMyb37"));
        assertTrue(report.contains("{source=A MYB-related transcription factor regulates effector gene expression in an oomycete pathogen; chunk=chunk-1}"));
        assertFalse(report.contains("chunk=chunk_id"));
        assertFalse(report.contains("Genes Involved in the Pathogenicity of Phytophthora infestans"));
    }
}
