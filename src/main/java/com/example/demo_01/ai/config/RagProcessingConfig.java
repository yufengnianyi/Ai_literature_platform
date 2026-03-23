package com.example.demo_01.ai.config;

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
    public RestClient grobidRestClient(AiPersistenceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getRag().getGrobid().getConnectTimeoutMs());
        requestFactory.setReadTimeout((int) properties.getRag().getGrobid().getReadTimeoutMs());
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        return RestClient.builder(restTemplate)
                .baseUrl(properties.getRag().getGrobid().getBaseUrl())
                .build();
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
}
