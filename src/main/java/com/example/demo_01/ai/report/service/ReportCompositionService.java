package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.report.model.ReportAggregationModels.CompoundAggregation;
import com.example.demo_01.ai.report.model.ReportAggregationModels.MechanismEntry;
import com.example.demo_01.ai.report.model.ReportAggregationModels.ReportAggregationResult;
import com.example.demo_01.ai.report.model.ReportAggregationModels.SupplementaryStats;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ReportCompositionService {

    public String compose(String question, ReportAggregationResult aggregation) {
        if (aggregation.overview().evidenceRowCount() == 0) {
            return """
                    ## 范围与数据概览

                    当前证据库中没有与问题匹配的结构化证据记录，因此无法形成统计归纳。

                    完整数据表见 XLSX 附件。
                    """;
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append("## 范围与数据概览\n\n");
        markdown.append("针对“").append(question).append("”，当前命中 ")
                .append(aggregation.overview().evidenceRowCount()).append(" 条结构化证据，覆盖 ")
                .append(aggregation.overview().distinctCompoundCount()).append(" 个去重实体、")
                .append(aggregation.overview().documentCount()).append(" 篇来源文献和 ")
                .append(aggregation.overview().organismCount()).append(" 个测试卵菌对象。")
                .append("其中纯化合物 ").append(aggregation.overview().pureCompoundCount())
                .append(" 个、天然提取物 ").append(aggregation.overview().naturalExtractCount())
                .append(" 个、文献内局部编号 ").append(aggregation.overview().localLabelCount())
                .append(" 个。完整记录见 XLSX 附件。\n\n");

        markdown.append("## 化合物类别分布\n\n");
        appendDistribution(markdown, "来源类别", aggregation.sourceCategoryCounts());
        appendDistribution(markdown, "结构类型", aggregation.structureTypeCounts());

        markdown.append("## 测试对象覆盖\n\n");
        appendDistribution(markdown, "测试卵菌", aggregation.organismCounts());

        markdown.append("## 实验方法分布\n\n");
        appendDistribution(markdown, "实验方法", aggregation.assayMethodCounts());

        markdown.append("## 活性概览\n\n");
        if (aggregation.compounds().isEmpty()) {
            markdown.append("当前命中记录未提供可归纳的活性数据。\n\n");
        } else {
            aggregation.compounds().stream().limit(20).forEach(compound -> {
                markdown.append("- **").append(compound.displayName()).append("**（")
                        .append(kindLabel(compound.nameKind())).append("）：覆盖 ")
                        .append(compound.documentCount()).append(" 篇文献、")
                        .append(compound.evidenceRowCount()).append(" 条记录");
                if (!compound.organisms().isEmpty()) {
                    markdown.append("；测试对象包括 ")
                            .append(String.join("、", compound.organisms().stream().limit(3).toList()));
                }
                if (!compound.activitySamples().isEmpty()) {
                    markdown.append("；活性示例如 ")
                            .append(String.join("；", compound.activitySamples().stream().limit(2).toList()));
                }
                if (!compound.sourceDocuments().isEmpty()) {
                    markdown.append(" [来源文献: ")
                            .append(compound.sourceDocuments().getFirst())
                            .append("]");
                }
                markdown.append("\n");
            });
            if (aggregation.compounds().size() > 20) {
                markdown.append("\n其余 ").append(aggregation.compounds().size() - 20)
                        .append(" 个实体见 XLSX 附件。\n");
            }
            markdown.append("\n");
        }

        markdown.append("## 作用机制（已报告清单）\n\n");
        if (aggregation.mechanisms().isEmpty()) {
            markdown.append("当前命中记录中，作用机制字段大多为空；仅能在有明确记录的条目中列举已知机制。\n\n");
        } else {
            for (MechanismEntry entry : aggregation.mechanisms()) {
                markdown.append("- **").append(entry.compoundName()).append("**：")
                        .append(entry.mechanism());
                if (!entry.validationMethod().isBlank()) {
                    markdown.append("（验证方法：").append(entry.validationMethod()).append("）");
                }
                if (!entry.sourceDocument().isBlank()) {
                    markdown.append(" [来源文献: ").append(entry.sourceDocument()).append("]");
                }
                markdown.append("\n");
            }
            markdown.append("\n");
        }

        markdown.append("## 补充信息（数据有限）\n\n");
        appendSupplementary(markdown, aggregation.supplementary());

        markdown.append("## 数据局限\n\n");
        markdown.append("- 本报告仅基于结构化证据表统计，不使用外部知识补充。\n");
        markdown.append("- 不同实验方法、浓度单位和评价指标通常不能直接横向比较。\n");
        markdown.append("- 机制、细胞毒性、抗性和协同等字段在现有数据中覆盖有限，相关章节仅列举已报告内容。\n");
        markdown.append("- 文献内局部编号（如 compound 1a）仅在单篇文献内有效，不参与跨文献合并。\n");

        if (!aggregation.sourceDocuments().isEmpty()) {
            markdown.append("\n## 来源文献\n\n");
            int index = 1;
            for (String title : aggregation.sourceDocuments()) {
                markdown.append(index++).append(". ").append(title).append("\n");
            }
        }

        return markdown.toString().trim();
    }

    private void appendDistribution(StringBuilder markdown, String label, Map<String, Integer> counts) {
        markdown.append("### ").append(label).append("\n\n");
        if (counts.isEmpty()) {
            markdown.append("暂无记录。\n\n");
            return;
        }
        counts.entrySet().stream().limit(12).forEach(entry -> markdown.append("- **")
                .append(entry.getKey()).append("**：")
                .append(entry.getValue()).append(" 条\n"));
        if (counts.size() > 12) {
            markdown.append("- 其余 ").append(counts.size() - 12).append(" 类见 XLSX 附件\n");
        }
        markdown.append("\n");
    }

    private void appendSupplementary(StringBuilder markdown, SupplementaryStats stats) {
        markdown.append("- 细胞毒性记录：").append(stats.cytotoxicityCount()).append(" 条");
        if (!stats.cytotoxicitySamples().isEmpty()) {
            markdown.append("；示例 ").append(String.join("；", stats.cytotoxicitySamples()));
        }
        markdown.append("\n");
        markdown.append("- 抗性/交叉抗性记录：").append(stats.resistanceCount()).append(" 条");
        if (!stats.resistanceSamples().isEmpty()) {
            markdown.append("；示例 ").append(String.join("；", stats.resistanceSamples()));
        }
        markdown.append("\n");
        markdown.append("- 协同增效记录：").append(stats.synergyCount()).append(" 条");
        if (!stats.synergySamples().isEmpty()) {
            markdown.append("；示例 ").append(String.join("；", stats.synergySamples()));
        }
        markdown.append("\n");
        markdown.append("- 专利信息记录：").append(stats.patentCount()).append(" 条\n\n");
    }

    private String kindLabel(NameKind nameKind) {
        return switch (nameKind) {
            case PURE_COMPOUND -> "纯化合物";
            case NATURAL_EXTRACT -> "天然提取物";
            case LOCAL_LABEL -> "文献内编号";
        };
    }
}
