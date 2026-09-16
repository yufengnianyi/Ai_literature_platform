package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentSubject;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentSubjectType;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PatentSubjectResolver {
    static final String VERSION = "patent-subject-rule-v1";
    private static final Pattern COMBINATION_CN = Pattern.compile(
            "(?:含有|含|包含|包括)([^，。；\\r\\n]{1,60}?)(?:和|与|及)([^，。；\\r\\n]{1,60}?)(?:的)?(?:杀菌|农药|组合物|复配)");
    private static final Pattern COMBINATION_EN = Pattern.compile(
            "(?:containing|comprising|including)\\s+([A-Za-z0-9\\- ]{2,60}?)\\s+(?:and|with)\\s+([A-Za-z0-9\\- ]{2,60}?)(?:\\s+fungicidal|\\s+composition|\\.|,|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SERIES = Pattern.compile("通式|式\\s*[（(]?[I1]|formula\\s+[I1]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMULATION = Pattern.compile("悬浮剂|乳油|水分散粒剂|可湿性粉剂|制剂|formulation|composition", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET = Pattern.compile(
            "马铃薯晚疫病|黄瓜霜霉病|霜霉病|晚疫病|疫霉|卵菌|downy mildew|late blight|phytophthora|oomycete",
            Pattern.CASE_INSENSITIVE);

    public PatentSubject resolve(PatentCandidate candidate, List<PatentPageAssessment> pages) {
        String title = candidate.title() == null ? "" : candidate.title();
        String abstractText = pages.stream()
                .filter(page -> page.roles().contains(PatentPageRole.ABSTRACT))
                .map(PatentPageAssessment::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("");
        String source = (title + "\n" + abstractText).strip();
        List<String> spans = source.isBlank() ? List.of() : List.of(truncate(source, 1200));
        Set<String> components = new LinkedHashSet<>();
        PatentSubjectType type = PatentSubjectType.UNKNOWN;
        String method = "rule:none";

        Matcher cn = COMBINATION_CN.matcher(source);
        Matcher en = COMBINATION_EN.matcher(source);
        if (cn.find()) {
            components.add(cleanName(cn.group(1)));
            components.add(cleanName(cn.group(2)));
            type = PatentSubjectType.COMBINATION;
            method = "rule:combination-cn";
        } else if (en.find()) {
            components.add(cleanName(en.group(1)));
            components.add(cleanName(en.group(2)));
            type = PatentSubjectType.COMBINATION;
            method = "rule:combination-en";
        } else if (SERIES.matcher(source).find()) {
            type = PatentSubjectType.COMPOUND_SERIES;
            method = "rule:formula-series";
        } else if (FORMULATION.matcher(source).find()) {
            type = PatentSubjectType.FORMULATION;
            method = "rule:formulation";
        }

        List<String> targets = targets(source);
        return new PatentSubject(type, List.copyOf(components), List.of(), targets, spans, VERSION, method);
    }

    private List<String> targets(String source) {
        List<String> targets = new ArrayList<>();
        Matcher matcher = TARGET.matcher(source);
        while (matcher.find()) {
            String value = matcher.group().strip();
            if (targets.stream().noneMatch(existing -> normal(existing).equals(normal(value)))) {
                targets.add(value);
            }
        }
        return List.copyOf(targets);
    }

    private String cleanName(String value) {
        return value == null ? "" : value.replaceAll("^[的\\s]+|[的\\s]+$", "").strip();
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String normal(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
