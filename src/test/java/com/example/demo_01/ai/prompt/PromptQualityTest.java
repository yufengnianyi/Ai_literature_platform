package com.example.demo_01.ai.prompt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PromptQualityTest {

    private static final Path PROMPTS = Path.of("src", "main", "resources", "prompts");
    private static final String[] KNOWN_MOJIBAKE_FRAGMENTS = {
            "\u9356\u6827\u608e",
            "\u7eeb?",
            "\u9239"
    };

    @Test
    void promptFilesAreNonBlankAfterOptimization() throws IOException {
        try (var stream = Files.walk(PROMPTS)) {
            for (Path path : stream.filter(Files::isRegularFile).filter(PromptQualityTest::isTextFile).toList()) {
                assertFalse(Files.readString(path).isBlank(), "Prompt must be non-blank: " + path);
            }
        }
    }

    @Test
    void promptFilesDoNotContainKnownMojibakeFragments() throws IOException {
        try (var stream = Files.walk(PROMPTS)) {
            for (Path path : stream.filter(Files::isRegularFile).filter(PromptQualityTest::isTextFile).toList()) {
                String content = Files.readString(path);

                for (String fragment : KNOWN_MOJIBAKE_FRAGMENTS) {
                    assertFalse(content.contains(fragment), "Prompt contains known mojibake fragment: " + path);
                }
            }
        }
    }

    private static boolean isTextFile(Path path) {
        return path.getFileName().toString().endsWith(".txt");
    }
}
