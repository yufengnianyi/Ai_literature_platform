package com.example.demo_01.ai.rag.support;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class FailedLiteratureCsvRecorder {

    private static final Logger log = LoggerFactory.getLogger(FailedLiteratureCsvRecorder.class);

    private static final String CSV_HEADER = "document_id,stage,failed_at,error_message";

    @Resource
    private AiPersistenceProperties properties;

    private final Object lock = new Object();

    public void append(UUID documentId, String stage, Throwable error) {
        synchronized (lock) {
            Path csvPath = Path.of(properties.getRag().getStorageRoot())
                    .resolve("failed_literature.csv")
                    .toAbsolutePath();
            try {
                Files.createDirectories(csvPath.getParent());
                if (Files.notExists(csvPath)) {
                    Files.writeString(csvPath, CSV_HEADER + System.lineSeparator(),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }

                String errorMessage = error == null ? "" : firstNonBlank(error.getMessage(), String.valueOf(error));
                String line = String.join(",",
                        csv(documentId.toString()),
                        csv(stage),
                        csv(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                        csv(errorMessage)
                );
                Files.writeString(csvPath, line + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ioException) {
                log.warn("Failed to append failed_literature.csv for document {}", documentId, ioException);
            }
        }
    }

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"").replaceAll("\\R", " ");
        return "\"" + safe + "\"";
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }
}
