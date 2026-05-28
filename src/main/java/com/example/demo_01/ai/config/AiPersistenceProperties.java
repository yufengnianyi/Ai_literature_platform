package com.example.demo_01.ai.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiPersistenceProperties {

    @Valid
    private Memory memory = new Memory();

    @Valid
    private Rag rag = new Rag();

    @Data
    public static class Memory {

        @Min(1)
        private int maxTokens = 4000;
    }

    @Data
    public static class Rag {

        @Min(1)
        private int embeddingDimension;




        @NotBlank
        private String vectorTable = "embedding_store";

        @NotBlank
        private String storageRoot = "data/rag";

        @Min(1)
        private int asyncThreads = 2;

        @Min(1)
        private int batchConcurrency = 2;

        @Valid
        private Grobid grobid = new Grobid();

        @Valid
        private Chunking chunking = new Chunking();



    }

    @Data
    public static class Grobid {

        @NotBlank
        private String baseUrl = "http://localhost:8070";

        @Min(1)
        private long connectTimeoutMs = 5_000L;

        @Min(1)
        private long readTimeoutMs = 180_000L;

        @Min(0)
        private int maxRetries = 2;

        @Min(0)
        private long retryBackoffMs = 1_000L;
    }

    @Data
    public static class Chunking {

        @Min(1)
        private int targetTokens = 700;

        @Min(1)
        private int maxTokens = 900;

        @Min(0)
        private int overlapSentences = 1;

        @NotBlank
        private String strategyVersion = "grobid-section-sentence-v1";
    }
}

