package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.markdown.MarkdownChunkBuffer;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReportGeneratorService {

    public String generateReport(QueryAnalysis analysis,
                                 List<FusedEvidenceGroup> groups,
                                 List<ExtractedEvidence> evidence) {
        return buildReport(analysis, groups, evidence, null, null);
    }

    public String generateReport(String mainQuestion,
                                 List<FusedEvidenceGroup> groups,
                                 List<ExtractedEvidence> evidence) {
        return generateReport(new QueryAnalysis(mainQuestion, List.of(), List.of(), List.of()),
                groups, evidence);
    }

    public String generateReport(String mainQuestion, List<FusedEvidenceGroup> groups) {
        return generateReport(mainQuestion, groups, List.of());
    }

    public Flux<String> generateReportStreaming(QueryAnalysis analysis,
                                                List<FusedEvidenceGroup> groups,
                                                List<ExtractedEvidence> evidence,
                                                String userGuidance,
                                                List<String> focusSubQuestions) {
        String report = buildReport(analysis, groups, evidence, userGuidance, focusSubQuestions);
        MarkdownChunkBuffer buffer = new MarkdownChunkBuffer();
        List<String> pieces = new ArrayList<>();
        pieces.addAll(buffer.append(report));
        pieces.addAll(buffer.flushRemaining());
        return Flux.fromIterable(pieces);
    }

    public Flux<String> generateReportStreaming(String mainQuestion,
                                                List<FusedEvidenceGroup> groups,
                                                List<ExtractedEvidence> evidence,
                                                String userGuidance,
                                                List<String> focusSubQuestions) {
        return generateReportStreaming(new QueryAnalysis(mainQuestion, List.of(), List.of(), List.of()),
                groups, evidence, userGuidance, focusSubQuestions);
    }

    public Flux<String> generateReportStreaming(String mainQuestion,
                                                List<FusedEvidenceGroup> groups,
                                                String userGuidance,
                                                List<String> focusSubQuestions) {
        return generateReportStreaming(mainQuestion, groups, List.of(), userGuidance, focusSubQuestions);
    }

    private String buildReport(QueryAnalysis analysis,
                               List<FusedEvidenceGroup> groups,
                               List<ExtractedEvidence> evidence,
                               String userGuidance,
                               List<String> focusSubQuestions) {
        boolean zh = "zh".equalsIgnoreCase(analysis == null ? null : analysis.languageCode());
        String question = displayQuestion(analysis);
        List<ExtractedEvidence> safeEvidence = sanitizeEvidence(evidence);
        List<FusedEvidenceGroup> safeGroups = groups == null ? List.of() : groups.stream()
                .filter(Objects::nonNull)
                .toList();

        StringBuilder report = new StringBuilder();
        report.append("# ").append(zh ? "文献综述报告" : "Systematic Review Report").append("\n\n");
        report.append(heading(zh, "一、研究主题的概述", "1. Research Topic Overview"));
        report.append(topicOverview(question, safeEvidence, userGuidance, zh)).append("\n\n");

        report.append(heading(zh, "二、主要研究内容分类", "2. Main Research Categories"));
        report.append(researchCategoryTable(safeEvidence, zh)).append("\n\n");

        report.append(heading(zh, "三、关键发现总结", "3. Key Findings Summary"));
        report.append(keyFindings(safeEvidence, safeGroups, focusSubQuestions, zh)).append("\n\n");

        report.append(heading(zh, "四、研究方法与证据强度", "4. Research Methods and Evidence Strength"));
        report.append(methodsAndStrength(safeEvidence, zh)).append("\n\n");

        report.append(heading(zh, "五、当前存在的不足和未来研究方向", "5. Current Limitations and Future Directions"));
        report.append(limitationsAndFuture(safeEvidence, zh)).append("\n\n");

        report.append(heading(zh, "参考文献", "References"));
        report.append(references(safeEvidence, zh));
        return report.toString().trim();
    }

    private String displayQuestion(QueryAnalysis analysis) {
        if (analysis == null) {
            return "";
        }
        if (analysis.displayMainQuestion() != null && !analysis.displayMainQuestion().isBlank()) {
            return analysis.displayMainQuestion();
        }
        return safe(analysis.mainQuestion());
    }

    private List<ExtractedEvidence> sanitizeEvidence(List<ExtractedEvidence> evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.chunkId() != null && !item.chunkId().isBlank())
                .toList();
    }

    private String heading(boolean zh, String zhText, String enText) {
        return "## " + (zh ? zhText : enText) + "\n\n";
    }

    private String topicOverview(String question,
                                 List<ExtractedEvidence> evidence,
                                 String userGuidance,
                                 boolean zh) {
        List<String> genes = topTyped(evidence, typed -> typed.geneOrProtein(), 10);
        List<String> compounds = topTyped(evidence, this::compoundDisplayNames, 10);
        List<String> organisms = topTyped(evidence, typed -> typed.species(), 8);
        List<String> processes = topTyped(evidence, typed -> typed.pathwayOrProcess(), 8);
        List<String> stages = topTyped(evidence, typed -> typed.developmentalStage(), 6);

        StringBuilder out = new StringBuilder();
        if (zh) {
            out.append("本报告围绕“").append(question).append("”展开。");
            out.append("当前证据集包含 ").append(evidence.size()).append(" 条结构化证据。");
            appendZhList(out, "涉及的主要基因/蛋白包括", genes);
            appendZhList(out, "涉及的主要化合物包括", compounds);
            appendZhList(out, "覆盖的物种或目标生物包括", organisms);
            appendZhList(out, "相关过程或通路包括", processes);
            appendZhList(out, "相关发育阶段包括", stages);
            if (userGuidance != null && !userGuidance.isBlank()) {
                out.append("报告已优先响应用户补充关注点：").append(userGuidance).append("。");
            }
            out.append("所有结论均来自后续表格和引用中的证据项，未将无证据支持的推断写作既定结论。");
        } else {
            out.append("This report addresses: ").append(question).append(". ");
            out.append("The current evidence set contains ").append(evidence.size()).append(" structured evidence items. ");
            appendEnList(out, "Key genes/proteins include", genes);
            appendEnList(out, "Key compounds include", compounds);
            appendEnList(out, "Covered species or targets include", organisms);
            appendEnList(out, "Relevant processes or pathways include", processes);
            appendEnList(out, "Relevant developmental stages include", stages);
            if (userGuidance != null && !userGuidance.isBlank()) {
                out.append("The report prioritizes the user's added guidance: ").append(userGuidance).append(". ");
            }
            out.append("All conclusions below are derived from extracted evidence and cited source chunks.");
        }
        return out.toString();
    }

    private String researchCategoryTable(List<ExtractedEvidence> evidence, boolean zh) {
        StringBuilder table = new StringBuilder();
        if (zh) {
            table.append("| 研究对象 | 作用阶段/目标 | 机制或通路 | 证明方法 | 结论摘要 | 来源 |\n");
        } else {
            table.append("| Research Object | Stage / Target | Mechanism or Pathway | Method | Conclusion | Source |\n");
        }
        table.append("|---|---|---|---|---|---|\n");
        List<ExtractedEvidence> rows = evidence.stream()
                .sorted(Comparator.comparingDouble(ExtractedEvidence::confidence).reversed())
                .limit(20)
                .toList();
        if (rows.isEmpty()) {
            table.append(zh ? "| - | - | - | - | 当前没有可用证据 | - |\n"
                    : "| - | - | - | - | No evidence available | - |\n");
            return table.toString();
        }
        for (ExtractedEvidence item : rows) {
            TypedEntities typed = item.typedEntities();
            table.append("| ")
                    .append(cell(joinOrDash(merge(
                            typed == null ? List.of() : typed.geneOrProtein(),
                            typed == null ? List.of() : compoundDisplayNames(typed)))))
                    .append(" | ")
                    .append(cell(joinOrDash(merge(
                            typed == null ? List.of() : typed.developmentalStage(),
                            typed == null ? List.of() : typed.targetOrganism(),
                            typed == null ? List.of() : typed.phenotype()))))
                    .append(" | ")
                    .append(cell(joinOrDash(merge(
                            typed == null ? List.of() : typed.pathwayOrProcess(),
                            typed == null ? List.of() : typed.mechanism(),
                            typed == null ? List.of() : typed.proposedTarget()))))
                    .append(" | ")
                    .append(cell(firstNonBlank(item.methodology(),
                            joinOrDash(typed == null ? List.of() : typed.assayMethod()),
                            joinOrDash(typed == null ? List.of() : typed.method()))))
                    .append(" | ")
                    .append(cell(shortText(preferredFinding(item), 140)))
                    .append(" | ")
                    .append(cell(citation(item)))
                    .append(" |\n");
        }
        return table.toString();
    }

    private String keyFindings(List<ExtractedEvidence> evidence,
                               List<FusedEvidenceGroup> groups,
                               List<String> focusSubQuestions,
                               boolean zh) {
        StringBuilder out = new StringBuilder();
        List<String> focus = focusSubQuestions == null ? List.of() : focusSubQuestions;
        List<FusedEvidenceGroup> orderedGroups = new ArrayList<>();
        for (String item : focus) {
            groups.stream().filter(group -> item.equals(group.subQuestion())).findFirst().ifPresent(orderedGroups::add);
        }
        for (FusedEvidenceGroup group : groups) {
            if (!orderedGroups.contains(group)) {
                orderedGroups.add(group);
            }
        }
        if (!orderedGroups.isEmpty()) {
            for (FusedEvidenceGroup group : orderedGroups) {
                out.append("### ").append(group.subQuestion()).append("\n\n");
                out.append(localizedSummary(group.groupSummary(), zh)).append("\n\n");
            }
        }
        List<ExtractedEvidence> topItems = evidence.stream()
                .sorted(Comparator.comparingDouble(ExtractedEvidence::confidence).reversed())
                .limit(10)
                .toList();
        out.append(zh ? "| 关键发现 | 证据类型 | 证据强度 | 来源 |\n" : "| Key Finding | Evidence Type | Evidence Strength | Source |\n");
        out.append("|---|---|---|---|\n");
        if (topItems.isEmpty()) {
            out.append(zh ? "| 当前没有抽取到关键发现 | - | 弱 | - |\n"
                    : "| No key finding extracted | - | Weak | - |\n");
            return out.toString();
        }
        for (ExtractedEvidence item : topItems) {
            out.append("| ")
                    .append(cell(shortText(preferredFinding(item), 150)))
                    .append(" | ")
                    .append(cell(safe(item.evidenceType())))
                    .append(" | ")
                    .append(deriveStrength(item, evidence, zh))
                    .append(" | ")
                    .append(cell(citation(item)))
                    .append(" |\n");
        }
        return out.toString();
    }

    private String methodsAndStrength(List<ExtractedEvidence> evidence, boolean zh) {
        Map<String, Long> byType = evidence.stream()
                .collect(Collectors.groupingBy(item -> safeDefault(item.evidenceType(), "UNKNOWN"),
                        LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> byMethod = evidence.stream()
                .map(item -> firstNonBlank(item.methodology(), "Unspecified"))
                .collect(Collectors.groupingBy(method -> method, LinkedHashMap::new, Collectors.counting()));
        StringBuilder out = new StringBuilder();
        if (zh) {
            out.append("证据类型分布：").append(formatCounts(byType)).append("。");
            out.append("主要研究方法包括：").append(formatCounts(limitMap(byMethod, 8))).append("。");
            out.append("证据强度判定遵循：多篇文献一致且包含实验验证为强；单篇实验或跨文献一致为中等；综述性、计算性或单一间接证据为弱。");
        } else {
            out.append("Evidence type distribution: ").append(formatCounts(byType)).append(". ");
            out.append("Main methods include: ").append(formatCounts(limitMap(byMethod, 8))).append(". ");
            out.append("Strength is assigned as strong for consistent multi-source experimental support, moderate for single experimental or cross-source support, and weak for review-level, computational, or indirect evidence.");
        }
        return out.toString();
    }

    private String limitationsAndFuture(List<ExtractedEvidence> evidence, boolean zh) {
        long reviewCount = evidence.stream().filter(item -> "REVIEW".equalsIgnoreCase(item.evidenceType())).count();
        long computationalCount = evidence.stream().filter(item -> "COMPUTATIONAL".equalsIgnoreCase(item.evidenceType())).count();
        long noMethod = evidence.stream().filter(item -> item.methodology() == null || item.methodology().isBlank()).count();
        if (zh) {
            return "1. 当前结论严格受限于已抽取证据和召回文献，未覆盖的文献可能改变部分结论。\n"
                    + "2. 证据集中包含 " + reviewCount + " 条综述性证据和 " + computationalCount
                    + " 条计算性证据，需与功能验证实验区分解读。\n"
                    + "3. 有 " + noMethod + " 条证据缺少明确方法描述，后续应优先补充实验设计、剂量、阶段、物种和靶点信息。\n"
                    + "4. 未来研究应围绕高频实体开展跨物种验证、机制通路验证、剂量/效应关系验证，以及专利或应用转化状态核查。";
        }
        return "1. Conclusions are limited by the retrieved and extracted evidence; missing literature may change some findings.\n"
                + "2. The evidence set includes " + reviewCount + " review-level items and " + computationalCount
                + " computational items, which should be interpreted separately from functional validation.\n"
                + "3. " + noMethod + " evidence items lack explicit method descriptions; future extraction should prioritize study design, dosage, stage, species, and target information.\n"
                + "4. Future work should validate high-frequency entities across species, test mechanisms and pathways, quantify dose-effect relationships, and check patent or application status.";
    }

    private String references(List<ExtractedEvidence> evidence, boolean zh) {
        List<String> references = evidence.stream()
                .map(ExtractedEvidence::documentTitle)
                .filter(Objects::nonNull)
                .filter(title -> !title.isBlank())
                .distinct()
                .toList();
        if (references.isEmpty()) {
            return zh ? "1. 暂无可验证参考文献。" : "1. No verified references available.";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            builder.append(i + 1).append(". ").append(references.get(i)).append("\n");
        }
        return builder.toString().trim();
    }

    private List<String> topTyped(List<ExtractedEvidence> evidence,
                                  Function<TypedEntities, List<String>> extractor,
                                  int limit) {
        return evidence.stream()
                .map(ExtractedEvidence::typedEntities)
                .filter(Objects::nonNull)
                .map(extractor)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();
    }

    private void appendZhList(StringBuilder out, String label, List<String> values) {
        if (!values.isEmpty()) {
            out.append(label).append("：").append(String.join("、", values)).append("。");
        }
    }

    private void appendEnList(StringBuilder out, String label, List<String> values) {
        if (!values.isEmpty()) {
            out.append(label).append(": ").append(String.join(", ", values)).append(". ");
        }
    }

    private String preferredFinding(ExtractedEvidence item) {
        return firstNonBlank(item.finding(), item.claim(), "No finding extracted");
    }

    private List<String> compoundDisplayNames(TypedEntities typed) {
        if (typed == null) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addCompounds(names, typed.compoundCanonicalName());
        addCompounds(names, typed.moleculeOrMetabolite());
        addCompounds(names, typed.compoundIdentifier());
        boolean unresolved = typed.compoundResolutionStatus() != null
                && typed.compoundResolutionStatus().stream().anyMatch(value -> "UNRESOLVED".equalsIgnoreCase(value));
        if (typed.compoundLocalAlias() != null) {
            for (String alias : typed.compoundLocalAlias()) {
                if (alias == null || alias.isBlank()) {
                    continue;
                }
                names.add(unresolved
                        ? "local compound label in this document (unresolved global structure): " + alias
                        : alias.trim());
            }
        }
        return new ArrayList<>(names);
    }

    private void addCompounds(LinkedHashSet<String> names, List<String> values) {
        if (values == null) {
            return;
        }
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(names::add);
    }

    private String deriveStrength(ExtractedEvidence item, List<ExtractedEvidence> allEvidence, boolean zh) {
        long supportingSources = allEvidence.stream()
                .filter(other -> overlapEntities(item, other))
                .map(ExtractedEvidence::documentTitle)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        boolean experimental = "EXPERIMENTAL".equalsIgnoreCase(item.evidenceType());
        if (supportingSources >= 2 && experimental) {
            return zh ? "强" : "Strong";
        }
        if (experimental || supportingSources >= 2) {
            return zh ? "中等" : "Moderate";
        }
        return zh ? "弱" : "Weak";
    }

    private boolean overlapEntities(ExtractedEvidence left, ExtractedEvidence right) {
        Set<String> leftEntities = entitySet(left);
        Set<String> rightEntities = entitySet(right);
        if (leftEntities.isEmpty() || rightEntities.isEmpty()) {
            return Objects.equals(left.documentTitle(), right.documentTitle());
        }
        leftEntities.retainAll(rightEntities);
        return !leftEntities.isEmpty();
    }

    private Set<String> entitySet(ExtractedEvidence item) {
        if (item.typedEntities() != null) {
            return new LinkedHashSet<>(merge(
                    item.typedEntities().geneOrProtein(),
                    compoundDisplayNames(item.typedEntities()),
                    item.typedEntities().pathwayOrProcess(),
                    item.typedEntities().targetOrganism()));
        }
        return item.entities() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(item.entities());
    }

    @SafeVarargs
    private final List<String> merge(List<String>... values) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (List<String> value : values) {
            if (value != null) {
                value.stream()
                        .filter(Objects::nonNull)
                        .filter(item -> !item.isBlank())
                        .forEach(merged::add);
            }
        }
        return new ArrayList<>(merged);
    }

    private String localizedSummary(String summary, boolean zh) {
        if (summary == null || summary.isBlank()) {
            return zh ? "当前没有足够证据形成综合结论。" : "There is not enough evidence for a synthesis.";
        }
        return summary;
    }

    private String citation(ExtractedEvidence item) {
        return "{source=" + safeDefault(item.documentTitle(), "unknown") + "; chunk=" + item.chunkId() + "}";
    }

    private String shortText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 3) + "...";
    }

    private String joinOrDash(List<String> values) {
        return values == null || values.isEmpty() ? "-" : String.join(", ", values);
    }

    private String cell(String value) {
        return safeDefault(value, "-").replace("|", "\\|").replace("\n", " ");
    }

    private String formatCounts(Map<String, Long> counts) {
        if (counts.isEmpty()) {
            return "-";
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    private Map<String, Long> limitMap(Map<String, Long> input, int limit) {
        return input.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"-".equals(value)) {
                return value;
            }
        }
        return "";
    }

    private String safeDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
