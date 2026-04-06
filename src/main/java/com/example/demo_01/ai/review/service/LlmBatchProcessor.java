package com.example.demo_01.ai.review.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public class LlmBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(LlmBatchProcessor.class);

    public <T, R> List<R> processInBatches(List<T> items,
                                           int batchSize,
                                           Function<List<T>, List<R>> batchFunction,
                                           TaskExecutor executor) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<List<T>> batches = partition(items, batchSize);
        log.info("Processing {} items in {} batches (size={})", items.size(), batches.size(), batchSize);

        if (executor == null || batches.size() == 1) {
            List<R> results = new ArrayList<>();
            for (int i = 0; i < batches.size(); i++) {
                log.debug("Processing batch {}/{}", i + 1, batches.size());
                results.addAll(batchFunction.apply(batches.get(i)));
            }
            return results;
        }

        AtomicInteger counter = new AtomicInteger(0);
        @SuppressWarnings("unchecked")
        CompletableFuture<List<R>>[] futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> {
                    int idx = counter.incrementAndGet();
                    log.debug("Processing batch {}/{} on thread {}", idx, batches.size(),
                            Thread.currentThread().getName());
                    return batchFunction.apply(batch);
                }, executor))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();

        List<R> results = new ArrayList<>();
        for (CompletableFuture<List<R>> future : futures) {
            results.addAll(future.join());
        }
        return results;
    }

    public <T, R> List<R> processWithTokenBudget(List<T> items,
                                                  int maxTokensPerBatch,
                                                  ToIntFunction<T> tokenEstimator,
                                                  Function<List<T>, List<R>> batchFunction) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<List<T>> batches = partitionByTokens(items, maxTokensPerBatch, tokenEstimator);
        log.info("Token-budget partitioning: {} items → {} batches (budget={})",
                items.size(), batches.size(), maxTokensPerBatch);

        List<R> results = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            log.debug("Processing token-budget batch {}/{} ({} items)",
                    i + 1, batches.size(), batches.get(i).size());
            results.addAll(batchFunction.apply(batches.get(i)));
        }
        return results;
    }

    private <T> List<List<T>> partition(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        return batches;
    }

    private <T> List<List<T>> partitionByTokens(List<T> items, int maxTokens,
                                                 ToIntFunction<T> estimator) {
        List<List<T>> batches = new ArrayList<>();
        List<T> current = new ArrayList<>();
        int currentTokens = 0;

        for (T item : items) {
            int itemTokens = estimator.applyAsInt(item);
            if (!current.isEmpty() && currentTokens + itemTokens > maxTokens) {
                batches.add(List.copyOf(current));
                current.clear();
                currentTokens = 0;
            }
            current.add(item);
            currentTokens += itemTokens;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }
}
