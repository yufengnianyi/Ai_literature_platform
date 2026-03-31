package com.example.demo_01.ai.kg.config;

import com.example.demo_01.ai.kg.KgProperties;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class KgInfrastructureConfig {

    @Bean("kgTaskExecutor")
    public TaskExecutor kgTaskExecutor(KgProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("kg-pipeline-");
        executor.setCorePoolSize(properties.getAsyncThreads());
        executor.setMaxPoolSize(properties.getAsyncThreads());
        executor.setQueueCapacity(32);
        executor.initialize();
        return executor;
    }

    @Bean("kgGraphBuilderRestClient")
    public RestClient kgGraphBuilderRestClient(KgProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getGraphBuilder().getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getGraphBuilder().getReadTimeoutMs());
        return RestClient.builder(new RestTemplate(requestFactory)).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${spring.neo4j.uri:}')")
    public Driver kgNeo4jDriver(
            @Value("${spring.neo4j.uri:}") String uri,
            @Value("${spring.neo4j.authentication.username:}") String username,
            @Value("${spring.neo4j.authentication.password:}") String password) {
        if (!StringUtils.hasText(username)) {
            return GraphDatabase.driver(uri);
        }
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }
}
