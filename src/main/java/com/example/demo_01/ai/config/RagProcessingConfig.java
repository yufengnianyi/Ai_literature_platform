package com.example.demo_01.ai.config;

import com.example.demo_01.ai.preprocessing.PreprocessingProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RagProcessingConfig {

    @Bean
    public RestClient grobidRestClient(PreprocessingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getGrobid().getConnectTimeoutMs());
        requestFactory.setReadTimeout((int) properties.getGrobid().getReadTimeoutMs());
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        return RestClient.builder(restTemplate)
                .baseUrl(properties.getGrobid().getBaseUrl())
                .build();
    }

    @Bean("preprocessTaskExecutor")
    public TaskExecutor preprocessTaskExecutor(PreprocessingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("preprocess-pipeline-");
        executor.setCorePoolSize(properties.getAsyncThreads());
        executor.setMaxPoolSize(properties.getAsyncThreads());
        executor.setQueueCapacity(32);
        executor.initialize();
        return executor;
    }

    @Bean("ragTaskExecutor")
    public TaskExecutor ragTaskExecutor(AiPersistenceProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("rag-pipeline-");
        executor.setCorePoolSize(properties.getRag().getAsyncThreads());
        executor.setMaxPoolSize(properties.getRag().getAsyncThreads());
        executor.setQueueCapacity(32);
        executor.initialize();
        return executor;
    }

    @Bean("ragBatchWorkerExecutor")
    public TaskExecutor ragBatchWorkerExecutor(AiPersistenceProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("rag-batch-worker-");
        executor.setCorePoolSize(properties.getRag().getBatchConcurrency());
        executor.setMaxPoolSize(properties.getRag().getBatchConcurrency());
        executor.setQueueCapacity(0);
        executor.initialize();
        return executor;
    }

    /**
     * Bounded pool for concurrent per-document Q1 compound-reference resolution
     * during interactive chat turns.
     */
    @Bean("q1CompoundResolutionExecutor")
    public TaskExecutor q1CompoundResolutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("q1-compound-resolve-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.initialize();
        return executor;
    }
}
