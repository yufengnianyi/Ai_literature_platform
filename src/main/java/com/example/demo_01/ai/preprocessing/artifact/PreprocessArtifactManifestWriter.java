package com.example.demo_01.ai.preprocessing.artifact;

import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PreprocessArtifactManifestWriter {

    @Resource
    private ObjectMapper objectMapper;

    public Path write(Path manifestPath, PreprocessArtifact artifact) {
        try {
            Files.createDirectories(manifestPath.getParent());
            Files.writeString(manifestPath, objectMapper.writeValueAsString(artifact), StandardCharsets.UTF_8);
            return manifestPath;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize preprocess artifact manifest", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write preprocess artifact manifest: " + manifestPath, e);
        }
    }
}
