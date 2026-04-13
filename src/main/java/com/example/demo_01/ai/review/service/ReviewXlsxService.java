package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewXlsxService {

    public byte[] generateXlsx(ReviewTaskRecord task, List<ReviewEvidenceRecord> evidenceRecords) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            createEntitySheet(workbook, headerStyle, evidenceRecords);
            createConceptSheet(workbook, headerStyle, task, evidenceRecords);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate xlsx for task {}", task.taskId(), e);
            throw new RuntimeException("Failed to generate xlsx", e);
        }
    }

    private void createEntitySheet(Workbook workbook, CellStyle headerStyle,
                                   List<ReviewEvidenceRecord> evidenceRecords) {
        Sheet sheet = workbook.createSheet("Gene-Protein Summary");

        String[] headers = {
                "Gene/Protein", "Related Sub-question", "Evidence Count",
                "Key Finding", "Source Papers", "Evidence Type", "Methodology",
                "Confidence (avg)"
        };
        createHeaderRow(sheet, headerStyle, headers);

        Map<String, List<ReviewEvidenceRecord>> byEntity = new LinkedHashMap<>();
        for (ReviewEvidenceRecord e : evidenceRecords) {
            if (e.entities() != null) {
                for (String entity : e.entities()) {
                    byEntity.computeIfAbsent(entity, k -> new ArrayList<>()).add(e);
                }
            }
        }

        int rowIdx = 1;
        for (Map.Entry<String, List<ReviewEvidenceRecord>> entry : byEntity.entrySet()) {
            String entity = entry.getKey();
            List<ReviewEvidenceRecord> records = entry.getValue();

            Set<String> subQuestions = records.stream()
                    .map(ReviewEvidenceRecord::subQuestion)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            String keyFinding = records.stream()
                    .map(ReviewEvidenceRecord::finding)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("");

            Set<String> sources = records.stream()
                    .map(r -> r.documentId() != null ? r.documentId().toString() : "")
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<String> evidenceTypes = records.stream()
                    .map(ReviewEvidenceRecord::evidenceType)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<String> methodologies = records.stream()
                    .map(ReviewEvidenceRecord::methodology)
                    .filter(Objects::nonNull)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            double avgConfidence = records.stream()
                    .mapToDouble(ReviewEvidenceRecord::confidence)
                    .average()
                    .orElse(0.0);

            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(entity);
            row.createCell(1).setCellValue(String.join("; ", subQuestions));
            row.createCell(2).setCellValue(records.size());
            row.createCell(3).setCellValue(truncate(keyFinding, 500));
            row.createCell(4).setCellValue(String.join(", ", sources));
            row.createCell(5).setCellValue(String.join(", ", evidenceTypes));
            row.createCell(6).setCellValue(String.join("; ", methodologies));
            row.createCell(7).setCellValue(Math.round(avgConfidence * 100.0) / 100.0);
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void createConceptSheet(Workbook workbook, CellStyle headerStyle,
                                    ReviewTaskRecord task,
                                    List<ReviewEvidenceRecord> evidenceRecords) {
        Sheet sheet = workbook.createSheet("Concept Summary");

        String[] headers = {
                "Concept", "Related Sub-question", "Evidence Count",
                "Supporting Evidence", "Conflicting Evidence", "Consensus Status"
        };
        createHeaderRow(sheet, headerStyle, headers);

        QueryAnalysis analysis = task.queryAnalysis();
        List<String> concepts = analysis != null && analysis.keyConcepts() != null
                ? analysis.keyConcepts()
                : List.of();

        Map<String, List<ReviewEvidenceRecord>> bySubQuestion = evidenceRecords.stream()
                .filter(e -> e.subQuestion() != null)
                .collect(Collectors.groupingBy(ReviewEvidenceRecord::subQuestion,
                        LinkedHashMap::new, Collectors.toList()));

        int rowIdx = 1;
        for (String concept : concepts) {
            String conceptLower = concept.toLowerCase();
            List<ReviewEvidenceRecord> related = evidenceRecords.stream()
                    .filter(e -> mentionsConcept(e, conceptLower))
                    .toList();

            String relatedSubQuestions = related.stream()
                    .map(ReviewEvidenceRecord::subQuestion)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining("; "));

            long supportingCount = related.stream()
                    .filter(e -> "CONSISTENT".equalsIgnoreCase(e.consistency()))
                    .count();
            long conflictingCount = related.stream()
                    .filter(e -> "CONFLICTING".equalsIgnoreCase(e.consistency()))
                    .count();

            String consensusStatus;
            if (related.isEmpty()) {
                consensusStatus = "No evidence";
            } else if (conflictingCount > 0) {
                consensusStatus = "Conflicting";
            } else if (supportingCount > 0) {
                consensusStatus = "Consistent";
            } else {
                consensusStatus = "Insufficient";
            }

            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(concept);
            row.createCell(1).setCellValue(relatedSubQuestions);
            row.createCell(2).setCellValue(related.size());
            row.createCell(3).setCellValue(supportingCount);
            row.createCell(4).setCellValue(conflictingCount);
            row.createCell(5).setCellValue(consensusStatus);
        }

        if (analysis != null && analysis.keyEntities() != null) {
            for (String entity : analysis.keyEntities()) {
                if (concepts.contains(entity)) continue;
                String entityLower = entity.toLowerCase();
                List<ReviewEvidenceRecord> related = evidenceRecords.stream()
                        .filter(e -> mentionsEntity(e, entityLower))
                        .toList();

                if (related.isEmpty()) continue;

                String relatedSubQuestions = related.stream()
                        .map(ReviewEvidenceRecord::subQuestion)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.joining("; "));

                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(entity);
                row.createCell(1).setCellValue(relatedSubQuestions);
                row.createCell(2).setCellValue(related.size());
                row.createCell(3).setCellValue(0);
                row.createCell(4).setCellValue(0);
                row.createCell(5).setCellValue("See Gene/Protein sheet");
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private boolean mentionsConcept(ReviewEvidenceRecord e, String conceptLower) {
        String claim = e.claim() != null ? e.claim().toLowerCase() : "";
        String finding = e.finding() != null ? e.finding().toLowerCase() : "";
        String subQ = e.subQuestion() != null ? e.subQuestion().toLowerCase() : "";
        return claim.contains(conceptLower)
                || finding.contains(conceptLower)
                || subQ.contains(conceptLower);
    }

    private boolean mentionsEntity(ReviewEvidenceRecord e, String entityLower) {
        if (e.entities() != null) {
            for (String ent : e.entities()) {
                if (ent.toLowerCase().contains(entityLower)) return true;
            }
        }
        return false;
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private void createHeaderRow(Sheet sheet, CellStyle headerStyle, String[] headers) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
