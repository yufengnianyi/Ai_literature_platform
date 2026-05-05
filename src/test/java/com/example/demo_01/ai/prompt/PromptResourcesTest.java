package com.example.demo_01.ai.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptResourcesTest {

    @Test
    void loadExistingPromptReturnsContent() {
        String prompt = PromptResources.load(PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM);

        assertTrue(prompt != null && !prompt.isBlank());
    }

    @Test
    void formatExistingPromptAppliesArguments() {
        String prompt = PromptResources.format(
                PromptCatalog.KG_CHUNK_ENTITY_EXTRACTION_USER,
                "schema-v1",
                "Example title",
                "Methods",
                "chunk-1",
                "chunk text"
        );

        assertTrue(prompt.contains("schema_version=schema-v1"));
        assertTrue(prompt.contains("title=Example title"));
        assertTrue(prompt.contains("section_path=Methods"));
        assertTrue(prompt.contains("chunk_id=chunk-1"));
        assertTrue(prompt.contains("chunk text"));
    }

    @Test
    void missingPromptThrowsHelpfulError() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> PromptResources.load("prompts/missing.txt")
        );

        assertTrue(exception.getMessage().contains("prompts/missing.txt"));
    }

    @Test
    void loadCachesStableContent() {
        String first = PromptResources.load(PromptCatalog.REVIEW_QUERY_ANALYZER_SYSTEM);
        String second = PromptResources.load(PromptCatalog.REVIEW_QUERY_ANALYZER_SYSTEM);

        assertEquals(first, second);
    }
}
