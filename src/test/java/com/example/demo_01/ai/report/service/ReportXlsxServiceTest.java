package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels;
import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ReviewStatus;
import com.example.demo_01.ai.evidence.model.EvidenceModels.ValidationStatus;
import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportXlsxServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateHeaderOnlyWorkbookForNoMatches() throws Exception {
        Path output = tempDir.resolve("empty.xlsx");
        new ReportXlsxService().generate(output, List.of());

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            assertEquals(1, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals(EvidenceModels.HEADERS.size() + 4,
                    workbook.getSheetAt(0).getRow(0).getPhysicalNumberOfCells());
        }
    }

    @Test
    void shouldWriteEvidenceAndMetadataColumns() throws Exception {
        UUID evidenceId = UUID.randomUUID();
        CompoundEvidenceRecord evidence = new CompoundEvidenceRecord(
                evidenceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "来源文献",
                1,
                new CompoundEvidenceRow(
                        "化合物A", "Compound A", "结构", "来源", "描述",
                        "Phytophthora infestans", "MIC", "8 ug/mL",
                        "", "", "", "", "", "", "", ""
                ),
                "fingerprint",
                NameKind.PURE_COMPOUND,
                "compound:compound a",
                0.9,
                ValidationStatus.VALID,
                List.of(),
                ReviewStatus.PENDING,
                null,
                true,
                List.of(),
                Instant.now(),
                Instant.now()
        );
        Path output = tempDir.resolve("evidence.xlsx");
        new ReportXlsxService().generate(
                output,
                List.of(new RankedEvidence(evidence, 5.0, 1, null)));

        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(output))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getPhysicalNumberOfRows());
            assertEquals(evidenceId.toString(), sheet.getRow(1).getCell(16).getStringCellValue());
            assertEquals("PURE_COMPOUND", sheet.getRow(1).getCell(17).getStringCellValue());
            assertEquals("compound:compound a", sheet.getRow(1).getCell(18).getStringCellValue());
            assertEquals("来源文献", sheet.getRow(1).getCell(19).getStringCellValue());
            assertTrue(sheet.getRow(0).getCell(17).getStringCellValue().contains("实体类型"));
        }
    }
}
