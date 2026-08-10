package com.example.demo_01.ai.evidence.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.evidence")
public class EvidenceProperties {

    private boolean enabled = true;

    @Min(1)
    private int asyncThreads = 2;

    @Min(1)
    private int chunkBatchSize = 12;

    @Min(1)
    private int maxAttempts = 3;

    @Min(1)
    private int maxSinglePassChunks = 40;

    @Min(1)
    private int maxSinglePassChars = 120_000;

    private String outputRoot = "Evidence";

    /**
     * Feature switches for the agentized extraction pipeline.
     * All agents default to off so baseline behavior is preserved.
     */
    private Agents agents = new Agents();

    /**
     * On-demand table loading: when an evidence profile needs a table body (e.g. Q1 activity
     * data), the relevant table is parsed from the document TEI, resolved and injected as an
     * anchorable chunk. Disabled by default so baseline behavior is preserved.
     */
    private Table table = new Table();

    private Q1 q1 = new Q1();

    @Data
    public static class Table {
        private boolean enabled = true;
        private List<String> enabledQuestionIds = List.of("Q1");
        /** Use an LLM to pick which table captions are relevant; falls back to keywords on failure. */
        private boolean llmSelect = true;
        @Min(1)
        private int maxTables = 6;
        @Min(1)
        private int maxTableChars = 8_000;
        private Recovery recovery = new Recovery();
    }

    @Data
    public static class Recovery {
        private boolean enabled = true;
        @Min(1)
        private int maxRecoveredTables = 3;
        @Min(1)
        private int maxChars = 12_000;
    }

    @Data
    public static class Q1 {
        private PromptOnlyMarkdown promptOnlyMarkdown = new PromptOnlyMarkdown();
    }

    @Data
    public static class PromptOnlyMarkdown {
        private boolean enabled = true;
    }

    @Data
    public static class Agents {
        private ConstrainedDecoding constrainedDecoding = new ConstrainedDecoding();
        private Verifier verifier = new Verifier();
        private Coverage coverage = new Coverage();
        private Retriever retriever = new Retriever();
        private Reconciler reconciler = new Reconciler();
        private Telemetry telemetry = new Telemetry();
    }

    @Data
    public static class ConstrainedDecoding {
        private boolean enabled = false;
    }

    @Data
    public static class Verifier {
        private boolean enabled = false;
        /** When true, rows failing semantic verification are dropped; otherwise marked INVALID. */
        private boolean dropInvalidRows = false;
    }

    @Data
    public static class Coverage {
        private boolean enabled = false;
        @Min(0)
        private int maxRecoveryRounds = 1;
        @Min(1)
        private int maxCandidates = 40;
    }

    @Data
    public static class Retriever {
        private boolean onDemandEnabled = false;
        @Min(1)
        private int maxChunks = 24;
        @Min(0)
        private int expandParentSections = 1;
        private boolean preferTablesAndFigures = true;
    }

    @Data
    public static class Reconciler {
        private boolean entityLinkingEnabled = false;
        /** When true, unknown mentions are inserted as entity review candidates. */
        private boolean enqueueUnknownAsCandidates = true;
    }

    @Data
    public static class Telemetry {
        private boolean enabled = true;
    }
}
