package com.example.demo_01.ai.entitylibrary;

import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EntityLibraryRow;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.EvidenceItem;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewCandidateView;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewDecisionRequest;
import com.example.demo_01.ai.entitylibrary.model.EntityLibraryModels.ReviewStatus;
import com.example.demo_01.ai.entitylibrary.repository.EntityLibraryRepository;
import com.example.demo_01.ai.entitylibrary.service.EntityReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityReviewServiceTest {

    @Mock
    private EntityLibraryRepository entityLibraryRepository;

    @InjectMocks
    private EntityReviewService entityReviewService;

    private UUID lastInsertedEntityId;

    @Test
    void approveCreatesNewEntityAndDeduplicatesEvidenceByQuoteHash() {
        UUID candidateId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        ReviewCandidateView pending = new ReviewCandidateView(
                candidateId,
                "COMPOUND",
                "oxathiapiprolin",
                "Oxathiapiprolin",
                "oxathiapiprolin",
                List.of("OXTP"),
                "oxathiapiprolin inhibited mycelial growth",
                List.of(
                        new EvidenceItem("c1", "Oxathiapiprolin inhibited mycelial growth"),
                        new EvidenceItem("c2", "oxathiapiprolin   inhibited mycelial growth")
                ),
                0.91,
                documentId,
                "Demo paper",
                ReviewStatus.PENDING.name(),
                null,
                null,
                null,
                null
        );

        when(entityLibraryRepository.findCandidate(candidateId))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(new ReviewCandidateView(
                        candidateId,
                        pending.entityType(),
                        pending.mentionText(),
                        pending.canonicalName(),
                        pending.normalizedKey(),
                        pending.aliases(),
                        pending.reason(),
                        pending.evidence(),
                        pending.confidence(),
                        pending.sourceDocumentId(),
                        pending.sourceTitle(),
                        ReviewStatus.APPROVED.name(),
                        "ok",
                        null,
                        UUID.randomUUID(),
                        null
                )));
        when(entityLibraryRepository.lockByKey("COMPOUND", "oxathiapiprolin"))
                .thenReturn(Optional.empty());
        doAnswer(invocation -> {
            lastInsertedEntityId = invocation.getArgument(0);
            return null;
        }).when(entityLibraryRepository).insertEntity(
                any(UUID.class), anyString(), anyString(), anyString(), anyList(), anyInt());
        when(entityLibraryRepository.findByKey(eq("COMPOUND"), eq("oxathiapiprolin")))
                .thenAnswer(invocation -> Optional.of(new EntityLibraryRow(
                        lastInsertedEntityId,
                        "COMPOUND",
                        "oxathiapiprolin",
                        "Oxathiapiprolin",
                        List.of("OXTP"),
                        null,
                        "ACTIVE",
                        1
                )));

        ReviewCandidateView result = entityReviewService.decide(
                candidateId, new ReviewDecisionRequest("APPROVED", "ok"));

        assertEquals(ReviewStatus.APPROVED.name(), result.reviewStatus());
        verify(entityLibraryRepository).insertEntity(
                eq(lastInsertedEntityId),
                eq("COMPOUND"),
                eq("oxathiapiprolin"),
                eq("Oxathiapiprolin"),
                eq(List.of("OXTP")),
                eq(1));
        verify(entityLibraryRepository, never()).updateEntityOnMerge(any(), anyList());

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityLibraryRepository, times(2)).insertEvidenceIfAbsent(
                eq(lastInsertedEntityId),
                eq("oxathiapiprolin inhibited mycelial growth"),
                anyString(),
                eq(0.91),
                eq(documentId),
                eq("Demo paper"),
                hashCaptor.capture());

        String expectedHash = sha256("oxathiapiprolin inhibited mycelial growth");
        assertEquals(List.of(expectedHash, expectedHash), hashCaptor.getAllValues());
        assertEquals(64, expectedHash.length());
    }

    @Test
    @SuppressWarnings("unchecked")
    void approveMergesAliasesWhenEntityAlreadyExists() {
        UUID candidateId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        ReviewCandidateView pending = new ReviewCandidateView(
                candidateId,
                "SPECIES",
                "P. infestans",
                "Phytophthora infestans",
                "phytophthora_infestans",
                List.of("P. infestans"),
                "late blight pathogen",
                List.of(new EvidenceItem("c1", "Phytophthora infestans causes late blight")),
                0.88,
                null,
                null,
                ReviewStatus.PENDING.name(),
                null,
                null,
                entityId,
                null
        );

        when(entityLibraryRepository.findCandidate(candidateId))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(pending));
        when(entityLibraryRepository.lockByKey("SPECIES", "phytophthora_infestans"))
                .thenReturn(Optional.of(new EntityLibraryRow(
                        entityId,
                        "SPECIES",
                        "phytophthora_infestans",
                        "Phytophthora infestans",
                        List.of("potato late blight pathogen"),
                        null,
                        "ACTIVE",
                        2
                )));

        entityReviewService.decide(candidateId, new ReviewDecisionRequest("approve", null));

        ArgumentCaptor<List<String>> aliasesCaptor = ArgumentCaptor.forClass(List.class);
        verify(entityLibraryRepository).updateEntityOnMerge(eq(entityId), aliasesCaptor.capture());
        assertEquals(List.of("potato late blight pathogen", "P. infestans"), aliasesCaptor.getValue());
        verify(entityLibraryRepository, never()).insertEntity(
                any(), anyString(), anyString(), anyString(), anyList(), anyInt());
        verify(entityLibraryRepository).insertEvidenceIfAbsent(
                eq(entityId),
                eq("late blight pathogen"),
                eq("Phytophthora infestans causes late blight"),
                eq(0.88),
                isNull(),
                isNull(),
                eq(sha256("phytophthora infestans causes late blight")));
        verify(entityLibraryRepository).updateCandidateDecision(
                eq(candidateId), eq(ReviewStatus.APPROVED), isNull(), eq(entityId));
    }

    @Test
    void rejectOnlyUpdatesCandidateStatus() {
        UUID candidateId = UUID.randomUUID();
        ReviewCandidateView pending = new ReviewCandidateView(
                candidateId,
                "OTHER",
                "noise",
                "noise",
                "noise",
                List.of(),
                "not useful",
                List.of(),
                0.2,
                null,
                null,
                ReviewStatus.PENDING.name(),
                null,
                null,
                null,
                null
        );
        when(entityLibraryRepository.findCandidate(candidateId))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(new ReviewCandidateView(
                        candidateId, "OTHER", "noise", "noise", "noise", List.of(), "not useful",
                        List.of(), 0.2, null, null, ReviewStatus.REJECTED.name(), "skip", null, null, null)));

        entityReviewService.decide(candidateId, new ReviewDecisionRequest("REJECTED", "skip"));

        verify(entityLibraryRepository).updateCandidateDecision(
                eq(candidateId), eq(ReviewStatus.REJECTED), eq("skip"), isNull());
        verify(entityLibraryRepository, never()).lockByKey(anyString(), anyString());
        verify(entityLibraryRepository, never()).insertEntity(
                any(), anyString(), anyString(), anyString(), anyList(), anyInt());
    }

    private static String sha256(String normalizedQuote) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedQuote.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
