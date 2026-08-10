package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class MultiProfileOutputValidator {

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    @Resource
    private ObjectMapper objectMapper;

    public List<ClassifiedQuestion> parseClassification(String raw,
                                                        EvidenceProfileRegistry registry,
                                                        List<EvidenceChunk> suppliedChunks) {
        ClassificationOutput output;
        try {
            output = objectMapper.readValue(extractJson(raw), ClassificationOutput.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Classification output is not valid JSON", e);
        }
        if (output.questions() == null) {
            throw new IllegalArgumentException("Classification output must contain questions");
        }
        Set<String> validChunkIds = new LinkedHashSet<>();
        for (EvidenceChunk chunk : suppliedChunks) {
            if (hasText(chunk.chunkId())) {
                validChunkIds.add(chunk.chunkId());
            }
        }
        Map<String, RawQuestionClassification> byQuestion = new LinkedHashMap<>();
        for (RawQuestionClassification item : output.questions()) {
            if (item == null || !hasText(item.questionId())) {
                throw new IllegalArgumentException("Every classification requires questionId");
            }
            registry.require(item.questionId());
            if (byQuestion.putIfAbsent(item.questionId(), item) != null) {
                throw new IllegalArgumentException("Duplicate classification for " + item.questionId());
            }
        }
        List<ClassifiedQuestion> results = new ArrayList<>();
        for (EvidenceProfile profile : registry.all()) {
            RawQuestionClassification rawItem = byQuestion.get(profile.questionId());
            if (rawItem == null) {
                throw new IllegalArgumentException("Missing classification for " + profile.questionId());
            }
            double confidence = clamp(rawItem.confidence());
            String declared = value(rawItem.status()).trim().toUpperCase(Locale.ROOT);
            if (!Set.of("SUPPORTED", "UNCERTAIN", "NOT_SUPPORTED").contains(declared)) {
                throw new IllegalArgumentException(
                        "Invalid classification status for " + profile.questionId() + ": " + declared);
            }
            List<String> requestedIds = rawItem.chunkIds() == null
                    ? List.of() : rawItem.chunkIds().stream().filter(this::hasText).distinct().toList();
            List<String> validIds = requestedIds.stream().filter(validChunkIds::contains).toList();
            boolean invalidCitations = validIds.size() != requestedIds.size();
            ClassificationStatus status;
            if ("NOT_SUPPORTED".equals(declared)) {
                status = ClassificationStatus.NOT_SUPPORTED;
                validIds = List.of();
            } else if ("SUPPORTED".equals(declared)
                    && confidence >= 0.70 && !validIds.isEmpty() && !invalidCitations) {
                status = ClassificationStatus.SUPPORTED;
            } else if (confidence >= 0.40 || invalidCitations
                    || ("SUPPORTED".equals(declared) && validIds.isEmpty())) {
                status = ClassificationStatus.UNCERTAIN;
            } else {
                status = ClassificationStatus.NOT_SUPPORTED;
                validIds = List.of();
            }
            results.add(new ClassifiedQuestion(
                    profile.questionId(), status, confidence, value(rawItem.reason()), validIds));
        }
        return List.copyOf(results);
    }

    public List<ClassifiedQuestion> mergeClassifications(
            EvidenceProfileRegistry registry,
            List<List<ClassifiedQuestion>> batches) {
        List<ClassifiedQuestion> merged = new ArrayList<>();
        for (EvidenceProfile profile : registry.all()) {
            List<ClassifiedQuestion> candidates = batches.stream()
                    .flatMap(List::stream)
                    .filter(item -> profile.questionId().equals(item.questionId()))
                    .toList();
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("No classification batch for " + profile.questionId());
            }
            ClassificationStatus status = candidates.stream()
                    .map(ClassifiedQuestion::status)
                    .max(Comparator.comparingInt(this::rank))
                    .orElse(ClassificationStatus.NOT_SUPPORTED);
            double confidence = candidates.stream()
                    .filter(item -> item.status() == status)
                    .mapToDouble(ClassifiedQuestion::confidence)
                    .max().orElse(0);
            List<String> chunkIds = candidates.stream()
                    .filter(item -> item.status() == status)
                    .flatMap(item -> item.chunkIds().stream())
                    .distinct().toList();
            String reason = candidates.stream()
                    .filter(item -> item.status() == status && hasText(item.reason()))
                    .map(ClassifiedQuestion::reason)
                    .distinct()
                    .limit(3)
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("");
            merged.add(new ClassifiedQuestion(
                    profile.questionId(), status, confidence, reason, chunkIds));
        }
        return List.copyOf(merged);
    }

    public List<ValidatedEvidenceRow> parseAndValidateEvidence(String raw,
                                                               EvidenceProfile profile,
                                                               List<EvidenceChunk> suppliedChunks) {
        ExtractionOutput output;
        try {
            output = objectMapper.readValue(extractJson(raw), ExtractionOutput.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Extraction output is not valid JSON", e);
        }
        if (output.rows() == null) {
            throw new IllegalArgumentException("Extraction output must contain rows");
        }
        Map<String, EvidenceChunk> chunksById = new HashMap<>();
        for (EvidenceChunk chunk : suppliedChunks) {
            if (hasText(chunk.chunkId())) {
                chunksById.put(chunk.chunkId(), chunk);
            }
        }
        Map<String, ValidatedEvidenceRow> unique = new LinkedHashMap<>();
        int inputIndex = 0;
        for (ExtractedRowInput input : output.rows()) {
            inputIndex++;
            if (input == null || input.cells() == null
                    || input.cells().size() != profile.headers().size()) {
                throw new IllegalArgumentException("Evidence row " + inputIndex
                        + " must contain exactly " + profile.headers().size() + " cells");
            }
            List<String> cells = input.cells().stream().map(this::value).toList();
            for (Integer index : profile.primaryFieldIndexes()) {
                if (index == null || index < 0 || index >= cells.size()
                        || !hasText(cells.get(index))) {
                    throw new IllegalArgumentException("Evidence row " + inputIndex
                            + " is missing primary field " + profile.headers().get(index));
                }
            }
            if (input.anchors() == null || input.anchors().isEmpty()) {
                throw new IllegalArgumentException(
                        "Evidence row " + inputIndex + " requires at least one anchor");
            }
            List<ValidatedAnchor> anchors = new ArrayList<>();
            Set<String> seenAnchors = new LinkedHashSet<>();
            for (AnchorInput anchor : input.anchors()) {
                if (anchor == null || !hasText(anchor.chunkId()) || !hasText(anchor.exactQuote())) {
                    throw new IllegalArgumentException(
                            "Evidence row " + inputIndex + " contains an empty anchor");
                }
                EvidenceChunk chunk = chunksById.get(anchor.chunkId());
                if (chunk == null) {
                    throw new IllegalArgumentException(
                            "Evidence row " + inputIndex + " cites unknown chunk " + anchor.chunkId());
                }
                String normalizedQuote = normalizeText(anchor.exactQuote());
                if (!normalizeText(chunk.text()).contains(normalizedQuote)) {
                    throw new IllegalArgumentException(
                            "Evidence row " + inputIndex + " quote is not present in chunk "
                                    + anchor.chunkId());
                }
                String quoteHash = sha256(normalizedQuote);
                if (!seenAnchors.add(anchor.chunkId() + "\u001f" + quoteHash)) {
                    continue;
                }
                anchors.add(new ValidatedAnchor(
                        anchor.chunkId(), chunk.sectionPath(), chunk.paragraphIndex(),
                        chunk.sentenceStart(), chunk.sentenceEnd(), anchor.exactQuote().trim(), quoteHash));
            }
            String fingerprint = fingerprint(profile.questionId(), cells);
            unique.putIfAbsent(fingerprint, new ValidatedEvidenceRow(
                    UUID.randomUUID(), cells, fingerprint, List.copyOf(anchors)));
        }
        return List.copyOf(unique.values());
    }

    public String fingerprint(String questionId, List<String> cells) {
        String canonical = value(questionId).trim().toUpperCase(Locale.ROOT) + "\u001f"
                + cells.stream().map(this::normalizeText).map(String::toLowerCase)
                .reduce((left, right) -> left + "\u001f" + right).orElse("");
        return sha256(canonical);
    }

    public String renderMarkdown(EvidenceProfile profile, List<ValidatedEvidenceRow> rows) {
        StringBuilder markdown = new StringBuilder();
        appendMarkdownRow(markdown, profile.headers());
        appendMarkdownRow(markdown, java.util.Collections.nCopies(profile.headers().size(), "---"));
        for (ValidatedEvidenceRow row : rows) {
            appendMarkdownRow(markdown, row.cells());
        }
        return markdown.toString();
    }

    private void appendMarkdownRow(StringBuilder output, List<String> cells) {
        output.append("| ");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                output.append(" | ");
            }
            output.append(value(cells.get(index))
                    .replace("\\", "\\\\")
                    .replace("|", "\\|")
                    .replace("\r", " ")
                    .replace("\n", " "));
        }
        output.append(" |\n");
    }

    private int rank(ClassificationStatus status) {
        return switch (status) {
            case SUPPORTED -> 3;
            case UNCERTAIN -> 2;
            case NOT_SUPPORTED -> 1;
            case FAILED, NOT_CLASSIFIED -> 0;
        };
    }

    private double clamp(Double confidence) {
        if (confidence == null || confidence.isNaN() || confidence.isInfinite()) {
            return 0;
        }
        return Math.max(0, Math.min(1, confidence));
    }

    private String extractJson(String raw) {
        if (!hasText(raw)) {
            throw new IllegalArgumentException("Model returned no JSON");
        }
        String trimmed = raw.trim();
        int objectStart = trimmed.indexOf('{');
        int objectEnd = trimmed.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new IllegalArgumentException("Model output does not contain a JSON object");
        }
        return trimmed.substring(objectStart, objectEnd + 1);
    }

    private String normalizeText(String value) {
        return WHITESPACE.matcher(value(value)).replaceAll(" ").trim();
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
