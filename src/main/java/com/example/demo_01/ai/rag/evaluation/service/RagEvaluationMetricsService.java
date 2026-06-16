package com.example.demo_01.ai.rag.evaluation.service;

import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RagEvaluationMetricsService {

    private static final List<Integer> RANK_CUTS = List.of(5, 10, 20, 50, 100);

    public RagEvaluationMetrics calculate(List<RagEvaluationDocumentJudgment> judgments,
                                          List<RagEvaluationRetrievalHit> hits) {
        List<RagEvaluationDocumentJudgment> safeJudgments = judgments == null ? List.of() : judgments;
        List<RagEvaluationRetrievalHit> safeHits = hits == null ? List.of() : hits;
        List<RagEvaluationDocumentJudgment> relevantJudgments = safeJudgments.stream()
                .filter(j -> j.effectiveLabel() == JudgmentLabel.RELEVANT)
                .toList();
        Set<UUID> relevantDocs = relevantJudgments.stream()
                .map(RagEvaluationDocumentJudgment::documentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> allKeyChunks = relevantJudgments.stream()
                .flatMap(j -> j.keyChunkIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, RagEvaluationDocumentJudgment> judgmentByDoc = safeJudgments.stream()
                .collect(Collectors.toMap(RagEvaluationDocumentJudgment::documentId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));

        List<RouteMetrics> routeMetrics = new ArrayList<>();
        for (RetrievalRoute route : RetrievalRoute.values()) {
            List<RagEvaluationRetrievalHit> routeHits = safeHits.stream()
                    .filter(hit -> hit.route() == route)
                    .sorted(Comparator.comparingInt(RagEvaluationRetrievalHit::rank))
                    .toList();
            List<MetricSlice> slices = new ArrayList<>();
            for (Integer cut : RANK_CUTS) {
                slices.add(calculateSlice("@" + cut, routeHits.stream().limit(cut).toList(),
                        cut, relevantDocs, allKeyChunks, judgmentByDoc));
            }
            slices.add(calculateSlice("@all", routeHits, null, relevantDocs, allKeyChunks, judgmentByDoc));
            routeMetrics.add(new RouteMetrics(route, slices));
        }
        return new RagEvaluationMetrics(routeMetrics, Instant.now());
    }

    private MetricSlice calculateSlice(String at,
                                       List<RagEvaluationRetrievalHit> hits,
                                       Integer rankCut,
                                       Set<UUID> relevantDocs,
                                       Set<String> allKeyChunks,
                                       Map<UUID, RagEvaluationDocumentJudgment> judgmentByDoc) {
        List<UUID> rankedDocs = rankedDocuments(hits);
        Set<UUID> retrievedDocs = rankedDocs.stream()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> retrievedChunks = hits.stream()
                .map(RagEvaluationRetrievalHit::chunkId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        long relevantRetrieved = retrievedDocs.stream().filter(relevantDocs::contains).count();
        long distractorRetrieved = retrievedDocs.stream()
                .map(judgmentByDoc::get)
                .filter(j -> j != null && j.effectiveLabel() == JudgmentLabel.DISTRACTOR)
                .count();
        long irrelevantRetrieved = retrievedDocs.stream()
                .map(judgmentByDoc::get)
                .filter(j -> j != null && j.effectiveLabel() == JudgmentLabel.IRRELEVANT)
                .count();

        List<UUID> missedRelevantDocs = relevantDocs.stream()
                .filter(doc -> !retrievedDocs.contains(doc))
                .toList();
        Set<String> retrievedKeyChunks = allKeyChunks.stream()
                .filter(retrievedChunks::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> missedKeyChunks = allKeyChunks.stream()
                .filter(chunk -> !retrievedChunks.contains(chunk))
                .toList();

        int retrievedDocCount = retrievedDocs.size();
        int precisionDenominator = rankCut == null ? retrievedDocCount : rankCut;
        double recallAtK = ratio(relevantRetrieved, relevantDocs.size());
        double precisionAtK = ratio(relevantRetrieved, precisionDenominator);
        return new MetricSlice(
                at,
                ratio(relevantRetrieved, relevantDocs.size()),
                ratio(relevantRetrieved, retrievedDocCount),
                ratio(distractorRetrieved, retrievedDocCount),
                ratio(irrelevantRetrieved, retrievedDocCount),
                retrievedDocCount,
                missedRelevantDocs,
                ratio(retrievedKeyChunks.size(), allKeyChunks.size()),
                retrievedKeyChunks.size(),
                allKeyChunks.size(),
                missedKeyChunks,
                recallAtK,
                precisionAtK,
                reciprocalRank(rankedDocs, relevantDocs),
                ndcgAtK(rankedDocs, relevantDocs, rankCut),
                averagePrecision(rankedDocs, relevantDocs)
        );
    }

    private List<UUID> rankedDocuments(List<RagEvaluationRetrievalHit> hits) {
        return hits.stream()
                .map(RagEvaluationRetrievalHit::documentId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private double reciprocalRank(List<UUID> rankedDocs, Set<UUID> relevantDocs) {
        for (int i = 0; i < rankedDocs.size(); i++) {
            if (relevantDocs.contains(rankedDocs.get(i))) {
                return ratio(1.0, i + 1.0);
            }
        }
        return 0.0;
    }

    private double ndcgAtK(List<UUID> rankedDocs, Set<UUID> relevantDocs, Integer rankCut) {
        int limit = rankCut == null ? rankedDocs.size() : rankCut;
        double dcg = 0.0;
        for (int i = 0; i < Math.min(limit, rankedDocs.size()); i++) {
            if (relevantDocs.contains(rankedDocs.get(i))) {
                dcg += discountedGain(i + 1);
            }
        }
        int idealRelevantCount = Math.min(limit, relevantDocs.size());
        double idealDcg = 0.0;
        for (int i = 1; i <= idealRelevantCount; i++) {
            idealDcg += discountedGain(i);
        }
        return ratio(dcg, idealDcg);
    }

    private double discountedGain(int rank) {
        return 1.0 / (Math.log(rank + 1.0) / Math.log(2.0));
    }

    private double averagePrecision(List<UUID> rankedDocs, Set<UUID> relevantDocs) {
        if (relevantDocs.isEmpty()) {
            return 0.0;
        }
        int relevantSeen = 0;
        double precisionSum = 0.0;
        for (int i = 0; i < rankedDocs.size(); i++) {
            if (relevantDocs.contains(rankedDocs.get(i))) {
                relevantSeen++;
                precisionSum += relevantSeen / (double) (i + 1);
            }
        }
        return ratio(precisionSum, relevantDocs.size());
    }

    private double ratio(double numerator, double denominator) {
        if (denominator <= 0.0) {
            return 0.0;
        }
        return Math.round((numerator / denominator) * 10000.0) / 10000.0;
    }
}
