package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewEvidenceRecord;
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
        List<ReviewSummaryTable> summaryTables = service.buildSummaryTables(task, evidenceRecords);
        assertEquals(List.of(
                "Compound Activity Summary",
                "Gene-Protein Summary",
                "Process-Pathway Summary",
                "Stage Summary",
                "Species Summary",
                "Method Summary",
                "Concept Summary"
        ), summaryTables.stream().map(ReviewSummaryTable::title).toList());
        assertEquals("cinnamaldehyde (derivatives: alpha-methylcinnamaldehyde)",
                summaryTables.get(0).rows().get(0).get(0));
        assertEquals("EC50 = 120 mg/L; 菌丝生长: 25 uM: 0% growth (5d); 100 uM: 6% growth",
                summaryTables.get(0).rows().get(0).get(3));

        byte[] bytes = service.generateXlsx(task, evidenceRecords);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertNotNull(workbook.getSheet("Gene-Protein Summary"));
            assertNotNull(workbook.getSheet("Stage Summary"));
            assertNotNull(workbook.getSheet("Compound Activity Summary"));
            String[] expectedHeaders = {
                    "化合物名称（英文）", "结构类型", "来源", "抑菌活性", "作用病原菌",
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
            String compoundValue = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(0).getStringCellValue();
            assertEquals("cinnamaldehyde (derivatives: alpha-methylcinnamaldehyde)", compoundValue);
            String activityValue = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(3).getStringCellValue();
            assertEquals("EC50 = 120 mg/L; 菌丝生长: 25 uM: 0% growth (5d); 100 uM: 6% growth", activityValue);
            String pathogenValue = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(4).getStringCellValue();
            assertEquals("Phytophthora sojae; Phytophthora capsici", pathogenValue);
            String safetyValue = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(7).getStringCellValue();
            assertEquals("XTT reduction IC50 > 200 uM in mammalian cells", safetyValue);
            String patentValue = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(9).getStringCellValue();
            assertEquals("未提及", patentValue);
            assertFalse(geneValue.contains("zoospore"));
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
