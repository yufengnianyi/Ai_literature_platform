package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.rag.chunk.TeiChunker;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeiChunkerTest {

    private TeiChunker teiChunker;

    @BeforeEach
    void setUp() {
        teiChunker = new TeiChunker();
        AiPersistenceProperties properties = new AiPersistenceProperties();
        properties.getRag().getChunking().setTargetTokens(9);
        properties.getRag().getChunking().setMaxTokens(12);
        properties.getRag().getChunking().setOverlapSentences(1);
        ReflectionTestUtils.setField(teiChunker, "properties", properties);
        ReflectionTestUtils.setField(teiChunker, "tokenCountEstimator", new TokenCountEstimator() {
            @Override
            public int estimateTokenCountInText(String text) {
                return text.split("\\s+").length;
            }

            @Override
            public int estimateTokenCountInMessage(ChatMessage message) {
                return estimateTokenCountInText(message.toString());
            }

            @Override
            public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                int total = 0;
                for (ChatMessage message : messages) {
                    total += estimateTokenCountInMessage(message);
                }
                return total;
            }
        });
    }

    @Test
    void chunkShouldHonorSectionBoundariesAndAddOverlap() {
        ParsedTeiDocument parsed = new ParsedTeiDocument(
                new RagDocumentMetadata("10.1000/test", "10.1000/test", "Sample Paper", List.of(), List.of(), null, null, null, null),
                List.of(
                        new ChunkUnit("body", "Intro", 1, 1, "One two"),
                        new ChunkUnit("body", "Intro", 1, 2, "Three four"),
                        new ChunkUnit("body", "Intro", 1, 3, "Five six"),
                        new ChunkUnit("body", "Methods", 1, 1, "Method sentence")
                )
        );

        List<RagChunk> chunks = teiChunker.chunk(UUID.fromString("11111111-1111-1111-1111-111111111111"), "doi:10.1000/test", parsed,
                Path.of("paper.pdf"), Path.of("paper.tei.xml"));

        assertEquals(3, chunks.size());
        assertEquals("Intro", chunks.get(0).sectionPath());
        assertEquals("Intro", chunks.get(1).sectionPath());
        assertEquals("Methods", chunks.get(2).sectionPath());
        assertTrue(chunks.get(1).text().startsWith("Three four"));
        assertEquals(2, chunks.get(1).sentenceStart());
    }
}
