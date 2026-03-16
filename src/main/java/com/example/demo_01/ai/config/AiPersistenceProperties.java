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
        private String docsPath = "src/main/resources/docs";

        @NotBlank
        private String vectorTable = "embedding_store";

        private RagBootstrapMode bootstrapMode = RagBootstrapMode.IF_EMPTY;
    }
}