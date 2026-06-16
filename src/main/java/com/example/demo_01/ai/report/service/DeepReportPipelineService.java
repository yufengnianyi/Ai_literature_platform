package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ReviewStatus;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.report.config.ReportProperties;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureAnalysisStatus;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureClaim;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureProfile;
import com.example.demo_01.ai.report.model.ReportModels.LiteratureSourceType;
import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import com.example.demo_01.ai.report.model.ReportModels.ReportClaimDraft;
import com.example.demo_01.ai.report.model.ReportModels.ReportStatus;
import com.example.demo_01.ai.report.model.ReportModels.SectionEvidenceMatrix;
import com.example.demo_01.ai.report.model.ReportModels.SelectedLiterature;
import com.example.demo_01.ai.report.repository.ReportLiteratureRepository;
import com.example.demo_01.ai.report.repository.ReportRepository;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentStatus;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.review.service.ReviewReasoningChatClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DeepReportPipelineService {

    private static final Pattern LITERATURE_ALIAS = Pattern.compile("\\[文献(\\d+)]");
    private static final Pattern LITERATURE_BRACKET = Pattern.compile("\\[[^\\]]*文献[^\\]]*]");
    private static final Pattern RAW_CITATION_TOKEN = Pattern.compile("(?i)\\[(?:EVIDENCE|LITERATURE):");
    private static final Pattern UUID_CITATION = Pattern.compile(
            "(?i)\\[(?:文献|EVIDENCE|LITERATURE)[:：]?\\s*"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\s*]");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。；！？\\n])");
    private static final Pattern NUMERIC_WITH_UNIT = Pattern.compile(
            "(?i)(?:\\d+(?:\\.\\d+)?(?:\\s*±\\s*\\d+(?:\\.\\d+)?)?\\s*)"
                    + "(?:%|μg/mL|ug/mL|mg/L|g/L|ng/mL|μM|uM|mM|mol/L|mmol/g|g/ha|mg/kg)");
    private static final Pattern ACTIVITY_METRIC = Pattern.compile(
            "(?i)\\b(EC50|IC50|MIC|MFC)\\b|抑制率|防效");
    private static final Pattern ACTIVITY_UNIT = Pattern.compile(
            "(?i)(μg/mL|ug/mL|mg/L|g/L|ng/mL|μM|uM|mM|mol/L|mmol/g|g/ha|mg/kg|%)");
    private static final List<SectionSpec> SECTIONS = List.of(
            new SectionSpec("scope", "范围与直接结论", 350, 550),
            new SectionSpec("background", "研究背景", 300, 500),
            new SectionSpec("classes", "常见化合物类别", 450, 700),
            new SectionSpec("activity", "活性强弱分析", 450, 750),
            new SectionSpec("mechanisms", "作用机制总结", 350, 600),
            new SectionSpec("applications", "应用潜力", 350, 600),
            new SectionSpec("conflicts", "文献间一致性与冲突", 300, 550),
            new SectionSpec("limitations", "证据限制", 250, 450)
    );

    @Resource
    private ReportRepository reportRepository;

    @Resource
    private ReportLiteratureRepository literatureRepository;

    @Resource
    private RagDocumentRepository ragDocumentRepository;

    @Resource
    private ReportLiteratureRetrievalService retrievalService;

    @Resource
    private ReportFullDocumentAnalysisService fullDocumentAnalysisService;

    @Resource
    private ReviewReasoningChatClient chatClient;

    @Resource
    private ReportProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    public DeepReportResult generate(UUID reportId,
                                     String question,
                                     String rewrittenQuestion,
                                     List<RankedEvidence> allEvidence,
                                     List<RankedEvidence> representativeEvidence,
                                     ReportService.ReportOverview overview) {
        List<String> warnings = new ArrayList<>();

        reportRepository.updateProgress(
                reportId, ReportStatus.PLANNING, rewrittenQuestion,
                "正在规划报告章节", 15, 0, 0, warnings);
        SectionEvidenceMatrix matrix = buildEvidenceMatrix(question, rewrittenQuestion, allEvidence);

        reportRepository.updateProgress(
                reportId, ReportStatus.ANALYZING_EVIDENCE, rewrittenQuestion,
                "正在按类别、活性、机制和应用分析证据表", 25, 0, 0, warnings);
        List<CommonCompound> commonCompounds = commonCompounds(allEvidence);
        List<SelectedLiterature> direct = selectDirectLiterature(allEvidence);

        reportRepository.updateProgress(
                reportId, ReportStatus.RETRIEVING_LITERATURE, rewrittenQuestion,
                "正在检索全文文献以补充证据缺口", 35, direct.size(), 0, warnings);
        List<SelectedLiterature> supplemental = selectSupplementalLiterature(
                matrix, direct, warnings);
        List<SelectedLiterature> selectedDocuments = combineAndRank(direct, supplemental);
        literatureRepository.replaceLiterature(reportId, selectedDocuments);

        reportRepository.updateProgress(
                reportId, ReportStatus.ANALYZING_LITERATURE, rewrittenQuestion,
                "正在逐篇分析核心文献的全部全文 chunks", 45,
                selectedDocuments.size(), 0, warnings);
        AnalysisResult analyses = analyzeDocuments(reportId, selectedDocuments, warnings);

        reportRepository.updateProgress(
                reportId, ReportStatus.SYNTHESIZING, rewrittenQuestion,
                "正在进行跨文献综合并分章节撰写", 82,
                selectedDocuments.size(), analyses.profiles().size(), warnings);
        SynthesisResult synthesis = synthesize(
                question, rewrittenQuestion, overview, representativeEvidence,
                commonCompounds, matrix, selectedDocuments, analyses.profiles(), warnings);

        reportRepository.updateProgress(
                reportId, ReportStatus.VALIDATING, rewrittenQuestion,
                "正在校验引用、数值和结论来源", 95,
                selectedDocuments.size(), analyses.profiles().size(), warnings);
        literatureRepository.replaceClaims(reportId, synthesis.claims());

        boolean partial = !warnings.isEmpty();
        return new DeepReportResult(
                synthesis.markdown(),
                List.copyOf(warnings),
                selectedDocuments.size(),
                analyses.profiles().size(),
                partial);
    }

    SectionEvidenceMatrix buildEvidenceMatrix(String question,
                                              String rewrittenQuestion,
                                              List<RankedEvidence> evidence) {
        Map<String, Integer> coverage = new LinkedHashMap<>();
        coverage.put("background", 0);
        coverage.put("classes", count(evidence, item ->
                hasText(item.evidence().row().sourceCategory())
                        || hasText(item.evidence().row().structureType())));
        coverage.put("activity", count(evidence, item ->
                hasText(item.evidence().row().activityData())));
        coverage.put("mechanisms", count(evidence, item ->
                hasText(item.evidence().row().targetOrMechanism())));
        coverage.put("applications", count(evidence, item ->
                isApplication(item.evidence().row().assayMethod())));
        coverage.put("safety", count(evidence, item ->
                hasText(item.evidence().row().cytotoxicity())
                        || hasText(item.evidence().row().resistanceCrossResistance())));
        coverage.put("conflicts", count(evidence, item -> item.conflictGroup() != null));

        List<String> missing = coverage.entrySet().stream()
                .filter(entry -> entry.getValue() == 0
                        || (entry.getKey().equals("mechanisms") && entry.getValue() < 3)
                        || (entry.getKey().equals("applications") && entry.getValue() < 3))
                .map(Map.Entry::getKey)
                .toList();
        return new SectionEvidenceMatrix(
                Map.copyOf(coverage),
                missing,
                buildRetrievalQueries(question, rewrittenQuestion, evidence, missing, 1));
    }

    List<CommonCompound> commonCompounds(List<RankedEvidence> evidence) {
        Map<String, MutableCommonCompound> grouped = new HashMap<>();
        for (RankedEvidence item : evidence) {
            CompoundEvidenceRecord record = item.evidence();
            String name = compoundName(record);
            if (name.isBlank()) {
                continue;
            }
            MutableCommonCompound compound = grouped.computeIfAbsent(
                    normalize(name), ignored -> new MutableCommonCompound(name));
            compound.documentIds.add(record.documentId());
            compound.evidenceCount++;
            compound.organisms.add(normalize(record.row().oomyceteScientificName()));
            if (record.reviewStatus() == ReviewStatus.APPROVED) {
                compound.approvedCount++;
            }
        }
        return grouped.values().stream()
                .map(MutableCommonCompound::freeze)
                .sorted(Comparator.comparingInt(CommonCompound::documentCount).reversed()
                        .thenComparing(Comparator.comparingInt(CommonCompound::approvedCount).reversed())
                        .thenComparing(Comparator.comparingInt(CommonCompound::evidenceCount).reversed())
                        .thenComparing(CommonCompound::name))
                .toList();
    }

    List<SelectedLiterature> selectDirectLiterature(List<RankedEvidence> evidence) {
        Map<UUID, MutableDocumentScore> documents = new HashMap<>();
        for (RankedEvidence item : evidence) {
            CompoundEvidenceRecord record = item.evidence();
            MutableDocumentScore score = documents.computeIfAbsent(
                    record.documentId(),
                    ignored -> new MutableDocumentScore(record.documentId(), record.documentTitle()));
            score.evidenceCount++;
            score.compounds.add(normalize(compoundName(record)));
            score.score += 1 + Math.max(0, item.matchScore());
            if (record.reviewStatus() == ReviewStatus.APPROVED) {
                score.score += 3;
            }
            if (hasText(record.row().targetOrMechanism())) {
                score.score += 1;
            }
            if (isApplication(record.row().assayMethod())) {
                score.score += 1;
            }
        }
        List<MutableDocumentScore> ranked = documents.values().stream()
                .sorted(Comparator.comparingDouble(MutableDocumentScore::selectionScore).reversed()
                        .thenComparing(score -> score.documentId.toString()))
                .toList();
        List<SelectedLiterature> selected = new ArrayList<>();
        for (MutableDocumentScore item : ranked) {
            if (selected.size() >= Math.max(0, properties.getMaxDirectDocuments())) {
                break;
            }
            RagDocumentRecord document = ragDocumentRepository.findById(item.documentId).orElse(null);
            if (document == null
                    || document.status() != RagDocumentStatus.COMPLETED
                    || document.duplicateOfDocumentId() != null
                    || !literatureRepository.hasDocumentChunks(item.documentId)) {
                continue;
            }
            selected.add(new SelectedLiterature(
                    item.documentId,
                    displayTitle(document),
                    LiteratureSourceType.DIRECT,
                    selected.size() + 1,
                    item.selectionScore(),
                    "证据表直接关联：" + item.evidenceCount + " 条证据，"
                            + item.compounds.size() + " 个化合物"));
        }
        return List.copyOf(selected);
    }

    List<SelectedLiterature> selectSupplementalLiterature(
            SectionEvidenceMatrix matrix,
            List<SelectedLiterature> direct,
            List<String> warnings) {
        int limit = Math.min(5, Math.max(0, properties.getMaxSupplementalDocuments()));
        if (limit == 0) {
            return List.of();
        }
        Set<UUID> excluded = direct.stream()
                .map(SelectedLiterature::documentId)
                .collect(Collectors.toSet());
        Map<UUID, ReportLiteratureRetrievalService.DocumentHit> candidates = new LinkedHashMap<>();
        List<String> currentMissing = new ArrayList<>(matrix.missingSections());
        List<String> queries = new ArrayList<>(matrix.retrievalQueries());
        int rounds = Math.min(2, Math.max(1, properties.getRetrievalRounds()));
        for (int round = 1; round <= rounds; round++) {
            try {
                for (var hit : retrievalService.retrieve(queries, limit * 8)) {
                    if (!excluded.contains(hit.documentId())) {
                        candidates.merge(hit.documentId(), hit, this::mergeHit);
                    }
                }
            } catch (RuntimeException e) {
                warnings.add("第 " + round + " 轮补充文献检索失败：" + safeMessage(e));
            }
            if (round < rounds) {
                currentMissing = missingAfterRetrieval(currentMissing, candidates.values());
                if (currentMissing.isEmpty()) {
                    break;
                }
                queries = buildGapQueries(currentMissing, candidates.values());
            }
        }

        List<SelectedLiterature> result = new ArrayList<>();
        for (var hit : candidates.values().stream()
                .sorted(Comparator.comparingDouble(
                                ReportLiteratureRetrievalService.DocumentHit::score).reversed())
                .toList()) {
            if (result.size() >= limit) {
                break;
            }
            var document = ragDocumentRepository.findById(hit.documentId()).orElse(null);
            if (document == null || document.status() != RagDocumentStatus.COMPLETED
                    || document.duplicateOfDocumentId() != null
                    || !literatureRepository.hasDocumentChunks(hit.documentId())) {
                continue;
            }
            result.add(new SelectedLiterature(
                    hit.documentId(),
                    displayTitle(document),
                    LiteratureSourceType.SUPPLEMENTAL,
                    direct.size() + result.size() + 1,
                    hit.score(),
                    "补充检索命中：" + String.join("；", hit.matchedQueries())));
        }
        return List.copyOf(result);
    }

    private ReportLiteratureRetrievalService.DocumentHit mergeHit(
            ReportLiteratureRetrievalService.DocumentHit first,
            ReportLiteratureRetrievalService.DocumentHit second) {
        LinkedHashSet<String> queries = new LinkedHashSet<>(first.matchedQueries());
        queries.addAll(second.matchedQueries());
        LinkedHashSet<String> chunks = new LinkedHashSet<>(first.matchedChunkIds());
        chunks.addAll(second.matchedChunkIds());
        return new ReportLiteratureRetrievalService.DocumentHit(
                first.documentId(),
                first.title() == null ? second.title() : first.title(),
                first.score() + second.score(),
                List.copyOf(queries),
                List.copyOf(chunks));
    }

    private List<SelectedLiterature> combineAndRank(List<SelectedLiterature> direct,
                                                    List<SelectedLiterature> supplemental) {
        List<SelectedLiterature> combined = new ArrayList<>();
        combined.addAll(direct);
        combined.addAll(supplemental);
        List<SelectedLiterature> ranked = new ArrayList<>();
        for (int index = 0; index < combined.size(); index++) {
            SelectedLiterature item = combined.get(index);
            ranked.add(new SelectedLiterature(
                    item.documentId(), item.title(), item.sourceType(), index + 1,
                    item.relevanceScore(), item.selectionReason()));
        }
        return List.copyOf(ranked);
    }

    AnalysisResult analyzeDocuments(UUID reportId,
                                    List<SelectedLiterature> selected,
                                    List<String> warnings) {
        List<LiteratureProfile> profiles = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            SelectedLiterature item = selected.get(index);
            RagDocumentRecord document = ragDocumentRepository.findById(item.documentId()).orElse(null);
            if (document == null) {
                String warning = "未找到文献：" + item.title();
                warnings.add(warning);
                literatureRepository.updateLiteratureStatus(
                        reportId, item.documentId(), LiteratureAnalysisStatus.FAILED, warning);
                continue;
            }
            try {
                var outcome = fullDocumentAnalysisService.analyze(document);
                profiles.add(outcome.profile());
                literatureRepository.updateLiteratureStatus(
                        reportId,
                        item.documentId(),
                        outcome.cached()
                                ? LiteratureAnalysisStatus.CACHED
                                : LiteratureAnalysisStatus.COMPLETED,
                        null);
            } catch (RuntimeException e) {
                String warning = "全文分析失败：" + displayTitle(document) + "（" + safeMessage(e) + "）";
                warnings.add(warning);
                literatureRepository.updateLiteratureStatus(
                        reportId, item.documentId(), LiteratureAnalysisStatus.FAILED, safeMessage(e));
            }
            int progress = 45 + (int) Math.round((index + 1) * 32.0 / Math.max(1, selected.size()));
            reportRepository.updateProgress(
                    reportId, ReportStatus.ANALYZING_LITERATURE, null,
                    "正在分析全文文献 " + (index + 1) + "/" + selected.size(),
                    progress, selected.size(), profiles.size(), warnings);
        }
        return new AnalysisResult(List.copyOf(profiles));
    }

    private SynthesisResult synthesize(String question,
                                       String rewrittenQuestion,
                                       ReportService.ReportOverview overview,
                                       List<RankedEvidence> representativeEvidence,
                                       List<CommonCompound> commonCompounds,
                                       SectionEvidenceMatrix matrix,
                                       List<SelectedLiterature> selectedDocuments,
                                       List<LiteratureProfile> profiles,
                                       List<String> warnings) {
        Map<UUID, Integer> aliases = new LinkedHashMap<>();
        for (int index = 0; index < selectedDocuments.size(); index++) {
            aliases.put(selectedDocuments.get(index).documentId(), index + 1);
        }
        Map<UUID, LiteratureProfile> profilesByDocument = profiles.stream()
                .collect(Collectors.toMap(
                        LiteratureProfile::documentId,
                        profile -> profile,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<ComparableActivityGroup> comparableGroups = comparableActivityGroups(representativeEvidence);
        String globalNumericSource = buildGlobalNumericSource(
                representativeEvidence, profilesByDocument, aliases, overview);
        List<String> sections = new ArrayList<>();
        List<ReportClaimDraft> claims = new ArrayList<>();
        for (SectionSpec spec : SECTIONS) {
            SectionContext context = sectionContext(
                    spec.key(), overview, representativeEvidence, commonCompounds,
                    matrix, aliases, profilesByDocument, comparableGroups, globalNumericSource);
            String section = generateSection(
                    spec, question, rewrittenQuestion, context, aliases, overview, warnings);
            sections.add(section);
            claims.add(toClaim(spec.key(), section, context, aliases));
        }
        sections.add(referenceSection(selectedDocuments));
        String markdown = String.join("\n\n", sections).trim();
        return new SynthesisResult(markdown, List.copyOf(claims));
    }

    private String generateSection(SectionSpec spec,
                                   String question,
                                   String rewrittenQuestion,
                                   SectionContext context,
                                   Map<UUID, Integer> aliases,
                                   ReportService.ReportOverview overview,
                                   List<String> warnings) {
        int literatureCount = aliases.size();
        String prompt = PromptResources.load(PromptCatalog.REPORT_SECTION_SYNTHESIS_SYSTEM);
        String input;
        try {
            input = """
                    用户问题：%s
                    检索改写：%s
                    当前章节：## %s
                    目标长度：%d-%d个中文字符
                    允许的文献编号：[文献1] 至 [文献%d]

                    章节证据上下文：
                    %s

                    只输出以“## %s”开头的本章节。
                    """.formatted(
                    question,
                    rewrittenQuestion,
                    spec.title(),
                    spec.minChars(),
                    spec.maxChars(),
                    literatureCount,
                    objectMapper.writeValueAsString(context),
                    spec.title());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize section context", e);
        }

        RuntimeException lastError = null;
        String lastAnswer = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String answer = repairCitations(chatClient.chatStandard(
                        SystemMessage.from(prompt),
                        UserMessage.from(input)
                ).aiMessage().text().trim(), aliases);
                lastAnswer = answer;
                validateSection(spec, answer, context, literatureCount);
                return answer;
            } catch (RuntimeException e) {
                lastError = e;
                input += "\n\n上一次输出未通过校验，请仅修正当前章节：" + safeMessage(e);
            }
        }

        String salvaged = softDegrade(spec, lastAnswer, context, literatureCount);
        if (salvaged != null) {
            warnings.add("“" + spec.title() + "”章节软降级（已剔除违规内容）：" + safeMessage(lastError));
            return salvaged;
        }
        warnings.add("“" + spec.title() + "”章节生成降级：" + safeMessage(lastError));
        return fallbackSection(spec, context, overview, warnings);
    }

    private String repairCitations(String answer, Map<UUID, Integer> aliases) {
        if (answer == null || answer.isEmpty()) {
            return answer;
        }
        Matcher matcher = UUID_CITATION.matcher(answer);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String replacement = "";
            try {
                Integer alias = aliases.get(UUID.fromString(matcher.group(1).toLowerCase(Locale.ROOT)));
                if (alias != null) {
                    replacement = "[文献" + alias + "]";
                }
            } catch (IllegalArgumentException ignored) {
                // unparseable uuid -> drop the broken citation token
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String softDegrade(SectionSpec spec,
                               String lastAnswer,
                               SectionContext context,
                               int literatureCount) {
        if (lastAnswer == null || lastAnswer.isBlank()) {
            return null;
        }
        String header = "## " + spec.title();
        int headerIndex = lastAnswer.indexOf(header);
        String body = headerIndex >= 0
                ? lastAnswer.substring(headerIndex + header.length())
                : lastAnswer;

        String sourceText = normalizeNumericSource(context.numericSource());
        StringBuilder kept = new StringBuilder();
        for (String sentence : SENTENCE_SPLIT.split(body)) {
            if (sentence.isBlank()) {
                kept.append(sentence);
                continue;
            }
            if (hasIllegalCitation(sentence, literatureCount)
                    || hasUntraceableNumber(sentence, sourceText)) {
                continue;
            }
            kept.append(sentence);
        }
        String cleaned = kept.toString().strip();
        if (cleaned.length() < 60) {
            return null;
        }
        return header + "\n\n" + cleaned;
    }

    private boolean hasIllegalCitation(String text, int literatureCount) {
        if (RAW_CITATION_TOKEN.matcher(text).find() || UUID_CITATION.matcher(text).find()) {
            return true;
        }
        Matcher bracket = LITERATURE_BRACKET.matcher(text);
        while (bracket.find()) {
            if (!bracket.group().matches("\\[文献\\d+]")) {
                return true;
            }
        }
        Matcher alias = LITERATURE_ALIAS.matcher(text);
        while (alias.find()) {
            int value = Integer.parseInt(alias.group(1));
            if (value < 1 || value > literatureCount) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUntraceableNumber(String text, String normalizedSource) {
        Matcher numeric = NUMERIC_WITH_UNIT.matcher(text);
        while (numeric.find()) {
            if (!normalizedSource.contains(normalizeNumericSource(numeric.group()))) {
                return true;
            }
        }
        return false;
    }

    private void validateSection(SectionSpec spec,
                                 String answer,
                                 SectionContext context,
                                 int literatureCount) {
        if (answer == null || !answer.startsWith("## " + spec.title())) {
            throw new IllegalStateException("章节标题不符合要求");
        }
        if (RAW_CITATION_TOKEN.matcher(answer).find()) {
            throw new IllegalStateException("出现未替换的原始引用标记（EVIDENCE/LITERATURE）");
        }
        Matcher bracketMatcher = LITERATURE_BRACKET.matcher(answer);
        while (bracketMatcher.find()) {
            if (!bracketMatcher.group().matches("\\[文献\\d+]")) {
                throw new IllegalStateException("出现非法的引用标记：" + bracketMatcher.group());
            }
        }
        Matcher aliasMatcher = LITERATURE_ALIAS.matcher(answer);
        boolean citationFound = false;
        while (aliasMatcher.find()) {
            citationFound = true;
            int alias = Integer.parseInt(aliasMatcher.group(1));
            if (alias < 1 || alias > literatureCount) {
                throw new IllegalStateException("引用了不存在的文献编号");
            }
        }
        boolean sourceHasLiterature = context.evidenceRows().stream()
                .map(row -> row.get("literature"))
                .anyMatch(value -> value instanceof String text && !text.isBlank())
                || !context.literatureClaims().isEmpty();
        if (sourceHasLiterature && !citationFound) {
            throw new IllegalStateException("章节包含文献事实但没有文献引用");
        }
        String sourceText = normalizeNumericSource(context.numericSource());
        Matcher numericMatcher = NUMERIC_WITH_UNIT.matcher(answer);
        while (numericMatcher.find()) {
            String fact = normalizeNumericSource(numericMatcher.group());
            if (!sourceText.contains(fact)) {
                throw new IllegalStateException("出现无法追溯的数值：" + numericMatcher.group());
            }
        }
        if (spec.key().equals("activity")
                && context.comparableActivityGroups().isEmpty()
                && containsComparativeClaim(answer)) {
            throw new IllegalStateException("当前证据不存在可直接比较的活性组");
        }
    }

    private SectionContext sectionContext(
            String sectionKey,
            ReportService.ReportOverview overview,
            List<RankedEvidence> evidence,
            List<CommonCompound> commonCompounds,
            SectionEvidenceMatrix matrix,
            Map<UUID, Integer> aliases,
            Map<UUID, LiteratureProfile> profiles,
            List<ComparableActivityGroup> comparableGroups,
            String globalNumericSource) {
        List<Map<String, Object>> evidenceRows = evidence.stream()
                .filter(item -> relevantEvidence(sectionKey, item))
                .limit(Math.max(1, properties.getMaxModelEvidence()))
                .map(item -> evidenceRow(item, aliases.get(item.evidence().documentId())))
                .toList();
        List<Map<String, Object>> literatureClaims = new ArrayList<>();
        Map<UUID, List<String>> chunkIdsByDocument = new LinkedHashMap<>();
        for (var entry : profiles.entrySet()) {
            List<LiteratureClaim> relevantClaims = claimsForSection(entry.getValue(), sectionKey)
                    .stream().limit(12).toList();
            if (relevantClaims.isEmpty()) {
                continue;
            }
            literatureClaims.add(Map.of(
                    "literature", "[文献" + aliases.get(entry.getKey()) + "]",
                    "title", entry.getValue().title(),
                    "claims", relevantClaims));
            chunkIdsByDocument.put(
                    entry.getKey(),
                    relevantClaims.stream()
                            .flatMap(claim -> claim.chunkIds().stream())
                            .distinct()
                            .toList());
        }
        String numericSource = globalNumericSource == null || globalNumericSource.isBlank()
                ? evidenceRows + " " + literatureClaims + " " + overview
                : globalNumericSource;
        return new SectionContext(
                overview,
                sectionKey.equals("classes") || sectionKey.equals("scope")
                        ? commonCompounds.stream().limit(20).toList()
                        : List.of(),
                matrix,
                evidenceRows,
                List.copyOf(literatureClaims),
                sectionKey.equals("activity") ? comparableGroups : List.of(),
                numericSource,
                Map.copyOf(chunkIdsByDocument));
    }

    private String buildGlobalNumericSource(List<RankedEvidence> evidence,
                                            Map<UUID, LiteratureProfile> profiles,
                                            Map<UUID, Integer> aliases,
                                            ReportService.ReportOverview overview) {
        StringBuilder builder = new StringBuilder();
        for (RankedEvidence item : evidence) {
            builder.append(evidenceRow(item, aliases.get(item.evidence().documentId())))
                    .append(' ');
        }
        for (LiteratureProfile profile : profiles.values()) {
            builder.append(profile).append(' ');
        }
        builder.append(overview);
        return builder.toString();
    }

    private Map<String, Object> evidenceRow(RankedEvidence item, Integer alias) {
        var row = item.evidence().row();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", item.evidence().evidenceId());
        value.put("literature", alias == null ? "" : "[文献" + alias + "]");
        value.put("compound", compoundName(item.evidence()));
        value.put("structureType", row.structureType());
        value.put("sourceCategory", row.sourceCategory());
        value.put("organism", row.oomyceteScientificName());
        value.put("method", row.assayMethod());
        value.put("activity", row.activityData());
        value.put("mechanism", row.targetOrMechanism());
        value.put("applicationSafety", String.join("；",
                nonBlank(row.cytotoxicity(), row.resistanceCrossResistance(), row.synergy())));
        value.put("reviewStatus", item.evidence().reviewStatus());
        value.put("conflictGroup", item.conflictGroup());
        return value;
    }

    private List<LiteratureClaim> claimsForSection(LiteratureProfile profile, String sectionKey) {
        return switch (sectionKey) {
            case "scope", "background" -> concat(profile.background(), profile.conclusions());
            case "classes" -> profile.compounds();
            case "activity" -> profile.activity();
            case "mechanisms" -> profile.mechanisms();
            case "applications" -> concat(profile.applications(), profile.safetyAndResistance());
            case "conflicts" -> concat(profile.activity(), profile.limitations());
            case "limitations" -> concat(profile.limitations(), profile.safetyAndResistance());
            default -> List.of();
        };
    }

    private boolean relevantEvidence(String sectionKey, RankedEvidence item) {
        var row = item.evidence().row();
        return switch (sectionKey) {
            case "scope" -> true;
            case "background" -> false;
            case "classes" -> hasText(row.sourceCategory()) || hasText(row.structureType());
            case "activity" -> hasText(row.activityData());
            case "mechanisms" -> hasText(row.targetOrMechanism());
            case "applications" -> isApplication(row.assayMethod())
                    || hasText(row.cytotoxicity())
                    || hasText(row.resistanceCrossResistance())
                    || hasText(row.synergy());
            case "conflicts" -> item.conflictGroup() != null || isInactive(row.activityData());
            case "limitations" -> true;
            default -> false;
        };
    }

    List<ComparableActivityGroup> comparableActivityGroups(List<RankedEvidence> evidence) {
        Map<String, List<RankedEvidence>> grouped = new LinkedHashMap<>();
        for (RankedEvidence item : evidence) {
            String activity = item.evidence().row().activityData();
            Matcher metric = ACTIVITY_METRIC.matcher(activity == null ? "" : activity);
            Matcher unit = ACTIVITY_UNIT.matcher(activity == null ? "" : activity);
            if (!metric.find() || !unit.find()) {
                continue;
            }
            String key = normalize(item.evidence().row().oomyceteScientificName())
                    + "|" + normalize(item.evidence().row().assayMethod())
                    + "|" + normalize(metric.group())
                    + "|" + normalize(unit.group());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        return grouped.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .map(item -> item.evidence().evidenceId())
                        .distinct().count() >= 2)
                .map(entry -> {
                    RankedEvidence first = entry.getValue().getFirst();
                    return new ComparableActivityGroup(
                            first.evidence().row().oomyceteScientificName(),
                            first.evidence().row().assayMethod(),
                            entry.getKey().split("\\|")[2],
                            entry.getKey().split("\\|")[3],
                            entry.getValue().stream()
                                    .map(item -> Map.of(
                                            "compound", firstNonBlank(
                                                    compoundName(item.evidence()), "未命名化合物"),
                                            "activity", firstNonBlank(
                                                    item.evidence().row().activityData(), "未提供")))
                                    .toList());
                })
                .toList();
    }

    private ReportClaimDraft toClaim(String sectionKey,
                                     String section,
                                     SectionContext context,
                                     Map<UUID, Integer> aliases) {
        Set<Integer> citedAliases = new HashSet<>();
        Matcher matcher = LITERATURE_ALIAS.matcher(section);
        while (matcher.find()) {
            citedAliases.add(Integer.parseInt(matcher.group(1)));
        }
        Set<UUID> citedDocuments = aliases.entrySet().stream()
                .filter(entry -> citedAliases.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        List<UUID> evidenceIds = context.evidenceRows().stream()
                .filter(row -> {
                    Object alias = row.get("literature");
                    if (!(alias instanceof String text)) {
                        return false;
                    }
                    Matcher aliasMatcher = LITERATURE_ALIAS.matcher(text);
                    return aliasMatcher.find() && citedAliases.contains(
                            Integer.parseInt(aliasMatcher.group(1)));
                })
                .map(row -> UUID.fromString(row.get("evidenceId").toString()))
                .toList();
        Map<UUID, List<String>> chunks = context.chunkIdsByDocument().entrySet().stream()
                .filter(entry -> citedDocuments.contains(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        return new ReportClaimDraft(sectionKey, section, evidenceIds, chunks);
    }

    private String referenceSection(List<SelectedLiterature> selectedDocuments) {
        StringBuilder references = new StringBuilder("## 参考文献\n");
        if (selectedDocuments.isEmpty()) {
            return references.append("\n当前未获得可用于全文分析的内部文献。").toString();
        }
        for (int index = 0; index < selectedDocuments.size(); index++) {
            SelectedLiterature item = selectedDocuments.get(index);
            references.append("\n").append(index + 1).append(". ")
                    .append(item.title() == null || item.title().isBlank()
                            ? "未命名内部文献"
                            : item.title())
                    .append(item.sourceType() == LiteratureSourceType.SUPPLEMENTAL
                            ? "（补充检索）"
                            : "")
                    .append('\n');
        }
        return references.toString().trim();
    }

    private String classesFallback(List<CommonCompound> compounds) {
        if (compounds.isEmpty()) {
            return "当前证据不足以形成可靠的常见化合物排序。";
        }
        List<CommonCompound> readable = compounds.stream()
                .filter(item -> item.name().length() <= 32)
                .limit(8)
                .toList();
        List<CommonCompound> chosen = readable.isEmpty()
                ? compounds.stream().limit(5).toList()
                : readable;
        String list = chosen.stream()
                .map(item -> shortCompoundName(item.name()) + "（" + item.documentCount() + "篇文献）")
                .collect(Collectors.joining("、"));
        return "“常见”按独立文献覆盖优先统计，代表化合物包括：" + list
                + "。其余化合物多为结构复杂的合成或天然产物，完整的结构类别与活性数据详见随附数据表。";
    }

    private String shortCompoundName(String name) {
        return name.length() <= 32 ? name : name.substring(0, 30) + "…";
    }

    private String fallbackSection(SectionSpec spec,
                                   SectionContext context,
                                   ReportService.ReportOverview overview,
                                   List<String> warnings) {
        String body = switch (spec.key()) {
            case "scope" -> "本报告围绕用户问题，综合了 "
                    + overview.evidenceCount() + " 条结构化证据及已成功完成全文分析的内部文献。"
                    + "当前证据主要用于科研检索和候选筛选，不能替代人工核验。";
            case "background" -> context.literatureClaims().isEmpty()
                    ? "当前全文文献未提供足够一致的研究背景信息。"
                    : "相关研究主要围绕卵菌及相关病原体的化合物筛选、活性评价和作用机制展开。";
            case "classes" -> classesFallback(context.commonCompounds());
            case "activity" -> "不同实验对象、方法、指标和单位之间不能直接横向比较。"
                    + (context.comparableActivityGroups().isEmpty()
                    ? "当前没有形成满足全部可比条件的活性数据组。"
                    : "报告仅在满足可比条件的数据组内描述活性差异。");
            case "mechanisms" -> "当前证据表和全文分析中的机制信息覆盖有限，"
                    + "未明确报道或未经验证的机制不作推断。";
            case "applications" -> "应用潜力需同时考虑活体或田间证据、毒性、安全性和抗性信息；"
                    + "只有体外活性的数据只能视为初步候选。";
            case "conflicts" -> "看似冲突的结果可能来自实验对象、方法、浓度和评价指标差异，"
                    + "在条件未对齐前不判定为真实矛盾。";
            case "limitations" -> "结构化证据可能包含机器抽取误差，且部分全文文献分析未完成。"
                    + (warnings.isEmpty() ? "" : "本次报告包含 " + warnings.size() + " 条流程警告。");
            default -> "当前证据不足以生成该章节。";
        };
        return "## " + spec.title() + "\n\n" + body;
    }

    private List<String> buildRetrievalQueries(String question,
                                               String rewrittenQuestion,
                                               List<RankedEvidence> evidence,
                                               List<String> missing,
                                               int round) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(rewrittenQuestion);
        queries.add(question + " 研究背景 化合物分类 卵菌");
        List<String> topCompounds = commonCompounds(evidence).stream()
                .limit(3).map(CommonCompound::name).toList();
        for (String compound : topCompounds) {
            queries.add(compound + " oomycete activity mechanism");
        }
        if (missing.contains("mechanisms")) {
            queries.add(String.join(" ", topCompounds) + " mode of action target mechanism");
        }
        if (missing.contains("applications")) {
            queries.add(String.join(" ", topCompounds) + " in vivo field efficacy application");
        }
        if (missing.contains("safety")) {
            queries.add(String.join(" ", topCompounds) + " toxicity phytotoxicity resistance safety");
        }
        if (missing.contains("conflicts") || round > 1) {
            queries.add(String.join(" ", topCompounds) + " sensitivity resistance conflicting results");
        }
        return queries.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(Math.max(1, properties.getMaxQueriesPerRound()))
                .toList();
    }

    private List<String> buildGapQueries(
            List<String> missing,
            java.util.Collection<ReportLiteratureRetrievalService.DocumentHit> previousHits) {
        String titles = previousHits.stream()
                .map(ReportLiteratureRetrievalService.DocumentHit::title)
                .filter(this::hasText)
                .limit(3)
                .collect(Collectors.joining(" "));
        return missing.stream()
                .map(section -> switch (section) {
                    case "mechanisms" -> titles + " target mode of action mechanism validation";
                    case "applications" -> titles + " in vivo leaf field greenhouse efficacy";
                    case "safety" -> titles + " cytotoxicity phytotoxicity resistance safety";
                    case "conflicts" -> titles + " sensitivity variation resistance conflicting results";
                    default -> titles + " " + section + " oomycete antimicrobial compounds";
                })
                .limit(Math.max(1, properties.getMaxQueriesPerRound()))
                .toList();
    }

    private List<String> missingAfterRetrieval(
            List<String> currentMissing,
            java.util.Collection<ReportLiteratureRetrievalService.DocumentHit> hits) {
        String queryText = hits.stream()
                .flatMap(hit -> hit.matchedQueries().stream())
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        return currentMissing.stream()
                .filter(section -> !queryText.contains(switch (section) {
                    case "mechanisms" -> "mechanism";
                    case "applications" -> "field";
                    case "safety" -> "toxicity";
                    case "conflicts" -> "conflict";
                    default -> section;
                }))
                .toList();
    }

    private int count(List<RankedEvidence> evidence,
                      java.util.function.Predicate<RankedEvidence> predicate) {
        return (int) evidence.stream().filter(predicate).count();
    }

    private boolean containsComparativeClaim(String answer) {
        String normalized = normalize(answer);
        return normalized.contains("更强")
                || normalized.contains("更弱")
                || normalized.contains("最高")
                || normalized.contains("最低")
                || normalized.contains("优于")
                || normalized.contains("劣于");
    }

    private String normalizeNumericSource(String value) {
        return value == null ? "" : value
                .toLowerCase(Locale.ROOT)
                .replace("μ", "u")
                .replaceAll("±\\s*\\d+(?:\\.\\d+)?", "")
                .replaceAll("\\s+", "");
    }

    private boolean isApplication(String method) {
        String normalized = normalize(method);
        return normalized.contains("leaf")
                || normalized.contains("叶片")
                || normalized.contains("温室")
                || normalized.contains("田间")
                || normalized.contains("field")
                || normalized.contains("in vivo")
                || normalized.contains("plant");
    }

    private boolean isInactive(String activity) {
        String normalized = normalize(activity);
        return normalized.contains("无活性")
                || normalized.contains("无抑制")
                || normalized.contains("no activity")
                || normalized.contains("inactive");
    }

    private String compoundName(CompoundEvidenceRecord evidence) {
        return firstNonBlank(
                evidence.row().compoundStandardName(),
                evidence.row().compoundOriginalName());
    }

    private String displayTitle(RagDocumentRecord document) {
        String filenameTitle = document.sourceFilename() == null
                ? null
                : document.sourceFilename().replaceFirst("(?i)\\.pdf$", "");
        if (isLikelyJunkTitle(document.title())) {
            return firstNonBlank(filenameTitle, document.title(), "未命名内部文献");
        }
        return firstNonBlank(document.title(), filenameTitle, "未命名内部文献");
    }

    private boolean isLikelyJunkTitle(String title) {
        if (title == null || title.isBlank()) {
            return true;
        }
        String trimmed = title.trim();
        if (trimmed.length() < 8) {
            return true;
        }
        boolean hasLetters = trimmed.chars().anyMatch(Character::isLetter);
        boolean hasLowercase = trimmed.chars().anyMatch(Character::isLowerCase);
        if (hasLetters && !hasLowercase && trimmed.length() < 40) {
            return true;
        }
        String firstWord = trimmed.split("\\s+")[0].toLowerCase(Locale.ROOT);
        return Set.of("of", "and", "the", "for", "in", "on", "with", "a", "an")
                .contains(firstWord);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private List<String> nonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (hasText(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    @SafeVarargs
    private final <T> List<T> concat(List<T>... lists) {
        List<T> result = new ArrayList<>();
        for (List<T> list : lists) {
            result.addAll(list);
        }
        return List.copyOf(result);
    }

    public record DeepReportResult(
            String markdown,
            List<String> warnings,
            int selectedDocumentCount,
            int analyzedDocumentCount,
            boolean partial
    ) {
    }

    record CommonCompound(
            String name,
            int documentCount,
            int evidenceCount,
            int organismCount,
            int approvedCount
    ) {
    }

    record ComparableActivityGroup(
            String organism,
            String method,
            String metric,
            String unit,
            List<Map<String, String>> values
    ) {
    }

    private record SectionSpec(String key, String title, int minChars, int maxChars) {
    }

    private record SectionContext(
            ReportService.ReportOverview overview,
            List<CommonCompound> commonCompounds,
            SectionEvidenceMatrix evidenceMatrix,
            List<Map<String, Object>> evidenceRows,
            List<Map<String, Object>> literatureClaims,
            List<ComparableActivityGroup> comparableActivityGroups,
            String numericSource,
            Map<UUID, List<String>> chunkIdsByDocument
    ) {
    }

    record AnalysisResult(List<LiteratureProfile> profiles) {
    }

    private record SynthesisResult(String markdown, List<ReportClaimDraft> claims) {
    }

    private static final class MutableCommonCompound {
        private final String name;
        private final Set<UUID> documentIds = new HashSet<>();
        private final Set<String> organisms = new HashSet<>();
        private int evidenceCount;
        private int approvedCount;

        private MutableCommonCompound(String name) {
            this.name = name;
        }

        private CommonCompound freeze() {
            return new CommonCompound(
                    name, documentIds.size(), evidenceCount,
                    (int) organisms.stream().filter(value -> !value.isBlank()).count(),
                    approvedCount);
        }
    }

    private static final class MutableDocumentScore {
        private final UUID documentId;
        private final String title;
        private final Set<String> compounds = new HashSet<>();
        private int evidenceCount;
        private double score;

        private MutableDocumentScore(UUID documentId, String title) {
            this.documentId = documentId;
            this.title = title;
        }

        private double selectionScore() {
            return score + compounds.size() * 2.0;
        }
    }
}
