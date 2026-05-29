package com.example.demo_01.ai.prompt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptInventoryTest {

    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");
    private static final Path PROMPTS = MAIN_RESOURCES.resolve("prompts");

    @Test
    void promptFilesDoNotLiveAtResourcesRoot() throws IOException {
        try (var stream = Files.list(MAIN_RESOURCES)) {
            List<Path> rootPromptFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().contains("prompt"))
                    .toList();

            assertTrue(rootPromptFiles.isEmpty(), "Root prompt files must move under prompts/: " + rootPromptFiles);
        }
    }

    @Test
    void allPromptTextFilesLiveUnderCanonicalPromptSubtree() throws IOException {
        try (var stream = Files.walk(MAIN_RESOURCES)) {
            List<Path> promptFilesOutsideCanonicalSubtree = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().contains("prompt"))
                    .filter(path -> path.toString().endsWith(".txt"))
                    .filter(path -> !path.normalize().startsWith(PROMPTS.normalize()))
                    .toList();

            assertTrue(
                    promptFilesOutsideCanonicalSubtree.isEmpty(),
                    "Prompt text files outside prompts/: " + promptFilesOutsideCanonicalSubtree
            );
        }
    }

    @Test
    void migratedAiHelperServiceSystemPromptExistsAndIsNonBlank() throws IOException {
        Path servicePrompt = PROMPTS.resolve(Path.of("ai", "ai-code-helper-service-system.txt"));

        assertTrue(Files.exists(servicePrompt));
        String prompt = Files.readString(servicePrompt);
        assertFalse(prompt.isBlank());
        assertFalse(prompt.contains("卵菌"));
        assertFalse(prompt.contains("检索片段"));
        assertFalse(prompt.contains("chunk_id"));
    }

    @Test
    void promptOptimizationFillsAiCodeHelperPrompt() throws IOException {
        Path helperPrompt = PROMPTS.resolve(Path.of("ai", "ai-code-helper-system.txt"));

        assertTrue(Files.exists(helperPrompt));
        assertFalse(
                Files.readString(helperPrompt).isBlank(),
                "Prompt optimization phase must provide ai-code-helper-system.txt content"
        );
    }
}
