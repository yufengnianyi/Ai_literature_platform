package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.DocumentKnowledgeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentKnowledgeEnrichmentService {

    public static final String PROMPT_VERSION = "document-knowledge-enrichment-v2";
    private static final String KNOWLEDGE_VERSION = "v1";
    private static final int MAX_CHUNKS_PER_DOCUMENT = 12;
    private static final int MAX_CHUNK_CHARS = 2500;

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private DocumentKnowledgeRepository documentKnowledgeRepository;

    @Resource
    private CompoundIdentityResolver compoundIdentityResolver;

    @Resource
    private DocumentKnowledgeMerger documentKnowledgeMerger;

    @Resource
    private CompoundDefinitionAnchorRetriever compoundDefinitionAnchorRetriever;

    public Map<UUID, DocumentKnowledgeContext> enrich(UUID taskId,
                                                      QueryAnalysis analysis,
                                                      List<RetrievedChunk> approvedChunks) {
        Map<UUID, List<RetrievedChunk>> byDocument = groupByDocument(approvedChunks);
        if (byDocument.isEmpty()) {
            return Map.of();
        }
        Map<UUID, DocumentKnowledgeContext> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<RetrievedChunk>> entry : byDocument.entrySet()) {
            UUID documentId = entry.getKey();
            List<RetrievedChunk> chunks = entry.getValue();
            try {
                result.put(documentId, enrichOne(taskId, analysis, documentId, chunks));
            } catch (Exception e) {
                log.warn("Document knowledge enrichment failed for document {}: {}", documentId, e.getMessage());
                documentKnowledgeRepository.findKnowledge(documentId)
                        .ifPresentOrElse(
                                knowledge -> result.put(documentId, toContext(knowledge, aliases(documentId))),
                                () -> result.put(documentId, emptyContext(documentId, KnowledgeStatus.MISS))
                        );
            }
        }
        return result;
    }

    private DocumentKnowledgeContext enrichOne(UUID taskId,
                                               QueryAnalysis analysis,
                                               UUID documentId,
                                               List<RetrievedChunk> chunks) {
        Optional<DocumentKnowledgeRecord> existing = documentKnowledgeRepository.findKnowledge(documentId);
        KnowledgeStatus status = classify(existing.orElse(null), chunks, analysis);
        if (status == KnowledgeStatus.HIT) {
            DocumentKnowledgeRecord knowledge = existing.orElseThrow();
            documentKnowledgeRepository.insertUpdateLog(taskId, documentId, PROMPT_VERSION,
                    List.of("cacheHit"), chunks.stream().map(RetrievedChunk::chunkId).toList());
            return toContext(knowledge, aliases(documentId));
        }

        List<RetrievedChunk> enrichedChunks = injectDefinitionChunks(documentId, chunks);
        DocumentKnowledgeRecord extracted = extractKnowledge(analysis, documentId, enrichedChunks, existing.orElse(null));
        DocumentKnowledgeRecord withIdentity = attachCompoundIdentities(extracted, documentId);
        DocumentKnowledgeRecord merged = documentKnowledgeMerger.merge(
                existing.orElse(null),
                withIdentity,
                documentId,
                taskId,
                KnowledgeStatus.HIT,
                PROMPT_VERSION,
                KNOWLEDGE_VERSION,
                chunks.stream().map(RetrievedChunk::chunkId).filter(Objects::nonNull).toList()
        );
        List<DocumentKnowledgeCompound> persistedCompounds = persistCompounds(documentId, merged.compounds());
        DocumentKnowledgeRecord persisted = withCompounds(merged, persistedCompounds);
        documentKnowledgeRepository.upsertKnowledge(persisted);
        documentKnowledgeRepository.insertUpdateLog(taskId, documentId, PROMPT_VERSION,
                updatedFields(status, persisted), persisted.coverageChunkIds());
        return toContext(persisted, aliases(documentId));
    }

    private KnowledgeStatus classify(DocumentKnowledgeRecord existing,
                                     List<RetrievedChunk> chunks,
                                     QueryAnalysis analysis) {
        if (existing == null) {
            return KnowledgeStatus.MISS;
        }
        if (!PROMPT_VERSION.equals(existing.promptVersion())) {
            return KnowledgeStatus.STALE;
        }
        Set<String> covered = new LinkedHashSet<>(safeList(existing.coverageChunkIds()));
        boolean hasNewChunk = chunks.stream()
                .map(RetrievedChunk::chunkId)
                .filter(Objects::nonNull)
                .anyMatch(id -> !covered.contains(id));
        if (hasNewChunk) {
            return KnowledgeStatus.PARTIAL;
        }
        if (queryNeedsCompounds(analysis) && safeList(existing.compounds()).isEmpty()) {
            return KnowledgeStatus.PARTIAL;
        }
        return KnowledgeStatus.HIT;
    }

    private boolean queryNeedsCompounds(QueryAnalysis analysis) {
        if (analysis == null) {
            return false;
        }
        String combined = String.join(" ",
                safe(analysis.mainQuestion()),
                String.join(" ", safeList(analysis.subQuestions())),
                String.join(" ", safeList(analysis.keyEntities())),
                String.join(" ", safeList(analysis.keyConcepts())))
                .toLowerCase();
        return combined.contains("compound")
                || combined.contains("chemical")
                || combined.contains("metabolite")
                || combined.contains("molecule")
                || combined.contains("化合物");
    }

    private List<RetrievedChunk> injectDefinitionChunks(UUID documentId, List<RetrievedChunk> existing) {
        try {
            List<RetrievedChunk> defChunks = compoundDefinitionAnchorRetriever.findDefinitionChunks(documentId, 3);
            if (defChunks.isEmpty()) return existing;
            Set<String> existingIds = existing.stream().map(RetrievedChunk::chunkId).collect(java.util.stream.Collectors.toSet());
            List<RetrievedChunk> combined = new ArrayList<>(existing);
            for (RetrievedChunk dc : defChunks) {
                if (!existingIds.contains(dc.chunkId())) {
                    combined.add(new RetrievedChunk(dc.chunkId(), dc.documentId(), dc.documentTitle(),
                            "[COMPOUND_DEFINITION_CHUNK] " + dc.text(), dc.sectionPath(), dc.score(), "DEF_ANCHOR"));
                }
            }
            return combined;
        } catch (Exception e) {
            log.debug("Definition chunk injection skipped for {}: {}", documentId, e.getMessage());
            return existing;
        }
    }

    private DocumentKnowledgeRecord extractKnowledge(QueryAnalysis analysis,
                                                     UUID documentId,
                                                     List<RetrievedChunk> chunks,
                                                     DocumentKnowledgeRecord existing) {
        String userPrompt = buildUserPrompt(analysis, documentId, chunks, existing);
        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_DOCUMENT_KNOWLEDGE_ENRICHMENT_SYSTEM)),
                    UserMessage.from(userPrompt)
            );
            AiMessage ai = response.aiMessage();
            String raw = ai == null || ai.text() == null ? "{}" : ai.text();
            DocumentKnowledgeRecord parsed = objectMapper.readValue(extractJson(raw), DocumentKnowledgeRecord.class);
            return normalizeParsed(documentId, parsed, chunks);
        } catch (Exception e) {
            log.warn("Document knowledge LLM extraction failed for document {}: {}", documentId, e.getMessage());
            return fallbackKnowledge(documentId, chunks);
        }
    }

    private String buildUserPrompt(QueryAnalysis analysis,
                                   UUID documentId,
                                   List<RetrievedChunk> chunks,
                                   DocumentKnowledgeRecord existing) {
        StringBuilder prompt = new StringBuilder();
        StringBuilder subQuestionsText = new StringBuilder();
        for (String subQuestion : safeList(analysis == null ? null : analysis.subQuestions())) {
            subQuestionsText.append("- ").append(subQuestion).append("\n");
        }
        String existingJson;
        try {
            existingJson = existing == null ? "{}" : objectMapper.writeValueAsString(existing);
        } catch (Exception ignored) {
            existingJson = "{}";
        }
        StringBuilder chunksText = new StringBuilder();
        chunks.stream().limit(MAX_CHUNKS_PER_DOCUMENT).forEach(chunk -> chunksText.append(PromptResources.format(
                PromptCatalog.REVIEW_DOCUMENT_KNOWLEDGE_ENRICHMENT_CHUNK,
                chunk.chunkId(),
                safe(chunk.sectionPath()),
                truncate(chunk.text(), MAX_CHUNK_CHARS))));
        prompt.append(PromptResources.format(
                PromptCatalog.REVIEW_DOCUMENT_KNOWLEDGE_ENRICHMENT_USER,
                safe(analysis == null ? null : analysis.mainQuestion()),
                subQuestionsText,
                documentId,
                chunks.stream().map(RetrievedChunk::documentTitle)
                        .filter(Objects::nonNull).findFirst().orElse(""),
                existingJson,
                chunksText));
        return prompt.toString();
    }

    private DocumentKnowledgeRecord normalizeParsed(UUID documentId,
                                                    DocumentKnowledgeRecord parsed,
                                                    List<RetrievedChunk> chunks) {
        return new DocumentKnowledgeRecord(
                documentId,
                parsed.documentSummary(),
                safeList(parsed.researchObjects()),
                safeList(parsed.species()),
                safeList(parsed.genesOrProteins()),
                safeList(parsed.pathwaysOrProcesses()),
                safeList(parsed.developmentalStages()),
                safeList(parsed.methods()),
                safeList(parsed.compounds()),
                safeList(parsed.keyFindings()),
                safeList(parsed.innovationPoints()),
                safeList(parsed.limitations()),
                safeList(parsed.evidenceAnchors()),
                KnowledgeStatus.PARTIAL,
                PROMPT_VERSION,
                KNOWLEDGE_VERSION,
                parsed.confidence() > 0 ? parsed.confidence() : averageCompoundConfidence(parsed.compounds()),
                chunks.stream().map(RetrievedChunk::chunkId).filter(Objects::nonNull).toList(),
                null,
                Instant.now()
        );
    }

    private DocumentKnowledgeRecord fallbackKnowledge(UUID documentId, List<RetrievedChunk> chunks) {
        List<DocumentEvidenceAnchor> anchors = chunks.stream().limit(4)
                .map(chunk -> new DocumentEvidenceAnchor(chunk.chunkId(), truncate(chunk.text(), 500),
                        "fallback evidence anchor", 0.2))
                .toList();
        return new DocumentKnowledgeRecord(
                documentId,
                chunks.stream().map(RetrievedChunk::documentTitle).filter(Objects::nonNull).findFirst().orElse(null),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                chunks.stream().map(RetrievedChunk::text).filter(Objects::nonNull).map(text -> truncate(text, 220)).limit(3).toList(),
                List.of(), List.of(), anchors, KnowledgeStatus.PARTIAL, PROMPT_VERSION, KNOWLEDGE_VERSION,
                0.2, chunks.stream().map(RetrievedChunk::chunkId).filter(Objects::nonNull).toList(), null, Instant.now()
        );
    }

    private DocumentKnowledgeRecord attachCompoundIdentities(DocumentKnowledgeRecord record, UUID documentId) {
        List<DocumentKnowledgeCompound> compounds = safeList(record.compounds()).stream()
                .map(compound -> {
                    CompoundIdentity identity = compoundIdentityResolver.resolve(compound);
                    DocumentKnowledgeCompound withIdentity = compoundIdentityResolver.attachIdentity(compound, identity);
                    return normalizeCompound(withIdentity);
                })
                .toList();
        return new DocumentKnowledgeRecord(
                documentId, record.documentSummary(), record.researchObjects(), record.species(),
                record.genesOrProteins(), record.pathwaysOrProcesses(), record.developmentalStages(),
                record.methods(), compounds, record.keyFindings(), record.innovationPoints(),
                record.limitations(), record.evidenceAnchors(), record.knowledgeStatus(),
                record.promptVersion(), record.knowledgeVersion(), record.confidence(),
                record.coverageChunkIds(), record.lastSeenTaskId(), record.updatedAt()
        );
    }

    private DocumentKnowledgeCompound normalizeCompound(DocumentKnowledgeCompound compound) {
        CompoundResolutionStatus status = compound.resolutionStatus() == null
                ? CompoundResolutionStatus.UNRESOLVED
                : compound.resolutionStatus();
        return new DocumentKnowledgeCompound(
                compound.localAlias(), compound.resolvedName(), compound.canonicalName(),
                compound.iupacName(), compound.casNumber(), compound.smiles(), compound.inchiKey(),
                compound.molecularFormula(), compound.structureType(), compound.source(), compound.bioactivity(),
                safeList(compound.targetOrganism()), safeList(compound.mechanism()), status,
                compound.evidenceChunkId(), compound.evidenceText(), compound.confidence(),
                compound.normalizedCompoundId()
        );
    }

    private DocumentKnowledgeRecord withCompounds(DocumentKnowledgeRecord record, List<DocumentKnowledgeCompound> compounds) {
        return new DocumentKnowledgeRecord(
                record.documentId(), record.documentSummary(), record.researchObjects(), record.species(),
                record.genesOrProteins(), record.pathwaysOrProcesses(), record.developmentalStages(),
                record.methods(), compounds, record.keyFindings(), record.innovationPoints(),
                record.limitations(), record.evidenceAnchors(), record.knowledgeStatus(),
                record.promptVersion(), record.knowledgeVersion(), record.confidence(),
                record.coverageChunkIds(), record.lastSeenTaskId(), record.updatedAt()
        );
    }

    private DocumentKnowledgeCompound withNormalizedCompoundId(DocumentKnowledgeCompound compound, UUID normalizedCompoundId) {
        if (compound == null || normalizedCompoundId == null) {
            return compound;
        }
        return new DocumentKnowledgeCompound(
                compound.localAlias(), compound.resolvedName(), compound.canonicalName(),
                compound.iupacName(), compound.casNumber(), compound.smiles(), compound.inchiKey(),
                compound.molecularFormula(), compound.structureType(), compound.source(), compound.bioactivity(),
                safeList(compound.targetOrganism()), safeList(compound.mechanism()), compound.resolutionStatus(),
                compound.evidenceChunkId(), compound.evidenceText(), compound.confidence(),
                normalizedCompoundId.toString()
        );
    }

    private List<DocumentKnowledgeCompound> persistCompounds(UUID documentId, List<DocumentKnowledgeCompound> compounds) {
        List<DocumentKnowledgeCompound> persisted = new ArrayList<>();
        for (DocumentKnowledgeCompound compound : safeList(compounds)) {
            CompoundIdentity identity = compoundIdentityResolver.resolve(compound);
            UUID persistedIdentityId = documentKnowledgeRepository.upsertCompoundIdentity(identity);
            DocumentKnowledgeCompound aliasCompound = withNormalizedCompoundId(compound, persistedIdentityId);
            documentKnowledgeRepository.upsertAlias(documentId, aliasCompound);
            persisted.add(aliasCompound);
        }
        return persisted;
    }

    private DocumentKnowledgeContext toContext(DocumentKnowledgeRecord knowledge, List<DocumentCompoundAlias> aliases) {
        Map<String, String> aliasMap = buildAliasResolutionMap(knowledge, aliases);
        return new DocumentKnowledgeContext(
                knowledge.documentId(),
                knowledge.knowledgeStatus(),
                aliases,
                knownCompounds(knowledge, aliases),
                safeList(knowledge.species()),
                safeList(knowledge.genesOrProteins()),
                safeList(knowledge.pathwaysOrProcesses()),
                safeList(knowledge.developmentalStages()),
                safeList(knowledge.methods()),
                safeList(knowledge.keyFindings()),
                safeList(knowledge.innovationPoints()),
                aliasMap
        );
    }

    private Map<String, String> buildAliasResolutionMap(DocumentKnowledgeRecord knowledge,
                                                         List<DocumentCompoundAlias> aliases) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (DocumentKnowledgeCompound compound : safeList(knowledge.compounds())) {
            if (compound.localAlias() != null && !compound.localAlias().isBlank()) {
                String key = compound.localAlias().toLowerCase(java.util.Locale.ROOT).trim();
                String canonical = compound.canonicalName() != null ? compound.canonicalName()
                        : compound.resolvedName();
                if (canonical != null && !canonical.isBlank()
                        && compound.resolutionStatus() != CompoundResolutionStatus.UNRESOLVED) {
                    map.put(key, canonical);
                } else {
                    map.putIfAbsent(key, "local:" + knowledge.documentId() + ":" + compound.localAlias().trim());
                }
            }
        }
        for (DocumentCompoundAlias alias : safeList(aliases)) {
            if (alias.localAlias() != null) {
                String key = alias.localAlias().toLowerCase(java.util.Locale.ROOT).trim();
                if (alias.resolvedName() != null && !alias.resolvedName().isBlank()
                        && alias.resolutionStatus() != CompoundResolutionStatus.UNRESOLVED) {
                    map.putIfAbsent(key, alias.resolvedName());
                } else {
                    map.putIfAbsent(key, "local:" + knowledge.documentId() + ":" + alias.localAlias().trim());
                }
            }
        }
        return map;
    }

    private List<String> knownCompounds(DocumentKnowledgeRecord knowledge, List<DocumentCompoundAlias> aliases) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        safeList(knowledge.compounds()).forEach(compound -> {
            add(names, compound.canonicalName());
            add(names, compound.resolvedName());
            add(names, compound.iupacName());
            if (compound.resolutionStatus() == CompoundResolutionStatus.UNRESOLVED) {
                add(names, "unresolved local compound label in this document: " + compound.localAlias());
            }
        });
        safeList(aliases).forEach(alias -> {
            add(names, alias.resolvedName());
            if (alias.resolutionStatus() == CompoundResolutionStatus.UNRESOLVED) {
                add(names, "unresolved local compound label in this document: " + alias.localAlias());
            }
        });
        return new ArrayList<>(names);
    }

    private DocumentKnowledgeContext emptyContext(UUID documentId, KnowledgeStatus status) {
        return new DocumentKnowledgeContext(documentId, status, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private List<DocumentCompoundAlias> aliases(UUID documentId) {
        return documentKnowledgeRepository.findAliasesByDocumentIds(Set.of(documentId))
                .getOrDefault(documentId, List.of());
    }

    private Map<UUID, List<RetrievedChunk>> groupByDocument(List<RetrievedChunk> chunks) {
        if (chunks == null) {
            return Map.of();
        }
        return chunks.stream()
                .filter(chunk -> chunk.documentId() != null)
                .collect(Collectors.groupingBy(RetrievedChunk::documentId, LinkedHashMap::new, Collectors.toList()));
    }

    private List<String> updatedFields(KnowledgeStatus previousStatus, DocumentKnowledgeRecord record) {
        List<String> fields = new ArrayList<>();
        fields.add(previousStatus.name());
        if (!safeList(record.compounds()).isEmpty()) fields.add("compounds");
        if (!safeList(record.keyFindings()).isEmpty()) fields.add("keyFindings");
        if (!safeList(record.innovationPoints()).isEmpty()) fields.add("innovationPoints");
        return fields;
    }

    private double averageCompoundConfidence(List<DocumentKnowledgeCompound> compounds) {
        return safeList(compounds).stream().mapToDouble(DocumentKnowledgeCompound::confidence).average().orElse(0.5);
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

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void add(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
