package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.report.config.ReportProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

@Service
public class ReportAttachmentStorage {

    private static final String ATTACHMENT_FILE_NAME = "compound-evidence.xlsx";

    private final Path root;

    public ReportAttachmentStorage(ReportProperties properties) {
        this.root = Path.of(properties.getOutputRoot()).toAbsolutePath().normalize();
    }

    public Path createAttachmentPath(String userId, UUID reportId) {
        Path path = root.resolve(safeComponent(userId))
                .resolve(reportId.toString())
                .resolve(ATTACHMENT_FILE_NAME)
                .normalize();
        assertUnderRoot(path);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create report attachment directory", e);
        }
        return path;
    }

    public String relativePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        assertUnderRoot(normalized);
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalStateException("Report attachment path is missing");
        }
        Path resolved = root.resolve(relativePath).normalize();
        assertUnderRoot(resolved);
        return resolved;
    }

    public void deleteReportAttachment(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path file = resolve(relativePath);
        Path directory = file.getParent();
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete report attachment: " + path, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete report attachment directory", e);
        }
    }

    public String attachmentFileName() {
        return ATTACHMENT_FILE_NAME;
    }

    private void assertUnderRoot(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new IllegalArgumentException("Report attachment path escapes configured root");
        }
    }

    private String safeComponent(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        String safe = value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.equals(".") || safe.equals("..") || safe.isBlank()) {
            throw new IllegalArgumentException("userId is invalid");
        }
        return safe;
    }
}
