package com.example.demo_01.ai.pretreatment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class CrossrefClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Resource
    private PretreatmentProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    public CrossrefWork findByDoi(String doi) {
        if (doi == null || doi.isBlank()) {
            return null;
        }
        PretreatmentProperties.JournalResolution cfg = properties.getJournalResolution();
        String encodedDoi = UriUtils.encodePathSegment(doi.trim(), StandardCharsets.UTF_8);
        String url = trimSlash(cfg.getCrossrefBaseUrl()) + "/works/" + encodedDoi;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(cfg.getReadTimeoutMs()))
                .header("Accept", "application/json")
                .header("User-Agent", cfg.getCrossrefUserAgent())
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Crossref DOI lookup failed with HTTP " + response.statusCode());
            }
            Map<String, Object> root = objectMapper.readValue(response.body(), MAP_TYPE);
            Object message = root.get("message");
            if (!(message instanceof Map<?, ?> rawMessage)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) rawMessage;
            return new CrossrefWork(
                    firstString(fields.get("title")),
                    firstString(fields.get("container-title")),
                    stringList(fields.get("ISSN")),
                    stringValue(fields.get("publisher"))
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse Crossref response for DOI: " + doi, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Crossref DOI lookup: " + doi, e);
        }
    }

    private String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.crossref.org";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String firstString(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return stringValue(list.get(0));
        }
        return stringValue(value);
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::stringValue)
                    .filter(text -> text != null && !text.isBlank())
                    .toList();
        }
        String text = stringValue(value);
        return text == null || text.isBlank() ? List.of() : List.of(text);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record CrossrefWork(
            String title,
            String journal,
            List<String> issns,
            String publisher
    ) {
    }
}
