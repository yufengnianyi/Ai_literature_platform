package com.example.demo_01.ai.evidence.table;

import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedAnchor;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidationStatus;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceRepository.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Source-only patent table validation. Never repairs model answers or supplies taxonomy. */
@Component
public class PatentQ1EvidenceSupport {
    public static final String EXTRACTION_INSTRUCTION =
            "prompts/evidence/patent-q1-extraction-instruction.txt";
    private static final Pattern RATIO = Pattern.compile("\\d+(?:\\.\\d+)?[:\\u2236]\\d+(?:\\.\\d+)?");
    private static final String NUMBER = "[<>\u2264\u2265~]?[+-]?\\d+(?:\\.\\d+)?(?:e[+-]?\\d+)?";
    private static final Pattern TIME = Pattern.compile("(?<![0-9a-z.])(\\d+(?:\\.\\d+)?)\\s*(h|d|\u5c0f\u65f6|\u5929)(?![a-z])");
    private final ObjectMapper mapper;
    private final TableSerializer serializer;

    public PatentQ1EvidenceSupport(ObjectMapper mapper, TableSerializer serializer) {
        this.mapper = mapper;
        this.serializer = serializer;
    }

    public Optional<PatentInput> load(EvidenceProfile profile, SourceDocument document) {
        return load(profile, document.storageRoot() == null || document.storageRoot().isBlank()
                ? null : Path.of(document.storageRoot()));
    }

    /** No TEI lookup or negative cache: a later successful recovery must be visible immediately. */
    public Optional<PatentInput> load(EvidenceProfile profile, Path root) {
        if (profile == null || !"Q1".equals(profile.questionId()) || root == null
                || !Files.isRegularFile(root.resolve("patent-table-manifest.json"))) {
            return Optional.empty();
        }
        try {
            JsonNode manifest = mapper.readTree(root.resolve("patent-table-manifest.json").toFile());
            if (!"PATENT_Q1".equals(manifest.path("kind").asText())) {
                return Optional.empty();
            }
            require(!manifest.path("publicationNumber").asText().isBlank(), "Missing publicationNumber");
            require(manifest.path("pdfSha256").asText().matches("(?i)[a-f0-9]{64}"), "Invalid pdfSha256");
            require(manifest.path("tables").isArray(), "Missing manifest tables");
            Map<String, JsonNode> metadata = new LinkedHashMap<>();
            for (JsonNode item : manifest.path("tables")) {
                String ref = item.path("tableRef").asText();
                require(!ref.isBlank() && !metadata.containsKey(ref), "Missing/duplicate tableRef");
                require(Set.of("VISION", "TEXT", "TEI").contains(item.path("sourceKind").asText()),
                        "Invalid sourceKind for " + ref);
                boolean vision = "VISION".equals(item.path("sourceKind").asText());
                if (vision || item.hasNonNull("pageNumber")) {
                    require(item.path("pageNumber").isIntegralNumber() && item.path("pageNumber").canConvertToInt()
                            && item.path("pageNumber").asInt() > 0, "Invalid pageNumber for " + ref);
                }
                require(Set.of("VERIFIED", "REVIEW_REQUIRED", "FAILED").contains(item.path("readStatus").asText()),
                        "Invalid readStatus for " + ref);
                JsonNode region = item.path("region");
                if (vision || item.hasNonNull("region")) {
                    for (String key : List.of("x", "y", "width", "height")) {
                        require(region.path(key).isNumber() && Double.isFinite(region.path(key).asDouble())
                                && region.path(key).asDouble() >= 0, "Invalid region for " + ref);
                    }
                    require(region.path("width").asDouble() > 0 && region.path("height").asDouble() > 0,
                            "Empty region for " + ref);
                }
                metadata.put(ref, item);
            }
            Map<String, ParsedTable> tables = new LinkedHashMap<>();
            Path jsonl = root.resolve("tables.jsonl");
            if (Files.isRegularFile(jsonl)) {
                for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    ParsedTable table = mapper.readValue(line, ParsedTable.class);
                    JsonNode item = metadata.get(table.tableRef());
                    require(item != null && "VERIFIED".equals(item.path("readStatus").asText()),
                            "Native table is not VERIFIED: " + table.tableRef());
                    require(table.structured() && table.headers() != null && !table.headers().isEmpty()
                            && table.rows() != null && !table.rows().isEmpty(), "Empty native table " + table.tableRef());
                    require(table.headers().stream().allMatch(Objects::nonNull)
                            && table.rows().stream().allMatch(row -> row != null
                            && row.size() == table.headers().size() && row.stream().allMatch(Objects::nonNull)),
                            "Malformed native cells " + table.tableRef());
                    require(tables.putIfAbsent(table.tableRef(), table) == null, "Duplicate native table");
                }
            }
            for (var item : metadata.entrySet()) {
                require(!"VERIFIED".equals(item.getValue().path("readStatus").asText())
                        || tables.containsKey(item.getKey()), "Missing VERIFIED table " + item.getKey());
            }
            List<JsonNode> bundles = readJsonNodes(root.resolve("patent-evidence-bundles.jsonl"));
            return Optional.of(new PatentInput(root.toAbsolutePath().normalize(), manifest,
                    List.copyOf(tables.values()), Map.copyOf(metadata), bundles));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read patent Q1 artifacts at " + root, e);
        }
    }

    public List<EvidenceChunk> augment(PatentInput input, List<EvidenceChunk> chunks,
                                       String documentId, TableLegendResolver resolver) {
        List<EvidenceChunk> result = new ArrayList<>();
        String sourceTei = chunks == null ? "" : chunks.stream().map(EvidenceChunk::sourceTei)
                .filter(Objects::nonNull).filter(value -> !value.isBlank()).findFirst().orElse("");
        if (chunks != null) {
            for (EvidenceChunk chunk : chunks) {
                if (chunk.chunkId() != null && chunk.chunkId().contains(":patent-table:")) continue;
                var markers = Pattern.compile("\\[evidence_role=([^]]+)]").matcher(text(chunk.text()));
                List<Integer> starts = new ArrayList<>();
                while (markers.find()) starts.add(markers.start());
                if (starts.isEmpty()) {
                    result.add(new EvidenceChunk(chunk.chunkId(), chunk.sectionPath(), chunk.paragraphIndex(),
                            chunk.sentenceStart(), chunk.sentenceEnd(), "[evidence_role=" + role(chunk) + "]\n" + text(chunk.text()),
                            chunk.contentType(), chunk.sourceTei()));
                } else {
                    // Each role belongs only to its own paragraph; never promote the rest of a mixed page.
                    String prefix = text(chunk.text()).substring(0, starts.getFirst());
                    if (!prefix.replaceAll("\\[[^]\\r\\n]+]", "").isBlank()) {
                        result.add(new EvidenceChunk(chunk.chunkId() + ":role:prefix", chunk.sectionPath(), chunk.paragraphIndex(),
                                chunk.sentenceStart(), chunk.sentenceEnd(), "[evidence_role=UNKNOWN]\n" + prefix,
                                chunk.contentType(), chunk.sourceTei()));
                        prefix = "";
                    }
                    for (int i = 0; i < starts.size(); i++) {
                        String paragraph = text(chunk.text()).substring(starts.get(i),
                                i + 1 < starts.size() ? starts.get(i + 1) : text(chunk.text()).length());
                        result.add(new EvidenceChunk(chunk.chunkId() + ":role:" + i, chunk.sectionPath(), chunk.paragraphIndex(),
                                chunk.sentenceStart(), chunk.sentenceEnd(), prefix + paragraph, chunk.contentType(), chunk.sourceTei()));
                    }
                }
            }
        }
        List<EvidenceChunk> context = List.copyOf(result);
        for (ParsedTable table : input.tables()) {
            JsonNode metadata = input.metadata().get(table.tableRef());
            Integer page = metadata.hasNonNull("pageNumber") ? metadata.path("pageNumber").asInt() : null;
            String prefix = "[publication_number=" + input.manifest().path("publicationNumber").asText()
                    + "]" + (page == null ? "" : "[patent_page=" + page + "]") + "[table_ref=" + table.tableRef()
                    + "][source_kind=" + metadata.path("sourceKind").asText() + "][read_status=VERIFIED]"
                    + "[image_path=" + metadata.path("imagePath").asText() + "][evidence_role=RESULT]";
            var legend = resolver.resolve(table, context);
            String body = serializer.render(table.caption(), table.headers(), table.rows(), table.footnotes(), true)
                    + legend.legendText();
            for (String quote : legend.supportingQuotes()) body += "\n" + quote;
            Set<String> methodDurations = times(context.stream().filter(c -> role(c).contains("METHOD"))
                    .map(this::originalText).reduce("", (left, right) -> left + "\n" + right));
            Set<String> tableDurations = times(String.join(";", table.headers()));
            if (methodDurations.size() == 1 && tableDurations.size() == 1
                    && Double.compare(hours(methodDurations.iterator().next()), hours(tableDurations.iterator().next())) != 0) {
                body += "\n[derived_source_notice=TIME_CONFLICT] Original METHOD duration: " + methodDurations
                        + "; original table duration: " + tableDurations
                        + ". The original source is inconsistent. Preserve both and explicitly label the conflict in Activity data.";
            }
            result.add(new EvidenceChunk(documentId + ":patent-table:" + table.tableRef(),
                    "patent/result/table/" + table.tableRef() + (page == null ? "" : " page=" + page),
                    page, null, null, prefix + "\n" + body, "table_body", sourceTei));
        }
        return List.copyOf(result);
    }

    public List<EvidenceChunk> extractionChunks(List<EvidenceChunk> chunks) {
        return extractionChunks(null, chunks);
    }

    public List<EvidenceChunk> extractionChunks(PatentInput input, List<EvidenceChunk> chunks) {
        if (input != null && input.bundles() != null && !input.bundles().isEmpty()) {
            Set<Integer> pages = input.bundles().stream()
                    .flatMap(bundle -> java.util.stream.StreamSupport.stream(bundle.path("pages").spliterator(), false))
                    .filter(JsonNode::isInt)
                    .map(JsonNode::asInt)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> tableRefs = input.bundles().stream()
                    .flatMap(bundle -> java.util.stream.StreamSupport.stream(bundle.path("tableRefs").spliterator(), false))
                    .map(JsonNode::asText)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<EvidenceChunk> scoped = chunks.stream()
                    .filter(chunk -> tableRefs.stream().anyMatch(ref -> text(chunk.chunkId()).endsWith(":patent-table:" + ref))
                            || pages.contains(chunk.paragraphIndex())
                            || pages.stream().anyMatch(page -> text(chunk.text()).contains("[patent_page=" + page + "]")))
                    .filter(chunk -> {
                        if (text(chunk.sectionPath()).contains("compound-map")) return true;
                        return (role(chunk).contains("METHOD") || role(chunk).contains("RESULT"))
                                && !text(chunk.text()).contains("[q1_eligible=false]");
                    })
                    .toList();
            if (!scoped.isEmpty()) {
                return scoped;
            }
        }
        return chunks.stream().filter(chunk -> {
            if (text(chunk.sectionPath()).contains("compound-map")) return true;
            return (role(chunk).contains("METHOD") || role(chunk).contains("RESULT"))
                    && !text(chunk.text()).contains("[q1_eligible=false]");
        }).toList();
    }

    /** Coverage is defined by rows actually supplied to this call, never by a fixed answer count. */
    public Assessment assess(PatentInput input, List<EvidenceChunk> chunks, List<ValidatedEvidenceRow> rows) {
        List<ExpectedRow> expected = new ArrayList<>();
        for (ParsedTable table : input.tables()) {
            EvidenceChunk chunk = chunks.stream().filter(c -> text(c.chunkId()).endsWith(":patent-table:" + table.tableRef()))
                    .findFirst().orElse(null);
            if (chunk == null) continue;
            int nameColumn = 0;
            for (int i = 0; i < table.headers().size(); i++) {
                if (normal(table.headers().get(i)).matches(".*(compound|treatment|name|\u836f\u5242|\u5904\u7406|\u540d\u79f0).*")) {
                    nameColumn = i;
                    break;
                }
            }
            for (int i = 0; i < table.rows().size(); i++) {
                List<String> nativeCells = table.rows().get(i);
                String quote = serializer.render(null, table.headers(), List.of(nativeCells), List.of(), true)
                        .lines().skip(2).findFirst().orElseThrow();
                expected.add(new ExpectedRow(table, i + 1, nativeCells.get(nameColumn),
                        nativeCells.stream().flatMap(value -> ratios(value).stream())
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()), metric(table, nativeCells, "ec50"),
                        metric(table, nativeCells, "ctc"), chunk, quote));
            }
        }
        List<EvidenceChunk> methods = chunks.stream().filter(c -> role(c).contains("METHOD")).toList();
        String empirical = chunks.stream().filter(c -> role(c).contains("METHOD") || role(c).contains("RESULT"))
                .map(this::originalText).reduce("", (left, right) -> left + "\n" + right);
        Set<String> methodTimes = times(methods.stream().map(this::originalText).reduce("", (left, right) -> left + "\n" + right));
        List<String> originalTargets = methods.stream().map(this::originalText)
                .flatMap(value -> Pattern.compile("(?:\u8bd5\u9a8c\u76ee\u6807\u7269\u4e3a|\u4f9b\u8bd5\u75c5\u539f\u4e3a)([^\u3002\uFF1B\\r\\n]+)")
                        .matcher(value).results().map(match -> match.group(1).strip())).distinct().toList();
        Set<UUID> used = new LinkedHashSet<>();
        List<RowComparison> comparisons = new ArrayList<>();
        Map<UUID, List<ValidatedAnchor>> anchors = new LinkedHashMap<>();
        List<String> mismatches = new ArrayList<>();
        for (ExpectedRow source : expected) {
            ValidatedEvidenceRow best = null;
            int bestScore = -1;
            for (ValidatedEvidenceRow candidate : rows) {
                if (used.contains(candidate.recordId())) continue;
                boolean name = nameMatches(source.name(), cell(candidate, 0));
                boolean ratio = source.ratios().equals(ratios(cell(candidate, 0)));
                int score = (name ? 8 : 0) + (ratio ? 4 : 0)
                        + (metricMatches(source.ec50(), cell(candidate, 7), "ec50") ? 2 : 0)
                        + (metricMatches(source.ctc(), cell(candidate, 13) + ";" + cell(candidate, 7), "ctc") ? 1 : 0);
                if (name && score > bestScore) { best = candidate; bestScore = score; }
            }
            Map<String, Boolean> flags = new LinkedHashMap<>();
            flags.put("rowPresent", best != null);
            flags.put("nameAccurate", best != null && nameMatches(source.name(), cell(best, 0)));
            flags.put("ratioAccurate", best != null && source.ratios().equals(ratios(cell(best, 0))));
            flags.put("ec50Accurate", best != null && metricMatches(source.ec50(), cell(best, 7), "ec50"));
            flags.put("ctcAccurate", best != null && metricMatches(source.ctc(), cell(best, 13) + ";" + cell(best, 7), "ctc"));
            flags.put("exactTableAnchor", text(source.chunk().text()).contains(source.quote()));
            flags.put("methodContextPresent", !methods.isEmpty());
            String activity = best == null ? "" : cell(best, 7);
            flags.put("originalTargetPreserved", best != null && originalTargets.stream()
                    .allMatch(target -> normal(activity).contains(normal(target))));
            Set<String> tableTimes = times(String.join(";", source.table().headers()));
            flags.put("reportedTimesPreserved", best != null && methodTimes.stream().allMatch(time -> normal(activity).contains(time))
                    && tableTimes.stream().allMatch(time -> normal(activity).contains(time)));
            boolean timeConflict = methodTimes.size() == 1 && tableTimes.size() == 1
                    && Double.compare(hours(methodTimes.iterator().next()), hours(tableTimes.iterator().next())) != 0;
            flags.put("timeConflictExplicit", !timeConflict || normal(activity).matches(
                    ".*(conflict|inconsisten|contradict|\u51b2\u7a81|\u4e0d\u4e00\u81f4).*"));
            String demonstrated = normal(empirical);
            ValidatedEvidenceRow matched = best;
            flags.put("demonstratedFieldsSupported", best != null && java.util.stream.IntStream.of(9, 10, 11, 12)
                    .allMatch(index -> cell(matched, index).isBlank() || demonstrated.contains(normal(cell(matched, index)))));
            flags.put("singleAgentSynergyAbsent", best != null && (!source.ratios().isEmpty() || cell(best, 13).isBlank()));
            if (best != null) {
                used.add(best.recordId());
                List<ValidatedAnchor> rowAnchors = new ArrayList<>();
                if (flags.get("exactTableAnchor")) rowAnchors.add(anchor(source.chunk(), source.quote()));
                for (EvidenceChunk method : methods) {
                    String quote = originalText(method);
                    if (!quote.isBlank()) rowAnchors.add(anchor(method, quote));
                }
                anchors.put(best.recordId(), List.copyOf(rowAnchors));
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("cells", source.table().rows().get(source.rowIndex() - 1));
            values.put("headers", source.table().headers());
            values.put("name", source.name());
            values.put("ratios", source.ratios());
            values.put("ec50", source.ec50());
            values.put("ctc", source.ctc());
            RowComparison comparison = new RowComparison(best == null ? null : best.recordId(),
                    source.table().tableRef(), source.rowIndex(), input.metadata().get(source.table().tableRef()),
                    source.quote(), values, best == null ? List.of() : best.cells(), Map.copyOf(flags));
            comparisons.add(comparison);
            if (flags.values().stream().anyMatch(flag -> !flag)) {
                mismatches.add(source.table().tableRef() + " row " + source.rowIndex() + " expected=" + values
                        + " actual=" + comparison.actual() + " flags=" + flags);
            }
        }
        List<UUID> extras = rows.stream().map(ValidatedEvidenceRow::recordId).filter(id -> !used.contains(id)).toList();
        if (!extras.isEmpty()) mismatches.add("Extra/unmatched model rows: " + extras);
        return new Assessment(List.copyOf(comparisons), extras, List.copyOf(mismatches), Map.copyOf(anchors));
    }

    public List<ValidatedEvidenceRow> attachAnchors(List<ValidatedEvidenceRow> rows, Assessment assessment) {
        return rows.stream().map(row -> new ValidatedEvidenceRow(row.recordId(), row.cells(), row.fingerprint(),
                assessment.anchors().getOrDefault(row.recordId(), List.of()), row.validationStatus(), row.verificationNote())).toList();
    }

    /** Runs after the generic verifier, retaining source-backed rows even when required fields are missing. */
    public List<ValidatedEvidenceRow> guard(PatentInput input, List<EvidenceChunk> chunks,
                                           List<ValidatedEvidenceRow> rows) {
        Assessment assessment = assess(input, chunks, rows);
        String original = chunks.stream().map(this::originalText).reduce("", (a, b) -> a + "\n" + b);
        String empirical = chunks.stream().filter(c -> role(c).contains("RESULT") || role(c).contains("METHOD"))
                .map(this::originalText).reduce("", (a, b) -> a + "\n" + b);
        List<ValidatedEvidenceRow> guarded = new ArrayList<>();
        for (ValidatedEvidenceRow row : attachAnchors(rows, assessment)) {
            List<String> errors = new ArrayList<>();
            RowComparison comparison = assessment.comparisons().stream()
                    .filter(c -> row.recordId().equals(c.recordId())).findFirst().orElse(null);
            if (comparison == null) errors.add("No VERIFIED native result row supports this record");
            else comparison.flags().forEach((key, valid) -> { if (!valid) errors.add(key); });
            if (cell(row, 5).isBlank()) errors.add("Missing required tested oomycete Latin name");
            else if (!original.contains(cell(row, 5)) || !cell(row, 5).matches(".*[A-Z][a-z]+(?:\\.|\\s)[\\sA-Za-z.-]*[a-z]{2,}.*")) {
                errors.add("Latin name is not explicit in supplied original content");
            }
            if (cell(row, 6).isBlank()) errors.add("Missing required assay method");
            if (!cell(row, 8).isBlank() && (!normal(empirical).matches(".*(positivecontrol|\u9633\u6027\u5bf9\u7167).*"))) {
                errors.add("Positive control is not identified by original experimental context");
            }
            for (int field : List.of(9, 10, 11, 12)) {
                if (!cell(row, field).isBlank() && !normal(empirical).contains(normal(cell(row, field)))) {
                    errors.add("Unsupported demonstrated field " + field + " (background/claims are insufficient)");
                }
            }
            guarded.add(errors.isEmpty() ? row : row.withValidation(ValidationStatus.INVALID,
                    text(row.verificationNote()) + "; Patent Q1: " + String.join("; ", errors)));
        }
        return List.copyOf(guarded);
    }

    /** Scope-keyed artifact fallback works for both batch and single-question extraction callers. */
    public Path writeAudit(PatentInput input, UUID scopeId, List<EvidenceChunk> chunks,
                           List<ValidatedEvidenceRow> rows, String phase, int attempt, String error) {
        Path output = input.root().resolve("extraction").resolve(scopeId == null ? "unscoped" : scopeId.toString())
                .resolve("patent-q1-audit.json");
        try {
            Files.createDirectories(output.getParent());
            ObjectNode audit = Files.isRegularFile(output) ? (ObjectNode) mapper.readTree(output.toFile()) : mapper.createObjectNode();
            audit.put("kind", "PATENT_Q1");
            audit.put("scopeId", scopeId == null ? null : scopeId.toString());
            audit.set("manifest", input.manifest());
            Assessment assessment = assess(input, chunks, rows);
            ObjectNode entry = mapper.createObjectNode();
            entry.put("phase", phase);
            entry.put("attempt", attempt);
            entry.put("error", error);
            entry.put("coverageAccurate", assessment.mismatches().isEmpty());
            entry.put("inputChunkCount", chunks.size());
            entry.put("inputChars", chunks.stream().mapToInt(chunk -> text(chunk.text()).length()).sum());
            entry.set("inputChunkIds", mapper.valueToTree(chunks.stream().map(EvidenceChunk::chunkId).toList()));
            entry.set("comparisons", mapper.valueToTree(assessment.comparisons()));
            entry.set("extraRecordIds", mapper.valueToTree(assessment.extraRecordIds()));
            entry.set("mismatches", mapper.valueToTree(assessment.mismatches()));
            entry.set("records", mapper.valueToTree(rows));
            audit.withArray("attempts").add(entry);
            audit.set("latest", entry);
            Path temporary = Files.createTempFile(output.getParent(), "patent-q1-audit-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), audit);
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist patent Q1 audit: " + output, e);
        }
    }

    private String metric(ParsedTable table, List<String> row, String metric) {
        for (int i = 0; i < table.headers().size(); i++) {
            String header = normal(table.headers().get(i));
            if (header.contains(metric) || (metric.equals("ctc") && header.contains("\u5171\u6bd2\u7cfb\u6570"))) return row.get(i);
        }
        return "";
    }

    private List<JsonNode> readJsonNodes(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                nodes.add(mapper.readTree(line));
            }
        }
        return List.copyOf(nodes);
    }

    private Set<String> times(String text) {
        return TIME.matcher(Normalizer.normalize(text(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
                .results().map(match -> match.group(1) + match.group(2))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private double hours(String time) {
        var matcher = TIME.matcher(time);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid source duration");
        return Double.parseDouble(matcher.group(1)) * (Set.of("d", "\u5929").contains(matcher.group(2)) ? 24 : 1);
    }

    private boolean metricMatches(String expected, String actual, String metric) {
        var nativeValue = Pattern.compile(NUMBER).matcher(normal(expected));
        String expectedValue = nativeValue.find() ? nativeValue.group() : "";
        String label = metric.equals("ctc") ? "(?:ctc|\u5171\u6bd2\u7cfb\u6570)(?:\\(ctc\\))?" : "ec50";
        var values = Pattern.compile(label + "(?:\\([^)]*\\)|\\[[^]]*])?[:=]?((?:" + NUMBER + "))")
                .matcher(normal(actual));
        Set<String> found = new LinkedHashSet<>();
        while (values.find()) found.add(values.group(1));
        // A reported CTC may be followed by the threshold defining synergy, not another measurement.
        if (metric.equals("ctc") && !expectedValue.matches("^[<>\u2264\u2265~].*")) {
            found.removeIf(value -> value.matches("^[<>\u2264\u2265~].*"));
        }
        return expectedValue.isBlank() ? found.isEmpty() : found.equals(Set.of(expectedValue));
    }

    private boolean nameMatches(String expected, String actual) {
        String name = RATIO.matcher(normal(expected)).replaceAll("").replaceAll("[()\\[\\]]", "");
        return !name.isBlank() && normal(actual).contains(name);
    }

    private Set<String> ratios(String value) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = RATIO.matcher(normal(value));
        while (matcher.find()) result.add(matcher.group().replace('\u2236', ':'));
        return Set.copyOf(result);
    }

    private String role(EvidenceChunk chunk) {
        String content = text(chunk.text());
        var explicit = Pattern.compile("\\[evidence_role=([^]]+)]").matcher(content);
        if (explicit.find()) return explicit.group(1);
        String metadata = text(chunk.sectionPath()).toLowerCase(Locale.ROOT);
        var pageRole = Pattern.compile("\\[page_role=([^]]+)]").matcher(content);
        if (pageRole.find()) metadata += " " + pageRole.group(1).toLowerCase(Locale.ROOT);
        List<String> roles = new ArrayList<>();
        if (metadata.contains("method") || metadata.contains("test_definition")) roles.add("METHOD");
        if (metadata.contains("result") || metadata.contains("activity_table")) roles.add("RESULT");
        if (metadata.contains("background") || metadata.contains("abstract")) roles.add("BACKGROUND");
        if (metadata.contains("claim")) roles.add("CLAIM");
        return roles.isEmpty() ? "UNKNOWN" : String.join("|", roles);
    }

    private String originalText(EvidenceChunk chunk) {
        String value = text(chunk.text());
        while (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) break;
            value = value.substring(end + 1).stripLeading();
        }
        return value;
    }

    private ValidatedAnchor anchor(EvidenceChunk chunk, String quote) {
        try {
            return new ValidatedAnchor(chunk.chunkId(), chunk.sectionPath(), chunk.paragraphIndex(),
                    chunk.sentenceStart(), chunk.sentenceEnd(), quote,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(quote.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String cell(ValidatedEvidenceRow row, int index) {
        return row.cells().size() <= index ? "" : text(row.cells().get(index));
    }

    private String normal(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_{}]", "");
    }

    private String text(String value) { return Objects.requireNonNullElse(value, ""); }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("Invalid patent Q1 artifact: " + message);
    }

    public record PatentInput(Path root, JsonNode manifest, List<ParsedTable> tables,
                              Map<String, JsonNode> metadata, List<JsonNode> bundles) { }

    private record ExpectedRow(ParsedTable table, int rowIndex, String name, Set<String> ratios,
                               String ec50, String ctc, EvidenceChunk chunk, String quote) { }

    public record RowComparison(UUID recordId, String tableRef, int rowIndex, JsonNode provenance,
                                String exactRowQuote, Map<String, Object> expected, List<String> actual,
                                Map<String, Boolean> flags) { }

    public record Assessment(List<RowComparison> comparisons, List<UUID> extraRecordIds,
                             List<String> mismatches, Map<UUID, List<ValidatedAnchor>> anchors) { }
}
