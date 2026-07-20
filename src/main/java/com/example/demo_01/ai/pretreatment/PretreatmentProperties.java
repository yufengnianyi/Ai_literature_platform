package com.example.demo_01.ai.pretreatment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.pretreatment")
public class PretreatmentProperties {

    @NotBlank
    private String artifactRoot = "data/rag";

    @NotBlank
    private String outputRoot = "PreTreatment/outputs";

    @NotBlank
    private String journalQualityPath = "PreTreatment/config/journal-quality.csv";

    @NotBlank
    private String promptPath = "PreTreatment/prompts/oomycete-main-study-system.txt";

    @DecimalMin("0.0")
    private double acceptanceConfidenceThreshold = 0.65;

    @Min(0)
    private int maxDocuments = 0;

    @Min(1)
    private int representativeChunks = 8;

    @Min(1000)
    private int maxLlmInputChars = 24000;

    @Min(1)
    private int llmMaxAttempts = 3;

    @Valid
    private Quality quality = new Quality();

    @Valid
    private TitleVector titleVector = new TitleVector();

    @Valid
    private JournalResolution journalResolution = new JournalResolution();

    @Valid
    private Cli cli = new Cli();

    @Data
    public static class JournalResolution {
        private boolean enabled = true;
        private String crossrefBaseUrl = "https://api.crossref.org";
        private String crossrefUserAgent = "PreTreatment/1.0 (mailto:example@example.com)";
        @Min(1)
        private int connectTimeoutMs = 5000;
        @Min(1)
        private int readTimeoutMs = 15000;
    }

    @Data
    public static class Cli {
        private boolean enabled = false;
        private String mode = "scan";
        private String applyRunId;
        private boolean dryRun = true;
    }

    @Data
    public static class Quality {
        @Min(0)
        private int minChunks = 3;
        @Min(0)
        private int minTotalTextChars = 1500;
        @DecimalMin("0.0")
        private double maxReplacementCharRatio = 0.03;
        @DecimalMin("0.0")
        private double maxShortLineRatio = 0.80;
    }

    @Data
    public static class TitleVector {
        @DecimalMin("0.0")
        private double activeThreshold = 0.30;
        private List<Double> thresholds = List.of(0.30, 0.40, 0.50, 0.60);
    }
}
