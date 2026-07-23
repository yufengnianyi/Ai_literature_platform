package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.config.EvidenceProperties;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class EvidenceRetrievalAgent {

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private EvidenceProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    public List<List<EvidenceChunk>> modelBatches(EvidenceProfile profile,
                                                  List<EvidenceChunk> chunks) {
        if (!properties.getAgents().getRetriever().isOnDemandEnabled()) {
            return staticBatches(chunks);
        }
        List<EvidenceChunk> selected = selectChunks(profile, chunks);
        if (selected.isEmpty()) {
            return staticBatches(chunks);
        }
        int totalChars = selected.stream().mapToInt(chunk -> value(chunk.text()).length()).sum();
        if (selected.size() <= properties.getMaxSinglePassChunks()
                && totalChars <= properties.getMaxSinglePassChars()) {
            return List.of(selected);
        }
        return partition(selected, Math.max(1, properties.getChunkBatchSize()));
    }

    private List<EvidenceChunk> selectChunks(EvidenceProfile profile, List<EvidenceChunk> chunks) {
        List<EvidenceChunk> ranked = rankHeuristically(profile, chunks);
        List<EvidenceChunk> shortlist = ranked.stream()
                .limit(Math.max(properties.getAgents().getRetriever().getMaxChunks() * 2L, 12))
                .toList();
        try {
            String systemPrompt = PromptResources.load(
                    PromptCatalog.EVIDENCE_MULTI_PROFILE_RETRIEVAL_SYSTEM);
            String userMessage = retrievalInput(profile, shortlist);
            String raw = responseText(chatClient.chatStandard(
                    SystemMessage.from(systemPrompt), UserMessage.from(userMessage)));
            RetrievalOutput output = objectMapper.readValue(extractJson(raw), RetrievalOutput.class);
            Map<String, EvidenceChunk> byId = new LinkedHashMap<>();
            for (EvidenceChunk chunk : chunks) {
                if (hasText(chunk.chunkId())) {
                    byId.put(chunk.chunkId(), chunk);
                }
            }
            List<EvidenceChunk> chosen = new ArrayList<>();
            if (output.chunkIds() != null) {
                for (String chunkId : output.chunkIds()) {
                    EvidenceChunk chunk = byId.get(chunkId);
                    if (chunk != null) {
                        chosen.add(chunk);
                    }
                }
            }
            if (Boolean.TRUE.equals(output.needExpansion())) {
                chosen = expand(chosen, chunks);
            }
            if (chosen.isEmpty()) {
                chosen = new ArrayList<>(shortlist.stream()
                        .limit(properties.getAgents().getRetriever().getMaxChunks())
                        .toList());
            }
            return trim(chosen);
        } catch (Exception e) {
            log.warn("On-demand retrieval failed for {}; falling back to heuristics: {}",
                    profile.questionId(), e.getMessage());
            return trim(shortlist);
        }
    }

    private List<EvidenceChunk> rankHeuristically(EvidenceProfile profile, List<EvidenceChunk> chunks) {
        boolean preferTables = properties.getAgents().getRetriever().isPreferTablesAndFigures();
        String haystack = (profile.title() + " " + profile.scope() + " " + profile.guidance())
                .toLowerCase(Locale.ROOT);
        return chunks.stream()
                .sorted(Comparator
                        .comparingInt((EvidenceChunk chunk) -> score(chunk, haystack, preferTables))
                        .reversed())
                .toList();
    }

    private int score(EvidenceChunk chunk, String profileText, boolean preferTables) {
        int score = 0;
        String section = value(chunk.sectionPath()).toLowerCase(Locale.ROOT);
        String text = value(chunk.text()).toLowerCase(Locale.ROOT);
        if (preferTables && (section.contains("table") || section.contains("figure")
                || section.contains("表") || section.contains("图"))) {
            score += 8;
        }
        if (section.contains("result") || section.contains("结果")
                || section.contains("method") || section.contains("方法")
                || section.contains("abstract") || section.contains("摘要")) {
            score += 4;
        }
        for (String token : profileText.split("\\W+")) {
            if (token.length() < 4) {
                continue;
            }
            if (text.contains(token) || section.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private List<EvidenceChunk> expand(List<EvidenceChunk> selected, List<EvidenceChunk> all) {
        int expand = properties.getAgents().getRetriever().getExpandParentSections();
        if (expand <= 0 || selected.isEmpty()) {
            return selected;
        }
        Set<String> parents = new LinkedHashSet<>();
        for (EvidenceChunk chunk : selected) {
            String section = value(chunk.sectionPath());
            int cut = section.indexOf('>');
            parents.add(cut > 0 ? section.substring(0, cut).trim().toLowerCase(Locale.ROOT)
                    : section.toLowerCase(Locale.ROOT));
        }
        Map<String, EvidenceChunk> merged = new LinkedHashMap<>();
        for (EvidenceChunk chunk : selected) {
            merged.put(chunkKey(chunk), chunk);
        }
        for (EvidenceChunk chunk : all) {
            String section = value(chunk.sectionPath()).toLowerCase(Locale.ROOT);
            for (String parent : parents) {
                if (!parent.isBlank() && section.startsWith(parent)) {
                    merged.putIfAbsent(chunkKey(chunk), chunk);
                    break;
                }
            }
        }
        return List.copyOf(merged.values());
    }

    private List<EvidenceChunk> trim(List<EvidenceChunk> chunks) {
        int max = Math.max(1, properties.getAgents().getRetriever().getMaxChunks());
        return chunks.stream().limit(max).toList();
    }

    private List<List<EvidenceChunk>> staticBatches(List<EvidenceChunk> chunks) {
        int totalChars = chunks.stream().mapToInt(chunk -> value(chunk.text()).length()).sum();
        if (chunks.size() <= properties.getMaxSinglePassChunks()
                && totalChars <= properties.getMaxSinglePassChars()) {
            return List.of(mergeChunks(contextChunks(chunks), chunks));
        }
        int batchSize = Math.max(1, properties.getChunkBatchSize());
        List<EvidenceChunk> sharedContext = contextChunks(chunks);
        List<List<EvidenceChunk>> batches = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(chunks.size(), start + batchSize);
            int adjacentStart = Math.max(0, start - 1);
            int adjacentEnd = Math.min(chunks.size(), end + 1);
            batches.add(mergeChunks(
                    sharedContext, chunks.subList(adjacentStart, adjacentEnd)));
        }
        return List.copyOf(batches);
    }

    private List<List<EvidenceChunk>> partition(List<EvidenceChunk> chunks, int batchSize) {
        List<List<EvidenceChunk>> batches = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(chunks.size(), start + batchSize);
            batches.add(List.copyOf(chunks.subList(start, end)));
        }
        return List.copyOf(batches);
    }

    private List<EvidenceChunk> contextChunks(List<EvidenceChunk> chunks) {
        List<EvidenceChunk> context = chunks.stream()
                .filter(chunk -> {
                    String section = value(chunk.sectionPath()).toLowerCase(Locale.ROOT);
                    return section.contains("abstract") || section.contains("摘要")
                            || section.contains("method") || section.contains("材料")
                            || section.contains("方法") || section.contains("result")
                            || section.contains("结果") || section.contains("table")
                            || section.contains("figure");
                })
                .limit(6)
                .toList();
        return context.isEmpty() ? chunks.stream().limit(2).toList() : context;
    }

    private List<EvidenceChunk> mergeChunks(List<EvidenceChunk> context, List<EvidenceChunk> batch) {
        Map<String, EvidenceChunk> merged = new LinkedHashMap<>();
        for (EvidenceChunk chunk : context) {
            merged.put(chunkKey(chunk), chunk);
        }
        for (EvidenceChunk chunk : batch) {
            merged.put(chunkKey(chunk), chunk);
        }
        return List.copyOf(merged.values());
    }

    private String retrievalInput(EvidenceProfile profile, List<EvidenceChunk> chunks) {
        StringBuilder catalog = new StringBuilder();
        for (EvidenceChunk chunk : chunks) {
            catalog.append("\n- chunk_id=").append(value(chunk.chunkId()))
                    .append("; section=").append(value(chunk.sectionPath()))
                    .append("; preview=")
                    .append(preview(chunk.text(), 180));
        }
        return """
                Evidence profile:
                - questionId: %s
                - title: %s
                - scope: %s
                - max chunks: %s

                Chunk catalog:
                %s
                """.formatted(
                profile.questionId(), profile.title(), profile.scope(),
                properties.getAgents().getRetriever().getMaxChunks(), catalog);
    }

    private String preview(String text, int max) {
        String value = value(text).replaceAll("\\s+", " ").trim();
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Model returned no JSON");
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Model output does not contain a JSON object");
        }
        return trimmed.substring(start, end + 1);
    }

    private String responseText(dev.langchain4j.model.chat.response.ChatResponse response) {
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
            throw new IllegalArgumentException("Model returned no text");
        }
        return response.aiMessage().text().trim();
    }

    private String chunkKey(EvidenceChunk chunk) {
        return value(chunk.chunkId()) + "\u001f" + value(chunk.text());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String value(String value) {
        return Objects.requireNonNullElse(value, "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetrievalOutput(List<String> chunkIds, String reason, Boolean needExpansion) {
    }
}
