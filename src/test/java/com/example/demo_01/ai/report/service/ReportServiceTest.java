package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ReviewStatus;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ValidationStatus;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.report.config.ReportProperties;
import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.service.QueryAnalyzerService;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private final ReportService service = new ReportService();

    @Test
    void broadQueryShouldKeepAllEvidenceRows() {
        List<CompoundEvidenceRecord> evidence = List.of(
                evidence("Compound A", "Phytophthora infestans", "MIC", "8 ug/mL"),
                evidence("Compound B", "Pythium ultimum", "disc diffusion", "12 mm")
        );

        List<RankedEvidence> ranked = service.rank(evidence, List.of(), 500);

        assertEquals(2, ranked.size());
    }

    @Test
    void specificQueryShouldOnlyKeepPositiveMatches() {
        CompoundEvidenceRecord matching =
                evidence("Metalaxyl", "Phytophthora infestans", "MIC", "8 ug/mL");
        CompoundEvidenceRecord unrelated =
                evidence("Compound B", "Pythium ultimum", "disc diffusion", "12 mm");

        List<RankedEvidence> ranked = service.rank(
                List.of(matching, unrelated),
                List.of("metalaxyl"),
                500);

        assertEquals(1, ranked.size());
        assertEquals(matching.evidenceId(), ranked.get(0).evidence().evidenceId());
        assertTrue(ranked.get(0).matchScore() >= 5);
    }

    @Test
    void unmatchedSpecificQueryShouldReturnEmptyTableSelection() {
        List<RankedEvidence> ranked = service.rank(
                List.of(evidence("Compound A", "Pythium ultimum", "MIC", "8 ug/mL")),
                List.of("nonexistent"),
                500);

        assertTrue(ranked.isEmpty());
    }

    @Test
    void broadAntimicrobialQuestionShouldNotBeRewrittenAsAntibacterial() {
        QueryAnalyzerService analyzer = mock(QueryAnalyzerService.class);
        ReflectionTestUtils.setField(service, "queryAnalyzerService", analyzer);
        when(analyzer.analyze("告诉我关于抑菌化合物的相关信息")).thenReturn(new QueryAnalysis(
                "What is known about antibacterial compounds?",
                List.of(),
                List.of(),
                List.of()));

        var query = service.rewrite("告诉我关于抑菌化合物的相关信息");

        assertTrue(query.rewrittenQuestion().contains("oomycetes"));
        assertFalse(query.rewrittenQuestion().contains("antibacterial"));
        assertTrue(query.terms().isEmpty());
    }

    @Test
    void shouldBuildDeterministicOverviewAndBalanceRepresentativeCompounds() {
        List<RankedEvidence> evidence = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            evidence.add(new RankedEvidence(
                    evidence("Compound A", "synthetic", "Phytophthora infestans",
                            "菌丝生长抑制", index == 0 ? "无抑制活性" : "EC50 = " + (index + 1)),
                    0,
                    index + 1,
                    index == 1 ? "conflict-a" : null));
        }
        for (int index = 0; index < 6; index++) {
            evidence.add(new RankedEvidence(
                    evidence("Compound " + (char) ('B' + index), "natural product",
                            "Pythium ultimum", "离体叶片接种", "MIC = " + (index + 1)),
                    0,
                    index + 7,
                    null));
        }

        ReportService.ReportOverview overview = service.buildOverview(evidence);
        List<RankedEvidence> representatives = service.selectRepresentativeEvidence(evidence, 6);
        Set<String> compounds = representatives.stream()
                .map(item -> item.evidence().row().compoundStandardName())
                .collect(Collectors.toSet());

        assertEquals(12, overview.evidenceCount());
        assertEquals(7, overview.compoundCount());
        assertEquals(12, overview.pendingCount());
        assertTrue(overview.allPending());
        assertEquals(6, representatives.size());
        assertTrue(compounds.size() >= 4);
    }

    @Test
    void shouldExcludeProteinRecordsFromRepresentativeNarrative() {
        UUID evidenceId = UUID.randomUUID();
        CompoundEvidenceRecord protein = new CompoundEvidenceRecord(
                evidenceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Protein paper",
                1,
                new CompoundEvidenceRow(
                        "Moringa oleifera chitin-binding protein 3",
                        "Moringa oleifera chitin-binding protein 3",
                        "蛋白质（植物凝集素样）",
                        "植物天然产物",
                        "Moringa oleifera seed",
                        "Pythium oligandrum",
                        "孢子萌发抑制",
                        "无抑制活性",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        ""),
                "fingerprint-" + evidenceId,
                NameKind.PURE_COMPOUND,
                "compound:protein",
                0.8,
                ValidationStatus.VALID,
                List.of(),
                ReviewStatus.PENDING,
                null,
                true,
                List.of(),
                Instant.now(),
                Instant.now());
        RankedEvidence compound = new RankedEvidence(
                evidence("Metalaxyl", "synthetic", "Phytophthora infestans", "MIC", "8 ug/mL"),
                5,
                2,
                null);

        List<RankedEvidence> representatives = service.selectRepresentativeEvidence(
                List.of(new RankedEvidence(protein, 5, 1, null), compound),
                10);
        ReportService.ReportOverview overview = service.buildOverview(
                List.of(new RankedEvidence(protein, 5, 1, null), compound));

        assertEquals(1, representatives.size());
        assertEquals("Metalaxyl", representatives.getFirst().evidence().row().compoundStandardName());
        assertEquals(1, overview.suspectedNonCompoundCount());
    }

    @Test
    void modelFailureShouldReturnStructuredChineseSynthesisWithoutInternalError() {
        CompoundEvidenceRecord record =
                evidence("Metalaxyl", "synthetic", "Phytophthora infestans",
                        "离体叶片接种", "EC50 = 8 ug/mL");
        RankedEvidence ranked = new RankedEvidence(record, 5.0, 1, null);
        ReviewReasoningChatClient chatClient = mock(ReviewReasoningChatClient.class);
        ReflectionTestUtils.setField(service, "chatClient", chatClient);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        when(chatClient.chatStandard(any(), any()))
                .thenThrow(new IllegalStateException("timeout"));

        String answer = ReflectionTestUtils.invokeMethod(
                service,
                "generateAnswer",
                "告诉我关于抑菌化合物的相关信息",
                "What is known about compounds active against oomycetes?",
                List.of(ranked),
                List.of(ranked),
                service.buildOverview(List.of(ranked)),
                List.of(),
                List.of());

        assertTrue(answer.contains("## 范围说明与直接结论"));
        assertTrue(answer.contains("## 数据概览"));
        assertTrue(answer.contains("## 来源分类"));
        assertTrue(answer.contains("## 代表性发现"));
        assertTrue(answer.contains("全部证据均为机器抽取、待审核"));
        assertFalse(answer.contains("模型综述生成失败"));
        assertFalse(answer.contains("timeout"));
    }

    @Test
    void citationValidationFailureShouldTriggerOneRepairAttempt() {
        CompoundEvidenceRecord record =
                evidence("Metalaxyl", "synthetic", "Phytophthora infestans",
                        "MIC", "8 ug/mL");
        RankedEvidence ranked = new RankedEvidence(record, 5.0, 1, null);
        ReviewReasoningChatClient chatClient = mock(ReviewReasoningChatClient.class);
        ReflectionTestUtils.setField(service, "chatClient", chatClient);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        String invalidCitation = "[EVIDENCE:" + UUID.randomUUID() + "]";
        String repairedCitation = "[EVIDENCE:" + record.evidenceId() + "]";
        when(chatClient.chatStandard(any(), any())).thenReturn(
                response(structuredAnswer(invalidCitation)),
                response(structuredAnswer(repairedCitation)));

        String answer = ReflectionTestUtils.invokeMethod(
                service,
                "generateAnswer",
                "Metalaxyl 有何活性？",
                "What activity does Metalaxyl have?",
                List.of(ranked),
                List.of(ranked),
                service.buildOverview(List.of(ranked)),
                List.of(),
                List.of());

        assertEquals(structuredAnswer(repairedCitation), answer);
        verify(chatClient, times(2)).chatStandard(any(), any());
    }

    @Test
    void shouldAcceptEvidenceAndLiteratureCitationsFromCurrentSelection() {
        CompoundEvidenceRecord record =
                evidence("Metalaxyl", "Phytophthora infestans", "MIC", "8 ug/mL");
        RankedEvidence ranked = new RankedEvidence(record, 5.0, 1, null);
        ReportService.LiteratureContext literature = literature(record.documentId());

        service.validateCitations(
                "结论 [EVIDENCE:" + record.evidenceId() + "]，文献背景 "
                        + "[LITERATURE:" + record.documentId() + "]。",
                List.of(ranked),
                List.of(literature));
    }

    @Test
    void shouldRejectCitationOutsideCurrentEvidenceSelection() {
        CompoundEvidenceRecord record =
                evidence("Metalaxyl", "Phytophthora infestans", "MIC", "8 ug/mL");
        RankedEvidence ranked = new RankedEvidence(record, 5.0, 1, null);

        assertThrows(IllegalStateException.class, () -> service.validateCitations(
                "错误引用 [EVIDENCE:" + UUID.randomUUID() + "]。",
                List.of(ranked),
                List.of()));
    }

    @Test
    void shouldRejectCitationOutsideCurrentLiteratureSelection() {
        CompoundEvidenceRecord record =
                evidence("Metalaxyl", "Phytophthora infestans", "MIC", "8 ug/mL");
        RankedEvidence ranked = new RankedEvidence(record, 5.0, 1, null);

        assertThrows(IllegalStateException.class, () -> service.validateCitations(
                "证据 [EVIDENCE:" + record.evidenceId() + "]，错误文献 "
                        + "[LITERATURE:" + UUID.randomUUID() + "]。",
                List.of(ranked),
                List.of(literature(record.documentId()))));
    }

    @Test
    void reportPromptShouldRequireChineseQuestionFocusedSynthesis() {
        String prompt = PromptResources.load(PromptCatalog.REPORT_EVIDENCE_SYSTEM);

        assertTrue(prompt.contains("始终使用中文回答"));
        assertTrue(prompt.contains("围绕用户的具体问题"));
        assertTrue(prompt.contains("[LITERATURE:<document-uuid>]"));
        assertTrue(prompt.contains("不要生硬复述每个单元格"));
    }

    @Test
    void shouldBuildLiteratureContextWhenAbstractAndChunksAreMissing() {
        UUID documentId = UUID.randomUUID();
        CompoundEvidenceRecord record = evidence(
                documentId, "Metalaxyl", "Phytophthora infestans", "MIC", "8 ug/mL");
        EvidenceRepository evidenceRepository = mock(EvidenceRepository.class);
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        ReportProperties properties = new ReportProperties();
        ReportService contextService = new ReportService();
        ReflectionTestUtils.setField(contextService, "evidenceRepository", evidenceRepository);
        ReflectionTestUtils.setField(contextService, "ragDocumentRepository", documentRepository);
        ReflectionTestUtils.setField(contextService, "properties", properties);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(new RagDocumentRecord(
                documentId, null, null, "key", null, "10.1000/test", "sha",
                "Test paper", List.of("Author"), List.of(), null, "Journal",
                "2026", 2026, null, "paper.pdf", "data", RagDocumentStatus.COMPLETED,
                Instant.now(), Instant.now())));
        when(evidenceRepository.findDocumentChunks(documentId))
                .thenThrow(new IllegalStateException("chunks unavailable"));

        List<ReportService.LiteratureContext> contexts = contextService.buildLiteratureContexts(
                List.of(new RankedEvidence(record, 5.0, 1, null)),
                List.of("metalaxyl"));

        assertEquals(1, contexts.size());
        assertEquals("", contexts.get(0).abstractText());
        assertTrue(contexts.get(0).chunks().isEmpty());
    }

    @Test
    void shouldReplacePublicationNoticeWithSourceFilename() {
        UUID documentId = UUID.randomUUID();
        CompoundEvidenceRecord record = evidence(
                documentId, "Metalaxyl", "Phytophthora infestans", "MIC", "8 ug/mL");
        EvidenceRepository evidenceRepository = mock(EvidenceRepository.class);
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        ReportService contextService = new ReportService();
        ReflectionTestUtils.setField(contextService, "evidenceRepository", evidenceRepository);
        ReflectionTestUtils.setField(contextService, "ragDocumentRepository", documentRepository);
        ReflectionTestUtils.setField(contextService, "properties", new ReportProperties());
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(new RagDocumentRecord(
                documentId, null, null, "key", null, null, "sha",
                "This article has been accepted for publication and copyediting",
                List.of(), List.of(), null, null, null, null, null,
                "useful-paper.pdf", "data", RagDocumentStatus.COMPLETED,
                Instant.now(), Instant.now())));
        when(evidenceRepository.findDocumentChunks(documentId)).thenReturn(List.of());

        List<ReportService.LiteratureContext> contexts = contextService.buildLiteratureContexts(
                List.of(new RankedEvidence(record, 5.0, 1, null)),
                List.of("metalaxyl"));

        assertEquals("useful-paper", contexts.get(0).title());
    }

    private ReportService.LiteratureContext literature(UUID documentId) {
        return new ReportService.LiteratureContext(
                documentId,
                "Test paper",
                List.of("Author"),
                "Journal",
                2026,
                "10.1000/test",
                "Abstract",
                List.of());
    }

    private CompoundEvidenceRecord evidence(
            String compound,
            String organism,
            String method,
            String activity) {
        return evidence(UUID.randomUUID(), compound, organism, method, activity);
    }

    private CompoundEvidenceRecord evidence(
            UUID documentId,
            String compound,
            String organism,
            String method,
            String activity) {
        return evidence(documentId, compound, "synthetic", organism, method, activity);
    }

    private CompoundEvidenceRecord evidence(
            String compound,
            String sourceCategory,
            String organism,
            String method,
            String activity) {
        return evidence(UUID.randomUUID(), compound, sourceCategory, organism, method, activity);
    }

    private CompoundEvidenceRecord evidence(
            UUID documentId,
            String compound,
            String sourceCategory,
            String organism,
            String method,
            String activity) {
        UUID evidenceId = UUID.randomUUID();
        return new CompoundEvidenceRecord(
                evidenceId,
                UUID.randomUUID(),
                documentId,
                "Test paper",
                1,
                new CompoundEvidenceRow(
                        compound, compound, "small molecule", sourceCategory, "",
                        organism, method, activity, "", "", "", "", "", "", "", ""
                ),
                "fingerprint-" + evidenceId,
                NameKind.PURE_COMPOUND,
                "compound:" + compound.toLowerCase(),
                0.8,
                ValidationStatus.VALID,
                List.of(),
                ReviewStatus.PENDING,
                null,
                true,
                List.of(),
                Instant.now(),
                Instant.now()
        );
    }

    private ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private String structuredAnswer(String citation) {
        return """
                ## 范围说明与直接结论
                结论 %s
                ## 数据概览
                概览
                ## 来源分类
                分类
                ## 代表性发现
                发现
                ## 机制与应用
                机制
                ## 冲突或无效结果
                差异
                ## 证据限制
                限制
                ## 关键文献
                暂无
                """.formatted(citation).trim();
    }
}
