package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.model.EvidenceModels;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

@Component
public class EvidenceProfileRegistry {

    private final Map<String, EvidenceProfile> profiles;

    public EvidenceProfileRegistry() {
        Map<String, EvidenceProfile> values = new LinkedHashMap<>();
        add(values, profile("Q1", "化合物抑菌证据", EvidenceModels.HEADERS, List.of(0, 5, 6),
                "化合物、提取物、混合物、农药或衍生物对卵菌的活性证据",
                "一个化合物-测试卵菌-实验方法组合",
                "多个卵菌或实验方法分别成行；同一方法的多个浓度或时间点合并；Markush系列只保留母核和最多3个最优代表。"));
        add(values, profile("Q2", "效应子致病机制", List.of(
                        "效应子名称", "别名/同源基因", "效应子家族", "来源卵菌种", "菌株/分离物",
                        "基因ID/登录号", "氨基酸长度", "信号肽存在", "保守结构域", "效应子类型",
                        "寄主靶标蛋白", "靶标功能", "作用机制", "亚细胞定位", "诱导表达条件",
                        "表达验证方法", "功能验证表型", "是否被植物识别(AVR活性)", "同源物存在",
                        "参考文献", "专利信息", "备注"), List.of(0),
                "卵菌效应子、寄主靶标、作用机制、定位、表达和功能验证证据",
                "一个效应子-卵菌种-寄主靶标组合",
                "多个寄主靶标或卵菌种分别成行；综述仅抽取明确引用的原始研究并在备注标明。"));
        add(values, profile("Q3", "卵菌抗性", List.of(
                        "抗性类型", "基因/位点名称", "别名/同源基因", "植物物种(拉丁名)", "植物品种/品系",
                        "抗性对象(卵菌种/菌株)", "识别无毒基因(AVR)", "抗性谱(小种范围)", "基因类型",
                        "核苷酸结合结构域", "染色体位置", "克隆状态", "抗性机制(分子层面)", "抗性水平",
                        "等位基因变异", "抗性丧失情况", "是否涉及PTI/ETI", "信号通路(SA/JA/ET)",
                        "转基因/基因编辑应用", "分子标记", "育种应用", "功能验证方法", "表达模式",
                        "参考文献", "专利信息", "备注"), List.of(0, 1, 3),
                "寄主抗性、非寄主抗性及部分或数量抗性的基因、QTL、机制和验证证据",
                "一个基因或机制-植物物种-抗性类型组合",
                "不同病原对象、等位基因或抗性类型分别成行；QTL和GWAS候选必须注明克隆状态。"));
        add(values, profile("Q4", "杀菌剂抗性", List.of(
                        "杀菌剂通用名", "杀菌剂类型(FRAC代码)", "作用靶标", "靶向卵菌种", "菌株/分离物",
                        "抗性突变基因", "突变位点(氨基酸变化)", "突变类型", "抗性水平(EC50比值)",
                        "田间发生情况", "分子检测方法", "交叉抗性", "抗性治理建议", "参考文献",
                        "专利信息", "备注"), List.of(0, 3),
                "卵菌对杀菌剂的抗性、靶标突变、抗性水平、田间分布和检测证据",
                "一个药剂-卵菌种-突变位点组合",
                "不同突变位点或卵菌种分别成行。"));
        add(values, profile("Q5", "卵菌基因组与效应子组", List.of(
                        "卵菌种(拉丁名)", "菌株/分离物", "基因组大小(Mb)", "Contig N50(kb)", "基因总数",
                        "效应子总数", "RXLR数目", "CRN数目", "其他效应子家族", "特有基因家族",
                        "重复序列比例(%)", "参考基因组版本", "参考文献", "备注"), List.of(0),
                "卵菌基因组组装、基因数量和效应子组统计证据",
                "一个卵菌种-菌株组合",
                "同一物种不同菌株分别成行；未报道的效应子统计留空。"));
        add(values, profile("Q6", "生物防治", List.of(
                        "生防剂名称", "生防剂类型", "来源(菌株/植物种)", "靶向卵菌种", "作用方式",
                        "抑菌机制", "离体活性数据", "活体/田间防效(%)", "应用方式", "与化学药剂协同性",
                        "参考文献", "专利信息", "备注"), List.of(0, 3),
                "微生物、植物提取物或诱导剂防治卵菌病害的离体、活体或田间证据",
                "一个生防剂-靶向卵菌组合",
                "不同卵菌或应用方式分别成行。"));
        add(values, profile("Q7", "病害诊断与分子检测", List.of(
                        "靶标卵菌种", "检测靶标基因", "检测技术", "引物/探针名称", "引物/探针序列(5'→3')",
                        "扩增片段大小(bp)", "灵敏度(检出限)", "特异性(与其他种交叉)", "适用样本类型",
                        "参考文献", "专利信息", "备注"), List.of(0, 2),
                "卵菌病原的PCR、qPCR、LAMP、RPA等诊断或分子检测证据",
                "一个检测靶标-技术组合",
                "同一靶标不同检测技术分别成行。"));
        add(values, profile("Q8", "病害流行与预测模型", List.of(
                        "病害名称", "病原卵菌种", "主要寄主", "有利环境因子", "预测模型名称",
                        "模型输入变量", "预测输出", "模型准确率/验证", "参考文献", "备注"), List.of(0, 1, 4),
                "卵菌病害流行环境因子、风险预警和预测模型的建立或验证证据",
                "一个病害-模型组合",
                "同一病害不同模型分别成行。"));
        add(values, profile("Q9", "病原菌多样性与生理小种", List.of(
                        "卵菌种", "寄主作物", "生理小种/致病型名称", "鉴别寄主品种", "无毒基因型(Avr)",
                        "对应抗病基因(R)", "地理分布", "来源样本", "参考文献", "备注"), List.of(0, 2),
                "卵菌病原群体中的生理小种、致病型、Avr-R对应关系和地理分布证据",
                "一个卵菌种-寄主-小种组合",
                "不同小种或地区分别成行。"));
        add(values, profile("Q10", "前沿技术", List.of(
                        "技术类型", "靶向基因/序列", "靶向对象(卵菌种/寄主)", "递送/转化方法",
                        "体外/体内效果", "对致病性的影响", "抗性表现", "参考文献", "专利信息", "备注"),
                List.of(0, 1, 2),
                "RNAi、CRISPR/Cas9、基因沉默或转基因等技术用于卵菌或寄主抗性的实验证据",
                "一个技术-靶向对象组合",
                "不同靶基因或靶向对象分别成行。"));
        profiles = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public List<EvidenceProfile> all() {
        return profiles.values().stream().toList();
    }

    public EvidenceProfile require(String questionId) {
        EvidenceProfile profile = profiles.get(questionId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown evidence question: " + questionId);
        }
        return profile;
    }

    private void add(Map<String, EvidenceProfile> values, EvidenceProfile profile) {
        values.put(profile.questionId(), profile);
    }

    private EvidenceProfile profile(String id, String title, List<String> headers,
                                    List<Integer> primaryFieldIndexes, String scope,
                                    String rowUnit, String splitRules) {
        return new EvidenceProfile(id, title, List.copyOf(headers), List.copyOf(primaryFieldIndexes),
                scope, rowUnit, splitRules, guidance(id));
    }

    private String guidance(String questionId) {
        return switch (questionId) {
            case "Q1" -> """
                    来源类别只使用植物天然产物、微生物、化学合成、商品化、半合成。
                    活性数据原样保留EC50、IC50、MIC、浓度、时间、抑制率或田间防效及单位。
                    标准名称无原文或公认依据时留空；阳性对照必须同时记录名称和对应数据。
                    """;
            case "Q2" -> """
                    效应子家族包括RXLR、CRN、NLPP、GP15、elicitin等；类型为胞内或胞外。
                    功能表型必须说明过表达、沉默或敲除对致病性的影响；AVR活性记录对应R基因识别。
                    """;
            case "Q3" -> """
                    抗性类型只使用寄主抗性、非寄主抗性、部分/数量抗性。
                    PTI/ETI和SA/JA/ET仅在原文有证据时填写；QTL、GWAS候选及克隆状态必须区分。
                    """;
            case "Q4" -> """
                    保留FRAC代码、靶标基因、氨基酸变化、EC50抗性倍数、田间地区和分子检测方法。
                    无明确突变位点时该单元格留空，不得根据药剂类别推断。
                    """;
            case "Q5" -> """
                    基因组大小、N50、基因和效应子数量必须保留原单位与组装版本。
                    初步组装未分析效应子时相关统计留空。
                    """;
            case "Q6" -> """
                    区分离体活性与温室/大田防效，并保留应用方式、剂量、协同药剂及实验条件。
                    生防剂可以是细菌、真菌、放线菌、植物提取物或诱导剂。
                    """;
            case "Q7" -> """
                    引物和探针序列按5'到3'原样抄录；保留检出限、扩增片段和近缘种交叉反应。
                    """;
            case "Q8" -> """
                    必须区分环境相关性描述与真正的预测模型；记录输入变量、预测输出和独立验证指标。
                    """;
            case "Q9" -> """
                    生理小种或致病型必须有鉴别寄主、Avr/R关系、地理或样本证据之一，不把一般群体多样性强行写成小种。
                    """;
            case "Q10" -> """
                    技术类型包括RNAi、CRISPR/Cas9、基因沉默和转基因；保留靶序列、递送方式、表达变化和致病表型。
                    """;
            default -> "";
        };
    }

    public record EvidenceProfile(
            String questionId,
            String title,
            List<String> headers,
            List<Integer> primaryFieldIndexes,
            String scope,
            String rowUnit,
            String splitRules,
            String guidance
    ) {
    }
}
