package com.example.demo_01.ai.rag;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.rag.retrieval")
public class RagRetrievalProperties {

    @Min(1)
    private int denseMaxResults = 5;

    @Min(0)
    private double denseMinScore = 0.6;

    @Min(1)
    private int bm25MaxResults = 8;

    @Min(1)
    private int fusedMaxResults = 5;

    @Min(1)
    private int rrfK = 60;

    @NotBlank
    private String bm25IndexPath = "data/bm25-index";
}
