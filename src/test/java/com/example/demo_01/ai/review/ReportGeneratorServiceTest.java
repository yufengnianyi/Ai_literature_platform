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

    @Test
    void generateReportShouldIncludeCompoundClassAnalysisWhenCompoundEvidenceExists() {
        ReportGeneratorService service = new ReportGeneratorService();
        ExtractedEvidence evidence = new ExtractedEvidence(
                "chunk-2",
                "doc-2",
                "Paper B",
                "alpha-methylcinnamaldehyde inhibits Phytophthora capsici.",
                "alpha-methylcinnamaldehyde reduced mycelial growth of Phytophthora capsici.",
                "mycelial growth assay",
                new TypedEntities(
                        List.of("Phytophthora capsici"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("mycelial growth assay"),
                        List.of("alpha-methylcinnamaldehyde"),
                        List.of("phenylpropanoid"),
                        List.of("chemical synthesis"),
                        List.of("菌丝生长: 25 uM: 0% growth (5d); 100 uM: 6% growth"),
                        List.of("mycelial growth assay"),
                        List.of("Phytophthora capsici"),
                        List.of("cell membrane"),
                        List.of("disrupts membrane integrity"),
                        List.of("Paper B"),
                        List.of("not mentioned"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("XTT reduction IC50 > 200 uM in mammalian cells")
                ),
                List.of("alpha-methylcinnamaldehyde", "Phytophthora capsici"),
                "EXPERIMENTAL",
                0.88,
                "alpha-methylcinnamaldehyde reduced mycelial growth.",
                "Which compounds inhibit Phytophthora?"
        );
        QueryAnalysis analysis = new QueryAnalysis(
                "Which antimicrobial compounds inhibit Phytophthora?",
                List.of(evidence.subQuestion()),
                List.of("Phytophthora capsici"),
                List.of("antimicrobial compounds"),
                "zh",
                "哪些抑菌化合物可以抑制疫霉菌？",
                List.of("哪些化合物有效？")
        );

        String report = service.generateReport(
                analysis,
                List.of(new FusedEvidenceGroup(evidence.subQuestion(), "", List.of(), 0, 0, List.of())),
                List.of(evidence)
        );

        assertTrue(report.contains("三、抑菌化合物同类分析"));
        assertTrue(report.contains("phenylpropanoid"));
        assertTrue(report.contains("chemical synthesis"));
        assertTrue(report.contains("Phytophthora capsici"));
        assertTrue(report.contains("菌丝生长: 25 uM: 0% growth (5d); 100 uM: 6% growth"));
        assertTrue(report.contains("EC50、MIC、抑制率"));
    }
}
