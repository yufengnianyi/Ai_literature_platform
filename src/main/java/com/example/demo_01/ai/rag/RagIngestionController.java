package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.config.RagBootstrapMode;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@RestController
@RequestMapping("/rag/ingestions")
public class RagIngestionController {

    @Resource
    private RagIngestionService ragIngestionService;

    @PostMapping
    public IngestionResponse ingest(@RequestParam(defaultValue = "rebuild") String mode) {
        RagBootstrapMode bootstrapMode = parseMode(mode);
        ragIngestionService.ingest(bootstrapMode);
        RagIngestionService.RagIngestionStatus status = ragIngestionService.status();
        return new IngestionResponse(bootstrapMode, status.rowCount(), status.datasetHash(), status.updatedAt());
    }

    @GetMapping("/status")
    public RagIngestionService.RagIngestionStatus status() {
        return ragIngestionService.status();
    }

    private RagBootstrapMode parseMode(String mode) {
        String normalized = mode == null
                ? ""
                : mode.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return RagBootstrapMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid mode: " + mode + ". Supported values: rebuild, if-empty, skip"
            );
        }
    }

    public record IngestionResponse(
            RagBootstrapMode mode,
            long rowCount,
            String datasetHash,
            java.time.Instant updatedAt
    ) {
    }
}