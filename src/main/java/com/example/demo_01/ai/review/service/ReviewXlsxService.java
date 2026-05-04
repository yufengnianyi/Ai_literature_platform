package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.QueryAnalysis;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewEvidenceRecord;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewTaskRecord;
import com.example.demo_01.ai.review.model.ReviewModels.TypedEntities;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReviewXlsxService {

    public byte[] generateXlsx(ReviewTaskRecord task, List<ReviewEvidenceRecord> evidenceRecords) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            createCompoundActivitySheet(workbook, headerStyle, evidenceRecords);
            createGeneProteinSheet(workbook, headerStyle, evidenceRecords);
            createCategorySheet(workbook, headerStyle, "Process-Pathway Summary", evidenceRecords,
                    typed -> merge(typed.pathwayOrProcess(), typed.phenotype()), "Process/Pathway");
            createCategorySheet(workbook, headerStyle, "Stage Summary", evidenceRecords,
                    TypedEntities::developmentalStage, "Developmental Stage");
            createCategorySheet(workbook, headerStyle, "Species Summary", evidenceRecords,
                    TypedEntities::species, "Species");
            createCategorySheet(workbook, headerStyle, "Method Summary", evidenceRecords,
                    TypedEntities::method, "Method");
            createConceptSheet(workbook, headerStyle, task, evidenceRecords);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate xlsx for task {}", task.taskId(), e);
            throw new RuntimeException("Failed to generate xlsx", e);
        }
    }

    private void createCompoundActivitySheet(Workbook workbook, CellStyle headerStyle,
                                             List<ReviewEvidenceRecord> evidenceRecords) {
        Sheet sheet = workbook.createSheet("Compound Activity Summary");
        String[] headers = {
                "化合物名称（英文）", "结构类型", "来源", "抑菌活性", "实验手段",
                "作用目标", "可能的作用靶标和作用机制", "参考文献", "专利情况"
        };
        createHeaderRow(sheet, headerStyle, headers);

        Map<String, List<ReviewEvidenceRecord>> byCompound = new LinkedHashMap<>();
        for (ReviewEvidenceRecord evidence : evidenceRecords) {
            for (String compound : typedList(evidence.typedEntities(), TypedEntities::moleculeOrMetabolite)) {
                byCompound.computeIfAbsent(compound, key -> new ArrayList<>()).add(evidence);
            }
        }

        int rowIdx = 1;
        for (Map.Entry<String, List<ReviewEvidenceRecord>> entry : byCompound.entrySet()) {
            List<ReviewEvidenceRecord> records = entry.getValue();
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(entry.getKey());
            row.createCell(1).setCellValue(joinDistinct(records,
                    r -> typedList(r.typedEntities(), TypedEntities::compoundStructureType)));
            row.createCell(2).setCellValue(joinDistinct(records,
                    r -> typedList(r.typedEntities(), TypedEntities::compoundSource)));
            row.createCell(3).setCellValue(valueOrFallback(
                    joinDistinct(records, r -> typedList(r.typedEntities(), TypedEntities::antimicrobialActivity)),
                    truncate(firstNonBlank(records.stream().map(ReviewEvidenceRecord::finding).toList()), 500)));
            row.createCell(4).setCellValue(valueOrFallback(
                    joinDistinct(records, r -> merge(
                            typedList(r.typedEntities(), TypedEntities::assayMethod),
                            typedList(r.typedEntities(), TypedEntities::method))),
                    joinDistinct(records, r -> listOf(r.methodology()))));
            row.createCell(5).setCellValue(valueOrFallback(
                    joinDistinct(records, r -> typedList(r.typedEntities(), TypedEntities::targetOrganism)),
                    joinDistinct(records, r -> typedList(r.typedEntities(), TypedEntities::species))));
            row.createCell(6).setCellValue(joinDistinct(records, r -> merge(
                    typedList(r.typedEntities(), TypedEntities::proposedTarget),
                    typedList(r.typedEntities(), TypedEntities::mechanism))));
            row.createCell(7).setCellValue(valueOrFallback(
                    joinDistinct(records, r -> typedList(r.typedEntities(), TypedEntities::reference)),
                    joinDistinct(records, r -> listOf(documentLabel(r)))));
            row.createCell(8).setCellValue(valueOrFallback(
                    joinDistinct(records, r -> typedList(r.typedEntities(), TypedEntities::patentStatus)),
                    "未提及"));
        }

        autoSize(sheet, headers.length);
    }

    private void createGeneProteinSheet(Workbook workbook, CellStyle headerStyle,
                                        List<ReviewEvidenceRecord> evidenceRecords) {
        Sheet sheet = workbook.createSheet("Gene-Protein Summary");
        String[] headers = {
                "Gene/Protein", "Species", "Related Sub-question", "Evidence Count",
                "Key Finding", "Process/Stage", "Source Papers", "Evidence Type",
                "Methodology", "Confidence (avg)"
        };
        createHeaderRow(sheet, headerStyle, headers);

        Map<String, List<ReviewEvidenceRecord>> byGene = new LinkedHashMap<>();
        for (ReviewEvidenceRecord evidence : evidenceRecords) {
            for (String gene : typedList(evidence.typedEntities(), TypedEntities::geneOrProtein)) {
                byGene.computeIfAbsent(gene, key -> new ArrayList<>()).add(evidence);
            }
        }

        int rowIdx = 1;
        for (Map.Entry<String, List<ReviewEvidenceRecord>> entry : byGene.entrySet()) {
            List<ReviewEvidenceRecord> records = entry.getValue();
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(entry.getKey());
            row.createCell(1).setCellValue(joinDistinct(records, r -> typedList(r.typedEntities(), TypedEntities::species)));
            row.createCell(2).setCellValue(joinDistinct(records, r -> listOf(r.subQuestion())));
            row.createCell(3).setCellValue(records.size());
            row.createCell(4).setCellValue(truncate(firstNonBlank(records.stream().map(ReviewEvidenceRecord::finding).toList()), 500));
            row.createCell(5).setCellValue(joinDistinct(records, r -> merge(
                    typedList(r.typedEntities(), TypedEntities::pathwayOrProcess),
                    typedList(r.typedEntities(), TypedEntities::developmentalStage),
                    typedList(r.typedEntities(), TypedEntities::phenotype))));
            row.createCell(6).setCellValue(joinDistinct(records, r -> listOf(documentLabel(r))));
            row.createCell(7).setCellValue(joinDistinct(records, r -> listOf(r.evidenceType())));
            row.createCell(8).setCellValue(joinDistinct(records, r -> listOf(r.methodology())));
            row.createCell(9).setCellValue(avgConfidence(records));
        }

        autoSize(sheet, headers.length);
    }

    private void createCategorySheet(Workbook workbook, CellStyle headerStyle,
                                     String sheetName,
                                     List<ReviewEvidenceRecord> evidenceRecords,
                                     Function<TypedEntities, List<String>> extractor,
                                     String label) {
        Sheet sheet = workbook.createSheet(sheetName);
        String[] headers = {
                label, "Related Sub-question", "Evidence Count", "Linked Gene/Protein",
                "Key Finding", "Source Papers", "Confidence (avg)"
        };
        createHeaderRow(sheet, headerStyle, headers);

        Map<String, List<ReviewEvidenceRecord>> byCategory = new LinkedHashMap<>();
        for (ReviewEvidenceRecord evidence : evidenceRecords) {
            for (String item : typedList(evidence.typedEntities(), extractor)) {
                byCategory.computeIfAbsent(item, key -> new ArrayList<>()).add(evidence);
            }
        }

        int rowIdx = 1;
        for (Map.Entry<String, List<ReviewEvidenceRecord>> entry : byCategory.entrySet()) {
            List<ReviewEvidenceRecord> records = entry.getValue();
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(entry.getKey());
            row.createCell(1).setCellValue(joinDistinct(records, r -> listOf(r.subQuestion())));
            row.createCell(2).setCellValue(records.size());
            row.createCell(3).setCellValue(joinDistinct(records, r -> typedList(r.typedEntities(), TypedEntities::geneOrProtein)));
            row.createCell(4).setCellValue(truncate(firstNonBlank(records.stream().map(ReviewEvidenceRecord::finding).toList()), 500));
            row.createCell(5).setCellValue(joinDistinct(records, r -> listOf(documentLabel(r))));
            row.createCell(6).setCellValue(avgConfidence(records));
        }

        autoSize(sheet, headers.length);
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

        autoSize(sheet, headers.length);
    }

    private boolean mentionsConcept(ReviewEvidenceRecord evidence, String conceptLower) {
        String claim = evidence.claim() != null ? evidence.claim().toLowerCase() : "";
        String finding = evidence.finding() != null ? evidence.finding().toLowerCase() : "";
        String subQuestion = evidence.subQuestion() != null ? evidence.subQuestion().toLowerCase() : "";
        String typed = String.join(" ", merge(
                typedList(evidence.typedEntities(), TypedEntities::pathwayOrProcess),
                typedList(evidence.typedEntities(), TypedEntities::developmentalStage),
                typedList(evidence.typedEntities(), TypedEntities::phenotype),
                typedList(evidence.typedEntities(), TypedEntities::method)));
        return claim.contains(conceptLower)
                || finding.contains(conceptLower)
                || subQuestion.contains(conceptLower)
                || typed.toLowerCase().contains(conceptLower);
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

    private void autoSize(Sheet sheet, int width) {
        for (int i = 0; i < width; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private List<String> typedList(TypedEntities typedEntities, Function<TypedEntities, List<String>> extractor) {
        if (typedEntities == null) {
            return List.of();
        }
        List<String> values = extractor.apply(typedEntities);
        return values == null ? List.of() : values;
    }

    private String joinDistinct(List<ReviewEvidenceRecord> records,
                                Function<ReviewEvidenceRecord, List<String>> extractor) {
        Set<String> values = new LinkedHashSet<>();
        for (ReviewEvidenceRecord record : records) {
            values.addAll(extractor.apply(record));
        }
        values.removeIf(item -> item == null || item.isBlank());
        return String.join("; ", values);
    }

    @SafeVarargs
    private final List<String> merge(List<String>... lists) {
        Set<String> merged = new LinkedHashSet<>();
        for (List<String> list : lists) {
            if (list != null) {
                merged.addAll(list);
            }
        }
        merged.removeIf(item -> item == null || item.isBlank());
        return new ArrayList<>(merged);
    }

    private List<String> listOf(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private String documentLabel(ReviewEvidenceRecord record) {
        if (record.documentTitle() != null && !record.documentTitle().isBlank()) {
            return record.documentTitle();
        }
        return record.documentId() != null ? record.documentId().toString() : "-";
    }

    private String firstNonBlank(List<String> values) {
        return values.stream().filter(item -> item != null && !item.isBlank()).findFirst().orElse("");
    }

    private String valueOrFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private double avgConfidence(List<ReviewEvidenceRecord> records) {
        return Math.round(records.stream().mapToDouble(ReviewEvidenceRecord::confidence).average().orElse(0.0) * 100.0) / 100.0;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
