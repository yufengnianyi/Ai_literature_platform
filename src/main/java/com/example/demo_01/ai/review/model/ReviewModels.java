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
        QUEUED, RUNNING, AWAITING_USER, COMPLETED, FAILED
    }

    public enum ReviewStage {
        QUERY_ANALYSIS, QUERY_EXPANSION, RETRIEVAL, DOCUMENT_PROMOTION, RERANKING,
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

    public enum KnowledgeStatus {
        MISS, PARTIAL, HIT, STALE
    }

    public enum CompoundResolutionStatus {
        RESOLVED, AMBIGUOUS, UNRESOLVED
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TypedEntities(
            List<String> species,
            List<String> geneOrProtein,
            List<String> pathwayOrProcess,
            List<String> developmentalStage,
            List<String> phenotype,
            List<String> method,
            List<String> moleculeOrMetabolite,
            List<String> compoundStructureType,
            List<String> compoundSource,
            List<String> antimicrobialActivity,
            List<String> assayMethod,
            List<String> targetOrganism,
            List<String> proposedTarget,
            List<String> mechanism,
            List<String> reference,
            List<String> patentStatus,
            List<String> compoundLocalAlias,
            List<String> compoundCanonicalName,
            List<String> compoundIdentifier,
            List<String> compoundResolutionStatus,
            List<String> cytotoxicitySafety
    ) {
        public TypedEntities(List<String> species,
                             List<String> geneOrProtein,
                             List<String> pathwayOrProcess,
                             List<String> developmentalStage,
                             List<String> phenotype,
                             List<String> method,
                             List<String> moleculeOrMetabolite,
                             List<String> compoundStructureType,
                             List<String> compoundSource,
                             List<String> antimicrobialActivity,
                             List<String> assayMethod,
                             List<String> targetOrganism,
                             List<String> proposedTarget,
                             List<String> mechanism,
                             List<String> reference,
                             List<String> patentStatus,
                             List<String> compoundLocalAlias,
                             List<String> compoundCanonicalName,
                             List<String> compoundIdentifier,
                             List<String> compoundResolutionStatus) {
            this(species, geneOrProtein, pathwayOrProcess, developmentalStage, phenotype, method,
                    moleculeOrMetabolite, compoundStructureType, compoundSource, antimicrobialActivity,
                    assayMethod, targetOrganism, proposedTarget, mechanism, reference, patentStatus,
                    compoundLocalAlias, compoundCanonicalName, compoundIdentifier, compoundResolutionStatus,
                    List.of());
        }

        public TypedEntities(List<String> species,
                             List<String> geneOrProtein,
                             List<String> pathwayOrProcess,
                             List<String> developmentalStage,
                             List<String> phenotype,
                             List<String> method,
                             List<String> moleculeOrMetabolite,
                             List<String> compoundStructureType,
                             List<String> compoundSource,
                             List<String> antimicrobialActivity,
                             List<String> assayMethod,
                             List<String> targetOrganism,
                             List<String> proposedTarget,
                             List<String> mechanism,
                             List<String> reference,
                             List<String> patentStatus) {
            this(species, geneOrProtein, pathwayOrProcess, developmentalStage, phenotype, method,
                    moleculeOrMetabolite, compoundStructureType, compoundSource, antimicrobialActivity,
                    assayMethod, targetOrganism, proposedTarget, mechanism, reference, patentStatus,
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    // ── Query Analysis ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryAnalysis(
            String mainQuestion,
            List<String> subQuestions,
            List<String> keyEntities,
            List<String> keyConcepts,
            String languageCode,
            String displayMainQuestion,
            List<String> displaySubQuestions
    ) {
        public QueryAnalysis(String mainQuestion,
                             List<String> subQuestions,
                             List<String> keyEntities,
                             List<String> keyConcepts) {
            this(mainQuestion, subQuestions, keyEntities, keyConcepts,
                    null, null, null);
        }
    }

    public record ReviewScopePreview(
            QueryAnalysis analysis,
            List<QuestionOption> questions,
            List<EntityOption> entities,
            List<DocumentOption> documents
    ) {
    }

    public record QuestionOption(
            String id,
            String canonicalText,
            String displayText,
            boolean selected
    ) {
    }

    public record EntityOption(
            String id,
            String canonicalName,
            String displayName,
            String category,
            List<String> relatedQuestionIds,
            boolean selected
    ) {
    }

    public record DocumentOption(
            String id,
            UUID documentId,
            String title,
            List<String> relatedQuestionIds,
            List<String> relatedEntities,
            List<String> keyFindings,
            List<String> innovationPoints,
            Relevance relevance,
            Double score,
            String reason,
            boolean selected,
            List<String> chunkIds,
            String previewText,
            KnowledgeStatus knowledgeStatus,
            List<DocumentCompoundAlias> compoundAliases,
            List<String> compounds
    ) {
    }

    // Review-time document knowledge cache

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentKnowledgeRecord(
            UUID documentId,
            String documentSummary,
            List<String> researchObjects,
            List<String> species,
            List<String> genesOrProteins,
            List<String> pathwaysOrProcesses,
            List<String> developmentalStages,
            List<String> methods,
            List<DocumentKnowledgeCompound> compounds,
            List<String> keyFindings,
            List<String> innovationPoints,
            List<String> limitations,
            List<DocumentEvidenceAnchor> evidenceAnchors,
            KnowledgeStatus knowledgeStatus,
            String promptVersion,
            String knowledgeVersion,
            double confidence,
            List<String> coverageChunkIds,
            UUID lastSeenTaskId,
            Instant updatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentKnowledgeCompound(
            String localAlias,
            String resolvedName,
            String canonicalName,
            String iupacName,
            String casNumber,
            String smiles,
            String inchiKey,
            String molecularFormula,
            String structureType,
            String source,
            String bioactivity,
            List<String> targetOrganism,
            List<String> mechanism,
            CompoundResolutionStatus resolutionStatus,
            String evidenceChunkId,
            String evidenceText,
            double confidence,
            String normalizedCompoundId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentEvidenceAnchor(
            String sourceChunkId,
            String evidenceText,
            String note,
            double confidence
    ) {
    }

    public record DocumentCompoundAlias(
            UUID documentId,
            String localAlias,
            String resolvedName,
            String normalizedCompoundId,
            String evidenceChunkId,
            String evidenceText,
            CompoundResolutionStatus resolutionStatus,
            double confidence
    ) {
    }

    public record CompoundIdentity(
            String compoundId,
            String canonicalName,
            String iupacName,
            String casNumber,
            String smiles,
            String inchiKey,
            String molecularFormula,
            String structureType,
            List<String> synonyms,
            double confidence
    ) {
    }

    public record DocumentKnowledgeContext(
            UUID documentId,
            KnowledgeStatus knowledgeStatus,
            List<DocumentCompoundAlias> compoundAliases,
            List<String> knownCompounds,
            List<String> species,
            List<String> genesOrProteins,
            List<String> pathwaysOrProcesses,
            List<String> developmentalStages,
            List<String> methods,
            List<String> keyFindings,
            List<String> innovationPoints
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
            Integer documentCount,
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
            Long documentPromotionMs,
            Long rerankMs,
            Long extractionMs,
            Long fusionMs,
            Long reportMs,
            Long totalMs
    ) {
    }

    public record ReviewTaskSubmitRequest(String question) {
    }

    public record ReviewGenerateRequest(
            String question,
            String mainQuestion,
            List<String> selectedSubQuestions,
            List<String> selectedEntities,
            List<String> selectedConcepts,
            List<String> customSubQuestions,
            String languageCode,
            String displayMainQuestion,
            List<String> displaySubQuestions
    ) {
        public QueryAnalysis toFilteredAnalysis() {
            List<String> allSubQuestions = new java.util.ArrayList<>(
                    selectedSubQuestions != null ? selectedSubQuestions : List.of());
            if (customSubQuestions != null) {
                allSubQuestions.addAll(customSubQuestions);
            }
            List<String> allDisplaySubQuestions = displaySubQuestions != null
                    ? new java.util.ArrayList<>(displaySubQuestions)
                    : null;
            if (allDisplaySubQuestions != null && customSubQuestions != null) {
                allDisplaySubQuestions.addAll(customSubQuestions);
            }
            return new QueryAnalysis(
                    mainQuestion != null ? mainQuestion : question,
                    allSubQuestions,
                    selectedEntities != null ? selectedEntities : List.of(),
                    selectedConcepts != null ? selectedConcepts : List.of(),
                    languageCode,
                    displayMainQuestion,
                    allDisplaySubQuestions
            );
        }
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
            String sectionPath,
            String retrievalPhase,
            Double documentScore,
            Relevance documentRelevance,
            String documentReason,
            Double rerankScore,
            Relevance relevance,
            String screeningReason,
            boolean included,
            String chunkText
    ) {
    }

    public record ReviewDocumentCandidate(
            Long id,
            UUID taskId,
            UUID documentId,
            String documentTitle,
            int seedChunkCount,
            List<String> seedChunkIds,
            Double seedMaxScore,
            Double seedAvgTop3Score,
            Double sectionPriorScore,
            Double entityCoverageScore,
            Double contributionScore,
            Double finalScore,
            Relevance relevance,
            String promotionReason,
            String synopsisSummary,
            List<String> innovationPoints,
            List<String> keyFindings,
            boolean expanded,
            boolean selected
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
            TypedEntities typedEntities,
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
            String documentTitle,
            String claim,
            String finding,
            String methodology,
            TypedEntities typedEntities,
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

    public record ReviewSummaryTable(
            String id,
            String title,
            List<String> headers,
            List<List<String>> rows
    ) {
    }

    // ── Checkpoint requests ──

    public record CandidateReviewRequest(
            List<String> excludedChunkIds,
            List<String> prioritizedChunkIds,
            List<UUID> selectedDocumentIds,
            List<String> selectedQuestionIds,
            List<String> selectedEntityIds
    ) {
        public CandidateReviewRequest(List<String> excludedChunkIds,
                                      List<String> prioritizedChunkIds) {
            this(excludedChunkIds, prioritizedChunkIds, List.of(), List.of(), List.of());
        }
    }

    public record EvidenceReviewRequest(
            List<Long> excludedEvidenceIds,
            List<String> focusSubQuestions,
            String userGuidance
    ) {}

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
