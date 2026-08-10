package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Q1PromptOnlyMarkdownParserTest {

    private EvidenceProfileRegistry registry;
    private Q1PromptOnlyMarkdownParser parser;

    @BeforeEach
    void setUp() {
        MultiProfileOutputValidator outputValidator = new MultiProfileOutputValidator();
        registry = new EvidenceProfileRegistry();
        parser = new Q1PromptOnlyMarkdownParser(outputValidator);
    }

    @Test
    void parsesQ1MarkdownTableWithoutAnchorsAsUnverified() {
        String markdown = """
                | 化合物原文名称 | 化合物标准名称 | 结构类型 | 来源类别 | 来源具体描述 | 测试卵菌拉丁名 | 实验方法 | 活性数据 | 阳性对照 | 作用靶标/机制 | 靶标验证方法 | 细胞毒性 | 抗性/交叉抗性 | 协同增效 | 参考文献 | 专利信息 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | acremoxanthone E (1) | | heterodimeric polyketides | microbial | Acremonium camptosporum | Pythium aphanidermatum | radial growth assay | IC50 [μM] 5.9 | Metalaxyl 0.2 | | | | | | Acremoxanthone E paper | |
                """;

        List<ValidatedEvidenceRow> rows = parser.parse(markdown, registry.require("Q1"));

        assertEquals(1, rows.size());
        ValidatedEvidenceRow row = rows.getFirst();
        assertEquals(16, row.cells().size());
        assertEquals("acremoxanthone E (1)", row.cells().getFirst());
        assertEquals("", row.cells().get(1));
        assertTrue(row.anchors().isEmpty());
        assertEquals(ValidationStatus.UNVERIFIED, row.validationStatus());
    }

    @Test
    void padsShortRowsWithEmptyCells() {
        String markdown = """
                | 化合物原文名称 | 化合物标准名称 | 结构类型 | 来源类别 | 来源具体描述 | 测试卵菌拉丁名 | 实验方法 | 活性数据 | 阳性对照 | 作用靶标/机制 | 靶标验证方法 | 细胞毒性 | 抗性/交叉抗性 | 协同增效 | 参考文献 | 专利信息 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | compound A | | polyketide |
                """;

        List<ValidatedEvidenceRow> rows = parser.parse(markdown, registry.require("Q1"));

        assertEquals(1, rows.size());
        assertEquals(16, rows.getFirst().cells().size());
        assertEquals("polyketide", rows.getFirst().cells().get(2));
        assertEquals("", rows.getFirst().cells().get(15));
    }

    @Test
    void skipsRowsWithTooManyCells() {
        String markdown = """
                | 化合物原文名称 | 化合物标准名称 | 结构类型 | 来源类别 | 来源具体描述 | 测试卵菌拉丁名 | 实验方法 | 活性数据 | 阳性对照 | 作用靶标/机制 | 靶标验证方法 | 细胞毒性 | 抗性/交叉抗性 | 协同增效 | 参考文献 | 专利信息 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | a | b | c | d | e | f | g | h | i | j | k | l | m | n | o | p | q |
                """;

        assertTrue(parser.parse(markdown, registry.require("Q1")).isEmpty());
    }

    @Test
    void parsesNonQ1ProfileHeaders() {
        String markdown = """
                | Gene name | Alias/homologous gene | Gene ID/accession | Oomycete species (Latin name) | Strain/isolate | Gene functional category | Encoded protein/product | Functional validation method | Mutation phenotype (positive result) | Negative/no-effect phenotype (key) | Overexpression phenotype | Expression pattern | Upstream/downstream regulatory relationship | Biological process involved | Reference | Notes |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | PsMYB1 | PsMYBlike 2-7 | JX069980 | Phytophthora sojae | P6497; PsMYB1-silenced transformants T54, T77, T101; non-silenced T132 | growth/development | Myb transcription factor protein with R2R3 Myb DNA-binding domains | stable transformation-mediated gene silencing; qRT-PCR; DGE profiling; phenotype analysis | silenced mutants showed aberrant zoospore cleavage, reduced zoospore release, lower cyst germination, fewer oospores, and reduced zoospore-mediated virulence | mycelial growth and sporangia formation were not significantly affected | | expressed in mycelia, sporulating hyphae, zoospores, cysts, germinating cysts, and infection stages; down-regulated in PsSAK1-silenced cysts and IF1.5 h | PsMYB1 transcript depends on a functional PsSAK1 pathway; PsSAK1 regulates PsMYB1 | zoospore development; zoosporogenesis; cyst germination; oospore production; zoospore-mediated plant infection | A Myb Transcription Factor of Phytophthora sojae, Regulated by MAP Kinase PsSAK1, Is Required for Zoospore Development; DOI: 10.1371/journal.pone.0040246 | |
                """;

        List<ValidatedEvidenceRow> rows = parser.parse(markdown, registry.require("Q6"));

        assertEquals(1, rows.size());
        assertEquals(16, rows.getFirst().cells().size());
        assertEquals("PsMYB1", rows.getFirst().cells().getFirst());
        assertEquals("JX069980", rows.getFirst().cells().get(2));
        assertTrue(rows.getFirst().cells().get(8).contains("aberrant zoospore cleavage"));
        assertTrue(rows.getFirst().cells().get(12).contains("PsSAK1 regulates PsMYB1"));
    }
}
