package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.example.demo_01.ai.report.config.ReportProperties;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureProfile;
import com.example.demo_01.ai.report.model.ReportModels.ReportDocumentChunk;
import com.example.demo_01.ai.report.repository.ReportLiteratureRepository;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportFullDocumentAnalysisServiceTest {

    @Test
    void windowsShouldCoverEveryChunkAndKeepOneChunkOverlap() {
        ReportFullDocumentAnalysisService service = new ReportFullDocumentAnalysisService();
        ReportProperties properties = new ReportProperties();
        properties.setChunksPerAnalysisBatch(2);
        properties.setMaxCharsPerAnalysisBatch(1000);
        properties.setAnalysisBatchOverlap(1);
        ReflectionTestUtils.setField(service, "properties", properties);
        List<ReportDocumentChunk> chunks = List.of(
                chunk("c0", 0), chunk("c1", 1), chunk("c2", 2), chunk("c3", 3));

        List<List<ReportDocumentChunk>> windows = service.windows(chunks);

        assertEquals(List.of("c0", "c1"),
                windows.get(0).stream().map(ReportDocumentChunk::chunkId).toList());
        assertEquals(List.of("c1", "c2"),
                windows.get(1).stream().map(ReportDocumentChunk::chunkId).toList());
        assertEquals(List.of("c2", "c3"),
                windows.get(2).stream().map(ReportDocumentChunk::chunkId).toList());
        assertEquals(
                List.of("c0", "c1", "c2", "c3"),
                windows.stream().flatMap(List::stream)
                        .map(ReportDocumentChunk::chunkId).distinct().toList());
    }

    @Test
    void analyzeShouldReuseVersionedCacheWithoutCallingModel() {
        ReportFullDocumentAnalysisService service = new ReportFullDocumentAnalysisService();
        ReportLiteratureRepository repository = mock(ReportLiteratureRepository.class);
        ReviewReasoningChatClient chatClient = mock(ReviewReasoningChatClient.class);
        ReportProperties properties = new ReportProperties();
        DashScopeModelProperties modelProperties = modelProperties();
        RagDocumentRecord document = document("pdf-hash");
        LiteratureProfile cached = profile(document);
        when(repository.findDocumentChunks(document.documentId()))
                .thenReturn(List.of(chunk("c0", 0)));
        when(repository.findCachedProfile(
                org.mockito.ArgumentMatchers.eq(document.documentId()),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq("test-model")))
                .thenReturn(Optional.of(cached));
        inject(service, repository, chatClient, properties, modelProperties);

        var result = service.analyze(document);

        assertTrue(result.cached());
        assertEquals(cached, result.profile());
        verify(chatClient, never()).chatStandard(any(), any());
    }

    @Test
    void analyzeShouldPersistProfileWithSupportingChunkIds() {
        ReportFullDocumentAnalysisService service = new ReportFullDocumentAnalysisService();
        ReportLiteratureRepository repository = mock(ReportLiteratureRepository.class);
        ReviewReasoningChatClient chatClient = mock(ReviewReasoningChatClient.class);
        ReportProperties properties = new ReportProperties();
        RagDocumentRecord document = document("pdf-hash");
        when(repository.findDocumentChunks(document.documentId()))
                .thenReturn(List.of(chunk("c0", 0)));
        when(repository.findCachedProfile(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(chatClient.chatStandard(any(), any())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("""
                        {
                          "background": [],
                          "compounds": [],
                          "activity": [{
                            "category": "activity",
                            "statement": "Compound A 的 MIC = 8 mg/L。",
                            "chunkIds": ["c0"]
                          }],
                          "mechanisms": [],
                          "applications": [],
                          "safetyAndResistance": [],
                          "conclusions": [],
                          "limitations": []
                        }
                        """))
                .build());
        inject(service, repository, chatClient, properties, modelProperties());

        var result = service.analyze(document);

        assertEquals(1, result.profile().activity().size());
        assertEquals(List.of("c0"), result.profile().activity().getFirst().chunkIds());
        verify(repository).saveCachedProfile(
                org.mockito.ArgumentMatchers.eq(result.profile()),
                any(),
                org.mockito.ArgumentMatchers.eq("test-model"),
                org.mockito.ArgumentMatchers.eq(1));
    }

    private void inject(ReportFullDocumentAnalysisService service,
                        ReportLiteratureRepository repository,
                        ReviewReasoningChatClient chatClient,
                        ReportProperties properties,
                        DashScopeModelProperties modelProperties) {
        ReflectionTestUtils.setField(service, "literatureRepository", repository);
        ReflectionTestUtils.setField(service, "chatClient", chatClient);
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "modelProperties", modelProperties);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
    }

    private DashScopeModelProperties modelProperties() {
        DashScopeModelProperties properties = new DashScopeModelProperties();
        properties.getChatModel().setModelName("test-model");
        return properties;
    }

    private ReportDocumentChunk chunk(String id, int index) {
        return new ReportDocumentChunk(id, index, "Results", "chunk text " + id);
    }

    private RagDocumentRecord document(String pdfHash) {
        return new RagDocumentRecord(
                UUID.randomUUID(), null, null, "key", null, "10.1000/test", pdfHash,
                "Test paper", List.of("Author"), List.of(), "Abstract", "Journal",
                "2026", 2026, null, "paper.pdf", "data", RagDocumentStatus.COMPLETED,
                Instant.now(), Instant.now());
    }

    private LiteratureProfile profile(RagDocumentRecord document) {
        return new LiteratureProfile(
                document.documentId(), document.title(), document.pdfSha256(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
