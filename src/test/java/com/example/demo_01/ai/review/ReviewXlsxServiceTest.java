package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewEvidenceRecord;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewStage;
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
        ReviewTaskRecord task = new ReviewTaskRecord(
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

        byte[] bytes = service.generateXlsx(task, List.of(evidence));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertNotNull(workbook.getSheet("Gene-Protein Summary"));
            assertNotNull(workbook.getSheet("Stage Summary"));
            assertNotNull(workbook.getSheet("Compound Activity Summary"));
            String geneValue = workbook.getSheet("Gene-Protein Summary").getRow(1).getCell(0).getStringCellValue();
            assertEquals("PsMYB1", geneValue);
            String stageValue = workbook.getSheet("Stage Summary").getRow(1).getCell(0).getStringCellValue();
            assertEquals("zoospore development", stageValue);
            String compoundValue = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(0).getStringCellValue();
            assertEquals("Cinnamaldehyde", compoundValue);
            String activityValue = workbook.getSheet("Compound Activity Summary").getRow(1).getCell(3).getStringCellValue();
            assertEquals("EC50 = 120 mg/L", activityValue);
            assertFalse(geneValue.contains("zoospore"));
        }
    }
}
