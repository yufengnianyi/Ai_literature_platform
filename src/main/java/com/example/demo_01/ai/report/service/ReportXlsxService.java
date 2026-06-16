package com.example.demo_01.ai.report.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels;
import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReportXlsxService {

    private static final int STREAM_WINDOW_SIZE = 200;
    private static final List<String> EXTRA_HEADERS = List.of(
            "Evidence ID", "实体类型", "去重键", "来源文献"
    );

    public void generate(Path outputPath, List<RankedEvidence> evidence) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(STREAM_WINDOW_SIZE);
             OutputStream output = Files.newOutputStream(outputPath)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("Evidence");
            sheet.createFreezePane(0, 1);

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            List<String> headers = new ArrayList<>(EvidenceModels.HEADERS);
            headers.addAll(EXTRA_HEADERS);
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
                header.getCell(column).setCellStyle(headerStyle);
            }

            for (int rowIndex = 0; rowIndex < evidence.size(); rowIndex++) {
                RankedEvidence ranked = evidence.get(rowIndex);
                Row row = sheet.createRow(rowIndex + 1);
                List<String> cells = new ArrayList<>(ranked.evidence().row().cells());
                cells.add(ranked.evidence().evidenceId().toString());
                cells.add(ranked.evidence().nameKind() == null
                        ? "" : ranked.evidence().nameKind().name());
                cells.add(value(ranked.evidence().dedupKey()));
                cells.add(value(ranked.evidence().documentTitle()));
                for (int column = 0; column < cells.size(); column++) {
                    row.createCell(column).setCellValue(value(cells.get(column)));
                }
            }

            for (int column = 0; column < headers.size(); column++) {
                sheet.setColumnWidth(column, Math.min(column < 16 ? 6500 : 8500, 255 * 256));
            }
            workbook.write(output);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate report evidence workbook", e);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
