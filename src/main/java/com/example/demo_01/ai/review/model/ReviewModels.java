package com.example.demo_01.ai.review.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReviewModels {

    private ReviewModels() {
    }

    // ── Enums ──

    public enum ReviewTaskStatus {
        QUEUED, RUNNING, COMPLETED, FAILED
    }

    public enum ReviewStage {
        QUERY_ANALYSIS, QUERY_EXPANSION, RETRIEVAL, RERANKING,
        EVIDENCE_EXTRACTION, EVIDENCE_FUSION, REPORT_GENERATION, COMPLETED, FAILED
    }

    public enum Relevance {
        HIGH, MEDIUM, LOW, IRRELEVANT
    }

    public enum EvidenceType {
        EXPERIMENTAL, COMPUTATIONAL, REVIEW
    }

    public enum Consistency {
        CONSISTENT, CONFLICTING, INSUFFICIENT
    }

    // ── Query Analysis ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryAnalysis(
            String mainQuestion,
            List<String> subQuestions,
            List<String> keyEntities,
            List<String> keyConcepts
    ) {
    }

    // ── Task ──

    public record ReviewTaskRecord(
            UUID taskId,
            String userId,
            String question,
            ReviewTaskStatus status,
            ReviewStage stage,
            QueryAnalysis queryAnalysis,
            String reportMarkdown,
            Integer candidateCount,
            Integer evidenceCount,
            ReviewTaskMetrics metrics,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) {
    }

    public record ReviewTaskMetrics(
            Long retrievalMs,
            Long rerankMs,
            Long extractionMs,
            Long fusionMs,
            Long reportMs,
            Long totalMs
    ) {
    }

    public record ReviewTaskSubmitRequest(String question) {
    }

    public record ReviewTaskAcceptedResponse(UUID taskId, ReviewTaskStatus status) {
    }

    // ── Candidate ──

    public record ReviewCandidate(
            Long id,
            UUID taskId,
            String chunkId,
            UUID documentId,
            String documentTitle,
            double retrievalScore,
            String retrievalSource,
            Double rerankScore,
            Relevance relevance,
            String screeningReason,
            boolean included,
            String chunkText
    ) {
    }

    // ── Evidence ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedEvidence(
            String chunkId,
            String documentId,
            String documentTitle,
            String claim,
            String finding,
            String methodology,
            List<String> entities,
            String evidenceType,
            double confidence,
            String originalText,
            String subQuestion
    ) {
    }

    public record ReviewEvidenceRecord(
            Long id,
            UUID taskId,
            Long candidateId,
            String chunkId,
            UUID documentId,
            String claim,
            String finding,
            String methodology,
            List<String> entities,
            String evidenceType,
            double confidence,
            String originalText,
            String normalizedGroup,
            String subQuestion,
            String consistency
    ) {
    }

    // ── Evidence Fusion ──

    public record EvidenceCluster(
            String claimSummary,
            Consistency consistency,
            List<ExtractedEvidence> evidences,
            List<String> sourceDocuments
    ) {
    }

    public record FusedEvidenceGroup(
            String subQuestion,
            String groupSummary,
            List<EvidenceCluster> clusters,
            int supportingCount,
            int conflictingCount,
            List<String> consistencyNotes
    ) {
    }

    // ── Report ──

    public record Citation(
            String documentTitle,
            String documentId,
            String chunkId,
            String doi
    ) {
    }

    public record ReportSection(
            String subQuestion,
            String heading,
            String content,
            int evidenceCount,
            List<Citation> sectionReferences
    ) {
    }

    public record ReviewReport(
            String title,
            String executiveSummary,
            List<ReportSection> sections,
            String crossAnalysis,
            String limitations,
            String futureDirections,
            List<Citation> references
    ) {
    }

    // ── Retrieval intermediates ──

    public record RetrievedChunk(
            String chunkId,
            UUID documentId,
            String documentTitle,
            String text,
            String sectionPath,
            double score,
            String source
    ) {
    }

    // ── LLM reranking output ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkRelevanceJudgment(
            String chunkId,
            Relevance relevance,
            String reason
    ) {
    }
}
