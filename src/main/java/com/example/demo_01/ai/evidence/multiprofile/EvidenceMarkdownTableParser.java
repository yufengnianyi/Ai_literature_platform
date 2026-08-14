package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidatedEvidenceRow;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.ValidationStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class EvidenceMarkdownTableParser {

    public static final List<String> Q1_MARKDOWN_HEADERS = List.of(
            "化合物原文名称", "化合物标准名称", "结构类型", "来源类别", "来源具体描述",
            "测试卵菌拉丁名", "实验方法", "活性数据", "阳性对照", "作用靶标/机制",
            "靶标验证方法", "细胞毒性", "抗性/交叉抗性", "协同增效", "参考文献", "专利信息"
    );

    private final MultiProfileOutputValidator outputValidator;

    public EvidenceMarkdownTableParser(MultiProfileOutputValidator outputValidator) {
        this.outputValidator = outputValidator;
    }

    public List<ValidatedEvidenceRow> parse(String markdown, EvidenceProfile profile) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("Model returned an empty Markdown table");
        }
        List<String> lines = markdown.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        HeaderMatch header = findHeader(lines, profile);
        if (header == null) {
            throw new IllegalArgumentException(
                    "Markdown table header does not match the required contract for "
                            + profile.questionId());
        }
        if (header.index() + 1 >= lines.size()
                || !isSeparator(splitRow(lines.get(header.index() + 1)), header.headers().size())) {
            throw new IllegalArgumentException("Markdown table separator row is invalid");
        }

        Map<String, ValidatedEvidenceRow> uniqueRows = new LinkedHashMap<>();
        for (int index = header.index() + 2; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("|") || !line.endsWith("|")) {
                continue;
            }
            List<String> cells = splitRow(line);
            if (header.headers().equals(cells) || isSeparator(cells, header.headers().size())) {
                continue;
            }
            if (cells.size() > header.headers().size()) {
                log.warn("Skipping {} prompt-only Markdown row {} because it has {} cells; expected at most {}",
                        profile.questionId(), index + 1, cells.size(), header.headers().size());
                continue;
            }
            if (cells.size() < header.headers().size()) {
                List<String> padded = new ArrayList<>(cells);
                while (padded.size() < header.headers().size()) {
                    padded.add("");
                }
                cells = padded;
            }
            List<String> normalized = cells.stream()
                    .map(value -> value == null ? "" : value.trim())
                    .toList();
            if (normalized.stream().allMatch(String::isBlank)) {
                continue;
            }
            String fingerprint = outputValidator.fingerprint(profile.questionId(), normalized);
            uniqueRows.putIfAbsent(fingerprint, new ValidatedEvidenceRow(
                    UUID.randomUUID(), normalized, fingerprint, Collections.emptyList(),
                    ValidationStatus.UNVERIFIED, "Parsed from prompt-only Markdown output"));
        }
        return List.copyOf(uniqueRows.values());
    }

    private HeaderMatch findHeader(List<String> lines, EvidenceProfile profile) {
        List<List<String>> acceptedHeaders = acceptedHeaders(profile);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("|") || !line.endsWith("|")) {
                continue;
            }
            List<String> cells = splitRow(line);
            for (List<String> headers : acceptedHeaders) {
                if (headers.equals(cells)) {
                    return new HeaderMatch(index, headers);
                }
            }
        }
        return null;
    }

    private List<List<String>> acceptedHeaders(EvidenceProfile profile) {
        if ("Q1".equals(profile.questionId())) {
            return List.of(Q1_MARKDOWN_HEADERS, profile.headers());
        }
        return List.of(profile.headers());
    }

    private boolean isSeparator(List<String> cells, int expectedSize) {
        return cells.size() == expectedSize
                && cells.stream().allMatch(cell -> cell.matches(":?-{3,}:?"));
    }

    private List<String> splitRow(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        for (int index = 1; index < line.length() - 1; index++) {
            char value = line.charAt(index);
            if (escaped) {
                cell.append(value);
                escaped = false;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (value == '|') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
                continue;
            }
            cell.append(value);
        }
        if (escaped) {
            cell.append('\\');
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private record HeaderMatch(int index, List<String> headers) {
    }
}
