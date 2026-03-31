package com.example.demo_01.ai.kg.service;

import com.example.demo_01.ai.kg.KgProperties;
import com.example.demo_01.ai.kg.model.KgModels.GraphBuilderSyncResult;
import com.example.demo_01.ai.kg.model.KgModels.GraphBuilderSyncStatus;
import com.example.demo_01.ai.kg.model.KgModels.PaperGraphPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class Neo4jGraphBuilderClient {

    @Resource
    private KgProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    @Resource(name = "kgGraphBuilderRestClient")
    private RestClient restClient;

    public GraphBuilderSyncResult sync(PaperGraphPayload payload) {
        KgProperties.GraphBuilder graphBuilder = properties.getGraphBuilder();
        if (!properties.isEnabled() || !graphBuilder.isEnabled() || !StringUtils.hasText(graphBuilder.getEndpoint())) {
            return new GraphBuilderSyncResult(GraphBuilderSyncStatus.SKIPPED, null, null, null);
        }

        String requestBody = serializeRequest(payload);
        for (int attempt = 0; attempt <= graphBuilder.getMaxRetries(); attempt++) {
            try {
                String responseBody = restClient.post()
                        .uri(graphBuilder.getEndpoint())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(String.class);
                return new GraphBuilderSyncResult(GraphBuilderSyncStatus.SYNCED, requestBody, responseBody, null);
            } catch (RestClientResponseException ex) {
                boolean retryable = ex.getStatusCode().is5xxServerError() && attempt < graphBuilder.getMaxRetries();
                if (!retryable) {
                    return new GraphBuilderSyncResult(GraphBuilderSyncStatus.FAILED, requestBody, ex.getResponseBodyAsString(), ex.getMessage());
                }
            } catch (Exception ex) {
                if (attempt >= graphBuilder.getMaxRetries()) {
                    return new GraphBuilderSyncResult(GraphBuilderSyncStatus.FAILED, requestBody, null, ex.getMessage());
                }
            }
        }
        return new GraphBuilderSyncResult(GraphBuilderSyncStatus.FAILED, requestBody, null, "Graph Builder request failed");
    }

    public String endpoint() {
        return properties.getGraphBuilder().getEndpoint();
    }

    private String serializeRequest(PaperGraphPayload payload) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getGraphBuilder().getModel());
        request.put("schemaVersion", properties.getSchemaVersion());
        request.put("graph", payload);
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Graph Builder request", e);
        }
    }
}
