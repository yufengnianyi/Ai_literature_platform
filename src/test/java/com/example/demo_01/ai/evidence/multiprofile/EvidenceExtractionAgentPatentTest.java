package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.table.ParsedTable;
import com.example.demo_01.ai.evidence.table.PatentQ1EvidenceSupport;
import com.example.demo_01.ai.evidence.table.TableLegendResolver;
import com.example.demo_01.ai.evidence.table.TableSerializer;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceExtractionAgentPatentTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    final PatentQ1EvidenceSupport support = new PatentQ1EvidenceSupport(mapper, new TableSerializer());
    final EvidenceProfileRegistry registry = new EvidenceProfileRegistry();
    final ReviewReasoningChatClient chat = mock(ReviewReasoningChatClient.class);
    final EvidenceExtractionAgent agent = new EvidenceExtractionAgent();
    SourceDocument document;

    @BeforeEach
    void setup() {
        EvidenceProperties config = new EvidenceProperties();
        config.setMaxAttempts(2);
        ReflectionTestUtils.setField(agent, "properties", config);
        ReflectionTestUtils.setField(agent, "chatClient", chat);
        ReflectionTestUtils.setField(agent, "markdownTableParser", new EvidenceMarkdownTableParser(new MultiProfileOutputValidator()));
        ReflectionTestUtils.setField(agent, "patentQ1EvidenceSupport", support);
        document = new SourceDocument(UUID.randomUUID(), "Patent test", List.of(), null, null, null, root.toString());
    }

    @Test
    void retriesValueMismatchThenReturnsOnlyModelRowsWithExactAnchors() throws Exception {
        List<EvidenceChunk> chunks = nativeChunks();
        when(chat.chatCore(any(SystemMessage.class), any(UserMessage.class)))
                .thenReturn(response(markdown("9.99")), response(markdown("0.35")));
        UUID scope = UUID.randomUUID();
        var rows = agent.extract(scope, document, registry.require("Q1"), chunks);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().cells().get(7)).contains("0.35").doesNotContain("9.99");
        assertThat(rows.getFirst().anchors()).hasSizeGreaterThanOrEqualTo(2);
        var systems = ArgumentCaptor.forClass(SystemMessage.class);
        var users = ArgumentCaptor.forClass(UserMessage.class);
        verify(chat, times(2)).chatCore(systems.capture(), users.capture());
        assertThat(systems.getValue().text()).contains("validated PATENT_Q1", "Latin name EMPTY");
        assertThat(users.getValue().singleText()).contains("ec50Accurate=false", "expected=");
        var audit = mapper.readTree(root.resolve("extraction").resolve(scope.toString()).resolve("patent-q1-audit.json").toFile());
        assertThat(audit.path("attempts")).hasSize(2);
        assertThat(audit.path("latest").path("coverageAccurate").asBoolean()).isTrue();
    }

    @Test
    void mismatchStopsAtMaxAttemptsWithoutInsertingKnownAnswers() throws Exception {
        var chunks = nativeChunks();
        when(chat.chatCore(any(SystemMessage.class), any(UserMessage.class))).thenReturn(response(markdown("9.99")));
        assertThatThrownBy(() -> agent.extract(UUID.randomUUID(), document, registry.require("Q1"), chunks))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("after 2 attempts");
        verify(chat, times(2)).chatCore(any(SystemMessage.class), any(UserMessage.class));
    }

    @Test
    void nonPatentTextKeepsLegacyPromptAndDoesNotCreateAudit() {
        when(chat.chatCore(any(SystemMessage.class), any(UserMessage.class))).thenReturn(response(markdown("0.35")));
        var chunks = List.of(new EvidenceChunk("doc:body:1", "Methods", 1, null, null, "Original legacy text", "body", ""));
        assertThat(agent.extract(document, registry.require("Q1"), chunks)).hasSize(1);
        var system = ArgumentCaptor.forClass(SystemMessage.class);
        verify(chat).chatCore(system.capture(), any(UserMessage.class));
        assertThat(system.getValue().text()).doesNotContain("validated PATENT_Q1");
        assertThat(root.resolve("extraction")).doesNotExist();
    }

    private List<EvidenceChunk> nativeChunks() throws Exception {
        mapper.writeValue(root.resolve("patent-table-manifest.json").toFile(), Map.of(
                "kind", "PATENT_Q1", "publicationNumber", "TEST", "pdfSha256", "c".repeat(64),
                "tables", List.of(Map.of("tableRef", "T1", "pageNumber", 4, "sourceKind", "VISION", "readStatus", "VERIFIED",
                        "imagePath", "page.png", "region", Map.of("x", 0, "y", 0, "width", 100, "height", 100)))));
        var table = new ParsedTable("T1", "1", "Assay", List.of("Treatment", "72h EC50 mg/L", "CTC"),
                List.of(List.of("Agent A:Agent B (3:1)", "0.35", "131.4")), List.of(), "", true, "");
        Files.writeString(root.resolve("tables.jsonl"), mapper.writeValueAsString(table));
        var method = new EvidenceChunk("doc:page:4", "patent/method", 4, null, null, "Mycelial growth assay at 2d.", "body", "");
        return support.augment(support.load(registry.require("Q1"), root).orElseThrow(), List.of(method), "doc", new TableLegendResolver());
    }

    private String markdown(String ec50) {
        List<String> cells = new ArrayList<>(Collections.nCopies(16, ""));
        cells.set(0, "Agent A:Agent B (3:1)");
        cells.set(6, "Mycelial growth assay");
        cells.set(7, "EC50=" + ec50 + " mg/L; 72h; 2d; source time conflict");
        cells.set(13, "CTC=131.4");
        return "| " + String.join(" | ", EvidenceMarkdownTableParser.Q1_MARKDOWN_HEADERS) + " |\n| "
                + String.join(" | ", Collections.nCopies(16, "---")) + " |\n| " + String.join(" | ", cells) + " |";
    }

    private ChatResponse response(String value) {
        return ChatResponse.builder().aiMessage(AiMessage.from(value)).build();
    }
}
