package com.example.demo_01.ai.pretreatment;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PretreatmentModels {

    private PretreatmentModels() {
    }

    public enum PretreatmentRunStatus {
        RUNNING, COMPLETED, FAILED
    }

    public enum PretreatmentMode {
        scan, apply
    }

    public enum TitleDecision {
        TITLE_MATCH, TITLE_UNCERTAIN, REJECT_NO_OOMYCETE_SIGNAL
    }

    public enum QualityDecision {
        PASS, REJECT
    }

    public enum TitleVectorDecision {
        PASS, REJECT_LOW_TITLE_RELEVANCE
    }

    public enum JournalQualityTier {
        HIGH, MEDIUM, LOW, UNKNOWN
    }

    public enum LlmLabel {
        PRIMARY_OOMYCETE, INCIDENTAL_MENTION, NOT_OOMYCETE, UNCERTAIN, NOT_RUN
    }

    public enum FinalDecision {
        ACCEPTED, REJECTED, UNCERTAIN, SKIPPED
    }

    public record ArtifactDocument(
            UUID documentId,
            String storageDir,
            PreprocessArtifact manifest,
            List<RagChunk> chunks
    ) {
        public RagDocumentMetadata metadata() {
            return manifest == null ? null : manifest.metadata();
        }
    }

    public record SkippedArtifact(
            UUID documentId,
            String storageDir,
            String reason
    ) {
    }

    public record ArtifactScan(
            List<ArtifactDocument> documents,
            List<SkippedArtifact> skipped
    ) {
    }

    public record JournalQuality(
            JournalQualityTier tier,
            boolean trusted,
            String casPartition,
            String source,
            String note
    ) {
        public static JournalQuality unknown() {
            return new JournalQuality(JournalQualityTier.UNKNOWN, false, null, null, null);
        }
    }

    public record ResolvedJournal(
            String rawJournal,
            String resolvedJournal,
            List<String> issns,
            String publisher,
            String source,
            double confidence,
            JournalQuality quality
    ) {
        public static ResolvedJournal raw(String rawJournal, JournalQuality quality) {
            return new ResolvedJournal(rawJournal, rawJournal, List.of(), null, "RAW_METADATA", 0.3, quality);
        }
    }

    public record RepresentativeChunk(
            String chunkId,
            int chunkIndex,
            String sectionPath,
            String text
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LlmJudgment(
            LlmLabel label,
            Double confidence,
            List<String> taxa,
            String researchFocus,
            List<String> evidenceChunkIds,
            String reason
    ) {
        public static LlmJudgment notRun(String reason) {
            return new LlmJudgment(LlmLabel.NOT_RUN, 0.0, List.of(), "", List.of(), reason);
        }
    }

    public record PretreatmentDocumentResult(
            UUID runId,
            UUID documentId,
            String storageDir,
            String title,
            String journal,
            String doi,
            QualityDecision qualityDecision,
            Map<String, Object> qualityMetrics,
            TitleDecision titleDecision,
            Double titleVectorScore,
            String titleBestProfileTerm,
            Map<String, Boolean> titleThresholdPasses,
            TitleVectorDecision titleVectorDecision,
            JournalQualityTier journalQuality,
            LlmLabel llmLabel,
            double confidence,
            FinalDecision finalDecision,
            String rejectReasonCode,
            List<String> taxa,
            String researchFocus,
            List<String> evidenceChunkIds,
            String reason
    ) {
    }

    public record PretreatmentRunSummary(
            UUID runId,
            PretreatmentMode mode,
            String outputDir,
            int totalArtifacts,
            int processedDocuments,
            int acceptedDocuments,
            int rejectedDocuments,
            int uncertainDocuments,
            int skippedDocuments,
            int vectorsRemoved,
            boolean dryRun,
            Instant startedAt,
            Instant finishedAt
    ) {
    }
}
