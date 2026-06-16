package com.example.demo_01.ai.report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.report")
public class ReportProperties {

    private String outputRoot = "tmp";
    private UUID sourceExperimentId = UUID.fromString("efe44f92-8d28-4902-bf77-a15d3bf14ee8");
    private int maxEvidence = 50_000;
    private int maxEvidenceLoad = 100_000;
    private int maxModelEvidence = 80;
    private int maxLiteratureDocuments = 10;
    private int maxLiteratureChunksPerDocument = 3;
    private int maxLiteratureContextChars = 30000;
    private int maxDirectDocuments = 15;
    private int maxSupplementalDocuments = 5; // Hard-capped at 5 by the report pipeline.
    private int retrievalRounds = 2;
    private int maxQueriesPerRound = 8;
    private int denseMaxResults = 20;
    private double denseMinScore = 0.35;
    private int bm25MaxResults = 30;
    private int fusedMaxResults = 20;
    private int rrfK = 60;
    private int chunksPerAnalysisBatch = 8;
    private int maxCharsPerAnalysisBatch = 28000;
    private int analysisBatchOverlap = 1;
    private int maxDocumentAnalysisAttempts = 2;
    private int minReportChars = 3000;
    private int maxReportChars = 5000;
    private int asyncThreads = 2;
}
