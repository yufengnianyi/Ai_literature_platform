package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceQuestionPromptCatalogTest {

    @Test
    void loadsDedicatedExtractionPromptForEveryQuestion() {
        for (int question = 1; question <= 10; question++) {
            String questionId = "Q" + question;
            String path = PromptCatalog.evidenceQuestionExtractionSystem(questionId);
            String prompt = PromptResources.load(path);

            assertTrue(prompt.contains(questionId), questionId + " prompt should identify its question");
            assertTrue(prompt.contains("Output exactly one Markdown table"),
                    questionId + " prompt should require Markdown table output");
        }
    }

    @Test
    void q6PromptContainsFixedProfileHeaders() {
        String prompt = PromptResources.load(
                PromptCatalog.evidenceQuestionExtractionSystem("Q6"));

        assertTrue(prompt.contains("| Gene name | Alias/homologous gene | Gene ID/accession |"));
        assertTrue(prompt.contains("One row represents one oomycete species-gene combination."));
    }
}
