package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class EvidenceAgentTelemetryService {

    @Resource
    private EvidenceAgentTelemetryRepository repository;

    @Resource
    private EvidenceProperties properties;

    public <T> T timed(UUID batchId,
                       UUID documentId,
                       String questionId,
                       String agentName,
                       int attempt,
                       int llmCalls,
                       int retryCount,
                       Map<String, Object> detail,
                       Supplier<T> action) {
        if (!properties.getAgents().getTelemetry().isEnabled()) {
            return action.get();
        }
        long started = System.nanoTime();
        try {
            T result = action.get();
            repository.insertStep(
                    batchId, documentId, questionId, agentName, attempt, llmCalls,
                    null, null, retryCount, elapsedMs(started), true, detail, null);
            return result;
        } catch (RuntimeException e) {
            repository.insertStep(
                    batchId, documentId, questionId, agentName, attempt, llmCalls,
                    null, null, retryCount, elapsedMs(started), false, detail, e.getMessage());
            throw e;
        }
    }

    public void recordCoverage(UUID batchId,
                               UUID documentId,
                               String questionId,
                               int candidateCount,
                               int extractedBefore,
                               int recoveredCount,
                               int extractedAfter,
                               List<?> candidates,
                               List<String> recoveredFingerprints) {
        if (!properties.getAgents().getTelemetry().isEnabled()) {
            return;
        }
        repository.upsertCoverageAudit(
                batchId, documentId, questionId, candidateCount, extractedBefore,
                recoveredCount, extractedAfter, candidates, recoveredFingerprints);
    }

    public Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
