package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.DocumentKnowledgeRepository;
import com.example.demo_01.ai.review.service.CompoundIdentityResolver;
import com.example.demo_01.ai.review.service.DocumentKnowledgeEnrichmentService;
import com.example.demo_01.ai.review.service.DocumentKnowledgeMerger;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentKnowledgeEnrichmentServiceTest {

    @Test
    void enrichShouldExtractAndPersistWhenCacheMiss() {
        Fixture fixture = fixture();
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        RetrievedChunk chunk = chunk(documentId, "chunk-1", "compound 1 was identified as kaempferol.");
        when(fixture.repository.findKnowledge(documentId)).thenReturn(Optional.empty());
        when(fixture.repository.findAliasesByDocumentIds(any())).thenReturn(Map.of(
                documentId,
                List.of(new DocumentCompoundAlias(documentId, "compound 1", "kaempferol",
                        UUID.randomUUID().toString(), "chunk-1", "compound 1 was identified as kaempferol.",
                        CompoundResolutionStatus.RESOLVED, 0.91))
        ));
        when(fixture.chatModel.chat(any(), any())).thenReturn(response("""
                {
                  "documentSummary": "A compound bioactivity paper.",
                  "compounds": [{
                    "localAlias": "compound 1",
                    "resolvedName": "kaempferol",
                    "canonicalName": "kaempferol",
                    "molecularFormula": "C15H10O6",
                    "resolutionStatus": "RESOLVED",
                    "evidenceChunkId": "chunk-1",
                    "evidenceText": "compound 1 was identified as kaempferol.",
                    "confidence": 0.91
                  }],
                  "keyFindings": ["Kaempferol showed bioactivity."],
                  "innovationPoints": ["Resolved local compound label."],
                  "confidence": 0.91
                }
                """));

        Map<UUID, DocumentKnowledgeContext> result = fixture.service.enrich(
                taskId,
                new QueryAnalysis("Which compounds inhibit pathogens?", List.of("Which compounds are active?"),
                        List.of(), List.of("compound activity")),
                List.of(chunk)
        );

        assertEquals(KnowledgeStatus.HIT, result.get(documentId).knowledgeStatus());
        assertTrue(result.get(documentId).knownCompounds().contains("kaempferol"));
        verify(fixture.chatModel).chat(any(), any());
        verify(fixture.repository).upsertKnowledge(any(DocumentKnowledgeRecord.class));
        verify(fixture.repository).upsertAlias(eq(documentId), any(DocumentKnowledgeCompound.class));
        verify(fixture.repository).upsertCompoundIdentity(any(CompoundIdentity.class));
    }

    @Test
    void enrichShouldUseCacheWhenHit() {
        Fixture fixture = fixture();
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        RetrievedChunk chunk = chunk(documentId, "chunk-1", "cached text");
        DocumentKnowledgeRecord cached = cached(documentId, List.of("chunk-1"),
                List.of(new DocumentKnowledgeCompound("compound 1", "kaempferol", "kaempferol",
                        null, null, null, null, "C15H10O6", null, null, null,
                        List.of(), List.of(), CompoundResolutionStatus.RESOLVED, "chunk-1",
                        "compound 1 was identified as kaempferol.", 0.9, UUID.randomUUID().toString())));
        when(fixture.repository.findKnowledge(documentId)).thenReturn(Optional.of(cached));
        when(fixture.repository.findAliasesByDocumentIds(any())).thenReturn(Map.of());

        Map<UUID, DocumentKnowledgeContext> result = fixture.service.enrich(
                taskId,
                new QueryAnalysis("Which genes are involved?", List.of("Which genes?"), List.of(), List.of()),
                List.of(chunk)
        );

        assertEquals(KnowledgeStatus.HIT, result.get(documentId).knowledgeStatus());
        verify(fixture.chatModel, never()).chat(any(), any());
        verify(fixture.repository, never()).upsertKnowledge(any());
        verify(fixture.repository).insertUpdateLog(eq(taskId), eq(documentId), any(), eq(List.of("cacheHit")), any());
    }

    @Test
    void enrichShouldRefreshPartialCacheWhenCompoundDataMissing() {
        Fixture fixture = fixture();
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        RetrievedChunk chunk = chunk(documentId, "chunk-1", "compound 2 had activity but was unresolved.");
        when(fixture.repository.findKnowledge(documentId)).thenReturn(Optional.of(cached(documentId, List.of("chunk-1"), List.of())));
        when(fixture.repository.findAliasesByDocumentIds(any())).thenReturn(Map.of());
        when(fixture.chatModel.chat(any(), any())).thenReturn(response("""
                {
                  "documentSummary": "Partial compound paper.",
                  "compounds": [{
                    "localAlias": "compound 2",
                    "resolutionStatus": "UNRESOLVED",
                    "evidenceChunkId": "chunk-1",
                    "evidenceText": "compound 2 had activity but was unresolved.",
                    "confidence": 0.6
                  }],
                  "confidence": 0.6
                }
                """));

        fixture.service.enrich(
                taskId,
                new QueryAnalysis("Which compounds are active?", List.of("Which compounds?"), List.of(), List.of("compound")),
                List.of(chunk)
        );

        verify(fixture.chatModel).chat(any(), any());
        verify(fixture.repository).upsertAlias(eq(documentId), any(DocumentKnowledgeCompound.class));
    }

    @Test
    void enrichShouldFallbackWhenLlmFails() {
        Fixture fixture = fixture();
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        RetrievedChunk chunk = chunk(documentId, "chunk-1", "important evidence text");
        when(fixture.repository.findKnowledge(documentId)).thenReturn(Optional.empty());
        when(fixture.repository.findAliasesByDocumentIds(any())).thenReturn(Map.of());
        when(fixture.chatModel.chat(any(), any())).thenThrow(new RuntimeException("llm unavailable"));

        Map<UUID, DocumentKnowledgeContext> result = fixture.service.enrich(
                taskId,
                new QueryAnalysis("question", List.of("sub"), List.of(), List.of()),
                List.of(chunk)
        );

        assertEquals(KnowledgeStatus.HIT, result.get(documentId).knowledgeStatus());
        verify(fixture.repository).upsertKnowledge(any(DocumentKnowledgeRecord.class));
    }

    private Fixture fixture() {
        DocumentKnowledgeEnrichmentService service = new DocumentKnowledgeEnrichmentService();
        DocumentKnowledgeRepository repository = mock(DocumentKnowledgeRepository.class);
        ChatModel chatModel = mock(ChatModel.class);
        ReflectionTestUtils.setField(service, "documentKnowledgeRepository", repository);
        ReflectionTestUtils.setField(service, "chatModel", chatModel);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "compoundIdentityResolver", new CompoundIdentityResolver());
        ReflectionTestUtils.setField(service, "documentKnowledgeMerger", new DocumentKnowledgeMerger());
        return new Fixture(service, repository, chatModel);
    }

    private RetrievedChunk chunk(UUID documentId, String chunkId, String text) {
        return new RetrievedChunk(chunkId, documentId, "Paper A", text, "Results", 0.8, "BM25");
    }

    private DocumentKnowledgeRecord cached(UUID documentId,
                                           List<String> coverage,
                                           List<DocumentKnowledgeCompound> compounds) {
        return new DocumentKnowledgeRecord(
                documentId,
                "cached",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                compounds,
                List.of("cached finding"),
                List.of(),
                List.of(),
                List.of(),
                KnowledgeStatus.HIT,
                DocumentKnowledgeEnrichmentService.PROMPT_VERSION,
                "v1",
                0.9,
                coverage,
                UUID.randomUUID(),
                Instant.now()
        );
    }

    private ChatResponse response(String json) {
        return ChatResponse.builder().aiMessage(AiMessage.from(json)).build();
    }

    private record Fixture(
            DocumentKnowledgeEnrichmentService service,
            DocumentKnowledgeRepository repository,
            ChatModel chatModel
    ) {}
}
