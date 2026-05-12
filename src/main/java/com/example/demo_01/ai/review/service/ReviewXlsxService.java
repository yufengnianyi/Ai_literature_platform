package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.service.CompoundEvidenceAggregator.CompoundActivityRow;
import com.example.demo_01.ai.review.service.CompoundEvidenceAggregator.ComparativeRelationRow;
import com.example.demo_01.ai.review.service.CompoundEvidenceAggregator.DoseResponseRow;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
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

    private static final String CONCENTRATION_SUMMARY_SHEET = "\u6291\u83cc\u6d53\u5ea6\u4e13\u95e8\u603b\u7ed3";
    private static final String NOT_MENTIONED = "\u672a\u63d0\u53ca";
    private static final List<String> ANTIMICROBIAL_COMPOUND_HEADERS = List.of(
            "\u5316\u5408\u7269\u540d\u79f0",
            "\u7ed3\u6784\u7c7b\u578b",
            "\u6765\u6e90",
            "\u6291\u83cc\u6d53\u5ea6",
            "\u4f5c\u7528\u75c5\u539f\u83cc",
            "\u8bd5\u9a8c\u65b9\u6cd5",
            "\u53ef\u80fd\u7684\u4f5c\u7528\u9776\u6807/\u673a\u5236",
            "\u7ec6\u80de\u6bd2\u6027/\u5b89\u5168\u6027\u6570\u636e",
            "\u6765\u6e90\u6587\u732e",
            "\u4e13\u5229\u4fe1\u606f"
    );
    private static final List<String> DEFAULT_CONCENTRATION_HEADERS = List.of(
            "\u5316\u5408\u7269/\u6807\u7b7e",
            "\u6291\u83cc\u6d53\u5ea6",
            "\u6d53\u5ea6\u7c7b\u578b",
            "\u89c2\u5bdf\u6548\u679c",
            "\u4f5c\u7528\u75c5\u539f\u83cc",
            "\u8bd5\u9a8c\u65b9\u6cd5/\u6761\u4ef6",
            "\u6765\u6e90 chunk ids",
            "\u5907\u6ce8"
    );

    @Resource
    private ReviewProperties reviewProperties;

    public byte[] generateXlsx(ReviewTaskRecord task, List<ReviewEvidenceRecord> evidenceRecords) {
        return generateXlsx(task, evidenceRecords, null);
    }

    public byte[] generatePaperEvidenceXlsx(ReviewTaskRecord task, List<ReviewPaperEvidenceTable> paperTables) {
        return generatePaperEvidenceXlsx(task, paperTables, true);
    }

    private byte[] generatePaperEvidenceXlsx(ReviewTaskRecord task,
                                             List<ReviewPaperEvidenceTable> paperTables,
                                             boolean includeConcentrationSummarySheet) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            createIntegratedPaperEvidenceSheet(workbook, headerStyle, paperTables);
            createPerPaperEvidenceSheets(workbook, headerStyle, paperTables);
            if (includeConcentrationSummarySheet) {
                createConcentrationSummarySheet(workbook, headerStyle, paperTables);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate paper evidence xlsx for task {}", task.taskId(), e);
            throw new RuntimeException("Failed to generate paper evidence xlsx", e);
        }
    }

    public byte[] generateXlsx(ReviewTaskRecord task, List<ReviewEvidenceRecord> evidenceRecords,
                                List<SynthesizedCompoundRecord> synthesizedRecords) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            createCompoundActivitySheet(workbook, headerStyle, evidenceRecords, synthesizedRecords);
            if (synthesizedRecords != null && !synthesizedRecords.isEmpty()
                    && reviewProperties.getXlsx().isEnableThreeSheet()) {
                createDoseResponseSheet(workbook, headerStyle, synthesizedRecords);
                createComparativeRelationSheet(workbook, headerStyle, synthesizedRecords);
            }
            if (synthesizedRecords != null && !synthesizedRecords.isEmpty()) {
                createPerPaperRankingSheet(workbook, headerStyle, synthesizedRecords);
            }
            createGeneProteinSheet(workbook, headerStyle, evidenceRecords);
            createCategorySheet(workbook, headerStyle, "Process-Pathway Summary", evidenceRecords,
                    typed -> merge(typed.pathwayOrProcess(), typed.phenotype()), "Process/Pathway", synthesizedRecords);
            createCategorySheet(workbook, headerStyle, "Stage Summary", evidenceRecords,
                    TypedEntities::developmentalStage, "Developmental Stage", synthesizedRecords);
            createCategorySheet(workbook, headerStyle, "Species Summary", evidenceRecords,
                    TypedEntities::species, "Species", synthesizedRecords);
            createCategorySheet(workbook, headerStyle, "Method Summary", evidenceRecords,
                    TypedEntities::method, "Method", synthesizedRecords);
            createConceptSheet(workbook, headerStyle, task, evidenceRecords);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("Failed to generate xlsx for task {}", task.taskId(), e);
            throw new RuntimeException("Failed to generate xlsx", e);
        }
    }

    public List<ReviewSummaryTable> buildSummaryTables(ReviewTaskRecord task, List<ReviewEvidenceRecord> evidenceRecords,
                                                       List<SynthesizedCompoundRecord> synthesizedRecords) {
        byte[] xlsxBytes = generateXlsx(task, evidenceRecords, synthesizedRecords);
        return readSummaryTables(task, xlsxBytes);
    }

    public List<ReviewSummaryTable> buildPaperEvidenceSummaryTables(ReviewTaskRecord task,
                                                                    List<ReviewPaperEvidenceTable> paperTables) {
        byte[] xlsxBytes = generatePaperEvidenceXlsx(task, paperTables, false);
        return readSummaryTables(task, xlsxBytes);
    }

    private List<ReviewSummaryTable> readSummaryTables(ReviewTaskRecord task, byte[] xlsxBytes) {
        DataFormatter formatter = new DataFormatter();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            List<ReviewSummaryTable> tables = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Row headerRow = sheet.getRow(0);
                List<String> headers = new ArrayList<>();
                if (headerRow != null) {
                    for (int column = 0; column < headerRow.getLastCellNum(); column++) {
                        headers.add(formatter.formatCellValue(headerRow.getCell(column)));
                    }
                }

                List<List<String>> rows = new ArrayList<>();
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        continue;
                    }
                    List<String> values = new ArrayList<>();
                    boolean hasValue = false;
                    for (int column = 0; column < headers.size(); column++) {
                        String value = formatter.formatCellValue(row.getCell(column));
                        values.add(value);
                        hasValue = hasValue || !value.isBlank();
                    }
                    if (hasValue) {
                        rows.add(values);
                    }
                }

                tables.add(new ReviewSummaryTable(sheetId(sheet.getSheetName()), sheet.getSheetName(), headers, rows));
            }
            return tables;
        } catch (IOException e) {
            log.error("Failed to build summary tables for task {}", task.taskId(), e);
            throw new RuntimeException("Failed to build summary tables", e);
        }
    }

    private void createIntegratedPaperEvidenceSheet(Workbook workbook,
                                                    CellStyle headerStyle,
                                                    List<ReviewPaperEvidenceTable> paperTables) {
        Sheet sheet = workbook.createSheet("Paper Evidence Summary");
        List<String> headers = new ArrayList<>();
        headers.add("\u6587\u732e");
        headers.addAll(ANTIMICROBIAL_COMPOUND_HEADERS);
        createHeaderRow(sheet, headerStyle, headers.toArray(String[]::new));

        int rowIdx = 1;
        for (ReviewPaperEvidenceTable table : paperTables == null ? List.<ReviewPaperEvidenceTable>of() : paperTables) {
            List<List<String>> rows = table.rows() == null || table.rows().isEmpty()
                    ? List.of(List.of())
                    : table.rows();
            for (List<String> evidenceRow : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(safeDefault(table.documentTitle(),
                        table.documentId() == null ? "unknown" : table.documentId().toString()));
                for (int column = 0; column < ANTIMICROBIAL_COMPOUND_HEADERS.size(); column++) {
                    String value = evidenceRow != null && column < evidenceRow.size() ? evidenceRow.get(column) : null;
                    row.createCell(column + 1).setCellValue(safeDefault(value, NOT_MENTIONED));
                }
            }
        }
        autoSize(sheet, headers.size());
    }

    private void createPerPaperEvidenceSheets(Workbook workbook,
                                              CellStyle headerStyle,
                                              List<ReviewPaperEvidenceTable> paperTables) {
        int index = 1;
        for (ReviewPaperEvidenceTable table : paperTables == null ? List.<ReviewPaperEvidenceTable>of() : paperTables) {
            String title = safeDefault(table.documentTitle(), "Paper " + index);
            Sheet sheet = workbook.createSheet(safeSheetName(index + " " + title));
            List<String> headers = table.headers() == null || table.headers().isEmpty()
                    ? List.of("Finding", "Evidence", "Source")
                    : table.headers();
            createHeaderRow(sheet, headerStyle, headers.toArray(String[]::new));
            int rowIdx = 1;
            for (List<String> values : table.rows() == null ? List.<List<String>>of() : table.rows()) {
                Row row = sheet.createRow(rowIdx++);
                for (int column = 0; column < headers.size(); column++) {
                    String value = values != null && column < values.size() ? values.get(column) : "";
                    row.createCell(column).setCellValue(safe(value));
                }
            }
            autoSize(sheet, headers.size());
            index++;
        }
    }

    private void createConcentrationSummarySheet(Workbook workbook,
                                                 CellStyle headerStyle,
                                                 List<ReviewPaperEvidenceTable> paperTables) {
        Sheet sheet = workbook.createSheet(CONCENTRATION_SUMMARY_SHEET);
        List<String> headers = new ArrayList<>();
        headers.add("\u6587\u732e");
        headers.addAll(DEFAULT_CONCENTRATION_HEADERS);
        headers.add("\u6d53\u5ea6\u603b\u7ed3");
        createHeaderRow(sheet, headerStyle, headers.toArray(String[]::new));
        int rowIdx = 1;
        for (ReviewPaperEvidenceTable table : paperTables == null ? List.<ReviewPaperEvidenceTable>of() : paperTables) {
            List<String> concentrationHeaders = table.concentrationHeaders() == null || table.concentrationHeaders().isEmpty()
                    ? DEFAULT_CONCENTRATION_HEADERS
                    : table.concentrationHeaders();
            List<List<String>> concentrationRows = table.concentrationRows() == null || table.concentrationRows().isEmpty()
                    ? fallbackConcentrationRows(table)
                    : table.concentrationRows();
            for (List<String> values : concentrationRows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(safeDefault(table.documentTitle(),
                        table.documentId() == null ? "unknown" : table.documentId().toString()));
                for (int column = 0; column < DEFAULT_CONCENTRATION_HEADERS.size(); column++) {
                    String value = valueForConcentrationColumn(concentrationHeaders, values, DEFAULT_CONCENTRATION_HEADERS.get(column));
                    row.createCell(column + 1).setCellValue(safeDefault(value, NOT_MENTIONED));
                }
                row.createCell(DEFAULT_CONCENTRATION_HEADERS.size() + 1).setCellValue(
                        safeDefault(table.concentrationSummary(), safeDefault(table.concentrationDocument(), NOT_MENTIONED)));
            }
        }
        autoSize(sheet, headers.size());
    }

    private List<List<String>> fallbackConcentrationRows(ReviewPaperEvidenceTable table) {
        List<String> row = new ArrayList<>(java.util.Collections.nCopies(DEFAULT_CONCENTRATION_HEADERS.size(), NOT_MENTIONED));
        String legacyDocument = table == null ? null : table.concentrationDocument();
        if (legacyDocument != null && !legacyDocument.isBlank()) {
            row.set(DEFAULT_CONCENTRATION_HEADERS.size() - 1, legacyDocument);
        }
        return List.of(row);
    }

    private String valueForConcentrationColumn(List<String> headers, List<String> values, String targetHeader) {
        int index = headers == null ? -1 : headers.indexOf(targetHeader);
        if (index >= 0 && values != null && index < values.size()) {
            return values.get(index);
        }
        int defaultIndex = DEFAULT_CONCENTRATION_HEADERS.indexOf(targetHeader);
        if (defaultIndex >= 0 && values != null && defaultIndex < values.size()) {
            return values.get(defaultIndex);
        }
        return NOT_MENTIONED;
    }

    private String safeSheetName(String value) {
        String normalized = safeDefault(value, "Sheet")
                .replaceAll("[\\\\/?*\\[\\]:]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            normalized = "Sheet";
        }
        return normalized.length() > 31 ? normalized.substring(0, 31).trim() : normalized;
    }

    private String sheetId(String sheetName) {
        return sheetName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private void createCompoundActivitySheet(Workbook workbook, CellStyle headerStyle,
                                             List<ReviewEvidenceRecord> evidenceRecords,
                                             List<SynthesizedCompoundRecord> synthesizedRecords) {
        Sheet sheet = workbook.createSheet("Compound Activity Summary");
        String[] headers = {
                "化合物名称（英文）", "结构类型", "来源", "抑菌浓度", "作用病原菌",
                "试验方法", "可能的作用靶标/机制", "细胞毒性/安全性数据", "参考文献", "专利情况"
        };
        createHeaderRow(sheet, headerStyle, headers);

        CellStyle wrapStyle = workbook.createCellStyle();
        wrapStyle.setWrapText(true);

        int rowIdx = 1;
        if (synthesizedRecords != null && !synthesizedRecords.isEmpty()) {
            List<SynthesizedCompoundRecord> sorted = sortSynthesizedRecords(synthesizedRecords);
            for (SynthesizedCompoundRecord rec : sorted) {
                Row row = sheet.createRow(rowIdx++);
                String compoundLabel = (rec.compoundName() != null ? rec.compoundName() : "unknown");
                if (rec.role() != null) compoundLabel += " [" + rec.role().name() + "]";
                row.createCell(0).setCellValue(compoundLabel);
                row.createCell(1).setCellValue(safe(rec.structureType()));
                row.createCell(2).setCellValue(safe(rec.source()));

                Cell activityCell = row.createCell(3);
                activityCell.setCellValue(formatParadigmActivities(rec.paradigmActivities()));
                activityCell.setCellStyle(wrapStyle);

                row.createCell(4).setCellValue(rec.targetOrganisms() != null
                        ? String.join("; ", rec.targetOrganisms()) : CompoundEvidenceAggregator.NOT_MENTIONED);
                row.createCell(5).setCellValue(formatParadigmNames(rec.paradigmActivities()));

                String mechanism = safe(rec.mechanismSummary());
                if (rec.comparisons() != null && !rec.comparisons().isEmpty()) {
                    String comparisons = rec.comparisons().stream()
                            .filter(c -> c.relation() != null && !c.relation().isBlank())
                            .map(c -> c.relation() + (c.referenceCompound() != null ? " (vs " + c.referenceCompound() + ")" : ""))
                            .collect(Collectors.joining("; "));
                    if (!comparisons.isBlank()) {
                        mechanism = mechanism.isBlank() ? comparisons : mechanism + "\n" + comparisons;
                    }
                }
                Cell mechCell = row.createCell(6);
                mechCell.setCellValue(mechanism.isBlank() ? CompoundEvidenceAggregator.NOT_MENTIONED : mechanism);
                mechCell.setCellStyle(wrapStyle);

                row.createCell(7).setCellValue(rec.safetyProfile() != null && !rec.safetyProfile().isBlank()
                        ? rec.safetyProfile() : CompoundEvidenceAggregator.NOT_MENTIONED);
                row.createCell(8).setCellValue(rec.reference() != null && !rec.reference().isBlank()
                        ? rec.reference() : safe(rec.documentTitle()));
                row.createCell(9).setCellValue(CompoundEvidenceAggregator.NOT_MENTIONED);
            }
        } else {
            for (CompoundActivityRow activity : CompoundEvidenceAggregator.fromEvidenceRecords(evidenceRecords)) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(activity.compoundName());
                row.createCell(1).setCellValue(activity.structureType());
                row.createCell(2).setCellValue(activity.source());
                row.createCell(3).setCellValue(activity.antimicrobialActivity());
                row.createCell(4).setCellValue(activity.targetPathogen());
                row.createCell(5).setCellValue(activity.assayMethod());
                row.createCell(6).setCellValue(activity.mechanism());
                row.createCell(7).setCellValue(activity.cytotoxicitySafety());
                row.createCell(8).setCellValue(activity.reference());
                row.createCell(9).setCellValue(activity.patentStatus());
            }
        }
        autoSize(sheet, headers.length);
    }

    private List<SynthesizedCompoundRecord> sortSynthesizedRecords(List<SynthesizedCompoundRecord> records) {
        return records.stream().sorted((a, b) -> {
            int docCmp = safe(a.documentId()).compareTo(safe(b.documentId()));
            if (docCmp != 0) return docCmp;
            int roleA = a.role() == CompoundRole.SUBJECT ? 0 : 1;
            int roleB = b.role() == CompoundRole.SUBJECT ? 0 : 1;
            if (roleA != roleB) return Integer.compare(roleA, roleB);
            return Double.compare(b.confidence(), a.confidence());
        }).toList();
    }

    private String formatParadigmActivities(List<ParadigmActivityBlock> activities) {
        if (activities == null || activities.isEmpty()) return CompoundEvidenceAggregator.NOT_MENTIONED;
        StringBuilder sb = new StringBuilder();
        for (ParadigmActivityBlock block : activities) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("[").append(block.paradigm() != null ? block.paradigm() : "UNKNOWN").append("] ");
            if (block.keyMetric() != null && block.keyMetric().type() != null) {
                sb.append(block.keyMetric().type()).append("=")
                        .append(block.keyMetric().value() != null ? block.keyMetric().value() : "N/A");
            }
            if (block.doseGradient() != null && !block.doseGradient().isEmpty()) {
                String gradient = block.doseGradient().stream()
                        .map(dr -> {
                            String s = dr.concentration() != null ? dr.concentration() : "";
                            if (dr.effect() != null && !dr.effect().isBlank()) s += "→" + dr.effect();
                            if (dr.timepoint() != null && !dr.timepoint().isBlank()) s += " (" + dr.timepoint() + ")";
                            return s;
                        })
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.joining("; "));
                if (!gradient.isBlank()) {
                    if (block.keyMetric() != null && block.keyMetric().type() != null) sb.append("; ");
                    sb.append(gradient);
                }
            }
            if (block.doseDependent() != null && block.doseDependent()) sb.append(" (dose-dependent)");
            if (block.durability() != null && !block.durability().isBlank())
                sb.append(" [").append(block.durability()).append("]");
            if (block.observation() != null && !block.observation().isBlank())
                sb.append(" ").append(block.observation());
        }
        return sb.toString();
    }

    private String formatParadigmNames(List<ParadigmActivityBlock> activities) {
        if (activities == null || activities.isEmpty()) return CompoundEvidenceAggregator.NOT_MENTIONED;
        return activities.stream()
                .map(ParadigmActivityBlock::paradigm)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .collect(Collectors.joining("; "));
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String safeDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
                                     String label,
                                     List<SynthesizedCompoundRecord> synthesizedRecords) {
        Sheet sheet = workbook.createSheet(sheetName);
        boolean hasSynthesized = synthesizedRecords != null && !synthesizedRecords.isEmpty();
        String[] headers = hasSynthesized
                ? new String[]{label, "Compound", "Best Dose/Effect", "Evidence Count", "Source Papers", "Confidence (avg)"}
                : new String[]{label, "Related Sub-question", "Evidence Count", "Linked Gene/Protein",
                "Key Finding", "Source Papers", "Confidence (avg)"};
        createHeaderRow(sheet, headerStyle, headers);

        Map<String, List<ReviewEvidenceRecord>> byCategory = new LinkedHashMap<>();
        for (ReviewEvidenceRecord evidence : evidenceRecords) {
            for (String item : typedList(evidence.typedEntities(), extractor)) {
                byCategory.computeIfAbsent(item, key -> new ArrayList<>()).add(evidence);
            }
        }

        int rowIdx = 1;
        if (hasSynthesized) {
            for (Map.Entry<String, List<ReviewEvidenceRecord>> entry : byCategory.entrySet()) {
                String category = entry.getKey();
                List<ReviewEvidenceRecord> records = entry.getValue();
                Set<String> compoundsInCategory = new LinkedHashSet<>();
                for (ReviewEvidenceRecord r : records) {
                    TypedEntities typed = r.typedEntities();
                    if (typed != null) {
                        List<String> names = typed.compoundCanonicalName();
                        if (names == null || names.isEmpty()) names = typed.moleculeOrMetabolite();
                        if (names != null) names.forEach(n -> { if (n != null && !n.isBlank()) compoundsInCategory.add(n.trim()); });
                    }
                }
                if (compoundsInCategory.isEmpty()) compoundsInCategory.add("(general)");
                for (String compound : compoundsInCategory) {
                    Row row = sheet.createRow(rowIdx++);
                    row.createCell(0).setCellValue(category);
                    row.createCell(1).setCellValue(compound);
                    String bestDoseEffect = findBestDoseEffect(synthesizedRecords, compound, category);
                    row.createCell(2).setCellValue(bestDoseEffect);
                    row.createCell(3).setCellValue(records.size());
                    row.createCell(4).setCellValue(joinDistinct(records, r -> listOf(documentLabel(r))));
                    row.createCell(5).setCellValue(avgConfidence(records));
                }
            }
        } else {
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
        }
        autoSize(sheet, headers.length);
    }

    private String findBestDoseEffect(List<SynthesizedCompoundRecord> records, String compound, String category) {
        String categoryLower = category.toLowerCase(java.util.Locale.ROOT);
        for (SynthesizedCompoundRecord rec : records) {
            if (rec.compoundName() == null || !rec.compoundName().equalsIgnoreCase(compound)) continue;
            if (rec.paradigmActivities() == null) continue;
            for (ParadigmActivityBlock pab : rec.paradigmActivities()) {
                if (pab.bestDose() != null && !pab.bestDose().isBlank()) return pab.bestDose();
                if (pab.keyMetric() != null && pab.keyMetric().value() != null) {
                    return pab.keyMetric().type() + "=" + pab.keyMetric().value();
                }
            }
        }
        return "";
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

    private void createDoseResponseSheet(Workbook workbook, CellStyle headerStyle,
                                         List<SynthesizedCompoundRecord> synthesizedRecords) {
        Sheet sheet = workbook.createSheet("Dose-Response Detail");
        String[] headers = {"化合物", "实验范式", "浓度", "效果", "时间点", "条件", "目标病原菌"};
        createHeaderRow(sheet, headerStyle, headers);

        int rowIdx = 1;
        for (DoseResponseRow dr : CompoundEvidenceAggregator.doseResponseRows(synthesizedRecords)) {
            Row row = sheet.createRow(rowIdx++);
            row.createCell(0).setCellValue(dr.compoundName());
            row.createCell(1).setCellValue(dr.paradigm());
            row.createCell(2).setCellValue(dr.concentration());
            row.createCell(3).setCellValue(dr.effect());
            row.createCell(4).setCellValue(dr.timepoint());
            row.createCell(5).setCellValue(dr.conditions());
            row.createCell(6).setCellValue(dr.targetOrganism());
        }
        autoSize(sheet, headers.length);
    }

    private void createComparativeRelationSheet(Workbook workbook, CellStyle headerStyle,
                                                 List<SynthesizedCompoundRecord> synthesizedRecords) {
        Sheet sheet = workbook.createSheet("Comparative Relations");
        String[] headers = {"Document", "化合物", "参考化合物", "关系", "比较基础", "等效换算"};
        createHeaderRow(sheet, headerStyle, headers);

        int rowIdx = 1;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (SynthesizedCompoundRecord rec : synthesizedRecords) {
            if (rec.comparisons() == null) continue;
            for (ComparativeRelation cr : rec.comparisons()) {
                if (cr.relation() == null || cr.relation().isBlank()) continue;
                String dedup = safe(rec.documentTitle()) + "|" + safe(rec.compoundName()) + "|"
                        + safe(cr.referenceCompound()) + "|" + safe(cr.relation());
                if (!seen.add(dedup)) continue;
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(safe(rec.documentTitle()));
                row.createCell(1).setCellValue(safe(rec.compoundName()));
                row.createCell(2).setCellValue(safe(cr.referenceCompound()));
                row.createCell(3).setCellValue(safe(cr.relation()));
                row.createCell(4).setCellValue(safe(cr.basis()));
                row.createCell(5).setCellValue(safe(cr.derivedEquivalence()));
            }
        }
        autoSize(sheet, headers.length);
    }

    private void createPerPaperRankingSheet(Workbook workbook, CellStyle headerStyle,
                                            List<SynthesizedCompoundRecord> synthesizedRecords) {
        Sheet sheet = workbook.createSheet("Per-Paper Compound Ranking");
        String[] headers = {"Document", "Paradigm", "Compound", "Concentration", "Effect", "Conclusion"};
        createHeaderRow(sheet, headerStyle, headers);

        int rowIdx = 1;
        Map<String, List<SynthesizedCompoundRecord>> byDoc = synthesizedRecords.stream()
                .collect(Collectors.groupingBy(r -> safe(r.documentTitle()), java.util.LinkedHashMap::new, Collectors.toList()));

        for (var entry : byDoc.entrySet()) {
            String docTitle = entry.getKey();
            for (SynthesizedCompoundRecord rec : entry.getValue()) {
                if (rec.paradigmActivities() == null) continue;
                for (ParadigmActivityBlock pab : rec.paradigmActivities()) {
                    Row row = sheet.createRow(rowIdx++);
                    row.createCell(0).setCellValue(docTitle);
                    row.createCell(1).setCellValue(safe(pab.paradigm()));
                    row.createCell(2).setCellValue(safe(rec.compoundName()));

                    String conc = "";
                    String effect = "";
                    if (pab.keyMetric() != null && pab.keyMetric().type() != null) {
                        conc = pab.keyMetric().type() + "=" + safe(pab.keyMetric().value());
                    }
                    if (pab.doseGradient() != null && !pab.doseGradient().isEmpty()) {
                        DoseResponse best = pab.doseGradient().get(0);
                        if (conc.isBlank() && best.concentration() != null) conc = best.concentration();
                        if (best.effect() != null) effect = best.effect();
                    }
                    if (pab.observation() != null && !pab.observation().isBlank()) {
                        effect = effect.isBlank() ? pab.observation() : effect + "; " + pab.observation();
                    }
                    row.createCell(3).setCellValue(conc);
                    row.createCell(4).setCellValue(effect);

                    String conclusion = deriveConclusion(rec, pab);
                    row.createCell(5).setCellValue(conclusion);
                }
            }
        }
        autoSize(sheet, headers.length);
    }

    private String deriveConclusion(SynthesizedCompoundRecord rec, ParadigmActivityBlock pab) {
        if (rec.comparisons() != null) {
            for (ComparativeRelation cr : rec.comparisons()) {
                if (cr.relation() != null && !cr.relation().isBlank()) return cr.relation();
            }
        }
        if (rec.role() == CompoundRole.SUBJECT && rec.confidence() >= 0.8) return "most active candidate";
        return "";
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

    private double avgConfidence(List<ReviewEvidenceRecord> records) {
        return Math.round(records.stream().mapToDouble(ReviewEvidenceRecord::confidence).average().orElse(0.0) * 100.0) / 100.0;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
