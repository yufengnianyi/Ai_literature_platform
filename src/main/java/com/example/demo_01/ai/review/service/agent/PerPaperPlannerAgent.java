package com.example.demo_01.ai.review.service.agent;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Per-paper compound inventory + paradigm hints (single LLM call).
 * Used by {@link com.example.demo_01.ai.review.agent.node.PlannerNode} when agent mode is enabled.
 */
@Slf4j
@Service
public class PerPaperPlannerAgent {

    private static final String SYSTEM_PROMPT = """
            You are a paper compound planner. Given document knowledge context and top chunks from one paper,
            identify ALL distinct compounds mentioned and their roles.
            Return JSON only:
            {"compounds": [{"canonicalName": "...", "localLabels": ["compound 34"], "role": "SUBJECT|POSITIVE_CONTROL|COMPARATOR|REFERENCE", "paradigmHints": ["MYCELIAL_GROWTH_ASSAY"]}]}
            Rules:
            - List every compound that has quantitative activity data in this paper.
            - Use canonical names when resolved; keep local labels for unresolved ones.
            - role reflects the compound's purpose in THIS paper.
            - paradigmHints lists experimental paradigms where this compound is tested.
            """;

    @Resource
    private ReviewReasoningChatClient reasoningChatClient;

    @Resource
    private ObjectMapper objectMapper;

    public ArrayDeque<PerPaperAgentState.CompoundSpec> buildCompoundQueue(UUID documentId, List<RetrievedChunk> seedChunks) {
        String topChunks = seedChunks.stream()
                .limit(4)
                .map(c -> "--- " + c.sectionPath() + " ---\n" + truncate(c.text(), 2000))
                .collect(Collectors.joining("\n\n"));

        String userMsg = "Document ID: " + documentId + "\n\nTop chunks:\n" + topChunks;

        try {
            ChatResponse response = reasoningChatClient.chatStandard(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(userMsg));
            AiMessage ai = response.aiMessage();
            String raw = ai != null && ai.text() != null ? ai.text() : "{}";
            PlannerOutput output = objectMapper.readValue(extractJson(raw), PlannerOutput.class);

            ArrayDeque<PerPaperAgentState.CompoundSpec> queue = new ArrayDeque<>();
            if (output.compounds != null) {
                for (var c : output.compounds) {
                    queue.add(new PerPaperAgentState.CompoundSpec(
                            c.canonicalName != null ? c.canonicalName
                                    : (c.localLabels != null && !c.localLabels.isEmpty() ? c.localLabels.get(0) : "unknown"),
                            c.localLabels != null ? c.localLabels : List.of(),
                            c.role != null ? c.role : "SUBJECT",
                            c.paradigmHints != null ? c.paradigmHints : List.of()
                    ));
                }
            }
            log.info("PerPaperPlannerAgent identified {} compounds for document {}", queue.size(), documentId);
            return queue;
        } catch (Exception e) {
            log.warn("PerPaperPlannerAgent failed for document {}: {}", documentId, e.getMessage());
            return new ArrayDeque<>();
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('{');
            int last = trimmed.lastIndexOf('}');
            if (first >= 0 && last > first) return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : (s != null ? s : "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PlannerOutput {
        public List<CompoundEntry> compounds;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CompoundEntry {
        public String canonicalName;
        public List<String> localLabels;
        public String role;
        public List<String> paradigmHints;
    }
}
