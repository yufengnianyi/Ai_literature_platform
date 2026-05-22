package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.markdown.MarkdownChunkBuffer;
import com.example.demo_01.ai.prompt.PromptCatalog;
import com.example.demo_01.ai.prompt.PromptResources;
import com.example.demo_01.ai.review.service.CompoundEvidenceAggregator.CompoundActivityRow;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReportGeneratorService {

    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final int PAPER_TABLE_CONTEXT_MAX_CHARS = 60000;
    private static final int PAPER_TABLE_BATCH_CONTEXT_MAX_CHARS = 45000;
    private static final int BATCH_SUMMARY_MAX_CHARS = 3000;
    private static final List<String> ANTIMICROBIAL_COMPOUND_HEADERS = List.of(
            "\u5316\u5408\u7269\u540d\u79f0",
            "\u7ed3\u6784\u7c7b\u578b",
            "\u6765\u6e90",
            "\u6291\u83cc\u6d53\u5ea6",
            "\u4f5c\u7528\u75c5\u539f\u83cc",
            "\u8bd5\u9a8c\u65b9\u6cd5",
            "\u53ef\u80fd\u7684\u4f5c\u7528\u9776\u6807/\u673a\u5236",
            "\u7ec6\u80de\u6bd2\u6027/\u5b89\u5168\u6027\u6570\u636e",
            "\u6765\u6e90\u6587\u732e",
            "\u4e13\u5229\u4fe1\u606f"
    );

    @Resource(name = "reviewReportChatModel")
    private ChatModel reviewReportChatModel;

    public String generateReport(QueryAnalysis analysis,
                                 List<FusedEvidenceGroup> groups,
                                 List<ExtractedEvidence> evidence) {
        return buildReport(analysis, groups, evidence, null, null, null);
    }

    public String generateReport(QueryAnalysis analysis,
                                 List<FusedEvidenceGroup> groups,
                                 List<ExtractedEvidence> evidence,
                                 List<SynthesizedCompoundRecord> synthesizedRecords) {
        return buildReport(analysis, groups, evidence, null, null, synthesizedRecords);
    }

    public String generateReport(QueryAnalysis analysis,
                                 List<FusedEvidenceGroup> groups,
                                 List<ExtractedEvidence> evidence,
                                 List<SynthesizedCompoundRecord> synthesizedRecords,
                                 List<ReviewPaperEvidenceTable> paperEvidenceTables) {
        return buildReport(analysis, groups, evidence, null, null, synthesizedRecords, paperEvidenceTables);
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
        return generateReportStreaming(analysis, groups, evidence, userGuidance, focusSubQuestions, null);
    }

    public Flux<String> generateReportStreaming(QueryAnalysis analysis,
                                                List<FusedEvidenceGroup> groups,
                                                List<ExtractedEvidence> evidence,
                                                String userGuidance,
                                                List<String> focusSubQuestions,
                                                List<SynthesizedCompoundRecord> synthesizedRecords) {
        String report = buildReport(analysis, groups, evidence, userGuidance, focusSubQuestions, synthesizedRecords);
        return streamMarkdown(report);
    }

    public Flux<String> generateReportStreaming(QueryAnalysis analysis,
                                                List<FusedEvidenceGroup> groups,
                                                List<ExtractedEvidence> evidence,
                                                String userGuidance,
                                                List<String> focusSubQuestions,
                                                List<SynthesizedCompoundRecord> synthesizedRecords,
                                                List<ReviewPaperEvidenceTable> paperEvidenceTables) {
        String report = buildReport(analysis, groups, evidence, userGuidance, focusSubQuestions,
                synthesizedRecords, paperEvidenceTables);
        return streamMarkdown(report);
    }

    private Flux<String> streamMarkdown(String report) {
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
                groups, evidence, userGuidance, focusSubQuestions, null);
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
                               List<String> focusSubQuestions,
                               List<SynthesizedCompoundRecord> synthesizedRecords) {
        return buildReport(analysis, groups, evidence, userGuidance, focusSubQuestions,
                synthesizedRecords, null);
    }

    private String buildReport(QueryAnalysis analysis,
                               List<FusedEvidenceGroup> groups,
                               List<ExtractedEvidence> evidence,
                               String userGuidance,
                               List<String> focusSubQuestions,
                               List<SynthesizedCompoundRecord> synthesizedRecords,
                               List<ReviewPaperEvidenceTable> paperEvidenceTables) {
        boolean zh = "zh".equalsIgnoreCase(analysis == null ? null : analysis.languageCode());
        String question = displayQuestion(analysis);
        List<ExtractedEvidence> safeEvidence = sanitizeEvidence(evidence);
        List<ReviewPaperEvidenceTable> safePaperTables = paperEvidenceTables == null ? List.of() : paperEvidenceTables.stream()
                .filter(Objects::nonNull)
                .toList();
        List<FusedEvidenceGroup> safeGroups = groups == null ? List.of() : groups.stream()
                .filter(Objects::nonNull)
                .toList();

        if (!safePaperTables.isEmpty()) {
            return paperCentricReport(question, safePaperTables, userGuidance, zh);
        }

        StringBuilder report = new StringBuilder();
        int section = 1;
        report.append("# ").append(zh ? "文献综述报告" : "Systematic Review Report").append("\n\n");
        report.append(heading(section++, zh, "研究主题的概述", "Research Topic Overview"));
        report.append(topicOverview(question, safeEvidence, userGuidance, zh)).append("\n\n");


        List<CompoundActivityRow> compoundRows;
        if (synthesizedRecords != null && !synthesizedRecords.isEmpty()) {
            compoundRows = CompoundEvidenceAggregator.fromSynthesizedRecords(synthesizedRecords);
        } else {
            compoundRows = CompoundEvidenceAggregator.fromExtractedEvidence(safeEvidence);
        }
        if (shouldIncludeAntimicrobialCompoundAnalysis(analysis, compoundRows)) {
            report.append(heading(section++, zh, "抑菌化合物同类分析", "Antimicrobial Compound Class Analysis"));
            report.append(compoundClassAnalysis(compoundRows, zh)).append("\n\n");

            if (synthesizedRecords != null && !synthesizedRecords.isEmpty()) {
                report.append(paradigmDetailSection(synthesizedRecords, zh)).append("\n\n");
            }
        }

        report.append(heading(section++, zh, "关键发现总结", "Key Findings Summary"));
        report.append(keyFindings(analysis, safeEvidence, safeGroups, focusSubQuestions, zh)).append("\n\n");

        report.append(heading(section++, zh, "研究方法与证据强度", "Research Methods and Evidence Strength"));
        report.append(methodsAndStrength(safeEvidence, zh)).append("\n\n");

        report.append(heading(section++, zh, "当前存在的不足和未来研究方向", "Current Limitations and Future Directions"));
        report.append(limitationsAndFuture(safeEvidence, zh)).append("\n\n");

        report.append(heading(zh, "参考文献", "References"));
        report.append(references(safeEvidence, zh));
        return report.toString().trim();
    }

    private String paperCentricReport(String question,
                                      List<ReviewPaperEvidenceTable> tables,
                                      String userGuidance,
                                      boolean zh) {
        String llmReport = paperCentricReportWithModel(question, tables, userGuidance, zh);
        if (llmReport != null && !llmReport.isBlank()) {
            return llmReport;
        }
        return deterministicPaperCentricReport(question, tables, userGuidance, zh);
    }

    private String paperCentricReportWithModel(String question,
                                               List<ReviewPaperEvidenceTable> tables,
                                               String userGuidance,
                                               boolean zh) {
        if (reviewReportChatModel == null) {
            return null;
        }
        try {
            String context = buildFullPaperTableReportContext(question, tables, userGuidance, zh);
            if (context.length() > PAPER_TABLE_CONTEXT_MAX_CHARS) {
                return paperCentricReportWithBatches(question, tables, userGuidance, zh);
            }
            return callReportModel(PromptCatalog.REVIEW_REPORT_PAPER_CENTRIC_SYSTEM, context);
        } catch (Exception e) {
            log.warn("Failed to generate paper-centric report with LLM, falling back to deterministic report: {}", e.getMessage());
            return null;
        }
    }

    private String paperCentricReportWithBatches(String question,
                                                 List<ReviewPaperEvidenceTable> tables,
                                                 String userGuidance,
                                                 boolean zh) {
        List<PaperReportBatch> batches = splitPaperTablesForReport(tables, zh);
        if (batches.isEmpty()) {
            return null;
        }
        List<BatchSummary> summaries = new ArrayList<>();
        for (PaperReportBatch batch : batches) {
            String summary = summarizePaperTableBatch(question, batch, userGuidance, zh);
            if (summary == null || summary.isBlank()) {
                return null;
            }
            summaries.add(new BatchSummary(batch.index(), batch.paperCount(), summary.trim()));
        }
        String finalContext = buildBatchFinalReportContext(question, tables, userGuidance, summaries, zh,
                BATCH_SUMMARY_MAX_CHARS);
        if (finalContext.length() > PAPER_TABLE_CONTEXT_MAX_CHARS && !summaries.isEmpty()) {
            int summaryBudget = Math.max(800,
                    (PAPER_TABLE_CONTEXT_MAX_CHARS - 8000) / Math.max(1, summaries.size()));
            finalContext = buildBatchFinalReportContext(question, tables, userGuidance, summaries, zh, summaryBudget);
        }
        return callReportModel(PromptCatalog.REVIEW_REPORT_PAPER_CENTRIC_SYSTEM, finalContext);
    }

    private String summarizePaperTableBatch(String question,
                                            PaperReportBatch batch,
                                            String userGuidance,
                                            boolean zh) {
        StringBuilder context = new StringBuilder();
        context.append(zh ? "# 批次摘要任务\n" : "# Batch Summary Task\n");
        context.append(zh ? "用户问题: " : "User question: ")
                .append(safeDefault(question, "-")).append("\n");
        context.append(zh ? "批次: " : "Batch: ")
                .append(batch.index()).append(" / ")
                .append(zh ? "文献范围 " : "paper range ")
                .append(batch.startIndex()).append("-").append(batch.endIndex())
                .append(", ").append(batch.paperCount())
                .append(zh ? " 篇文献\n" : " papers\n");
        if (userGuidance != null && !userGuidance.isBlank()) {
            context.append(zh ? "补充要求: " : "User guidance: ")
                    .append(userGuidance).append("\n");
        }
        context.append("\n").append(batch.context()).append("\n\n");
        context.append(zh ? "# 输出要求\n" : "# Output Requirements\n");
        context.append(zh
                ? "请输出紧凑中文 Markdown 批次摘要，保留批次内规律、关键差异、证据缺口和代表性 citation token。不要说明上下文切分或技术实现。\n"
                : "Write a compact English Markdown batch summary preserving batch patterns, notable differences, evidence gaps, and representative citation tokens. Do not mention context splitting or implementation details.\n");
        return callReportModel(PromptCatalog.REVIEW_REPORT_PAPER_BATCH_SUMMARY_SYSTEM, context.toString());
    }

    private String callReportModel(String systemPromptResource, String context) {
        ChatResponse response = reviewReportChatModel.chat(
                SystemMessage.from(PromptResources.load(systemPromptResource)),
                UserMessage.from(context)
        );
        AiMessage aiMessage = response == null ? null : response.aiMessage();
        String report = aiMessage == null ? null : aiMessage.text();
        return report == null || report.isBlank() ? null : report.trim();
    }

    private List<PaperReportBatch> splitPaperTablesForReport(List<ReviewPaperEvidenceTable> tables,
                                                             boolean zh) {
        List<ReviewPaperEvidenceTable> safeTables = tables == null ? List.of() : tables.stream()
                .filter(Objects::nonNull)
                .toList();
        List<PaperReportBatch> batches = new ArrayList<>();
        List<ReviewPaperEvidenceTable> current = new ArrayList<>();
        int currentStart = 1;
        int batchIndex = 1;
        for (int i = 0; i < safeTables.size(); i++) {
            ReviewPaperEvidenceTable table = safeTables.get(i);
            List<ReviewPaperEvidenceTable> candidate = new ArrayList<>(current);
            candidate.add(table);
            String candidateContext = buildPaperTableEvidenceContext(candidate, zh, Integer.MAX_VALUE);
            if (!current.isEmpty() && candidateContext.length() > PAPER_TABLE_BATCH_CONTEXT_MAX_CHARS) {
                String currentContext = buildPaperTableEvidenceContext(current, zh, Integer.MAX_VALUE);
                batches.add(new PaperReportBatch(batchIndex++, currentStart, i, List.copyOf(current), currentContext));
                current = new ArrayList<>();
                currentStart = i + 1;
            }
            current.add(table);
        }
        if (!current.isEmpty()) {
            String context = buildPaperTableEvidenceContext(current, zh, Integer.MAX_VALUE);
            batches.add(new PaperReportBatch(batchIndex, currentStart, safeTables.size(), List.copyOf(current), context));
        }
        return batches;
    }

    private String buildBatchFinalReportContext(String question,
                                                List<ReviewPaperEvidenceTable> tables,
                                                String userGuidance,
                                                List<BatchSummary> summaries,
                                                boolean zh,
                                                int summaryMaxChars) {
        StringBuilder context = new StringBuilder();
        context.append(zh ? "# 报告任务\n" : "# Report Task\n");
        context.append(zh ? "用户问题: " : "User question: ")
                .append(safeDefault(question, "-")).append("\n");
        context.append(zh ? "纳入文献数: " : "Included papers: ")
                .append(tables == null ? 0 : tables.size()).append("\n");
        context.append(zh ? "中间摘要数: " : "Intermediate summaries: ")
                .append(summaries == null ? 0 : summaries.size()).append("\n");
        if (userGuidance != null && !userGuidance.isBlank()) {
            context.append(zh ? "补充要求: " : "User guidance: ")
                    .append(userGuidance).append("\n");
        }
        context.append("\n");
        context.append(zh ? "# 批次摘要（覆盖全部已选文献）\n" : "# Batch Summaries (All Selected Papers Covered)\n");
        for (BatchSummary summary : summaries == null ? List.<BatchSummary>of() : summaries) {
            context.append("\n## ")
                    .append(zh ? "摘要 " : "Summary ")
                    .append(summary.index())
                    .append(" (")
                    .append(summary.paperCount())
                    .append(zh ? " 篇文献" : " papers")
                    .append(")\n");
            context.append(shortText(summary.text(), summaryMaxChars)).append("\n");
        }
        context.append("\n");
        context.append(zh ? "# 输出要求\n" : "# Output Requirements\n");
        context.append(zh
                ? """
                请基于上面的所有批次摘要输出详细的中文 Markdown 综述报告。不要说明分批、上下文限制或技术实现。
                报告必须综合所有摘要，覆盖总体结论、主要规律、化合物类别、来源、抑菌浓度/活性模式、病原菌、试验方法、机制/靶标、细胞毒性/安全性、专利信息、跨文献共性/差异、证据缺口和参考依据。
                每个关键结论、证据表行或承载证据的段落末尾必须使用已有引用格式: {source=<文献标题>; chunk=<chunk_id>; quote=<证据摘要>}。
                引用值只能来自上面批次摘要中的 citation token。不得编造未出现的论文、化合物、浓度、机制、安全性或专利信息。
                """
                : """
                Write a detailed English Markdown review report from all batch summaries above. Do not mention batching, context limits, or implementation details.
                Synthesize every summary and cover overall conclusions, main patterns, compound classes, sources, antimicrobial concentration/activity patterns, pathogens, assays, mechanism/target, cytotoxicity/safety, patent evidence, cross-paper commonalities/differences, evidence gaps, and reference basis.
                Every key conclusion, evidence table row, or evidence-bearing paragraph must end with this citation token format: {source=<paper title>; chunk=<chunk_id>; quote=<short evidence summary>}.
                Use citation values only from the batch summaries. Do not invent papers, compounds, concentrations, mechanisms, safety data, or patent information.
                """);
        return context.toString();
    }

    private record PaperReportBatch(int index,
                                    int startIndex,
                                    int endIndex,
                                    List<ReviewPaperEvidenceTable> tables,
                                    String context) {
        int paperCount() {
            return tables == null ? 0 : tables.size();
        }
    }

    private record BatchSummary(int index, int paperCount, String text) {
    }

    private String deterministicPaperCentricReport(String question,
                                                   List<ReviewPaperEvidenceTable> tables,
                                                   String userGuidance,
                                                   boolean zh) {
        StringBuilder report = new StringBuilder();
        report.append("# ").append(zh ? "\u6587\u732e\u7efc\u8ff0\u62a5\u544a" : "Systematic Review Report").append("\n\n");
        report.append("## ").append(zh ? "1. \u7814\u7a76\u4e3b\u9898\u6982\u8ff0" : "1. Research Topic Overview").append("\n\n");
        if (zh) {
            report.append("\u672c\u62a5\u544a\u56f4\u7ed5: ").append(safeDefault(question, "-")).append("\u3002");
            report.append("\u7cfb\u7edf\u5df2\u6839\u636e\u9501\u5b9a\u7684\u76f8\u5173\u7247\u6bb5\u56de\u6eaf\u5230 ")
                    .append(tables.size()).append(" \u7bc7\u6587\u732e\uff0c\u5e76\u5bf9\u6bcf\u7bc7\u6587\u732e\u7684\u5168\u90e8\u53ef\u7528 chunks \u8fdb\u884c\u8bc1\u636e\u8868\u7efc\u5408\u3002\u4e0b\u5217\u5173\u952e\u7ed3\u8bba\u4ee5\u9010\u7bc7 source token \u8ffd\u6eaf\u5230\u539f\u59cb\u6587\u732e\u7247\u6bb5\u3002");
            if (userGuidance != null && !userGuidance.isBlank()) {
                report.append("\u8865\u5145\u8981\u6c42: ").append(userGuidance).append("\u3002");
            }
        } else {
            report.append("This report addresses: ").append(safeDefault(question, "-")).append(". ");
            report.append("The system traced locked relevant chunks back to ")
                    .append(tables.size()).append(" papers and analyzed all available chunks for each paper. Key claims below are traceable through source tokens. ");
            if (userGuidance != null && !userGuidance.isBlank()) {
                report.append("User guidance: ").append(userGuidance).append(". ");
            }
        }
        report.append("\n\n");
        report.append("## ").append(zh ? "2. \u9010\u7bc7\u6587\u732e\u8bc1\u636e\u603b\u7ed3" : "2. Per-Paper Evidence Summaries").append("\n\n");
        report.append(paperEvidenceTableSynthesis(tables, zh)).append("\n\n");
        report.append("## ").append(zh ? "3. \u8de8\u6587\u732e\u89c4\u5f8b\u603b\u7ed3" : "3. Cross-Paper Pattern Synthesis").append("\n\n");
        report.append(crossPaperPatternSynthesis(tables, zh)).append("\n\n");
        report.append("## ").append(zh ? "4. \u8bc1\u636e\u7f3a\u53e3\u548c\u4f7f\u7528\u6ce8\u610f\u4e8b\u9879" : "4. Evidence Gaps and Usage Cautions").append("\n\n");
        report.append(paperCentricEvidenceGaps(tables, zh)).append("\n\n");
        report.append("## ").append(zh ? "5. \u8868\u683c\u4e0b\u8f7d\u4e0e\u53c2\u8003\u4f9d\u636e" : "5. Table Export and References").append("\n\n");
        report.append(zh
                ? "\u4e0b\u8f7d\u7684 xlsx \u6587\u4ef6\u5c06\u6bcf\u7bc7\u6587\u732e\u7684\u5173\u952e\u8bc1\u636e\u884c\u6574\u5408\u5230\u7edf\u4e00\u8868\u683c\uff0c\u5e76\u4fdd\u7559\u6587\u732e\u6807\u9898\u3001\u8bc1\u636e\u8868\u6458\u8981\u3001\u7f6e\u4fe1\u5ea6\u3001\u8b66\u544a\u548c\u6e90 chunk ids\u3002\u6b63\u6587\u4e2d\u7684 source token \u662f\u524d\u7aef\u6e32\u67d3\u5f15\u7528\u89d2\u6807\u7684\u4e3b\u8981\u4f9d\u636e\u3002"
                : "The downloadable xlsx integrates key evidence rows across papers and preserves paper title, paper summary, confidence, warnings, and source chunk ids. Source tokens in the report body are the primary citation markers for front-end rendering.");
        report.append("\n\n");
        report.append(paperTableReferences(tables, zh));
        return report.toString().trim();
    }

    private String buildPaperTableReportContext(String question,
                                                List<ReviewPaperEvidenceTable> tables,
                                                String userGuidance,
                                                boolean zh) {
        return buildPaperTableReportContext(question, tables, userGuidance, zh, PAPER_TABLE_CONTEXT_MAX_CHARS);
    }

    private String buildFullPaperTableReportContext(String question,
                                                    List<ReviewPaperEvidenceTable> tables,
                                                    String userGuidance,
                                                    boolean zh) {
        return buildPaperTableReportContext(question, tables, userGuidance, zh, Integer.MAX_VALUE);
    }

    private String buildPaperTableReportContext(String question,
                                                List<ReviewPaperEvidenceTable> tables,
                                                String userGuidance,
                                                boolean zh,
                                                int maxChars) {
        StringBuilder context = new StringBuilder();
        context.append(zh ? "# \u62a5\u544a\u4efb\u52a1\n" : "# Report Task\n");
        context.append(zh ? "\u7528\u6237\u95ee\u9898: " : "User question: ")
                .append(safeDefault(question, "-")).append("\n");
        context.append(zh ? "\u7eb3\u5165\u6587\u732e\u6570: " : "Included papers: ")
                .append(tables == null ? 0 : tables.size()).append("\n");
        if (userGuidance != null && !userGuidance.isBlank()) {
            context.append(zh ? "\u8865\u5145\u8981\u6c42: " : "User guidance: ")
                    .append(userGuidance).append("\n");
        }
        context.append("\n");
        appendPaperTableEvidenceContext(context, tables, zh, maxChars);
        context.append("\n\n");
        appendPaperReportOutputRequirements(context, zh);
        return context.toString();
    }

    private String buildPaperTableEvidenceContext(List<ReviewPaperEvidenceTable> tables,
                                                  boolean zh,
                                                  int maxChars) {
        StringBuilder context = new StringBuilder();
        appendPaperTableEvidenceContext(context, tables, zh, maxChars);
        return context.toString();
    }

    private void appendPaperTableEvidenceContext(StringBuilder context,
                                                 List<ReviewPaperEvidenceTable> tables,
                                                 boolean zh,
                                                 int maxChars) {
        context.append(zh ? "# \u591a\u6587\u732e\u5408\u5e76\u603b\u7ed3\u8868\uff08\u9010\u6587\u732e\u4fdd\u7559\uff09\n" : "# Merged Summary Table (Per-Paper Rows Preserved)\n");
        appendMergedPaperTableMarkdown(context, tables, maxChars);
        context.append("\n\n");
        context.append(zh ? "# \u5355\u7bc7\u6587\u732e\u6458\u8981\u4e0e\u6d53\u5ea6\u4e13\u9879\u7ed3\u8bba\n" : "# Per-Paper Summaries and Concentration Findings\n");
        appendPerPaperBriefs(context, tables, maxChars);
    }

    private void appendPaperReportOutputRequirements(StringBuilder context, boolean zh) {
        context.append(zh ? "# \u8f93\u51fa\u8981\u6c42\n" : "# Output Requirements\n");
        if (zh) {
            context.append("""
                    \u8bf7\u57fa\u4e8e\u4e0a\u9762\u7684\u5408\u5e76\u8868\u548c\u9010\u7bc7\u6458\u8981\u8f93\u51fa\u8be6\u7ec6\u7684\u4e2d\u6587 Markdown \u7efc\u8ff0\u62a5\u544a\u3002\u4e0d\u8981\u5199\u5927\u89c4\u6a21\u6587\u732e\u5904\u7406\u6216 LLM \u4e0a\u4e0b\u6587\u6280\u672f\u8bf4\u660e\u3002
                    \u62a5\u544a\u9700\u4ece\u7efc\u8ff0\u5f52\u7eb3\u89d2\u5ea6\u8be6\u7ec6\u5c55\u5f00\uff0c\u81f3\u5c11\u8986\u76d6:
                    1. \u603b\u4f53\u7ed3\u8bba\u548c\u4e3b\u8981\u89c4\u5f8b;
                    2. \u5316\u5408\u7269\u7c7b\u522b\u3001\u6765\u6e90\u4e0e\u6291\u83cc\u6d53\u5ea6/\u6d3b\u6027\u6a21\u5f0f;
                    3. \u4f5c\u7528\u75c5\u539f\u83cc\u3001\u8bd5\u9a8c\u65b9\u6cd5\u548c\u53ef\u6bd4\u6027\u5206\u5e03;
                    4. \u53ef\u80fd\u673a\u5236\u3001\u7ec6\u80de\u6bd2\u6027/\u5b89\u5168\u6027\u548c\u4e13\u5229\u4fe1\u606f;
                    5. \u8de8\u6587\u732e\u5171\u6027\u3001\u5dee\u5f02\u548c\u89c4\u5f8b\u603b\u7ed3;
                    6. \u8bc1\u636e\u7f3a\u53e3\u3001\u672a\u63d0\u53ca\u5b57\u6bb5\u548c\u4f7f\u7528\u6ce8\u610f\u4e8b\u9879;
                    7. \u53c2\u8003\u4f9d\u636e\u3002
                    \u6bcf\u4e2a\u5173\u952e\u7ed3\u8bba\u3001\u8bc1\u636e\u8868\u884c\u6216\u627f\u8f7d\u8bc1\u636e\u7684\u6bb5\u843d\u672b\u5c3e\u5fc5\u987b\u4f7f\u7528\u73b0\u6709\u5f15\u7528\u683c\u5f0f: {source=<\u6587\u732e\u6807\u9898>; chunk=<chunk_id>; quote=<\u8bc1\u636e\u6458\u8981>}\u3002
                    \u5f15\u7528\u503c\u4ec5\u80fd\u6765\u81ea\u4e0a\u4e0b\u6587\u4e2d\u7684 Citation/source chunk/title/summary \u5b57\u6bb5\uff0c\u4fdd\u7559\u62c9\u4e01\u540d\u3001\u5316\u5408\u7269\u540d\u548c\u6d53\u5ea6\u5355\u4f4d\uff0c\u7981\u6b62\u8f93\u51fa\u4e71\u7801\u5f15\u7528\u5b57\u6bb5\u3002
                    """);
        } else {
            context.append("""
                    Write a detailed English Markdown review report from the merged table and per-paper briefs. Do not include large-corpus processing or LLM context-window technical notes.
                    Cover:
                    1. Overall conclusion and main patterns;
                    2. Compound classes, source types, and antimicrobial concentration/activity patterns;
                    3. Target pathogen, assay method, and comparability distribution;
                    4. Mechanism/target, cytotoxicity/safety, and patent evidence;
                    5. Cross-paper commonalities, differences, and recurring rules;
                    6. Evidence gaps, not-mentioned fields, and usage cautions;
                    7. Reference basis.
                    Every key conclusion, evidence table row, or evidence-bearing paragraph must end with this citation token format: {source=<paper title>; chunk=<chunk_id>; quote=<short evidence summary>}.
                    Use citation values only from the supplied Citation/source chunk/title/summary fields. Preserve Latin names, compound names, concentrations, and units without garbled characters.
                    """);
        }
    }

    private void appendMergedPaperTableMarkdown(StringBuilder out,
                                                List<ReviewPaperEvidenceTable> tables,
                                                int maxChars) {
        List<String> headers = new ArrayList<>();
        headers.add("\u6587\u732e");
        headers.addAll(ANTIMICROBIAL_COMPOUND_HEADERS);
        headers.add("Citation");
        out.append("| ").append(headers.stream().map(this::cell).collect(Collectors.joining(" | "))).append(" |\n");
        out.append("|").append(headers.stream().map(header -> "---").collect(Collectors.joining("|"))).append("|\n");
        int omittedRows = 0;
        for (ReviewPaperEvidenceTable table : tables == null ? List.<ReviewPaperEvidenceTable>of() : tables) {
            String title = safeDefault(table.documentTitle(),
                    table.documentId() == null ? "unknown" : table.documentId().toString());
            List<List<String>> rows = table.rows() == null || table.rows().isEmpty()
                    ? List.of(List.of())
                    : table.rows();
            for (List<String> sourceRow : rows) {
                List<String> values = new ArrayList<>();
                values.add(title);
                for (int column = 0; column < ANTIMICROBIAL_COMPOUND_HEADERS.size(); column++) {
                    String value = sourceRow != null && column < sourceRow.size() ? sourceRow.get(column) : null;
                    values.add(safeDefault(value, "\u672a\u63d0\u53ca"));
                }
                values.add(sourceCitation(table));
                String line = "| " + values.stream().map(this::cell).collect(Collectors.joining(" | ")) + " |\n";
                if (out.length() + line.length() > maxChars) {
                    omittedRows++;
                    continue;
                }
                out.append(line);
            }
        }
        if (omittedRows > 0) {
            out.append("\n").append("Omitted merged-table rows due to report context budget: ").append(omittedRows).append(".\n");
        }
    }

    private void appendPerPaperBriefs(StringBuilder out,
                                      List<ReviewPaperEvidenceTable> tables,
                                      int maxChars) {
        int omittedPapers = 0;
        for (ReviewPaperEvidenceTable table : tables == null ? List.<ReviewPaperEvidenceTable>of() : tables) {
            String title = safeDefault(table.documentTitle(),
                    table.documentId() == null ? "unknown" : table.documentId().toString());
            String warnings = table.warnings() == null || table.warnings().isEmpty()
                    ? "\u672a\u63d0\u53ca"
                    : String.join("; ", table.warnings());
            String brief = """
                    ## %s
                    Paper summary: %s
                    Concentration summary: %s
                    Confidence: %.3f
                    Warnings: %s
                    Citation: %s

                    """.formatted(
                    title,
                    shortText(safeDefault(table.paperSummary(), "\u672a\u63d0\u53ca"), 1200),
                    shortText(safeDefault(table.concentrationSummary(),
                            safeDefault(table.concentrationDocument(), "\u672a\u63d0\u53ca")), 1200),
                    table.confidence(),
                    shortText(warnings, 800),
                    sourceCitation(table)
            );
            if (out.length() + brief.length() > maxChars) {
                omittedPapers++;
                continue;
            }
            out.append(brief);
        }
        if (omittedPapers > 0) {
            out.append("Omitted per-paper briefs due to report context budget: ").append(omittedPapers).append(".\n");
        }
    }

    private String paperEvidenceTableSynthesis(List<ReviewPaperEvidenceTable> tables, boolean zh) {
        StringBuilder out = new StringBuilder();
        if (zh) {
            out.append("\u672c\u8282\u4ee5\u6bcf\u7bc7\u6587\u732e\u7684\u6700\u4f73\u8bc1\u636e\u8868\u4e3a\u4e3b\u8981\u4f9d\u636e\u8fdb\u884c\u7efc\u5408\uff0c\u5171\u7eb3\u5165 ")
                    .append(tables.size()).append(" \u7bc7\u6587\u732e\u3002");
        } else {
            out.append("This section aggregates the best evidence table from each matched paper. It includes ")
                    .append(tables.size()).append(" papers.");
        }
        out.append("\n\n");
        for (ReviewPaperEvidenceTable table : tables) {
            String title = safeDefault(table.documentTitle(),
                    table.documentId() == null ? "unknown" : table.documentId().toString());
            String citation = sourceCitation(table);
            out.append("### ").append(title).append("\n\n");
            out.append(safeDefault(table.paperSummary(), "-")).append(" ").append(citation).append("\n\n");
            List<String> headers = table.headers() == null || table.headers().isEmpty()
                    ? List.of("Finding", "Evidence", "Source")
                    : table.headers();
            List<String> displayHeaders = new ArrayList<>(headers);
            displayHeaders.add(zh ? "\u5f15\u7528" : "Citation");
            out.append("| ").append(displayHeaders.stream().map(this::cell).collect(Collectors.joining(" | "))).append(" |\n");
            out.append("|").append(displayHeaders.stream().map(header -> "---").collect(Collectors.joining("|"))).append("|\n");
            List<List<String>> rows = table.rows() == null ? List.of() : table.rows();
            for (List<String> row : rows) {
                List<String> cells = new ArrayList<>(row == null ? List.of() : row);
                while (cells.size() < headers.size()) {
                    cells.add("-");
                }
                cells.add(citation);
                out.append("| ")
                        .append(cells.stream().map(this::cell).collect(Collectors.joining(" | ")))
                        .append(" |\n");
            }
            if (table.warnings() != null && !table.warnings().isEmpty()) {
                out.append("\n")
                        .append(zh ? "\u8bc1\u636e\u8868\u8b66\u544a: " : "Table warnings: ")
                        .append(String.join("; ", table.warnings()))
                        .append(" ").append(citation)
                        .append("\n");
            }
            out.append("\n");
        }
        return out.toString().trim();
    }

    private String crossPaperPatternSynthesis(List<ReviewPaperEvidenceTable> tables, boolean zh) {
        Map<String, Long> structures = limitMap(countPaperTableColumn(tables, 1), 5);
        Map<String, Long> sources = limitMap(countPaperTableColumn(tables, 2), 5);
        Map<String, Long> pathogens = limitMap(countPaperTableColumn(tables, 4), 5);
        Map<String, Long> assays = limitMap(countPaperTableColumn(tables, 5), 5);
        List<String> examples = representativeEvidenceRows(tables, zh, 5);
        StringBuilder out = new StringBuilder();
        if (zh) {
            out.append("\u4ece\u5df2\u9009\u6587\u732e\u770b\uff0c\u8bc1\u636e\u66f4\u9002\u5408\u6309\u201c\u5316\u5408\u7269\u7c7b\u522b-\u6765\u6e90-\u6d53\u5ea6/\u6d3b\u6027-\u75c5\u539f\u83cc-\u65b9\u6cd5\u201d\u8fdb\u884c\u6a2a\u5411\u6bd4\u8f83\uff0c\u800c\u4e0d\u5b9c\u4ec5\u6309\u5355\u7bc7\u7ed3\u8bba\u6392\u5217\u3002")
                    .append(firstPaperTableCitation(tables)).append("\n\n");
            out.append("- \u7ed3\u6784\u7c7b\u578b\u5206\u5e03: ").append(formatCounts(structures)).append("\n");
            out.append("- \u5316\u5408\u7269\u6765\u6e90\u5206\u5e03: ").append(formatCounts(sources)).append("\n");
            out.append("- \u4f5c\u7528\u75c5\u539f\u83cc\u5206\u5e03: ").append(formatCounts(pathogens)).append("\n");
            out.append("- \u8bd5\u9a8c\u65b9\u6cd5\u5206\u5e03: ").append(formatCounts(assays)).append("\n\n");
            out.append("\u5178\u578b\u8bc1\u636e\u884c\u663e\u793a\uff0c\u6d53\u5ea6\u7ed3\u679c\u5fc5\u987b\u4e0e\u5bf9\u5e94\u7684\u75c5\u539f\u83cc\u3001\u65b9\u6cd5\u548c\u5355\u4f4d\u4e00\u8d77\u89e3\u8bfb\uff1b\u4e0d\u540c\u65b9\u6cd5\u6216\u4e0d\u540c\u5355\u4f4d\u4e0b\u7684\u6570\u503c\u4e0d\u5e94\u76f4\u63a5\u6392\u5e8f\u4e3a\u5f3a\u5f31\u3002");
        } else {
            out.append("Across the selected papers, the evidence is best compared by compound class, source, concentration/activity, pathogen, and assay method rather than by isolated paper-level claims. ")
                    .append(firstPaperTableCitation(tables)).append("\n\n");
            out.append("- Structure type distribution: ").append(formatCounts(structures)).append("\n");
            out.append("- Compound source distribution: ").append(formatCounts(sources)).append("\n");
            out.append("- Target pathogen distribution: ").append(formatCounts(pathogens)).append("\n");
            out.append("- Assay method distribution: ").append(formatCounts(assays)).append("\n\n");
            out.append("Representative rows show that concentration results must be interpreted together with the pathogen, method, and units; values from different methods or units should not be directly ranked as stronger or weaker activity.");
        }
        if (!examples.isEmpty()) {
            out.append("\n\n");
            for (String example : examples) {
                out.append("- ").append(example).append("\n");
            }
        }
        return out.toString().trim();
    }

    private String paperCentricEvidenceGaps(List<ReviewPaperEvidenceTable> tables, boolean zh) {
        long missingMechanism = countMissingPaperTableColumn(tables, 6);
        long missingSafety = countMissingPaperTableColumn(tables, 7);
        long missingPatent = countMissingPaperTableColumn(tables, 9);
        long warningCount = tables == null ? 0 : tables.stream()
                .filter(table -> table.warnings() != null && !table.warnings().isEmpty())
                .count();
        StringBuilder out = new StringBuilder();
        if (zh) {
            out.append("\u4e3b\u8981\u8bc1\u636e\u7f3a\u53e3\u96c6\u4e2d\u5728\u673a\u5236\u3001\u5b89\u5168\u6027\u548c\u4e13\u5229\u4fe1\u606f\u7684\u4e0d\u5b8c\u6574\u62a5\u9053: ")
                    .append(firstPaperTableCitation(tables)).append("\n\n");
            out.append("- \u672a\u63d0\u53ca\u6216\u7f3a\u4e4f\u53ef\u7528\u673a\u5236/\u9776\u6807\u4fe1\u606f\u7684\u8bc1\u636e\u884c: ").append(missingMechanism).append("\n");
            out.append("- \u672a\u63d0\u53ca\u6216\u7f3a\u4e4f\u7ec6\u80de\u6bd2\u6027/\u5b89\u5168\u6027\u4fe1\u606f\u7684\u8bc1\u636e\u884c: ").append(missingSafety).append("\n");
            out.append("- \u672a\u63d0\u53ca\u6216\u7f3a\u4e4f\u4e13\u5229\u4fe1\u606f\u7684\u8bc1\u636e\u884c: ").append(missingPatent).append("\n");
            out.append("- \u542b\u8bc1\u636e\u8868\u8b66\u544a\u7684\u6587\u732e\u6570: ").append(warningCount).append("\n\n");
            out.append("\u56e0\u6b64\uff0c\u62a5\u544a\u4e2d\u5173\u4e8e\u6d3b\u6027\u6f5c\u529b\u7684\u7ed3\u8bba\u5e94\u4e0e\u5177\u4f53\u6d53\u5ea6\u3001\u5b9e\u9a8c\u4f53\u7cfb\u548c\u539f\u6587\u6458\u8981\u540c\u65f6\u9605\u8bfb\uff0c\u673a\u5236\u6216\u5b89\u5168\u6027\u672a\u88ab\u8bc1\u660e\u65f6\u4e0d\u5e94\u88ab\u8868\u8ff0\u4e3a\u786e\u5b9a\u56e0\u679c\u3002");
        } else {
            out.append("The main gaps are concentrated in incomplete mechanism, safety, and patent reporting: ")
                    .append(firstPaperTableCitation(tables)).append("\n\n");
            out.append("- Evidence rows without usable mechanism/target information: ").append(missingMechanism).append("\n");
            out.append("- Evidence rows without usable cytotoxicity/safety information: ").append(missingSafety).append("\n");
            out.append("- Evidence rows without usable patent information: ").append(missingPatent).append("\n");
            out.append("- Papers with evidence table warnings: ").append(warningCount).append("\n\n");
            out.append("Activity claims should therefore be read alongside the exact concentration, assay system, and source summary. Mechanistic or safety conclusions should not be framed as causal when the source table marks them as not mentioned or incomplete.");
        }
        return out.toString().trim();
    }

    private String paperTableReferences(List<ReviewPaperEvidenceTable> tables, boolean zh) {
        StringBuilder out = new StringBuilder();
        List<ReviewPaperEvidenceTable> safeTables = tables == null ? List.of() : tables;
        for (int i = 0; i < safeTables.size(); i++) {
            ReviewPaperEvidenceTable table = safeTables.get(i);
            out.append(i + 1)
                    .append(". ")
                    .append(safeDefault(table.documentTitle(),
                            table.documentId() == null ? "unknown" : table.documentId().toString()))
                    .append(" ")
                    .append(sourceCitation(table))
                    .append("\n");
        }
        if (out.length() == 0) {
            return zh ? "\u6682\u65e0\u53ef\u5f15\u7528\u6587\u732e\u3002" : "No citable papers are available.";
        }
        return out.toString().trim();
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

    private boolean shouldIncludeAntimicrobialCompoundAnalysis(QueryAnalysis analysis,
                                                              List<CompoundActivityRow> compoundRows) {
        if (compoundRows == null || compoundRows.isEmpty()) {
            return mentionsAntimicrobialIntent(analysis);
        }
        return mentionsAntimicrobialIntent(analysis)
                || compoundRows.stream().anyMatch(row -> mentioned(row.antimicrobialActivity()));
    }

    private boolean mentionsAntimicrobialIntent(QueryAnalysis analysis) {
        if (analysis == null) {
            return false;
        }
        String text = String.join(" ",
                safe(analysis.mainQuestion()),
                safe(analysis.displayMainQuestion()),
                analysis.subQuestions() == null ? "" : String.join(" ", analysis.subQuestions()),
                analysis.displaySubQuestions() == null ? "" : String.join(" ", analysis.displaySubQuestions()),
                analysis.keyEntities() == null ? "" : String.join(" ", analysis.keyEntities()),
                analysis.keyConcepts() == null ? "" : String.join(" ", analysis.keyConcepts()));
        String normalized = text.toLowerCase();
        return normalized.contains("抑菌")
                || normalized.contains("抗菌")
                || normalized.contains("抗真菌")
                || normalized.contains("杀菌")
                || normalized.contains("防效")
                || normalized.contains("菌丝")
                || normalized.contains("antimicrobial")
                || normalized.contains("antibacterial")
                || normalized.contains("antifungal")
                || normalized.contains("anti-fungal")
                || normalized.contains("fungicidal")
                || normalized.contains("mycelial")
                || normalized.contains("mic")
                || normalized.contains("ec50");
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

    private String heading(int section, boolean zh, String zhText, String enText) {
        return "## " + (zh ? chineseSectionNumber(section) + "、" + zhText : section + ". " + enText) + "\n\n";
    }

    private String heading(boolean zh, String zhText, String enText) {
        return "## " + (zh ? zhText : enText) + "\n\n";
    }

    private String chineseSectionNumber(int section) {
        return switch (section) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            case 5 -> "五";
            case 6 -> "六";
            case 7 -> "七";
            case 8 -> "八";
            case 9 -> "九";
            default -> String.valueOf(section);
        };
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

    private String compoundClassAnalysis(List<CompoundActivityRow> rows, boolean zh) {
        if (rows == null || rows.isEmpty()) {
            return zh
                    ? "当前证据集中没有可汇总的抑菌化合物记录。"
                    : "The current evidence set contains no compound activity records for aggregation.";
        }

        Map<String, Long> byStructure = countDelimited(rows, CompoundActivityRow::structureType);
        Map<String, Long> bySource = countDelimited(rows, CompoundActivityRow::source);
        Map<String, Long> byPathogen = countDelimited(rows, CompoundActivityRow::targetPathogen);
        List<String> activityExamples = rows.stream()
                .filter(row -> mentioned(row.antimicrobialActivity()))
                .limit(5)
                .map(row -> row.compoundName() + ": " + row.antimicrobialActivity())
                .toList();
        List<String> notableRows = rows.stream()
                .filter(row -> mentioned(row.mechanism())
                        || mentioned(row.cytotoxicitySafety())
                        || mentioned(row.patentStatus()))
                .limit(5)
                .map(row -> row.compoundName()
                        + " | mechanism=" + row.mechanism()
                        + " | safety=" + row.cytotoxicitySafety()
                        + " | patent=" + row.patentStatus())
                .toList();

        StringBuilder out = new StringBuilder();
        if (zh) {
            out.append("本次证据集中可汇总的化合物或化合物衍生物组共有 ")
                    .append(rows.size()).append(" 组。");
            out.append("结构类型分布：").append(formatCounts(limitMap(byStructure, 8))).append("。");
            out.append("来源分布：").append(formatCounts(limitMap(bySource, 8))).append("。");
            out.append("作用病原菌覆盖：").append(formatCounts(limitMap(byPathogen, 8))).append("。");
            if (!activityExamples.isEmpty()) {
                out.append("\n\n代表性抑菌浓度记录：\n");
                appendNumbered(out, activityExamples);
            }
            if (!notableRows.isEmpty()) {
                out.append("\n\n差异化个例：\n");
                appendNumbered(out, notableRows);
            }
            out.append("\n\n注意：EC50、MIC、抑制率、菌丝生长率和防效来自不同试验体系时不可直接排序；报告仅按原文条件进行结构化展示。");
        } else {
            out.append("The evidence set contains ").append(rows.size())
                    .append(" compound or derivative groups. ");
            out.append("Structure classes: ").append(formatCounts(limitMap(byStructure, 8))).append(". ");
            out.append("Sources: ").append(formatCounts(limitMap(bySource, 8))).append(". ");
            out.append("Target pathogens: ").append(formatCounts(limitMap(byPathogen, 8))).append(". ");
            if (!activityExamples.isEmpty()) {
                out.append("\n\nRepresentative activity records:\n");
                appendNumbered(out, activityExamples);
            }
            if (!notableRows.isEmpty()) {
                out.append("\n\nNotable outliers or differentiators:\n");
                appendNumbered(out, notableRows);
            }
            out.append("\n\nEC50, MIC, inhibition rate, mycelial growth, and control efficacy are not directly ranked across assay systems; this report preserves source conditions.");
        }
        return out.toString();
    }

    private String paradigmDetailSection(List<SynthesizedCompoundRecord> records, boolean zh) {
        StringBuilder out = new StringBuilder();
        out.append(zh ? "### 化合物→范式详细视图\n\n" : "### Compound → Paradigm Detail View\n\n");

        for (SynthesizedCompoundRecord rec : records) {
            if (rec.paradigmActivities() == null || rec.paradigmActivities().isEmpty()) continue;
            String name = rec.compoundName() != null ? rec.compoundName() : "unknown";
            String role = rec.role() != null ? " [" + rec.role().name() + "]" : "";
            out.append("**").append(name).append(role).append("**\n\n");

            for (ParadigmActivityBlock block : rec.paradigmActivities()) {
                out.append("- ").append(block.paradigm());
                if (block.keyMetric() != null && block.keyMetric().type() != null) {
                    out.append(": ").append(block.keyMetric().type())
                            .append(" = ").append(block.keyMetric().value() != null ? block.keyMetric().value() : "N/A");
                }
                if (block.doseDependent() != null && block.doseDependent()) {
                    out.append(zh ? " (剂量依赖)" : " (dose-dependent)");
                }
                if (block.durability() != null) {
                    out.append(" [").append(block.durability()).append("]");
                }
                out.append("\n");

                if (block.doseGradient() != null && !block.doseGradient().isEmpty()) {
                    out.append(zh ? "  - 剂量梯度：" : "  - Dose gradient: ");
                    out.append(block.doseGradient().stream()
                            .map(dr -> dr.concentration() + " → " + (dr.effect() != null ? dr.effect() : ""))
                            .collect(Collectors.joining("; ")));
                    out.append("\n");
                }
            }

            if (rec.comparisons() != null && !rec.comparisons().isEmpty()) {
                out.append(zh ? "  - 比较关系：" : "  - Comparisons: ");
                for (ComparativeRelation cr : rec.comparisons()) {
                    out.append(cr.relation()).append(" vs ").append(cr.referenceCompound());
                    if (cr.derivedEquivalence() != null) out.append(" (").append(cr.derivedEquivalence()).append(")");
                    out.append("; ");
                }
                out.append("\n");
            }

            if (rec.coverageWarnings() != null && !rec.coverageWarnings().isEmpty()) {
                out.append(zh ? "  - ⚠ 覆盖预警：" : "  - ⚠ Coverage warnings: ");
                out.append(String.join(", ", rec.coverageWarnings())).append("\n");
            }
            out.append("\n");
        }
        return out.toString();
    }

    private String keyFindings(QueryAnalysis analysis,
                               List<ExtractedEvidence> evidence,
                               List<FusedEvidenceGroup> groups,
                               List<String> focusSubQuestions,
                               boolean zh) {
        StringBuilder out = new StringBuilder();
        List<String> focus = focusSubQuestions == null ? List.of() : focusSubQuestions;
        Map<String, String> displaySubQuestions = displaySubQuestionMap(analysis);
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
                String displaySubQuestion = displaySubQuestion(group.subQuestion(), displaySubQuestions);
                out.append("### ").append(displaySubQuestion).append("\n\n");
                out.append(localizedSummary(
                        group.groupSummary(),
                        displaySubQuestion,
                        evidenceForSubQuestion(group.subQuestion(), evidence),
                        zh)).append("\n\n");
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

    private Map<String, String> displaySubQuestionMap(QueryAnalysis analysis) {
        if (analysis == null || analysis.subQuestions() == null || analysis.subQuestions().isEmpty()) {
            return Map.of();
        }
        List<String> canonical = analysis.subQuestions();
        List<String> display = analysis.displaySubQuestions();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < canonical.size(); i++) {
            String canonicalQuestion = canonical.get(i);
            if (canonicalQuestion == null || canonicalQuestion.isBlank()) {
                continue;
            }
            String displayQuestion = display != null && i < display.size() ? display.get(i) : canonicalQuestion;
            mapping.put(canonicalQuestion, safeDefault(displayQuestion, canonicalQuestion));
        }
        return mapping;
    }

    private String displaySubQuestion(String canonicalSubQuestion, Map<String, String> displaySubQuestions) {
        if (canonicalSubQuestion == null || canonicalSubQuestion.isBlank()) {
            return "";
        }
        return displaySubQuestions.getOrDefault(canonicalSubQuestion, canonicalSubQuestion);
    }

    private List<ExtractedEvidence> evidenceForSubQuestion(String subQuestion, List<ExtractedEvidence> evidence) {
        if (subQuestion == null || evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        List<ExtractedEvidence> exactMatches = evidence.stream()
                .filter(item -> subQuestion.equals(item.subQuestion()))
                .toList();
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }
        String normalized = subQuestion.toLowerCase();
        return evidence.stream()
                .filter(item -> item.subQuestion() != null)
                .filter(item -> item.subQuestion().toLowerCase().contains(normalized)
                        || normalized.contains(item.subQuestion().toLowerCase()))
                .toList();
    }

    private String localizedSummary(String summary,
                                    String displaySubQuestion,
                                    List<ExtractedEvidence> evidence,
                                    boolean zh) {
        if (!zh) {
            return summary == null || summary.isBlank()
                    ? "There is not enough evidence for a synthesis."
                    : summary;
        }
        if (summary == null || summary.isBlank() || isNoEvidenceSummary(summary)) {
            return fallbackChineseSummary(displaySubQuestion, evidence);
        }
        if (containsChinese(summary)) {
            return summary;
        }
        String localized = localizeSummaryWithModel(summary, displaySubQuestion);
        return localized != null ? localized : fallbackChineseSummary(displaySubQuestion, evidence);
    }

    private String localizeSummaryWithModel(String summary, String displaySubQuestion) {
        if (reviewReportChatModel == null) {
            return null;
        }
        try {
            ChatResponse response = reviewReportChatModel.chat(
                    SystemMessage.from(PromptResources.load(PromptCatalog.REVIEW_REPORT_LOCALIZE_SUMMARY_SYSTEM)),
                    UserMessage.from("""
                            Section question:
                            %s

                            Canonical English synthesis:
                            %s
                            """.formatted(displaySubQuestion, summary))
            );
            AiMessage aiMessage = response.aiMessage();
            String localized = aiMessage == null ? null : aiMessage.text();
            if (localized == null || localized.isBlank()) {
                return null;
            }
            return localized.trim();
        } catch (Exception e) {
            log.warn("Failed to localize review section summary for '{}': {}", displaySubQuestion, e.getMessage());
            return null;
        }
    }

    private String fallbackChineseSummary(String displaySubQuestion, List<ExtractedEvidence> evidence) {
        int evidenceCount = evidence == null ? 0 : evidence.size();
        long sourceCount = evidence == null ? 0 : evidence.stream()
                .map(ExtractedEvidence::documentTitle)
                .filter(Objects::nonNull)
                .filter(title -> !title.isBlank())
                .distinct()
                .count();
        if (evidenceCount == 0) {
            return "\u5f53\u524d\u6ca1\u6709\u8db3\u591f\u8bc1\u636e\u652f\u6301\u8be5\u5b50\u95ee\u9898\u7684\u7efc\u5408\u7ed3\u8bba\u3002";
        }
        return "\u56f4\u7ed5\u201c" + safeDefault(displaySubQuestion, "\u8be5\u5b50\u95ee\u9898") + "\u201d\uff0c\u5f53\u524d\u5171\u7eb3\u5165 "
                + evidenceCount + " \u6761\u8bc1\u636e\uff0c\u6d89\u53ca " + sourceCount
                + " \u7bc7\u6765\u6e90\u6587\u732e\u3002\u8be6\u7ec6\u7ed3\u8bba\u4ee5\u4e0b\u65b9\u5173\u952e\u53d1\u73b0\u8868\u548c\u53c2\u8003\u6587\u732e\u533a\u4e2d\u7684\u53ef\u8ffd\u6eaf\u8bc1\u636e\u4e3a\u51c6\u3002";
    }

    private boolean isNoEvidenceSummary(String summary) {
        String normalized = safe(summary).trim().toLowerCase();
        return normalized.equals("no evidence found for this sub-question.")
                || normalized.equals("evidence summary pending (llm fusion failed).");
    }

    private boolean containsChinese(String value) {
        return value != null && CHINESE.matcher(value).find();
    }

    private String citation(ExtractedEvidence item) {
        return "{source=" + safeDefault(item.documentTitle(), "unknown") + "; chunk=" + item.chunkId() + "}";
    }

    private String firstPaperTableCitation(List<ReviewPaperEvidenceTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return "";
        }
        return sourceCitation(tables.get(0));
    }

    private String sourceCitation(ReviewPaperEvidenceTable table) {
        String source = citationField(safeDefault(table == null ? null : table.documentTitle(),
                table == null || table.documentId() == null ? "unknown" : table.documentId().toString()));
        String chunk = citationField(primaryChunkId(table));
        String quote = citationField(shortText(firstNonBlank(
                table == null ? null : table.concentrationSummary(),
                table == null ? null : table.concentrationDocument(),
                table == null ? null : table.paperSummary(),
                firstRepresentativeRowSummary(table),
                "Evidence summarized from this paper"), 120));
        return "{source=" + source + "; chunk=" + chunk + "; quote=" + quote + "}";
    }

    private String primaryChunkId(ReviewPaperEvidenceTable table) {
        if (table != null && table.sourceChunkIds() != null) {
            return table.sourceChunkIds().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse("paper-table");
        }
        return "paper-table";
    }

    private String citationField(String value) {
        return safeDefault(value, "unknown")
                .replace("{", "")
                .replace("}", "")
                .replace(";", ",")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
    }

    private String firstRepresentativeRowSummary(ReviewPaperEvidenceTable table) {
        if (table == null || table.rows() == null) {
            return "";
        }
        for (List<String> row : table.rows()) {
            String compound = rowValue(row, 0);
            String concentration = rowValue(row, 3);
            String pathogen = rowValue(row, 4);
            String method = rowValue(row, 5);
            List<String> parts = new ArrayList<>();
            parts.add(compound);
            parts.add(concentration);
            parts.add(pathogen);
            parts.add(method);
            String summary = parts.stream()
                    .filter(this::mentioned)
                    .collect(Collectors.joining("; "));
            if (!summary.isBlank()) {
                return summary;
            }
        }
        return "";
    }

    private Map<String, Long> countPaperTableColumn(List<ReviewPaperEvidenceTable> tables, int column) {
        return paperTableRows(tables).stream()
                .map(row -> rowValue(row, column))
                .filter(this::mentioned)
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
    }

    private long countMissingPaperTableColumn(List<ReviewPaperEvidenceTable> tables, int column) {
        return paperTableRows(tables).stream()
                .map(row -> rowValue(row, column))
                .filter(value -> !mentioned(value))
                .count();
    }

    private List<List<String>> paperTableRows(List<ReviewPaperEvidenceTable> tables) {
        if (tables == null) {
            return List.of();
        }
        return tables.stream()
                .filter(Objects::nonNull)
                .flatMap(table -> table.rows() == null ? List.<List<String>>of().stream() : table.rows().stream())
                .toList();
    }

    private List<String> representativeEvidenceRows(List<ReviewPaperEvidenceTable> tables, boolean zh, int limit) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        List<String> examples = new ArrayList<>();
        for (ReviewPaperEvidenceTable table : tables) {
            List<List<String>> rows = table.rows() == null ? List.of() : table.rows();
            for (List<String> row : rows) {
                String compound = safeDefault(rowValue(row, 0), "-");
                String concentration = safeDefault(rowValue(row, 3), "-");
                String pathogen = safeDefault(rowValue(row, 4), "-");
                String assay = safeDefault(rowValue(row, 5), "-");
                if (!mentioned(compound) && !mentioned(concentration) && !mentioned(pathogen) && !mentioned(assay)) {
                    continue;
                }
                if (zh) {
                    examples.add("\u4ee3\u8868\u884c: " + compound + " | " + concentration + " | "
                            + pathogen + " | " + assay + " " + sourceCitation(table));
                } else {
                    examples.add("Representative row: " + compound + " | " + concentration + " | "
                            + pathogen + " | " + assay + " " + sourceCitation(table));
                }
                if (examples.size() >= limit) {
                    return examples;
                }
            }
        }
        return examples;
    }

    private String rowValue(List<String> row, int column) {
        return row != null && column >= 0 && column < row.size() ? row.get(column) : null;
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

    private Map<String, Long> countDelimited(List<CompoundActivityRow> rows,
                                             Function<CompoundActivityRow, String> extractor) {
        return rows.stream()
                .map(extractor)
                .filter(this::mentioned)
                .flatMap(value -> List.of(value.split(";")).stream())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
    }

    private boolean mentioned(String value) {
        return value != null
                && !value.isBlank()
                && !"-".equals(value)
                && !CompoundEvidenceAggregator.NOT_MENTIONED.equals(value)
                && !"not mentioned".equalsIgnoreCase(value)
                && !"not reported".equalsIgnoreCase(value);
    }

    private void appendNumbered(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            out.append(i + 1).append(". ").append(values.get(i)).append("\n");
        }
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
