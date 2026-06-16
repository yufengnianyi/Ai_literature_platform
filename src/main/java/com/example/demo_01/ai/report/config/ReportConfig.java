package com.example.demo_01.ai.report.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ReportConfig {

    @Bean("reportTaskExecutor")
    public TaskExecutor reportTaskExecutor(ReportProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("report-");
        executor.setCorePoolSize(Math.max(1, properties.getAsyncThreads()));
        executor.setMaxPoolSize(Math.max(1, properties.getAsyncThreads()));
        executor.setQueueCapacity(64);
        executor.initialize();
        return executor;
    }
}
