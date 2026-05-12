package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewEvidenceRecord;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewPaperEvidenceTable;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewStage;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewSummaryTable;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewTaskMetrics;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewTaskRecord;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewTaskStatus;
import com.example.demo_01.ai.review.model.ReviewModels.TypedEntities;
import com.example.demo_01.ai.review.service.ReviewXlsxService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReviewXlsxServiceTest {

    @Test
    void generateXlsxShouldKeepStagesOutOfGeneSheet() throws Exception {
        ReviewXlsxService service = new ReviewXlsxService();
        ReviewTaskRecord task = task();
        ReviewEvidenceRecord evidence = new ReviewEvidenceRecord(
                1L,
                task.taskId(),
                null,
                "chunk-1",
                UUID.randomUUID(),
                "Paper A",
                "claim",
                "PsMYB1 is required for zoospore development.",
                "CRISPR/Cas9",
                new TypedEntities(
                        List.of("Phytophthora sojae"),
                        List.of("PsMYB1"),
                        List.of("pathogenicity"),
                        List.of("zoospore development"),
                        List.of(),
                        List.of("CRISPR/Cas9"),
                        List.of("Cinnamaldehyde"),
                        List.of("phenylpropanoid"),
                        List.of("plant natural product"),
                        List.of("EC50 = 120 mg/L"),
                        List.of("plate inhibition"),
                        List.of("Phytophthora sojae"),
                        List.of("cell membrane"),
                        List.of("disrupts membrane integrity"),
                        List.of("Paper A"),
                        List.of("not mentioned")
                ),
                List.of("Phytophthora sojae", "PsMYB1", "zoospore development"),
                "EXPERIMENTAL",
                0.9,
                "original",
                null,
                "sq",
                "CONSISTENT"
        );
        ReviewEvidenceRecord derivativeEvidence = new ReviewEvidenceRecord(
                2L,
                task.taskId(),
                null,
                "chunk-2",
                UUID.randomUUID(),
                "Paper B",
                "claim",
                "alpha-methylcinnamaldehyde inhibited mycelial growth.",
                "mycelial growth assay",
                new TypedEntities(
                        List.of("Phytophthora capsici"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("alpha-methylcinnamaldehyde"),
                        List.of("phenylpropanoid"),
                        List.of("chemical synthesis"),
                        List.of("菌丝生长: 25 uM: 0% growth (5d); 100 uM: 6% growth"),
                        List.of("mycelial growth assay"),
                        List.of("Phytophthora capsici"),
                        List.of("cell membrane"),
                        List.of("disrupts membrane integrity"),
                        List.of("Paper B"),
                        List.of("not mentioned"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("XTT reduction IC50 > 200 uM in mammalian cells")
                ),
                List.of("Phytophthora capsici", "alpha-methylcinnamaldehyde"),
                "EXPERIMENTAL",
                0.86,
                "original",
                null,
                "sq",
                "CONSISTENT"
        );

        List<ReviewEvidenceRecord> evidenceRecords = List.of(evidence, derivativeEvidence);
        List<ReviewSummaryTable> summaryTables = service.buildSummaryTables(task, evidenceRecords, null);
        assertEquals(List.of(
                "Compound Activity Summary",
                "Gene-Protein Summary",
                "Process-Pathway Summary",
                "Stage Summary",
                "Species Summary",
                "Method Summary",
                "Concept Summary"
        ), summaryTables.stream().map(ReviewSummaryTable::title).toList());
        assertEquals("Cinnamaldehyde",
                summaryTables.get(0).rows().get(0).get(0));
        assertEquals("EC50 = 120 mg/L",
                summaryTables.get(0).rows().get(0).get(3));
        assertEquals("alpha-methylcinnamaldehyde",
                summaryTables.get(0).rows().get(1).get(0));
        assertEquals("菌丝生长: 25 uM: 0% growth (5d); 100 uM: 6% growth",
                summaryTables.get(0).rows().get(1).get(3));

        byte[] bytes = service.generateXlsx(task, evidenceRecords);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertNotNull(workbook.getSheet("Gene-Protein Summary"));
            assertNotNull(workbook.getSheet("Stage Summary"));
            assertNotNull(workbook.getSheet("Compound Activity Summary"));
            String[] expectedHeaders = {
                    "化合物名称（英文）", "结构类型", "来源", "抑菌浓度", "作用病原菌",
                    "试验方法", "可能的作用靶标/机制", "细胞毒性/安全性数据", "参考文献", "专利情况"
            };
            for (int i = 0; i < expectedHeaders.length; i++) {
                assertEquals(expectedHeaders[i],
                        workbook.getSheet("Compound Activity Summary").getRow(0).getCell(i).getStringCellValue());
            }
            String geneValue = workbook.getSheet("Gene-Protein Summary").getRow(1).getCell(0).getStringCellValue();
            assertEquals("PsMYB1", geneValue);
            String stageValue = workbook.getSheet("Stage Summary").getRow(1).getCell(0).getStringCellValue();
            assertEquals("zoospore development", stageValue);
            String compoundRow1 = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(0).getStringCellValue();
            assertEquals("Cinnamaldehyde", compoundRow1);
            String activityRow1 = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(3).getStringCellValue();
            assertEquals("EC50 = 120 mg/L", activityRow1);
            String pathogenRow1 = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(4).getStringCellValue();
            assertEquals("Phytophthora sojae", pathogenRow1);

            String compoundRow2 = workbook.getSheet("Compound Activity Summary").getRow(2).getCell(0).getStringCellValue();
            assertEquals("alpha-methylcinnamaldehyde", compoundRow2);
            String activityRow2 = workbook.getSheet("Compound Activity Summary").getRow(2).getCell(3).getStringCellValue();
            assertEquals("菌丝生长: 25 uM: 0% growth (5d); 100 uM: 6% growth", activityRow2);
            String pathogenRow2 = workbook.getSheet("Compound Activity Summary").getRow(2).getCell(4).getStringCellValue();
            assertEquals("Phytophthora capsici", pathogenRow2);

            String safetyRow1 = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(7).getStringCellValue();
            assertEquals("未提及", safetyRow1);
            String safetyRow2 = workbook.getSheet("Compound Activity Summary").getRow(2).getCell(7).getStringCellValue();
            assertEquals("XTT reduction IC50 > 200 uM in mammalian cells", safetyRow2);
            String patentValue = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(9).getStringCellValue();
            assertEquals("未提及", patentValue);
            assertFalse(geneValue.contains("zoospore"));
        }
    }

    @Test
    void paperEvidenceSummaryPreviewShouldHideConcentrationDetailButXlsxShouldKeepDedicatedSheet() throws Exception {
        ReviewXlsxService service = new ReviewXlsxService();
        ReviewTaskRecord task = task();
        ReviewPaperEvidenceTable paperTable = new ReviewPaperEvidenceTable(
                task.taskId(),
                UUID.randomUUID(),
                "Anti-oomycete activities from Paper A",
                "请帮我总结当前疫霉菌领域的抑菌化合物",
                "Paper A reports concentration-dependent inhibition.",
                List.of("化合物名称", "结构类型", "来源", "抑菌浓度", "作用病原菌",
                        "试验方法", "可能的作用靶标/机制", "细胞毒性/安全性数据", "来源文献", "专利信息"),
                List.of(List.of("compound 34", "ellipticine derivative", "synthetic",
                        "25 uM strongly inhibited mycelial growth; 25 uM blocked zoospore formation",
                        "Phytophthora infestans", "mycelial growth and zoospore assay",
                        "未提及", "未提及", "Paper A", "未提及")),
                List.of("chunk-1", "chunk-2"),
                1,
                0.92,
                List.of(),
                Instant.now(),
                "antimicrobial_compound",
                "Summary: 25 uM strongly inhibited growth.",
                List.of("化合物/标签", "抑菌浓度", "浓度类型", "观察效果", "作用病原菌",
                        "试验方法/条件", "来源 chunk ids", "备注"),
                List.of(List.of("compound 34", "25 uM", "test concentration",
                        "strong mycelial growth inhibition", "Phytophthora infestans",
                        "mycelial growth assay", "chunk-1", "day 5 and day 13 observations")),
                "25 uM strongly inhibited growth and blocked zoospore formation.",
                List.of("chunk-1"),
                List.of("chunk-1", "chunk-2")
        );

        List<ReviewSummaryTable> previewTables = service.buildPaperEvidenceSummaryTables(task, List.of(paperTable));
        assertEquals(List.of("Paper Evidence Summary", "1 Anti-oomycete activities from"),
                previewTables.stream().map(ReviewSummaryTable::title).toList());
        assertEquals("抑菌浓度", previewTables.get(1).headers().get(3));

        byte[] bytes = service.generatePaperEvidenceXlsx(task, List.of(paperTable));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertNull(workbook.getSheet("Concentration Details"));
            var concentrationSheet = workbook.getSheet("抑菌浓度专门总结");
            assertNotNull(concentrationSheet);
            assertEquals("文献", concentrationSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("抑菌浓度", concentrationSheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("compound 34", concentrationSheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("25 uM", concentrationSheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("25 uM strongly inhibited growth and blocked zoospore formation.",
                    concentrationSheet.getRow(1).getCell(9).getStringCellValue());
        }
    }

    @Test
    void paperEvidenceSummaryShouldMergeMultiplePaperRowsStructurally() throws Exception {
        ReviewXlsxService service = new ReviewXlsxService();
        ReviewTaskRecord task = task();
        List<String> headers = List.of(
                "\u5316\u5408\u7269\u540d\u79f0", "\u7ed3\u6784\u7c7b\u578b", "\u6765\u6e90",
                "\u6291\u83cc\u6d53\u5ea6", "\u4f5c\u7528\u75c5\u539f\u83cc", "\u8bd5\u9a8c\u65b9\u6cd5",
                "\u53ef\u80fd\u7684\u4f5c\u7528\u9776\u6807/\u673a\u5236", "\u7ec6\u80de\u6bd2\u6027/\u5b89\u5168\u6027\u6570\u636e",
                "\u6765\u6e90\u6587\u732e", "\u4e13\u5229\u4fe1\u606f");
        ReviewPaperEvidenceTable first = new ReviewPaperEvidenceTable(
                task.taskId(),
                UUID.randomUUID(),
                "Paper A",
                "question",
                "summary A",
                headers,
                List.of(
                        List.of("compound A", "phenylpropanoid", "plant", "25 uM", "Phytophthora infestans",
                                "mycelial growth assay", "\u672a\u63d0\u53ca", "\u672a\u63d0\u53ca", "Paper A", "\u672a\u63d0\u53ca"),
                        List.of("compound B", "alkaloid", "synthetic", "1 mM", "Phytophthora infestans",
                                "zoospore assay", "zoospore formation", "\u672a\u63d0\u53ca", "Paper A", "\u672a\u63d0\u53ca")),
                List.of("chunk-a"),
                1,
                0.9,
                List.of(),
                Instant.now()
        );
        ReviewPaperEvidenceTable second = new ReviewPaperEvidenceTable(
                task.taskId(),
                UUID.randomUUID(),
                "Paper B",
                "question",
                "summary B",
                headers,
                List.of(List.of("compound C", "terpenoid", "plant")),
                List.of("chunk-b"),
                1,
                0.8,
                List.of(),
                Instant.now()
        );

        List<ReviewSummaryTable> previewTables = service.buildPaperEvidenceSummaryTables(task, List.of(first, second));
        ReviewSummaryTable merged = previewTables.get(0);
        assertEquals("Paper Evidence Summary", merged.title());
        assertEquals(List.of("\u6587\u732e", "\u5316\u5408\u7269\u540d\u79f0", "\u7ed3\u6784\u7c7b\u578b", "\u6765\u6e90",
                "\u6291\u83cc\u6d53\u5ea6", "\u4f5c\u7528\u75c5\u539f\u83cc", "\u8bd5\u9a8c\u65b9\u6cd5",
                "\u53ef\u80fd\u7684\u4f5c\u7528\u9776\u6807/\u673a\u5236", "\u7ec6\u80de\u6bd2\u6027/\u5b89\u5168\u6027\u6570\u636e",
                "\u6765\u6e90\u6587\u732e", "\u4e13\u5229\u4fe1\u606f"), merged.headers());
        assertEquals(3, merged.rows().size());
        assertEquals("Paper A", merged.rows().get(0).get(0));
        assertEquals("compound A", merged.rows().get(0).get(1));
        assertEquals("25 uM", merged.rows().get(0).get(4));
        assertEquals("Paper B", merged.rows().get(2).get(0));
        assertEquals("compound C", merged.rows().get(2).get(1));
        assertEquals("\u672a\u63d0\u53ca", merged.rows().get(2).get(4));
        assertEquals("\u672a\u63d0\u53ca", merged.rows().get(2).get(10));

        byte[] bytes = service.generatePaperEvidenceXlsx(task, List.of(first, second));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("Paper Evidence Summary", sheet.getSheetName());
            assertEquals("\u6587\u732e", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("\u5316\u5408\u7269\u540d\u79f0", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("compound C", sheet.getRow(3).getCell(1).getStringCellValue());
            assertEquals("\u672a\u63d0\u53ca", sheet.getRow(3).getCell(4).getStringCellValue());
            assertNull(workbook.getSheet("Concentration Details"));
            assertNotNull(workbook.getSheet("\u6291\u83cc\u6d53\u5ea6\u4e13\u95e8\u603b\u7ed3"));
        }
    }

    @Test
    void generateXlsxShouldNotCopyOneActivityPayloadAcrossMultipleCompounds() throws Exception {
        ReviewXlsxService service = new ReviewXlsxService();
        ReviewTaskRecord task = task();
        ReviewEvidenceRecord ambiguousEvidence = new ReviewEvidenceRecord(
                3L,
                task.taskId(),
                null,
                "chunk-3",
                UUID.randomUUID(),
                "Paper C",
                "claim",
                "Two compounds were reported in one paragraph.",
                "growth inhibition assay",
                new TypedEntities(
                        List.of("Phytophthora infestans"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("compound A", "compound B"),
                        List.of("phenylpropanoid"),
                        List.of("plant natural product"),
                        List.of("compound A: 10 uM inhibited growth; compound B: 20 uM inhibited growth"),
                        List.of("growth inhibition assay"),
                        List.of("Phytophthora infestans"),
                        List.of(),
                        List.of(),
                        List.of("Paper C"),
                        List.of("not mentioned"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("not mentioned")
                ),
                List.of("compound A", "compound B", "Phytophthora infestans"),
                "EXPERIMENTAL",
                0.75,
                "original",
                null,
                "sq",
                "CONSISTENT"
        );

        byte[] bytes = service.generateXlsx(task, List.of(ambiguousEvidence));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("Compound Activity Summary");
            assertEquals(1, sheet.getLastRowNum(), "multi-compound evidence must stay in one review row");
            String compoundValue = sheet.getRow(1).getCell(0).getStringCellValue();
            assertEquals("需人工复核的多化合物证据: compound A; compound B", compoundValue);
            String activityValue = sheet.getRow(1).getCell(3).getStringCellValue();
            assertEquals("compound A: 10 uM inhibited growth; compound B: 20 uM inhibited growth", activityValue);
        }
    }

    private ReviewTaskRecord task() {
        return new ReviewTaskRecord(
                UUID.randomUUID(),
                "user-1",
                "整理一下涉及疫霉菌生长、生殖、致病等过程的所有基因。",
                ReviewTaskStatus.COMPLETED,
                ReviewStage.COMPLETED,
                new QueryAnalysis("main", List.of("sq"), List.of(), List.of("pathogenicity")),
                "report",
                1,
                1,
                1,
                new ReviewTaskMetrics(1L, 1L, 1L, 1L, 1L, 1L, 1L),
                null,
                null,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
    }
}
