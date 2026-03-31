package com.example.demo_01.ai.rag.client;

import com.example.demo_01.ai.preprocessing.PreprocessingProperties;
import jakarta.annotation.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.file.Path;

@Component
public class GrobidClient {

    @Resource
    private RestClient grobidRestClient;

    @Resource
    private PreprocessingProperties properties;

    public String processHeaderDocument(Path pdfPath) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("input", new FileSystemResource(pdfPath));
        body.add("consolidateHeader", "2");
        return execute("/api/processHeaderDocument", body);
    }

    public String processFulltextDocument(Path pdfPath) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("input", new FileSystemResource(pdfPath));
        body.add("segmentSentences", "1");
        return execute("/api/processFulltextDocument", body);
    }

    private String execute(String uri, MultiValueMap<String, Object> body) {
        int attempts = properties.getGrobid().getMaxRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return grobidRestClient.post()
                        .uri(uri)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .accept(MediaType.APPLICATION_XML)
                        .body(body)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientResponseException ex) {
                boolean retryable = ex.getStatusCode().value() == 503 && attempt < attempts;
                if (!retryable) {
                    throw new IllegalStateException("GROBID request failed: " + uri + " -> " + ex.getStatusCode(), ex);
                }
                sleepBeforeRetry();
            }
        }
        throw new IllegalStateException("GROBID request failed after retries: " + uri);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(properties.getGrobid().getRetryBackoffMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying GROBID", e);
        }
    }
}
