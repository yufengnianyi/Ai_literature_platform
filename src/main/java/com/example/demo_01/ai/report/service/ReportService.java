package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ReviewStatus;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.report.config.ReportProperties;
import com.example.demo_01.ai.report.model.ReportModels.QueryTerms;
import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import com.example.demo_01.ai.report.model.ReportModels.ReportRunRecord;
import com.example.demo_01.ai.report.model.ReportModels.ReportRunResponse;
import com.example.demo_01.ai.report.model.ReportModels.ReportStatus;
import com.example.demo_01.ai.report.repository.ReportRepository;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.service.QueryAnalyzerService;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.example.demo_01.conversation.ConversationService;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ReportService {

    private static final Pattern EVIDENCE_CITATION =
            Pattern.compile("\\[EVIDENCE:([0-9a-fA-F-]{36})]");
    private static final Pattern LITERATURE_CITATION =
            Pattern.compile("\\[LITERATURE:([0-9a-fA-F-]{36})]");
    private static final Pattern SUSPECT_NON_COMPOUND_NAME = Pattern.compile(
            "\\b(protein|gene|transcription factor|receptor|transcript|enzyme)\\b|蛋白|基因|转录因子",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PUBLICATION_NOTICE_TITLE = Pattern.compile(
            "accepted for publication|version of record|copyediting|typesetting|proofreading|"
                    + "article type\\s*:|please cite this article as",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_TEXT = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final List<String> REQUIRED_REPORT_SECTIONS = List.of(
            "范围说明与直接结论",
            "数据概览",
            "来源分类",
            "代表性发现",
            "机制与应用",
            "冲突或无效结果",
            "证据限制",
            "关键文献");
    private static final Set<String> BROAD_TERMS = Set.of(
            "报告", "综述", "总结", "分析", "证据", "表格", "化合物", "抑菌", "抗菌",
            "告诉我", "请介绍", "关于", "相关", "信息", "有哪些", "是什么", "的",
            "report", "review", "summary", "analysis", "evidence", "table",
            "compound", "compounds", "antimicrobial", "antibacterial"
    );

    @Resource
    private ReportRepository reportRepository;

    @Resource
    private EvidenceRepository evidenceRepository;

    @Resource
    private RagDocumentRepository ragDocumentRepository;

    @Resource
    private ConversationService conversationService;

    @Resource
    private QueryAnalyzerService queryAnalyzerService;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private ReportXlsxService reportXlsxService;

    @Resource
    private ReportAttachmentStorage attachmentStorage;

    @Resource
    private ReportAggregationService reportAggregationService;

    @Resource
    private ReportCompositionService reportCompositionService;

    @Resource
    private ReportProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    @Resource(name = "reportTaskExecutor")
    private TaskExecutor taskExecutor;

    public ReportRunResponse submit(String userId, String conversationId, String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question is required");
        }
        UUID reportId = UUID.randomUUID();
        ReportRunRecord run = reportRepository.submit(
                reportId, userId, conversationId, question.trim());
        try {
            taskExecutor.execute(() -> execute(reportId));
        } catch (RuntimeException e) {
            reportRepository.fail(reportId, "REPORT_QUEUE_REJECTED", e.getMessage(), 0);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "failed to queue report");
        }
        return toResponse(run);
    }

    public ReportRunResponse getOwned(UUID reportId, String userId) {
        return toResponse(ownedRecord(reportId, userId));
    }

    public List<ReportRunResponse> listByConversation(String userId, String conversationId) {
        return reportRepository.findByConversation(userId, conversationId).stream()
                .map(this::toResponse)
                .toList();
    }

    public Path attachment(UUID reportId, String userId) {
        ReportRunRecord run = ownedRecord(reportId, userId);
        if ((run.status() != ReportStatus.COMPLETED
                && run.status() != ReportStatus.PARTIAL_COMPLETED)
                || run.attachmentRelativePath() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "report attachment is not ready");
        }
        Path path = attachmentStorage.resolve(run.attachmentRelativePath());
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "report attachment not found");
        }
        return path;
    }

    void execute(UUID reportId) {
        Instant startedAt = Instant.now();
        Path outputPath = null;
        try {
            ReportRunRecord run = reportRepository.find(reportId)
                    .orElseThrow(() -> new IllegalStateException("Report run not found: " + reportId));

            reportRepository.updateProgress(
                    reportId, ReportStatus.REWRITING, null,
                    "正在理解问题并规划检索范围", 5, 0, 0, List.of());
            QueryTerms query = rewrite(run.question());

            reportRepository.updateProgress(
                    reportId, ReportStatus.MATCHING, query.rewrittenQuestion(),
                    "正在匹配结构化证据表", 10, 0, 0, List.of());
            List<CompoundEvidenceRecord> candidates = evidenceRepository.findReportableEvidence(
                    properties.getMaxEvidenceLoad());
            List<RankedEvidence> selected = rank(candidates, query.terms(), properties.getMaxEvidence());

            outputPath = attachmentStorage.createAttachmentPath(run.userId(), reportId);
            reportXlsxService.generate(outputPath, selected);

            reportRepository.updateProgress(
                    reportId, ReportStatus.SYNTHESIZING, query.rewrittenQuestion(),
                    "正在生成确定性统计报告", 80, 0, 0, List.of());
            var aggregation = reportAggregationService.aggregate(selected);
            String markdown = reportCompositionService.compose(run.question(), aggregation);
            int documentCount = aggregation.overview().documentCount();

            reportRepository.complete(
                    reportId,
                    query.rewrittenQuestion(),
                    selected,
                    attachmentStorage.attachmentFileName(),
                    attachmentStorage.relativePath(outputPath),
                    markdown,
                    false,
                    List.of(),
                    documentCount,
                    0,
                    Duration.between(startedAt, Instant.now()).toMillis());
        } catch (Exception e) {
            log.warn("Report {} failed: {}", reportId, e.getMessage(), e);
            if (outputPath != null) {
                try {
                    attachmentStorage.deleteReportAttachment(attachmentStorage.relativePath(outputPath));
                } catch (Exception cleanupError) {
                    log.warn("Failed to clean report attachment {}: {}", outputPath, cleanupError.getMessage());
                }
            }
            reportRepository.fail(
                    reportId,
                    "REPORT_GENERATION_ERROR",
                    e.getMessage(),
                    Duration.between(startedAt, Instant.now()).toMillis());
        }
    }

    QueryTerms rewrite(String question) {
        QueryAnalysis analysis;
        try {
            analysis = queryAnalyzerService.analyze(question);
        } catch (Exception e) {
            log.warn("Report question rewrite failed, using original question: {}", e.getMessage());
            analysis = new QueryAnalysis(question, List.of(question), List.of(), List.of());
        }
        String rewritten = firstNonBlank(analysis.mainQuestion(), question);
        if (isBroadOnly(question)) {
            if (containsAny(rewritten, "antibacterial", "bacterial")) {
                rewritten = "What is known about compounds active against oomycetes, including their sources, "
                        + "activity, mechanisms, and applications?";
            }
            return new QueryTerms(rewritten, List.of());
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        collectTerms(terms, rewritten);
        if (analysis.subQuestions() != null) {
            analysis.subQuestions().forEach(value -> collectTerms(terms, value));
        }
        if (analysis.keyEntities() != null) {
            analysis.keyEntities().forEach(value -> collectTerms(terms, value));
        }
        if (analysis.keyConcepts() != null) {
            analysis.keyConcepts().forEach(value -> collectTerms(terms, value));
        }
        return new QueryTerms(rewritten, List.copyOf(terms));
    }

    List<RankedEvidence> rank(List<CompoundEvidenceRecord> candidates, List<String> terms, int requestedLimit) {
        int limit = Math.max(1, requestedLimit);
        List<ScoredEvidence> scored = candidates.stream()
                .map(evidence -> new ScoredEvidence(evidence, score(evidence, terms)))
                .filter(item -> terms.isEmpty() || item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredEvidence::score).reversed()
                        .thenComparing(item -> item.evidence().evidenceId()))
                .limit(limit)
                .toList();

        Map<String, Set<String>> activitiesByGroup = new HashMap<>();
        for (ScoredEvidence item : scored) {
            String key = conflictKey(item.evidence());
            activitiesByGroup.computeIfAbsent(key, ignored -> new HashSet<>())
                    .add(normalize(item.evidence().row().activityData()));
        }

        List<RankedEvidence> result = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            ScoredEvidence item = scored.get(index);
            String conflictKey = conflictKey(item.evidence());
            String conflictGroup = activitiesByGroup.getOrDefault(conflictKey, Set.of()).size() > 1
                    ? "conflict-" + shortHash(conflictKey)
                    : null;
            result.add(new RankedEvidence(
                    item.evidence(), item.score(), index + 1, conflictGroup));
        }
        return List.copyOf(result);
    }

    private double score(CompoundEvidenceRecord evidence, List<String> terms) {
        if (terms.isEmpty()) {
            return 0;
        }
        var row = evidence.row();
        double score = 0;
        for (String term : terms) {
            score += contains(row.compoundOriginalName(), term) ? 5 : 0;
            score += contains(row.compoundStandardName(), term) ? 5 : 0;
            score += contains(row.oomyceteScientificName(), term) ? 4 : 0;
            score += contains(row.assayMethod(), term) ? 3 : 0;
            score += contains(row.targetOrMechanism(), term) ? 3 : 0;
            score += contains(row.activityData(), term) ? 2 : 0;
            score += contains(row.structureType(), term) ? 2 : 0;
            score += contains(row.sourceCategory(), term) ? 2 : 0;
            score += contains(row.sourceDescription(), term) ? 1 : 0;
            score += contains(evidence.documentTitle(), term) ? 1 : 0;
        }
        if (score > 0) {
            score += evidence.reviewStatus() == ReviewStatus.APPROVED ? 0.4 : 0.2;
            score += evidence.modelConfidence() == null ? 0 : evidence.modelConfidence() * 0.2;
        }
        return score;
    }

    List<RankedEvidence> selectRepresentativeEvidence(
            List<RankedEvidence> evidence,
            int requestedLimit) {
        int limit = Math.min(Math.max(0, requestedLimit), evidence.size());
        if (limit == 0) {
            return List.of();
        }

        List<RankedEvidence> candidates = evidence.stream()
                .filter(item -> !isSuspectedNonCompound(item.evidence()))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<RankedEvidence> selected = new ArrayList<>();
        Set<UUID> selectedIds = new HashSet<>();
        Set<String> sources = new HashSet<>();
        Set<String> compounds = new HashSet<>();
        Set<String> documents = new HashSet<>();
        Set<String> organisms = new HashSet<>();
        Map<String, Integer> compoundCounts = new HashMap<>();

        while (selected.size() < limit) {
            RankedEvidence best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (RankedEvidence item : candidates) {
                if (selectedIds.contains(item.evidence().evidenceId())) {
                    continue;
                }
                String compound = compoundKey(item.evidence());
                if (compoundCounts.getOrDefault(compound, 0) >= 3) {
                    continue;
                }
                double score = representativeScore(
                        item, sources, compounds, documents, organisms);
                if (best == null
                        || score > bestScore
                        || (score == bestScore
                        && item.evidence().evidenceId().compareTo(best.evidence().evidenceId()) < 0)) {
                    best = item;
                    bestScore = score;
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best);
            selectedIds.add(best.evidence().evidenceId());
            var row = best.evidence().row();
            String compound = compoundKey(best.evidence());
            sources.add(normalize(row.sourceCategory()));
            compounds.add(compound);
            documents.add(best.evidence().documentId().toString());
            organisms.add(normalize(row.oomyceteScientificName()));
            compoundCounts.merge(compound, 1, Integer::sum);
        }

        if (selected.size() < limit) {
            candidates.stream()
                    .filter(item -> !selectedIds.contains(item.evidence().evidenceId()))
                    .sorted(Comparator.comparingInt(RankedEvidence::rank))
                    .limit(limit - selected.size())
                    .forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private double representativeScore(
            RankedEvidence item,
            Set<String> sources,
            Set<String> compounds,
            Set<String> documents,
            Set<String> organisms) {
        CompoundEvidenceRecord evidence = item.evidence();
        var row = evidence.row();
        String compound = compoundKey(evidence);
        double score = Math.min(5, item.matchScore());
        score += evidence.reviewStatus() == ReviewStatus.APPROVED ? 8 : 1;
        score += item.conflictGroup() == null ? 0 : 6;
        score += isInactiveResult(row.activityData()) ? 5 : 0;
        score += hasText(row.targetOrMechanism()) ? 4 : 0;
        score += isApplicationEvidence(row.assayMethod()) ? 3 : 0;
        score += evidence.modelConfidence() == null ? 0 : evidence.modelConfidence();
        score += sources.contains(normalize(row.sourceCategory())) ? 0 : 4;
        score += compounds.contains(compound) ? 0 : 8;
        score += documents.contains(evidence.documentId().toString()) ? 0 : 3;
        score += organisms.contains(normalize(row.oomyceteScientificName())) ? 0 : 3;
        return score;
    }

    ReportOverview buildOverview(List<RankedEvidence> evidence) {
        Set<String> compounds = new HashSet<>();
        Set<UUID> documents = new HashSet<>();
        Set<String> organisms = new HashSet<>();
        Map<String, Integer> sourceCounts = new HashMap<>();
        Map<String, Integer> assayCounts = new HashMap<>();
        int pending = 0;
        int approved = 0;
        int suspectedNonCompounds = 0;

        for (RankedEvidence item : evidence) {
            CompoundEvidenceRecord record = item.evidence();
            var row = record.row();
            addNonBlank(compounds, firstNonBlank(
                    row.compoundStandardName(), row.compoundOriginalName(), ""));
            documents.add(record.documentId());
            addNonBlank(organisms, row.oomyceteScientificName());
            increment(sourceCounts, firstNonBlank(row.sourceCategory(), "未注明来源"));
            increment(assayCounts, firstNonBlank(row.assayMethod(), "未注明实验方法"));
            pending += record.reviewStatus() == ReviewStatus.PENDING ? 1 : 0;
            approved += record.reviewStatus() == ReviewStatus.APPROVED ? 1 : 0;
            suspectedNonCompounds += isSuspectedNonCompound(record) ? 1 : 0;
        }

        return new ReportOverview(
                evidence.size(),
                compounds.size(),
                documents.size(),
                organisms.size(),
                pending,
                approved,
                suspectedNonCompounds,
                sortedCounts(sourceCounts),
                sortedCounts(assayCounts));
    }

    private String generateAnswer(
            String question,
            String rewrittenQuestion,
            List<RankedEvidence> allEvidence,
            List<RankedEvidence> representativeEvidence,
            ReportOverview overview,
            List<LiteratureContext> literature,
            List<ChatMessage> conversationContext) {
        if (allEvidence.isEmpty()) {
            return """
                    ## 范围说明与直接结论

                    当前内部化合物证据表中没有检索到与该问题匹配的证据，因此无法形成有依据的结论。

                    已生成仅包含表头的 XLSX 附件。本回答未使用外部知识补充缺失事实。
                    """;
        }

        String userInput;
        try {
            userInput = """
                    用户原始问题：
                    %s

                    用于检索的改写问题：
                    %s

                    当前证据库范围说明：
                    %s

                    先前会话上下文（只用于理解追问，不作为事实来源）：
                    %s

                    完整命中证据的确定性统计：
                    %s

                    从完整证据中均衡选择的代表性结构化证据：
                    %s

                    命中证据关联的内部文献上下文：
                    %s
                    """.formatted(
                    question,
                    rewrittenQuestion,
                    scopeNotice(question),
                    conversationContextJson(conversationContext),
                    objectMapper.writeValueAsString(overview),
                    evidenceJson(representativeEvidence),
                    literatureJson(literature));
        } catch (JsonProcessingException e) {
            log.warn("Report model input serialization failed; using deterministic synthesis: {}",
                    e.getMessage());
            return fallbackAnswer(question, allEvidence, representativeEvidence, overview, literature);
        }

        String answer;
        try {
            answer = modelText(chatClient.chatStandard(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REPORT_EVIDENCE_SYSTEM)),
                    UserMessage.from(userInput)
            ));
        } catch (Exception e) {
            log.warn("Report model invocation failed; using deterministic synthesis: {}", e.getMessage());
            return fallbackAnswer(question, allEvidence, representativeEvidence, overview, literature);
        }
        try {
            validateAnswerShape(answer);
        } catch (IllegalStateException e) {
            log.warn("Report answer contract validation failed; "
                    + "using deterministic synthesis: {}", e.getMessage());
            return fallbackAnswer(question, allEvidence, representativeEvidence, overview, literature);
        }

        try {
            validateCitations(answer, representativeEvidence, literature);
            return answer;
        } catch (IllegalStateException citationError) {
            log.warn("Report citation validation failed; attempting citation repair: {}",
                    citationError.getMessage());
            try {
                String repaired = repairCitations(answer, representativeEvidence, literature);
                validateCitations(repaired, representativeEvidence, literature);
                return repaired;
            } catch (Exception repairError) {
                log.warn("Report citation repair failed; using deterministic synthesis: {}",
                        repairError.getMessage());
                return fallbackAnswer(
                        question, allEvidence, representativeEvidence, overview, literature);
            }
        }
    }

    private String repairCitations(
            String draft,
            List<RankedEvidence> evidence,
            List<LiteratureContext> literature) throws JsonProcessingException {
        String input = """
                待修复报告：
                %s

                允许引用的结构化证据：
                %s

                允许引用的内部文献：
                %s
                """.formatted(draft, evidenceJson(evidence), literatureJson(literature));
        String repaired = modelText(chatClient.chatStandard(
                SystemMessage.from(PromptResources.load(
                        PromptCatalog.REPORT_EVIDENCE_CITATION_REPAIR_SYSTEM)),
                UserMessage.from(input)));
        if (!citationFreeText(draft).equals(citationFreeText(repaired))) {
            throw new IllegalStateException("Citation repair changed non-citation report content");
        }
        return repaired;
    }

    private String modelText(dev.langchain4j.model.chat.response.ChatResponse response) {
        if (response == null || response.aiMessage() == null
                || response.aiMessage().text() == null
                || response.aiMessage().text().isBlank()) {
            throw new IllegalStateException("Report model returned an empty answer");
        }
        return response.aiMessage().text().trim();
    }

    private void validateAnswerShape(String answer) {
        if (!CHINESE_TEXT.matcher(answer).find()) {
            throw new IllegalStateException("Report answer is not Chinese");
        }
        for (String section : REQUIRED_REPORT_SECTIONS) {
            if (!answer.contains(section)) {
                throw new IllegalStateException("Report answer is missing section: " + section);
            }
        }
    }

    private String citationFreeText(String answer) {
        return LITERATURE_CITATION.matcher(EVIDENCE_CITATION.matcher(answer).replaceAll(""))
                .replaceAll("")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String evidenceJson(List<RankedEvidence> evidence) throws JsonProcessingException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RankedEvidence item : evidence) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("evidenceId", item.evidence().evidenceId());
            row.put("documentTitle", item.evidence().documentTitle());
            row.put("fields", item.evidence().row());
            row.put("reviewStatus", item.evidence().reviewStatus());
            row.put("confidence", item.evidence().modelConfidence());
            row.put("conflictGroup", item.conflictGroup());
            rows.add(row);
        }
        return objectMapper.writeValueAsString(rows);
    }

    private String conversationContextJson(List<ChatMessage> messages) throws JsonProcessingException {
        List<Map<String, String>> rows = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage && userMessage.hasSingleText()) {
                rows.add(Map.of("role", "user", "content", userMessage.singleText()));
            } else if (message instanceof AiMessage aiMessage
                    && aiMessage.text() != null && !aiMessage.text().isBlank()) {
                rows.add(Map.of("role", "assistant", "content", aiMessage.text()));
            }
        }
        return objectMapper.writeValueAsString(rows);
    }

    private String literatureJson(List<LiteratureContext> literature) throws JsonProcessingException {
        return objectMapper.writeValueAsString(literature);
    }

    List<LiteratureContext> buildLiteratureContexts(
            List<RankedEvidence> evidence,
            List<String> terms) {
        LinkedHashSet<UUID> documentIds = new LinkedHashSet<>();
        int documentLimit = Math.max(0, properties.getMaxLiteratureDocuments());
        if (documentLimit == 0) {
            return List.of();
        }
        for (RankedEvidence item : evidence) {
            documentIds.add(item.evidence().documentId());
            if (documentIds.size() >= documentLimit) {
                break;
            }
        }

        List<RagDocumentRecord> documents = documentIds.stream()
                .map(ragDocumentRepository::findById)
                .flatMap(java.util.Optional::stream)
                .toList();
        int remainingChars = Math.max(0, properties.getMaxLiteratureContextChars());
        List<LiteratureContext> contexts = new ArrayList<>();
        for (int index = 0; index < documents.size() && remainingChars > 0; index++) {
            RagDocumentRecord document = documents.get(index);
            int documentsLeft = documents.size() - index;
            int documentBudget = Math.max(1, remainingChars / documentsLeft);
            int metadataBudget = Math.min(3000, Math.max(1, documentBudget / 2));
            int metadataRemaining = metadataBudget;
            String title = clip(displayDocumentTitle(document), Math.min(1000, metadataRemaining));
            metadataRemaining -= title.length();
            String journal = clip(document.journal(), Math.min(500, metadataRemaining));
            metadataRemaining -= journal.length();
            String doi = clip(document.doiNormalized(), Math.min(200, metadataRemaining));
            metadataRemaining -= doi.length();
            List<String> authors = new ArrayList<>();
            if (document.authors() != null) {
                for (String author : document.authors().stream().limit(20).toList()) {
                    String clippedAuthor = clip(author, Math.min(200, metadataRemaining));
                    if (!clippedAuthor.isBlank()) {
                        authors.add(clippedAuthor);
                        metadataRemaining -= clippedAuthor.length();
                    }
                    if (metadataRemaining == 0) {
                        break;
                    }
                }
            }
            int metadataChars = metadataBudget - metadataRemaining;
            int contentBudget = Math.max(0, documentBudget - metadataChars);
            int abstractBudget = Math.min(2000, contentBudget / 3);
            String abstractText = clip(document.abstractText(), abstractBudget);
            int used = metadataChars + abstractText.length();
            int chunkBudget = Math.max(0, documentBudget - used);

            List<EvidenceChunk> selectedChunks = loadLiteratureChunks(document.documentId()).stream()
                    .sorted(Comparator.comparingDouble(
                            (EvidenceChunk chunk) -> chunkScore(chunk, terms)).reversed())
                    .limit(Math.max(0L, properties.getMaxLiteratureChunksPerDocument()))
                    .toList();
            List<LiteratureChunk> chunks = new ArrayList<>();
            int perChunkBudget = selectedChunks.isEmpty() ? 0 : Math.max(1, chunkBudget / selectedChunks.size());
            for (EvidenceChunk chunk : selectedChunks) {
                String text = clip(chunk.text(), perChunkBudget);
                if (!text.isBlank()) {
                    chunks.add(new LiteratureChunk(chunk.chunkId(), chunk.sectionPath(), text));
                    used += text.length();
                }
            }

            contexts.add(new LiteratureContext(
                    document.documentId(),
                    title,
                    authors,
                    journal,
                    document.publicationYear(),
                    doi,
                    abstractText,
                    List.copyOf(chunks)));
            remainingChars -= Math.min(remainingChars, used);
        }
        return List.copyOf(contexts);
    }

    private List<EvidenceChunk> loadLiteratureChunks(UUID documentId) {
        try {
            return evidenceRepository.findDocumentChunks(documentId);
        } catch (RuntimeException e) {
            log.warn("Failed to load literature chunks for document {}: {}", documentId, e.getMessage());
            return List.of();
        }
    }

    private double chunkScore(EvidenceChunk chunk, List<String> terms) {
        double score = sectionPriority(chunk.sectionPath());
        for (String term : terms) {
            score += contains(chunk.text(), term) ? 3 : 0;
            score += contains(chunk.sectionPath(), term) ? 1 : 0;
        }
        return score;
    }

    private double sectionPriority(String sectionPath) {
        String section = normalize(sectionPath);
        if (section.contains("result") || section.contains("结果")) return 5;
        if (section.contains("discussion") || section.contains("讨论")) return 4;
        if (section.contains("conclusion") || section.contains("结论")) return 3;
        if (section.contains("abstract") || section.contains("摘要")) return 2;
        if (section.contains("method") || section.contains("方法")) return 1;
        return 0;
    }

    private String clip(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars <= 0) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private String displayDocumentTitle(RagDocumentRecord document) {
        String title = firstNonBlank(document.title(), "");
        if (!title.isBlank() && !PUBLICATION_NOTICE_TITLE.matcher(title).find()) {
            return title;
        }
        String sourceFilename = firstNonBlank(document.sourceFilename(), "");
        if (!sourceFilename.isBlank()) {
            return sourceFilename.replaceFirst("(?i)\\.pdf$", "");
        }
        if (document.doiNormalized() != null && !document.doiNormalized().isBlank()) {
            return "DOI " + document.doiNormalized();
        }
        return "未命名内部文献";
    }

    private String fallbackAnswer(
            String question,
            List<RankedEvidence> allEvidence,
            List<RankedEvidence> representativeEvidence,
            ReportOverview overview,
            List<LiteratureContext> literature) {
        List<RankedEvidence> visibleEvidence = representativeEvidence.isEmpty()
                ? allEvidence.stream()
                .filter(item -> !isSuspectedNonCompound(item.evidence()))
                .limit(Math.min(20, allEvidence.size()))
                .toList()
                : representativeEvidence;
        StringBuilder answer = new StringBuilder()
                .append("## 范围说明与直接结论\n\n")
                .append(scopeNotice(question)).append("\n\n")
                .append("针对“").append(question).append("”，内部证据表共命中 ")
                .append(overview.evidenceCount()).append(" 条记录，覆盖 ")
                .append(overview.compoundCount()).append(" 个化合物名称、")
                .append(overview.documentCount()).append(" 篇内部文献和 ")
                .append(overview.organismCount()).append(" 个实验对象。");
        if (overview.allPending()) {
            answer.append("当前全部证据均为机器抽取、待审核，以下内容应作为文献筛选和研究导航，")
                    .append("不应直接替代人工核验。");
        } else if (overview.pendingCount() > 0) {
            answer.append("其中 ").append(overview.pendingCount())
                    .append(" 条为机器抽取、待审核。");
        }
        answer.append("完整记录及原始数值见 XLSX 附件。\n\n")
                .append("## 数据概览\n\n")
                .append("- 结构化证据：").append(overview.evidenceCount()).append(" 条\n")
                .append("- 化合物名称：").append(overview.compoundCount()).append(" 个\n")
                .append("- 关联文献：").append(overview.documentCount()).append(" 篇\n")
                .append("- 实验对象：").append(overview.organismCount()).append(" 个\n")
                .append("- 审核状态：已审核 ").append(overview.approvedCount())
                .append(" 条，待审核 ").append(overview.pendingCount()).append(" 条\n");
        if (overview.suspectedNonCompoundCount() > 0) {
            answer.append("- 数据质量提示：").append(overview.suspectedNonCompoundCount())
                    .append(" 条记录疑似为基因或蛋白名称，未纳入正文代表性结论\n");
        }

        answer.append("\n## 来源分类\n\n");
        if (overview.sourceCounts().isEmpty()) {
            answer.append("现有记录未提供可归纳的来源类别。\n");
        } else {
            overview.sourceCounts().entrySet().stream().limit(6).forEach(entry -> {
                answer.append("- **").append(entry.getKey()).append("**：")
                        .append(entry.getValue()).append(" 条记录");
                findBySource(visibleEvidence, entry.getKey()).ifPresent(item ->
                        answer.append("；代表性记录为 ").append(compoundName(item.evidence()))
                                .append(" [EVIDENCE:")
                                .append(item.evidence().evidenceId()).append("]"));
                answer.append("\n");
            });
        }

        answer.append("\n## 代表性发现\n\n");
        List<RankedEvidence> active = visibleEvidence.stream()
                .filter(item -> !isInactiveResult(item.evidence().row().activityData()))
                .limit(6)
                .toList();
        appendEvidenceFindings(answer, active, "现有代表性记录中没有可直接概括的阳性活性结果。");

        answer.append("\n## 机制与应用\n\n");
        List<RankedEvidence> mechanismOrApplication = visibleEvidence.stream()
                .filter(item -> hasText(item.evidence().row().targetOrMechanism())
                        || isApplicationEvidence(item.evidence().row().assayMethod()))
                .limit(5)
                .toList();
        if (mechanismOrApplication.isEmpty()) {
            answer.append("当前代表性证据对作用机制或离体、温室、田间应用阶段的描述不足。\n");
        } else {
            mechanismOrApplication.forEach(item -> {
                var row = item.evidence().row();
                answer.append("- **").append(compoundName(item.evidence())).append("**：")
                        .append(firstNonBlank(row.targetOrMechanism(), row.assayMethod(), "未注明机制或应用方法"))
                        .append("；").append(firstNonBlank(row.activityData(), "未注明活性数据"))
                        .append(" [EVIDENCE:").append(item.evidence().evidenceId()).append("]\n");
            });
        }

        answer.append("\n## 冲突或无效结果\n\n");
        List<RankedEvidence> conflictingOrInactive = visibleEvidence.stream()
                .filter(item -> item.conflictGroup() != null
                        || isInactiveResult(item.evidence().row().activityData()))
                .limit(5)
                .toList();
        appendEvidenceFindings(
                answer,
                conflictingOrInactive,
                "当前代表性证据中未识别到明确的冲突组或无活性结果；这不代表完整证据表中不存在差异。");

        answer.append("\n## 证据限制\n\n")
                .append("- 正文仅展示经过均衡选择的代表性记录，完整命中结果保留在 XLSX 中。\n")
                .append("- 不同实验方法、浓度、对象和评价指标不能直接横向换算。\n")
                .append("- 本报告未使用外部知识补充证据表和内部文献之外的事实。\n");
        if (!literature.isEmpty()) {
            answer.append("\n## 关键文献\n\n");
            literature.stream().limit(5).forEach(item -> answer.append("- ")
                    .append(firstNonBlank(item.title(), "未命名文献"))
                    .append(" [LITERATURE:").append(item.documentId()).append("]\n"));
        } else {
            answer.append("\n## 关键文献\n\n当前命中记录缺少可用的内部文献元数据。\n");
        }
        return answer.toString();
    }

    private void appendEvidenceFindings(
            StringBuilder answer,
            List<RankedEvidence> evidence,
            String emptyMessage) {
        if (evidence.isEmpty()) {
            answer.append(emptyMessage).append("\n");
            return;
        }
        evidence.forEach(item -> {
            var row = item.evidence().row();
            answer.append("- **").append(compoundName(item.evidence())).append("** 对 ")
                    .append(firstNonBlank(row.oomyceteScientificName(), "未注明测试对象"))
                    .append(" 的记录采用 ")
                    .append(firstNonBlank(row.assayMethod(), "未注明实验方法"))
                    .append("，结果为 ")
                    .append(firstNonBlank(row.activityData(), "未注明活性数据"))
                    .append(" [EVIDENCE:").append(item.evidence().evidenceId()).append("]\n");
        });
    }

    void validateCitations(
            String answer,
            List<RankedEvidence> evidence,
            List<LiteratureContext> literature) {
        Set<UUID> allowedEvidence = evidence.stream()
                .map(item -> item.evidence().evidenceId())
                .collect(java.util.stream.Collectors.toSet());
        Matcher evidenceMatcher = EVIDENCE_CITATION.matcher(answer);
        boolean evidenceFound = false;
        while (evidenceMatcher.find()) {
            evidenceFound = true;
            UUID cited = UUID.fromString(evidenceMatcher.group(1));
            if (!allowedEvidence.contains(cited)) {
                throw new IllegalStateException("Report cited evidence outside the selected table");
            }
        }
        if (!evidenceFound) {
            throw new IllegalStateException("Report answer contains no evidence citations");
        }

        Set<UUID> allowedLiterature = literature.stream()
                .map(LiteratureContext::documentId)
                .collect(java.util.stream.Collectors.toSet());
        Matcher literatureMatcher = LITERATURE_CITATION.matcher(answer);
        boolean literatureFound = false;
        while (literatureMatcher.find()) {
            literatureFound = true;
            UUID cited = UUID.fromString(literatureMatcher.group(1));
            if (!allowedLiterature.contains(cited)) {
                throw new IllegalStateException("Report cited literature outside the selected documents");
            }
        }
        if (!allowedLiterature.isEmpty() && !literatureFound) {
            throw new IllegalStateException("Report answer contains no literature citations");
        }
    }

    private String scopeNotice(String question) {
        String normalized = normalize(question);
        if (containsAny(normalized, "antibacterial", "anti-bacterial", "抗细菌", "细菌")) {
            return "当前内部证据库主要覆盖卵菌及相关真菌样病原体，并不能代表完整的抗细菌研究；"
                    + "因此以下内容仅回答本证据库能够支持的抗卵菌化合物信息。";
        }
        if (containsAny(normalized, "抑菌", "抗菌", "antimicrobial")) {
            return "“抑菌/抗菌”可能包含不同微生物范围；当前内部证据主要覆盖卵菌及相关真菌样病原体，"
                    + "以下结论不外推为完整的抗细菌研究。";
        }
        return "当前报告仅综合内部证据表所覆盖的卵菌及相关真菌样病原体研究，"
                + "不使用外部知识扩展到其他微生物范围。";
    }

    private boolean isSuspectedNonCompound(CompoundEvidenceRecord evidence) {
        var row = evidence.row();
        String name = firstNonBlank(row.compoundStandardName(), row.compoundOriginalName(), "");
        boolean nameLooksBiological = hasText(name)
                && SUSPECT_NON_COMPOUND_NAME.matcher(name).find();
        boolean structureConfirmsBiologicalMacromolecule = containsAny(
                row.structureType(),
                "protein",
                "gene",
                "transcription factor",
                "enzyme",
                "蛋白",
                "基因",
                "转录因子",
                "酶");
        boolean missingCompoundMetadata = !hasText(row.structureType()) && !hasText(row.sourceCategory());
        return nameLooksBiological
                && (structureConfirmsBiologicalMacromolecule || missingCompoundMetadata);
    }

    private boolean isInactiveResult(String activity) {
        String normalized = normalize(activity);
        return containsAny(
                normalized,
                "无抑制",
                "无活性",
                "未见活性",
                "no inhibition",
                "no activity",
                "inactive",
                "not active",
                "ei% ≈ 0",
                "ei%=0");
    }

    private boolean isApplicationEvidence(String assayMethod) {
        String normalized = normalize(assayMethod);
        return containsAny(
                normalized,
                "leaf",
                "叶片",
                "温室",
                "田间",
                "field",
                "in vivo",
                "plant",
                "植株");
    }

    private boolean containsAny(String value, String... candidates) {
        String normalized = normalize(value);
        for (String candidate : candidates) {
            if (normalized.contains(normalize(candidate))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String compoundName(CompoundEvidenceRecord evidence) {
        return firstNonBlank(
                evidence.row().compoundStandardName(),
                evidence.row().compoundOriginalName(),
                "未命名化合物");
    }

    private String compoundKey(CompoundEvidenceRecord evidence) {
        return normalize(compoundName(evidence));
    }

    private Optional<RankedEvidence> findBySource(
            List<RankedEvidence> evidence,
            String source) {
        return evidence.stream()
                .filter(item -> source.equals(firstNonBlank(
                        item.evidence().row().sourceCategory(), "未注明来源")))
                .findFirst();
    }

    private void addNonBlank(Set<String> values, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            values.add(normalized);
        }
    }

    private void increment(Map<String, Integer> counts, String value) {
        counts.merge(value, 1, Integer::sum);
    }

    private Map<String, Integer> sortedCounts(Map<String, Integer> counts) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return java.util.Collections.unmodifiableMap(sorted);
    }

    private void collectTerms(Set<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = normalize(value);
        for (String broad : BROAD_TERMS) {
            normalized = normalized.replace(broad, " ");
        }
        for (String term : normalized.split("[\\s\\p{Punct}，。；：？！、（）【】]+")) {
            String candidate = term.trim();
            if (candidate.length() >= 2 && !BROAD_TERMS.contains(candidate)) {
                terms.add(candidate);
            }
        }
    }

    private boolean isBroadOnly(String question) {
        if (question == null || question.isBlank()) {
            return true;
        }
        String remaining = normalize(question);
        for (String broad : BROAD_TERMS) {
            remaining = remaining.replace(broad, " ");
        }
        remaining = remaining.replaceAll("[\\s\\p{Punct}，。；：？！、（）【】]+", "");
        return remaining.isBlank();
    }

    private boolean contains(String value, String term) {
        return value != null && normalize(value).contains(term);
    }

    private String conflictKey(CompoundEvidenceRecord evidence) {
        var row = evidence.row();
        return normalize(firstNonBlank(row.compoundStandardName(), row.compoundOriginalName(), ""))
                + "|" + normalize(row.oomyceteScientificName())
                + "|" + normalize(row.assayMethod());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private ReportRunRecord ownedRecord(UUID reportId, String userId) {
        return reportRepository.findOwned(reportId, userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "report not found"));
    }

    private ReportRunResponse toResponse(ReportRunRecord run) {
        return new ReportRunResponse(
                run.reportId(),
                run.conversationId(),
                run.question(),
                run.rewrittenQuestion(),
                run.status(),
                run.evidenceCount(),
                run.attachmentFileName(),
                run.status().terminal()
                        && run.status() != ReportStatus.FAILED
                        && run.attachmentRelativePath() != null,
                run.answerMarkdown(),
                run.errorMessage(),
                run.phaseMessage(),
                run.progressPercent(),
                run.selectedDocumentCount(),
                run.analyzedDocumentCount(),
                run.warnings(),
                run.createdAt(),
                run.updatedAt(),
                run.finishedAt()
        );
    }

    private record ScoredEvidence(CompoundEvidenceRecord evidence, double score) {
    }

    record ReportOverview(
            int evidenceCount,
            int compoundCount,
            int documentCount,
            int organismCount,
            int pendingCount,
            int approvedCount,
            int suspectedNonCompoundCount,
            Map<String, Integer> sourceCounts,
            Map<String, Integer> assayCounts) {

        boolean allPending() {
            return evidenceCount > 0 && pendingCount == evidenceCount;
        }
    }

    record LiteratureChunk(String chunkId, String sectionPath, String text) {
    }

    record LiteratureContext(
            UUID documentId,
            String title,
            List<String> authors,
            String journal,
            Integer publicationYear,
            String doi,
            String abstractText,
            List<LiteratureChunk> chunks) {
    }
}
