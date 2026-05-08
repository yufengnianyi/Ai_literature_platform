package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class QuantitativeAnchorRetriever {

    private static final Pattern P_CONCENTRATION =
            Pattern.compile("\\b\\d+(?:\\.\\d+)?\\s*(μ?g\\s*[/·]?\\s*mL|µM|μM|nM|mM|mg\\s*[/·]?\\s*L|ppm)\\b");
    private static final Pattern P_KEY_METRIC =
            Pattern.compile("\\b(MIC|MFC|EC50|EC₅₀|IC50|IC₅₀|LD50)\\b");
    private static final Pattern P_PARADIGM =
            Pattern.compile("\\b(dose[- ]dependent|concentration[- ]dependent|mycelial growth|micro[- ]?well dilution|zoosporogenesis|plate inhibition|XTT reduction)\\b",
                    Pattern.CASE_INSENSITIVE);

    @Resource
    private ReviewRepository reviewRepository;

    private final Map<String, ChunkAnchor> anchorMetadata = new LinkedHashMap<>();

    public List<RetrievedChunk> retrieveAnchors(Set<UUID> documentIds, int maxAnchorsPerDocument) {
        anchorMetadata.clear();
        List<RetrievedChunk> allAnchors = new ArrayList<>();
        for (UUID documentId : documentIds) {
            try {
                List<RetrievedChunk> docChunks = reviewRepository.findAllChunksByDocumentId(documentId);
                List<ScoredAnchor> scored = new ArrayList<>();
                for (RetrievedChunk chunk : docChunks) {
                    if (chunk.text() == null || chunk.text().isBlank()) continue;
                    int priority = 0;
                    List<String> matchedTokens = new ArrayList<>();
                    StringBuilder reason = new StringBuilder();

                    Matcher metricMatcher = P_KEY_METRIC.matcher(chunk.text());
                    while (metricMatcher.find()) {
                        matchedTokens.add(metricMatcher.group());
                        if (priority < 3) {
                            reason.append("KEY_METRIC:").append(metricMatcher.group()).append("; ");
                            priority = 3;
                        }
                    }

                    Matcher concMatcher = P_CONCENTRATION.matcher(chunk.text());
                    int concCount = 0;
                    while (concMatcher.find()) concCount++;
                    if (concCount >= 3) {
                        reason.append("DOSE_GRADIENT(").append(concCount).append("); ");
                        if (priority < 2) priority = 2;
                    }

                    Matcher paradigmMatcher = P_PARADIGM.matcher(chunk.text());
                    while (paradigmMatcher.find()) {
                        matchedTokens.add(paradigmMatcher.group());
                        if (priority < 1) {
                            reason.append("PARADIGM:").append(paradigmMatcher.group()).append("; ");
                            priority = 1;
                        }
                    }

                    if (priority > 0) {
                        scored.add(new ScoredAnchor(chunk, priority, reason.toString().trim(), matchedTokens));
                    }
                }

                scored.sort(Comparator.comparingInt(ScoredAnchor::priority).reversed());
                List<ScoredAnchor> selected = scored.subList(0, Math.min(maxAnchorsPerDocument, scored.size()));
                for (ScoredAnchor sa : selected) {
                    RetrievedChunk anchor = new RetrievedChunk(
                            sa.chunk().chunkId(), sa.chunk().documentId(), sa.chunk().documentTitle(),
                            sa.chunk().text(), sa.chunk().sectionPath(), sa.chunk().score(), "QUANT_ANCHOR");
                    allAnchors.add(anchor);
                    anchorMetadata.put(sa.chunk().chunkId(),
                            new ChunkAnchor(sa.chunk().chunkId(), AnchorType.QUANTITATIVE,
                                    sa.reason(), sa.matchedTokens()));
                }
            } catch (Exception e) {
                log.warn("Quantitative anchor retrieval failed for document {}: {}", documentId, e.getMessage());
            }
        }
        log.info("Quantitative anchor retrieval: {} anchors from {} documents", allAnchors.size(), documentIds.size());
        return allAnchors;
    }

    public Map<String, ChunkAnchor> getAnchorMetadata() {
        return Collections.unmodifiableMap(anchorMetadata);
    }

    private record ScoredAnchor(RetrievedChunk chunk, int priority, String reason, List<String> matchedTokens) {}
}
