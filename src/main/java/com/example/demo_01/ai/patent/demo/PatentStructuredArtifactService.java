package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDiscoveryOutcome;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentDiscoveryStatus;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentEvidenceBundle;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageIndexRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentSubject;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTableCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.StructuredArtifactResult;
import com.example.demo_01.ai.preprocessing.artifact.PreprocessArtifactManifestWriter;
import com.example.demo_01.ai.preprocessing.model.PreprocessModels.PreprocessArtifact;
import com.example.demo_01.ai.rag.artifact.JsonlArtifactWriter;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagChunk;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PatentStructuredArtifactService {

    static final String CHUNK_STRATEGY_VERSION = "patent-paragraph-triage-v2";
    static final String PREPROCESS_VERSION = "patent-demo-v2";
    private static final int MAX_CHUNK_TEXT_CHARS = 6_000;

    private final AiPersistenceProperties properties;
    private final JsonlArtifactWriter jsonlArtifactWriter;
    private final PreprocessArtifactManifestWriter manifestWriter;
    private final ObjectMapper objectMapper;
    private final PatentTableRecoveryService tableRecoveryService;
    private final PatentPageIndexService pageIndexService;
    private final PatentSubjectResolver subjectResolver;
    private final PatentTableCandidateService tableCandidateService;
    private final PatentEvidenceBundleAssembler bundleAssembler;

    @Autowired
    public PatentStructuredArtifactService(AiPersistenceProperties properties,
                                           JsonlArtifactWriter jsonlArtifactWriter,
                                           PreprocessArtifactManifestWriter manifestWriter,
                                           ObjectMapper objectMapper,
                                           PatentTableRecoveryService tableRecoveryService,
                                           PatentPageIndexService pageIndexService,
                                           PatentSubjectResolver subjectResolver,
                                           PatentTableCandidateService tableCandidateService,
                                           PatentEvidenceBundleAssembler bundleAssembler) {
        this.properties = properties;
        this.jsonlArtifactWriter = jsonlArtifactWriter;
        this.manifestWriter = manifestWriter;
        this.objectMapper = objectMapper;
        this.tableRecoveryService = tableRecoveryService;
        this.pageIndexService = pageIndexService;
        this.subjectResolver = subjectResolver;
        this.tableCandidateService = tableCandidateService;
        this.bundleAssembler = bundleAssembler;
    }

    public PatentStructuredArtifactService(AiPersistenceProperties properties,
                                           JsonlArtifactWriter jsonlArtifactWriter,
                                           PreprocessArtifactManifestWriter manifestWriter,
                                           ObjectMapper objectMapper,
                                           PatentTableRecoveryService tableRecoveryService) {
        this(properties, jsonlArtifactWriter, manifestWriter, objectMapper, tableRecoveryService,
                new PatentPageIndexService(), new PatentSubjectResolver(),
                new PatentTableCandidateService(), new PatentEvidenceBundleAssembler(objectMapper));
    }

    public StructuredArtifactResult write(UUID runId,
                                          UUID documentId,
                                          PatentCandidate candidate,
                                          PatentTriageResult triage) {
        try {
            String canonicalKey = "patent:" + candidate.publicationNumber();
            String pdfSha256 = sha256(candidate.pdfPath());
            RagDocumentMetadata metadata = metadata(candidate, triage);
            Path storageDir = storageDir(runId, candidate.publicationNumber());
            Files.createDirectories(storageDir);

            long started = System.nanoTime();
            List<PatentPageIndexRecord> pageIndex = pageIndexService.index(candidate.pdfPath(), triage.pages());
            writeJsonl(storageDir.resolve("patent-page-index.jsonl"), pageIndex);
            PatentSubject subject = subjectResolver.resolve(candidate, triage.pages());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(storageDir.resolve("patent-subject.json").toFile(), subject);
            List<PatentTableCandidate> tableCandidates = tableCandidateService.candidates(triage, pageIndex, subject);
            writeJsonl(storageDir.resolve("patent-table-candidates.jsonl"), tableCandidates);

            List<RagChunk> chunks = chunks(documentId, canonicalKey, candidate, triage, metadata);
            Path jsonlPath = jsonlArtifactWriter.write(storageDir.resolve("document.jsonl"), chunks);
            Path pagesPath = writePageTrace(storageDir.resolve("patent-demo-pages.jsonl"), triage.pages());
            Path tablesPath = storageDir.resolve("tables.jsonl");
            tableRecoveryService.recover(candidate, triage, storageDir, pdfSha256, tableCandidates);
            List<PatentEvidenceBundle> bundles = bundleAssembler.assemble(triage, tableCandidates, storageDir);
            bundleAssembler.write(storageDir, bundles);
            writeDiscoveryAudit(storageDir, pageIndex, tableCandidates, bundles, started);

            PreprocessArtifact artifact = new PreprocessArtifact(
                    documentId,
                    storageDir.toString(),
                    candidate.pdfPath().toString(),
                    null,
                    null,
                    jsonlPath.toString(),
                    tablesPath.toString(),
                    pdfSha256,
                    canonicalKey,
                    metadata,
                    chunks.size(),
                    CHUNK_STRATEGY_VERSION,
                    PREPROCESS_VERSION
            );
            manifestWriter.write(storageDir.resolve("artifact-manifest.json"), artifact);
            PatentDiscoveryStatus discoveryStatus = discoveryStatus(bundles);
            PatentDiscoveryOutcome discoveryOutcome = discoveryOutcome(bundles);
            return new StructuredArtifactResult(
                    documentId, storageDir, chunks.size(), pdfSha256, canonicalKey, metadata,
                    discoveryStatus, discoveryOutcome, bundles.size());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write patent demo artifact", e);
        }
    }

    private void writeJsonl(Path path, List<?> values) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (Object value : values) {
            builder.append(objectMapper.writeValueAsString(value)).append('\n');
        }
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
    }

    private void writeDiscoveryAudit(Path storageDir,
                                     List<PatentPageIndexRecord> pageIndex,
                                     List<PatentTableCandidate> tableCandidates,
                                     List<PatentEvidenceBundle> bundles,
                                     long startedNanos) throws IOException {
        ObjectNode audit = objectMapper.createObjectNode();
        audit.put("kind", "PATENT_Q1_DISCOVERY");
        audit.put("strategyVersion", "v3-sidecars");
        audit.put("pageCount", pageIndex.size());
        audit.put("candidateTableCount", tableCandidates.size());
        audit.put("selectedTableCandidateCount", tableCandidates.stream()
                .filter(PatentTableCandidate::selectedForRead).count());
        audit.put("bundleCount", bundles.size());
        audit.put("discoveryStatus", discoveryStatus(bundles).name());
        audit.put("discoveryOutcome", discoveryOutcome(bundles).name());
        audit.put("elapsedMs", (System.nanoTime() - startedNanos) / 1_000_000L);
        audit.set("unresolvedCandidates", objectMapper.valueToTree(tableCandidates.stream()
                .filter(candidate -> candidate.selectedForRead()
                        && bundles.stream().noneMatch(bundle -> !bundle.tableRefs().isEmpty()))
                .toList()));
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(storageDir.resolve("patent-discovery-audit.json").toFile(), audit);
        Files.writeString(storageDir.resolve("patent-stage-metrics.jsonl"),
                objectMapper.writeValueAsString(Map.of(
                        "stage", "ARTIFACT_DISCOVERY",
                        "elapsedMs", (System.nanoTime() - startedNanos) / 1_000_000L,
                        "pageCount", pageIndex.size(),
                        "candidateTableCount", tableCandidates.size(),
                        "bundleCount", bundles.size())) + "\n",
                StandardCharsets.UTF_8);
    }

    private PatentDiscoveryStatus discoveryStatus(List<PatentEvidenceBundle> bundles) {
        if (bundles == null || bundles.isEmpty()) {
            return PatentDiscoveryStatus.NOT_APPLICABLE;
        }
        if (bundles.stream().anyMatch(bundle -> bundle.discoveryStatus() == PatentDiscoveryStatus.FAILED)) {
            return PatentDiscoveryStatus.FAILED;
        }
        if (bundles.stream().anyMatch(bundle -> bundle.discoveryStatus() == PatentDiscoveryStatus.REVIEW_REQUIRED)) {
            return PatentDiscoveryStatus.REVIEW_REQUIRED;
        }
        if (bundles.stream().anyMatch(bundle -> bundle.discoveryStatus() == PatentDiscoveryStatus.PARTIAL)) {
            return PatentDiscoveryStatus.PARTIAL;
        }
        return PatentDiscoveryStatus.COMPLETE;
    }

    private PatentDiscoveryOutcome discoveryOutcome(List<PatentEvidenceBundle> bundles) {
        if (bundles == null || bundles.isEmpty()) {
            return PatentDiscoveryOutcome.UNRESOLVED;
        }
        if (bundles.stream().anyMatch(bundle -> bundle.discoveryOutcome() == PatentDiscoveryOutcome.EVIDENCE_FOUND)) {
            return PatentDiscoveryOutcome.EVIDENCE_FOUND;
        }
        if (bundles.stream().anyMatch(bundle -> bundle.discoveryOutcome() == PatentDiscoveryOutcome.UNRESOLVED)) {
            return PatentDiscoveryOutcome.UNRESOLVED;
        }
        return PatentDiscoveryOutcome.NO_EVIDENCE_FOUND;
    }

    private List<RagChunk> chunks(UUID documentId,
                                  String canonicalKey,
                                  PatentCandidate candidate,
                                  PatentTriageResult triage,
                                  RagDocumentMetadata metadata) {
        List<RagChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (PatentPageAssessment page : triage.selectedPages()) {
            int part = 0;
            for (String text : split(page.text())) {
                String prefixed = pagePrefix(candidate.publicationNumber(), page, part) + "\n" + text;
                chunks.add(new RagChunk(
                        documentId,
                        canonicalKey,
                        null,
                        documentId + ":patent-page:" + page.pageNumber() + ":" + part,
                        chunkIndex++,
                        contentType(page.roles()),
                        sectionPath(page.roles()),
                        page.pageNumber(),
                        part,
                        part,
                        metadata.title(),
                        prefixed,
                        candidate.pdfPath().toString(),
                        "",
                        CHUNK_STRATEGY_VERSION
                ));
                part++;
            }
        }
        return chunks;
    }

    private List<String> split(String text) {
        String safe = text == null ? "" : text.strip();
        if (safe.isEmpty()) {
            return List.of("");
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < safe.length()) {
            int end = Math.min(safe.length(), start + MAX_CHUNK_TEXT_CHARS);
            parts.add(safe.substring(start, end).strip());
            start = end;
        }
        return parts;
    }

    private String pagePrefix(String publicationNumber, PatentPageAssessment page, int part) {
        return "[publication_number=" + publicationNumber + "]"
                + "[patent_page=" + page.pageNumber() + "]"
                + "[page_role=" + roleString(page.roles()) + "]"
                + "[part=" + part + "]";
    }

    private String contentType(Set<PatentPageRole> roles) {
        if (roles.contains(PatentPageRole.ACTIVITY_TABLE)) {
            return "table_body";
        }
        return "body";
    }

    private String sectionPath(Set<PatentPageRole> roles) {
        if (roles.contains(PatentPageRole.TEST_DEFINITION)) {
            return "patent/method/test-definition";
        }
        if (roles.contains(PatentPageRole.ACTIVITY_TABLE)) {
            return "patent/result/activity-table";
        }
        if (roles.contains(PatentPageRole.Q1_ACTIVITY)) {
            return "patent/result/q1-activity-candidates";
        }
        if (roles.contains(PatentPageRole.COMPOUND_MAP)) {
            return "patent/compound-map";
        }
        if (roles.contains(PatentPageRole.CLAIMS_CONTEXT)) {
            return "patent/claims-context";
        }
        if (roles.contains(PatentPageRole.ABSTRACT)) {
            return "patent/abstract";
        }
        return "patent/context";
    }

    private RagDocumentMetadata metadata(PatentCandidate candidate, PatentTriageResult triage) {
        String title = candidate.title() == null || candidate.title().isBlank()
                ? candidate.publicationNumber()
                : candidate.title();
        String abstractText = triage.selectedPages().stream()
                .filter(page -> page.roles().contains(PatentPageRole.ABSTRACT))
                .map(PatentPageAssessment::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .map(text -> truncate(text.strip(), 4_000))
                .orElse(null);
        return new RagDocumentMetadata(null, null, title, List.of(), List.of(),
                abstractText, "Patent", null, null);
    }

    private Path storageDir(UUID runId, String publicationNumber) {
        return Path.of(properties.getRag().getStorageRoot())
                .toAbsolutePath()
                .normalize()
                .resolve("patent-demo")
                .resolve(runId.toString())
                .resolve(safePathSegment(publicationNumber));
    }

    private Path writePageTrace(Path path, List<PatentPageAssessment> pages) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (PatentPageAssessment page : pages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("page_number", page.pageNumber());
            row.put("score", page.score());
            row.put("role", roleString(page.roles()));
            row.put("selected", page.selected());
            row.put("reason", page.reason());
            row.put("text_chars", page.textChars());
            builder.append(objectMapper.writeValueAsString(row)).append('\n');
        }
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private String roleString(Set<PatentPageRole> roles) {
        return roles.stream().map(Enum::name).sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private String safePathSegment(String input) {
        String safe = input == null ? "unknown" : input.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to hash PDF: " + path, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
