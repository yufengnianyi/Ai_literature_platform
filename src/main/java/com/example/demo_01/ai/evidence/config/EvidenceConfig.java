package com.example.demo_01.ai.evidence.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class EvidenceConfig {

    @Bean("evidenceTaskExecutor")
    public TaskExecutor evidenceTaskExecutor(EvidenceProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("evidence-extraction-");
        executor.setCorePoolSize(properties.getAsyncThreads());
        executor.setMaxPoolSize(Math.max(properties.getAsyncThreads(), 4));
        executor.setQueueCapacity(128);
        executor.initialize();
        return executor;
    }

    @Bean("evidenceBatchTaskExecutor")
    public TaskExecutor evidenceBatchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("evidence-backfill-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        return executor;
    }

    @Bean("multiProfileEvidenceTaskExecutor")
    public TaskExecutor multiProfileEvidenceTaskExecutor(EvidenceProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("multi-profile-evidence-");
        executor.setCorePoolSize(properties.getAsyncThreads());
        executor.setMaxPoolSize(properties.getAsyncThreads());
        executor.setQueueCapacity(Math.max(16, properties.getAsyncThreads() * 4));
        executor.initialize();
        return executor;
    }

    @Bean("multiProfileEvidenceBatchTaskExecutor")
    public TaskExecutor multiProfileEvidenceBatchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("multi-profile-batch-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        return executor;
    }
}
