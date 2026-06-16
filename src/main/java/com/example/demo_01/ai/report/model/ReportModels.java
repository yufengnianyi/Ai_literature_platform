package com.example.demo_01.ai.report.model;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReportModels {

    private ReportModels() {
    }

    public enum ReportStatus {
        QUEUED,
        REWRITING,
        MATCHING,
        GENERATING,
        PLANNING,
        ANALYZING_EVIDENCE,
        RETRIEVING_LITERATURE,
        ANALYZING_LITERATURE,
        SYNTHESIZING,
        VALIDATING,
        COMPLETED,
        PARTIAL_COMPLETED,
        FAILED;

        public boolean terminal() {
            return this == COMPLETED || this == PARTIAL_COMPLETED || this == FAILED;
        }
    }

    public record SubmitReportRequest(String conversationId, String question) {
    }

    public record ReportRunRecord(
            UUID reportId,
            String userId,
            String conversationId,
            String question,
            String rewrittenQuestion,
            ReportStatus status,
            int evidenceCount,
            String attachmentFileName,
            String attachmentRelativePath,
            String answerMarkdown,
            long userMessageSeqNo,
            long assistantMessageSeqNo,
            String errorCode,
            String errorMessage,
            String phaseMessage,
            int progressPercent,
            int selectedDocumentCount,
            int analyzedDocumentCount,
            List<String> warnings,
            Long totalMs,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) {
    }

    public record ReportRunResponse(
            UUID reportId,
            String conversationId,
            String question,
            String rewrittenQuestion,
            ReportStatus status,
            int evidenceCount,
            String attachmentFileName,
            boolean attachmentAvailable,
            String answerMarkdown,
            String errorMessage,
            String phaseMessage,
            int progressPercent,
            int selectedDocumentCount,
            int analyzedDocumentCount,
            List<String> warnings,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) {
    }

    public record RankedEvidence(
            CompoundEvidenceRecord evidence,
            double matchScore,
            int rank,
            String conflictGroup
    ) {
    }

    public record QueryTerms(String rewrittenQuestion, List<String> terms) {
    }

    public enum LiteratureSourceType {
        DIRECT, SUPPLEMENTAL
    }

    public enum LiteratureAnalysisStatus {
        PENDING, CACHED, COMPLETED, FAILED
    }

    public record ReportDocumentChunk(
            String chunkId,
            int chunkIndex,
            String sectionPath,
            String text
    ) {
    }

    public record LiteratureClaim(
            String category,
            String statement,
            List<String> chunkIds
    ) {
        public LiteratureClaim {
            chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        }
    }

    public record LiteratureProfile(
            UUID documentId,
            String title,
            String documentHash,
            List<LiteratureClaim> background,
            List<LiteratureClaim> compounds,
            List<LiteratureClaim> activity,
            List<LiteratureClaim> mechanisms,
            List<LiteratureClaim> applications,
            List<LiteratureClaim> safetyAndResistance,
            List<LiteratureClaim> conclusions,
            List<LiteratureClaim> limitations
    ) {
        public LiteratureProfile {
            background = safe(background);
            compounds = safe(compounds);
            activity = safe(activity);
            mechanisms = safe(mechanisms);
            applications = safe(applications);
            safetyAndResistance = safe(safetyAndResistance);
            conclusions = safe(conclusions);
            limitations = safe(limitations);
        }

        private static List<LiteratureClaim> safe(List<LiteratureClaim> value) {
            return value == null ? List.of() : List.copyOf(value);
        }
    }

    public record SelectedLiterature(
            UUID documentId,
            String title,
            LiteratureSourceType sourceType,
            int rank,
            double relevanceScore,
            String selectionReason
    ) {
    }

    public record SectionEvidenceMatrix(
            Map<String, Integer> coverage,
            List<String> missingSections,
            List<String> retrievalQueries
    ) {
    }

    public record ReportClaimDraft(
            String sectionKey,
            String text,
            List<UUID> evidenceIds,
            Map<UUID, List<String>> chunksByDocument
    ) {
    }
}
