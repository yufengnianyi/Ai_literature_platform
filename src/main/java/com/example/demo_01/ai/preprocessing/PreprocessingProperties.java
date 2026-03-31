package com.example.demo_01.ai.preprocessing;

import com.example.demo_01.ai.config.AiPersistenceProperties;
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
@ConfigurationProperties(prefix = "app.ai.preprocessing")
public class PreprocessingProperties {

    @NotBlank
    private String storageRoot = "data/rag";

    @Min(1)
    private int asyncThreads = 2;

    @Valid
    private AiPersistenceProperties.Grobid grobid = new AiPersistenceProperties.Grobid();

    @Valid
    private AiPersistenceProperties.Chunking chunking = new AiPersistenceProperties.Chunking();

    @NotBlank
    private String version = "preprocess-v1";
}
