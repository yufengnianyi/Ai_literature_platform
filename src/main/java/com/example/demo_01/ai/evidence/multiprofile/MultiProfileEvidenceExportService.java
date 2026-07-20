package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.*;
import jakarta.annotation.Resource;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MultiProfileEvidenceExportService {

    private static final int STREAM_WINDOW = 200;
    private static final int EXCEL_CELL_LIMIT = 32_767;

    @Resource
    private MultiProfileEvidenceRepository repository;

    @Resource
    private EvidenceProfileRegistry profileRegistry;

    public Path generate(UUID batchId, Path outputPath) {
        List<DocumentRecord> documents = repository.findAllDocuments(batchId);
        List<QuestionMatchRecord> matches = repository.findAllMatches(batchId);
        List<GenericEvidenceRecord> evidence = repository.findAllEvidence(batchId);
        try {
            Files.createDirectories(outputPath.toAbsolutePath().normalize().getParent());
            try (SXSSFWorkbook workbook = new SXSSFWorkbook(STREAM_WINDOW);
                 OutputStream output = Files.newOutputStream(outputPath)) {
                workbook.setCompressTempFiles(true);
                CellStyle headerStyle = headerStyle(workbook);
                writeClassificationSheet(workbook, headerStyle, documents, matches);
                writeFailureSheet(workbook, headerStyle, documents, matches);
                for (EvidenceProfile profile : profileRegistry.all()) {
                    writeEvidenceSheet(workbook, headerStyle, profile, evidence);
                }
                workbook.write(output);
            }
            return outputPath;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate multi-profile evidence workbook", e);
        }
    }

    private void writeClassificationSheet(SXSSFWorkbook workbook,
                                          CellStyle headerStyle,
                                          List<DocumentRecord> documents,
                                          List<QuestionMatchRecord> matches) {
        Sheet sheet = workbook.createSheet("分类矩阵");
        sheet.createFreezePane(2, 1);
        List<String> headers = new ArrayList<>(List.of("文献ID", "文献标题", "文献状态"));
        for (EvidenceProfile profile : profileRegistry.all()) {
            headers.add(profile.questionId() + "状态");
            headers.add(profile.questionId() + "置信度");
            headers.add(profile.questionId() + "证据数");
        }
        writeHeader(sheet, headerStyle, headers);
        Map<String, QuestionMatchRecord> byDocumentQuestion = matches.stream()
                .collect(Collectors.toMap(
                        item -> item.documentId() + "\u001f" + item.questionId(),
                        item -> item,
                        (left, right) -> right));
        int rowIndex = 1;
        for (DocumentRecord document : documents) {
            List<String> cells = new ArrayList<>(List.of(
                    document.documentId().toString(),
                    value(document.documentTitle()),
                    document.status().name()));
            for (EvidenceProfile profile : profileRegistry.all()) {
                QuestionMatchRecord match = byDocumentQuestion.get(
                        document.documentId() + "\u001f" + profile.questionId());
                cells.add(match == null ? "" : match.classificationStatus().name());
                cells.add(match == null ? "" : String.valueOf(match.confidence()));
                cells.add(match == null ? "" : String.valueOf(match.evidenceCount()));
            }
            writeRow(sheet, rowIndex++, cells);
        }
        setWidths(sheet, headers.size(), 5200);
        sheet.setColumnWidth(0, 9500);
        sheet.setColumnWidth(1, 16000);
    }

    private void writeFailureSheet(SXSSFWorkbook workbook,
                                   CellStyle headerStyle,
                                   List<DocumentRecord> documents,
                                   List<QuestionMatchRecord> matches) {
        Sheet sheet = workbook.createSheet("失败清单");
        List<String> headers = List.of(
                "文献ID", "文献标题", "问题ID", "文献状态", "分类状态", "抽取状态", "错误信息");
        writeHeader(sheet, headerStyle, headers);
        Map<UUID, DocumentRecord> documentsById = documents.stream()
                .collect(Collectors.toMap(DocumentRecord::documentId, item -> item));
        int rowIndex = 1;
        for (QuestionMatchRecord match : matches) {
            if (match.classificationStatus() != ClassificationStatus.FAILED
                    && match.extractionStatus() != ProfileExtractionStatus.FAILED) {
                continue;
            }
            DocumentRecord document = documentsById.get(match.documentId());
            writeRow(sheet, rowIndex++, List.of(
                    match.documentId().toString(),
                    value(match.documentTitle()),
                    match.questionId(),
                    document == null ? "" : document.status().name(),
                    match.classificationStatus().name(),
                    match.extractionStatus().name(),
                    value(match.errorMessage())));
        }
        for (DocumentRecord document : documents) {
            if (document.status() != DocumentStatus.NO_CHUNKS
                    && document.status() != DocumentStatus.FAILED) {
                continue;
            }
            writeRow(sheet, rowIndex++, List.of(
                    document.documentId().toString(),
                    value(document.documentTitle()),
                    "",
                    document.status().name(),
                    "",
                    "",
                    value(document.errorMessage())));
        }
        setWidths(sheet, headers.size(), 7000);
        sheet.setColumnWidth(0, 9500);
        sheet.setColumnWidth(1, 15000);
        sheet.setColumnWidth(6, 18000);
    }

    private void writeEvidenceSheet(SXSSFWorkbook workbook,
                                    CellStyle headerStyle,
                                    EvidenceProfile profile,
                                    List<GenericEvidenceRecord> allEvidence) {
        String name = profile.questionId() + "-" + profile.title();
        Sheet sheet = workbook.createSheet(name.length() > 31 ? name.substring(0, 31) : name);
        sheet.createFreezePane(0, 1);
        List<String> headers = new ArrayList<>(profile.headers());
        headers.addAll(List.of(
                "文献ID", "文献标题", "记录ID", "分类状态", "审核状态", "原文锚点"));
        writeHeader(sheet, headerStyle, headers);
        int rowIndex = 1;
        for (GenericEvidenceRecord record : allEvidence) {
            if (!profile.questionId().equals(record.questionId())) {
                continue;
            }
            List<String> cells = new ArrayList<>(record.cells());
            cells.add(record.documentId().toString());
            cells.add(value(record.documentTitle()));
            cells.add(record.recordId().toString());
            cells.add(record.classificationStatus().name());
            cells.add(record.reviewStatus().name());
            cells.add(record.anchors().stream()
                    .map(anchor -> anchor.chunkId() + ": " + anchor.exactQuote())
                    .collect(Collectors.joining("\n")));
            writeRow(sheet, rowIndex++, cells);
        }
        setWidths(sheet, headers.size(), 6500);
        for (int index = profile.headers().size(); index < headers.size(); index++) {
            sheet.setColumnWidth(index, Math.min(index == headers.size() - 1 ? 18000 : 9000, 255 * 256));
        }
    }

    private CellStyle headerStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void writeHeader(Sheet sheet, CellStyle style, List<String> headers) {
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            row.createCell(index).setCellValue(headers.get(index));
            row.getCell(index).setCellStyle(style);
        }
    }

    private void writeRow(Sheet sheet, int rowIndex, List<String> values) {
        Row row = sheet.createRow(rowIndex);
        for (int index = 0; index < values.size(); index++) {
            row.createCell(index).setCellValue(clip(value(values.get(index))));
        }
    }

    private void setWidths(Sheet sheet, int columns, int width) {
        for (int index = 0; index < columns; index++) {
            sheet.setColumnWidth(index, Math.min(width, 255 * 256));
        }
    }

    private String clip(String value) {
        return value.length() < EXCEL_CELL_LIMIT
                ? value : value.substring(0, EXCEL_CELL_LIMIT - 1);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
