package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.ExtractedEvidence;
import com.example.demo_01.ai.review.model.ReviewModels.FusedEvidenceGroup;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewPaperEvidenceTable;
import com.example.demo_01.ai.review.model.ReviewModels.TypedEntities;
import com.example.demo_01.ai.review.service.ReportGeneratorService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertFalse(report.contains("主要研究内容分类"));
        assertFalse(report.contains("| 研究对象 | 作用阶段/目标 | 机制或通路 | 证明方法 | 结论摘要 | 来源 |"));
        assertFalse(report.contains("抑菌化合物同类分析"));
        assertTrue(report.contains("二、关键发现总结"));
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

        assertTrue(report.contains("二、抑菌化合物同类分析"));
        assertTrue(report.contains("三、关键发现总结"));
        assertTrue(report.contains("phenylpropanoid"));
        assertTrue(report.contains("chemical synthesis"));
        assertTrue(report.contains("Phytophthora capsici"));
        assertTrue(report.contains("菌丝生长: 25 uM: 0% growth (5d); 100 uM: 6% growth"));
        assertTrue(report.contains("EC50、MIC、抑制率"));
    }
    @Test
    void generateReportShouldRenderDisplaySubQuestionForChineseReports() {
        ReportGeneratorService service = new ReportGeneratorService();
        String canonicalSubQuestion = "Which specific antifungal compounds have been identified as effective against oomycetes?";
        String displaySubQuestion = "\u54ea\u4e9b\u6291\u83cc\u5316\u5408\u7269\u5df2\u88ab\u8bc1\u5b9e\u5bf9\u5375\u83cc\u6709\u6548\uff1f";
        ExtractedEvidence evidence = new ExtractedEvidence(
                "chunk-3",
                "doc-3",
                "Paper C",
                "Carvacrol inhibits Phytophthora infestans.",
                "Carvacrol reduced oomycete growth in vitro.",
                "growth inhibition assay",
                new TypedEntities(
                        List.of("Phytophthora infestans"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("growth inhibition assay"),
                        List.of("Carvacrol"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("Phytophthora infestans"),
                        List.of(),
                        List.of(),
                        List.of("Paper C"),
                        List.of("not mentioned")
                ),
                List.of("Carvacrol", "Phytophthora infestans"),
                "EXPERIMENTAL",
                0.91,
                "Carvacrol reduced oomycete growth in vitro.",
                canonicalSubQuestion
        );
        QueryAnalysis analysis = new QueryAnalysis(
                "Which antimicrobial compounds inhibit oomycetes?",
                List.of(canonicalSubQuestion),
                List.of("oomycetes"),
                List.of("antimicrobial compounds"),
                "zh",
                "\u54ea\u4e9b\u6291\u83cc\u5316\u5408\u7269\u5bf9\u5375\u83cc\u6709\u6548\uff1f",
                List.of(displaySubQuestion)
        );

        String report = service.generateReport(
                analysis,
                List.of(new FusedEvidenceGroup(
                        canonicalSubQuestion,
                        "Several antifungal compounds have been identified as effective against oomycetes.",
                        List.of(),
                        1,
                        0,
                        List.of()
                )),
                List.of(evidence)
        );

        assertTrue(report.contains("### " + displaySubQuestion));
        assertFalse(report.contains("### " + canonicalSubQuestion));
        assertFalse(report.contains("Several antifungal compounds have been identified as effective against oomycetes."));
        assertTrue(report.contains("\u56f4\u7ed5\u201c" + displaySubQuestion + "\u201d"));
    }

    @Test
    void paperCentricReportShouldUseMergedTableAsLlmContext() {
        ReportGeneratorService service = new ReportGeneratorService();
        ChatModel chatModel = mock(ChatModel.class);
        ReflectionTestUtils.setField(service, "reviewReportChatModel", chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("LLM synthesized paper report")).build());
        QueryAnalysis analysis = new QueryAnalysis(
                "Summarize anti-oomycete compounds.",
                List.of(),
                List.of("Phytophthora"),
                List.of("antimicrobial compounds"),
                "en",
                "Summarize anti-oomycete compounds.",
                List.of()
        );
        ReviewPaperEvidenceTable table = paperTable("Paper A", List.of(List.of(
                "compound 34",
                "ellipticine derivative",
                "synthetic",
                "25 uM",
                "Phytophthora infestans",
                "mycelial growth assay",
                "not mentioned",
                "not mentioned",
                "Paper A",
                "not mentioned"
        )));

        String report = service.generateReport(analysis, null, null, null, List.of(table));

        assertEquals("LLM synthesized paper report", report);
        ArgumentCaptor<UserMessage> userMessageCaptor = ArgumentCaptor.forClass(UserMessage.class);
        ArgumentCaptor<SystemMessage> systemMessageCaptor = ArgumentCaptor.forClass(SystemMessage.class);
        verify(chatModel).chat(systemMessageCaptor.capture(), userMessageCaptor.capture());
        String systemPrompt = systemMessageCaptor.getValue().text();
        String prompt = userMessageCaptor.getValue().singleText();
        assertTrue(prompt.contains("Merged Summary Table"));
        assertTrue(prompt.contains("compound 34"));
        assertTrue(prompt.contains("25 uM"));
        assertTrue(prompt.contains("{source=<paper title>; chunk=<chunk_id>; quote=<short evidence summary>}"));
        assertTrue(prompt.contains("{source=Paper A; chunk=chunk-1; quote=25 uM concentration summary}"));
        assertFalse(systemPrompt.contains("100,000 papers"));
        assertFalse(systemPrompt.contains("hierarchical map-reduce"));
        assertFalse(prompt.contains("100k papers"));
        assertFalse(prompt.contains("map-reduce"));
        assertFalse(prompt.contains("chunk text"));
    }

    @Test
    void paperCentricReportShouldFallbackWhenLlmFails() {
        ReportGeneratorService service = new ReportGeneratorService();
        ChatModel chatModel = mock(ChatModel.class);
        ReflectionTestUtils.setField(service, "reviewReportChatModel", chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenThrow(new RuntimeException("offline"));
        QueryAnalysis analysis = new QueryAnalysis(
                "Summarize anti-oomycete compounds.",
                List.of(),
                List.of("Phytophthora"),
                List.of("antimicrobial compounds"),
                "en",
                "Summarize anti-oomycete compounds.",
                List.of()
        );

        String report = service.generateReport(analysis, null, null, null,
                List.of(paperTable("Paper A", List.of(List.of("compound 34", "ellipticine derivative")))));

        assertTrue(report.contains("Systematic Review Report"));
        assertTrue(report.contains("Paper A"));
        assertTrue(report.contains("Cross-Paper Pattern Synthesis"));
        assertTrue(report.contains("Evidence Gaps and Usage Cautions"));
        assertTrue(report.contains("{source=Paper A; chunk=chunk-1; quote=25 uM concentration summary}"));
        assertFalse(report.contains("Context Assessment for 100k Papers"));
        assertFalse(report.contains("hierarchical map-reduce"));
        assertFalse(report.contains("100k"));
    }

    @Test
    void paperCentricChineseFallbackShouldUseCleanHeadingsAndSourceTokens() {
        ReportGeneratorService service = new ReportGeneratorService();
        QueryAnalysis analysis = new QueryAnalysis(
                "\u603b\u7ed3\u6291\u83cc\u5316\u5408\u7269\u3002",
                List.of(),
                List.of("\u75ab\u9709\u83cc"),
                List.of("\u6291\u83cc\u5316\u5408\u7269"),
                "zh",
                "\u603b\u7ed3\u6291\u83cc\u5316\u5408\u7269\u3002",
                List.of()
        );

        String report = service.generateReport(analysis, null, null, null,
                List.of(paperTable("Paper A", List.of(List.of(
                        "compound 34",
                        "ellipticine derivative",
                        "synthetic",
                        "25 uM",
                        "Phytophthora infestans",
                        "mycelial growth assay",
                        "not mentioned",
                        "not mentioned",
                        "Paper A",
                        "not mentioned"
                )))));

        assertTrue(report.contains("\u6587\u732e\u7efc\u8ff0\u62a5\u544a"));
        assertTrue(report.contains("\u9010\u7bc7\u6587\u732e\u8bc1\u636e\u603b\u7ed3"));
        assertTrue(report.contains("\u8de8\u6587\u732e\u89c4\u5f8b\u603b\u7ed3"));
        assertTrue(report.contains("\u8bc1\u636e\u7f3a\u53e3\u548c\u4f7f\u7528\u6ce8\u610f\u4e8b\u9879"));
        assertTrue(report.contains("{source=Paper A; chunk=chunk-1; quote=25 uM concentration summary}"));
        assertFalse(report.contains("\u93c2"));
        assertFalse(report.contains("\u9435"));
        assertFalse(report.contains("\u6d93"));
        assertFalse(report.contains("10w"));
        assertFalse(report.contains("map-reduce"));
    }

    @Test
    void oversizedPaperCentricReportShouldSummarizeBatchesBeforeFinalReport() {
        ReportGeneratorService service = new ReportGeneratorService();
        ChatModel chatModel = mock(ChatModel.class);
        ReflectionTestUtils.setField(service, "reviewReportChatModel", chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class)))
                .thenReturn(
                        ChatResponse.builder().aiMessage(AiMessage.from("Batch summary A {source=Paper A; chunk=chunk-1; quote=25 uM concentration summary}")).build(),
                        ChatResponse.builder().aiMessage(AiMessage.from("Batch summary B {source=Paper B; chunk=chunk-1; quote=25 uM concentration summary}")).build(),
                        ChatResponse.builder().aiMessage(AiMessage.from("Final combined report")).build()
                );
        QueryAnalysis analysis = new QueryAnalysis(
                "Summarize anti-oomycete compounds.",
                List.of(),
                List.of("Phytophthora"),
                List.of("antimicrobial compounds"),
                "en",
                "Summarize anti-oomycete compounds.",
                List.of()
        );
        ReviewPaperEvidenceTable paperA = paperTable("Paper A", List.of(largePaperRow("compound A")));
        ReviewPaperEvidenceTable paperB = paperTable("Paper B", List.of(largePaperRow("compound B")));

        String report = service.generateReport(analysis, null, null, null, List.of(paperA, paperB));

        assertEquals("Final combined report", report);
        ArgumentCaptor<UserMessage> userMessageCaptor = ArgumentCaptor.forClass(UserMessage.class);
        ArgumentCaptor<SystemMessage> systemMessageCaptor = ArgumentCaptor.forClass(SystemMessage.class);
        verify(chatModel, times(3)).chat(systemMessageCaptor.capture(), userMessageCaptor.capture());
        List<String> prompts = userMessageCaptor.getAllValues().stream()
                .map(UserMessage::singleText)
                .toList();
        assertTrue(systemMessageCaptor.getAllValues().get(0).text().contains("one batch"));
        assertTrue(prompts.get(0).contains("Paper A"));
        assertTrue(prompts.get(1).contains("Paper B"));
        assertTrue(prompts.get(2).contains("Batch Summaries (All Selected Papers Covered)"));
        assertTrue(prompts.get(2).contains("Batch summary A"));
        assertTrue(prompts.get(2).contains("Batch summary B"));
        assertFalse(prompts.get(2).contains("Omitted merged-table rows due to report context budget"));
    }

    @Test
    void oversizedPaperCentricReportShouldFallbackWhenBatchSummaryFails() {
        ReportGeneratorService service = new ReportGeneratorService();
        ChatModel chatModel = mock(ChatModel.class);
        ReflectionTestUtils.setField(service, "reviewReportChatModel", chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenThrow(new RuntimeException("offline"));
        QueryAnalysis analysis = new QueryAnalysis(
                "Summarize anti-oomycete compounds.",
                List.of(),
                List.of("Phytophthora"),
                List.of("antimicrobial compounds"),
                "en",
                "Summarize anti-oomycete compounds.",
                List.of()
        );
        ReviewPaperEvidenceTable paperA = paperTable("Paper A", List.of(largePaperRow("compound A")));
        ReviewPaperEvidenceTable paperB = paperTable("Paper B", List.of(largePaperRow("compound B")));

        String report = service.generateReport(analysis, null, null, null, List.of(paperA, paperB));

        assertTrue(report.contains("Systematic Review Report"));
        assertTrue(report.contains("Paper A"));
        assertTrue(report.contains("Paper B"));
        assertTrue(report.contains("{source=Paper A; chunk=chunk-1; quote=25 uM concentration summary}"));
    }

    private ReviewPaperEvidenceTable paperTable(String title, List<List<String>> rows) {
        return new ReviewPaperEvidenceTable(
                UUID.randomUUID(),
                UUID.randomUUID(),
                title,
                "Summarize anti-oomycete compounds.",
                title + " reports anti-oomycete activity.",
                List.of("\u5316\u5408\u7269\u540d\u79f0", "\u7ed3\u6784\u7c7b\u578b", "\u6765\u6e90",
                        "\u6291\u83cc\u6d53\u5ea6", "\u4f5c\u7528\u75c5\u539f\u83cc", "\u8bd5\u9a8c\u65b9\u6cd5",
                        "\u53ef\u80fd\u7684\u4f5c\u7528\u9776\u6807/\u673a\u5236",
                        "\u7ec6\u80de\u6bd2\u6027/\u5b89\u5168\u6027\u6570\u636e",
                        "\u6765\u6e90\u6587\u732e", "\u4e13\u5229\u4fe1\u606f"),
                rows,
                List.of("chunk-1"),
                1,
                0.9,
                List.of("not mentioned fields remain uncertain"),
                Instant.now(),
                "antimicrobial_compound",
                "25 uM concentration summary",
                List.of(),
                List.of()
        );
    }

    private List<String> largePaperRow(String compoundName) {
        String largeEvidence = "25 uM " + "activity evidence ".repeat(3500);
        return List.of(
                compoundName,
                "ellipticine derivative",
                "synthetic",
                largeEvidence,
                "Phytophthora infestans",
                "mycelial growth assay",
                "not mentioned",
                "not mentioned",
                compoundName + " source paper",
                "not mentioned"
        );
    }
}
