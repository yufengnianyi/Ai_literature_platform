package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageText;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import com.example.demo_01.ai.patent.demo.PatentEvidenceSections.EvidenceRole;
import com.example.demo_01.ai.patent.demo.PatentEvidenceSections.Section;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PatentPageTriageService {

    private static final int MAX_SELECTED_PAGES = 40;

    private static final Pattern OOMYCETE = Pattern.compile(
            "oomyc|phytophthora|pythium|plasmopara|peronospora|downy mildew|late blight|"
                    + "\u75ab\u9709|\u8150\u9709|\u971c\u9709|\u5375\u83cc",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVITY = Pattern.compile(
            "ic\\s*50|ec\\s*50|mic\\b|inhibi|activity|efficacy|control|fungicidal|"
                    + "mycel|sporang|zoospore|preventive|curative|disease rating|"
                    + "\u9632\u6548|\u6291\u5236\u7387|\u6291\u5236|\u6d3b\u6027|"
                    + "\u83cc\u4e1d|\u5b62\u5b50|\u75c5\u60c5\u6307\u6570",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPOUND = Pattern.compile(
            "cmpd\\s*(?:no\\.?)?|compound\\s*(?:no\\.?)?|formula|example|preparation|"
                    + "synthesis|\u5316\u5408\u7269|\u901a\u5f0f|\u5b9e\u65bd\u4f8b|"
                    + "\u5236\u5907\u4f8b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSTRACT = Pattern.compile(
            "\\babstract\\b|\u6458\u8981", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW_ID = Pattern.compile("^\\s*(\\d{1,5})\\s+(?:[-+]?\\d|\\*{1,3}|[-+])");
    private static final Pattern COMPOUND_MAP_SIGNAL = Pattern.compile(
            "\\bA\\s+is\\b|\\bR\\d*\\b|\\bG\\s+is\\b|\\bZ\\s+is\\b|molecular weight|"
                    + "\\bMS\\b|\\bNMR\\b|formula|synthesis|preparation|"
                    + "\u901a\u5f0f|\u53d6\u4ee3\u57fa|\u5236\u5907",
            Pattern.CASE_INSENSITIVE);

    private final PatentPdfTextService textService;

    public PatentPageTriageService(PatentPdfTextService textService) {
        this.textService = textService;
    }

    public PatentTriageResult triage(Path pdfPath) throws IOException {
        List<PatentPageText> sourcePages = textService.extractAllPages(pdfPath);
        Map<Integer, List<Section>> sections = PatentEvidenceSections.analyze(sourcePages).stream()
                .collect(Collectors.groupingBy(Section::pageNumber));
        List<MutablePage> pages = sourcePages.stream()
                .map(page -> assess(page, sections.getOrDefault(page.pageNumber(), List.of())))
                .collect(Collectors.toCollection(ArrayList::new));
        Map<Integer, MutablePage> byPage = pages.stream()
                .collect(Collectors.toMap(MutablePage::pageNumber, page -> page,
                        (left, right) -> left, LinkedHashMap::new));

        Set<Integer> activityCompoundIds = new LinkedHashSet<>();
        for (MutablePage page : pages) {
            if (page.roles.contains(PatentPageRole.ACTIVITY_TABLE)) {
                activityCompoundIds.addAll(extractActivityCompoundIds(page.text));
                selectWindow(byPage, page.pageNumber, 1, "activity-table-neighbor");
            }
            if (page.roles.contains(PatentPageRole.TEST_DEFINITION)) {
                selectWindow(byPage, page.pageNumber, 2, "test-definition-neighbor");
            }
            if (page.roles.contains(PatentPageRole.Q1_ACTIVITY)) {
                select(page, "q1-activity");
            }
            if (page.roles.contains(PatentPageRole.ABSTRACT) && page.score >= 6) {
                select(page, "abstract-context");
            }
        }

        for (MutablePage page : pages) {
            if (!page.roles.contains(PatentPageRole.ACTIVITY_TABLE)
                    && isCompoundMapPage(page.text, activityCompoundIds)) {
                page.roles.add(PatentPageRole.COMPOUND_MAP);
                page.score += 8;
                select(page, "compound-map");
            }
        }

        trimSelectedPages(pages);
        List<PatentPageAssessment> assessments = pages.stream()
                .map(MutablePage::toAssessment)
                .toList();
        List<PatentPageAssessment> selected = assessments.stream()
                .filter(PatentPageAssessment::selected)
                .sorted(Comparator.comparingInt(PatentPageAssessment::pageNumber))
                .toList();
        return new PatentTriageResult(assessments, selected);
    }

    private MutablePage assess(PatentPageText pageText, List<Section> sections) {
        String text = pageText.text() == null ? "" : pageText.text();
        boolean hasOomycete = OOMYCETE.matcher(text).find();
        boolean hasActivity = ACTIVITY.matcher(text).find();
        boolean hasCompound = COMPOUND.matcher(text).find();
        boolean hasClaims = sections.stream().anyMatch(section -> section.role() == EvidenceRole.CLAIMS);
        MutablePage page = new MutablePage(pageText.pageNumber(), text, visibleChars(text));
        page.annotatedText = PatentEvidenceSections.annotate(sections);
        if (ABSTRACT.matcher(text).find()) {
            page.roles.add(PatentPageRole.ABSTRACT);
            page.score += 2;
        }
        if (hasOomycete) {
            page.score += 4;
        }
        if (hasActivity) {
            page.score += 3;
        }
        if (hasCompound) {
            page.score += 2;
        }
        if (sections.stream().anyMatch(Section::q1Eligible)) {
            page.roles.add(PatentPageRole.Q1_ACTIVITY);
            page.score += 5;
        }
        if (sections.stream().anyMatch(section -> section.role() == EvidenceRole.METHOD)) {
            page.roles.add(PatentPageRole.TEST_DEFINITION);
            page.score += 9;
        }
        if (sections.stream().anyMatch(Section::activityTable)) {
            page.roles.add(PatentPageRole.ACTIVITY_TABLE);
            page.score += 9;
        }
        if (sections.stream().anyMatch(Section::imageReadRequired)) {
            page.roles.add(PatentPageRole.TABLE_CANDIDATE);
            page.reasons.add("table-image-read-required");
        }
        if (sections.stream().anyMatch(section -> section.role() == EvidenceRole.METHOD
                || section.role() == EvidenceRole.RESULT)) {
            select(page, "assay-inspection");
            if (!page.roles.contains(PatentPageRole.Q1_ACTIVITY)) {
                page.reasons.add("no-confirmed-q1-assay");
            }
        }
        if (hasClaims) {
            page.roles.add(PatentPageRole.CLAIMS_CONTEXT);
            if (!page.roles.contains(PatentPageRole.Q1_ACTIVITY)
                    && !page.roles.contains(PatentPageRole.ACTIVITY_TABLE)) {
                page.score -= 3;
            }
        }
        return page;
    }

    private Set<Integer> extractActivityCompoundIds(String text) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            Matcher matcher = ROW_ID.matcher(line);
            if (matcher.find()) {
                ids.add(Integer.parseInt(matcher.group(1)));
            }
        }
        return ids;
    }

    private boolean isCompoundMapPage(String text, Set<Integer> activityCompoundIds) {
        if (activityCompoundIds.isEmpty() || !COMPOUND_MAP_SIGNAL.matcher(text).find()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        int matched = 0;
        for (Integer id : activityCompoundIds) {
            if (Pattern.compile("\\b" + id + "\\b").matcher(normalized).find()) {
                matched++;
            }
            if (matched >= 2) {
                return true;
            }
        }
        return false;
    }

    private void selectWindow(Map<Integer, MutablePage> pages,
                              int centerPage,
                              int radius,
                              String reason) {
        for (int pageNumber = centerPage - radius; pageNumber <= centerPage + radius; pageNumber++) {
            MutablePage page = pages.get(pageNumber);
            if (page == null) {
                continue;
            }
            if (pageNumber != centerPage) {
                page.roles.add(PatentPageRole.NEIGHBOR_CONTEXT);
            }
            select(page, reason);
        }
    }

    private void select(MutablePage page, String reason) {
        page.selected = true;
        page.reasons.add(reason);
    }

    private void trimSelectedPages(List<MutablePage> pages) {
        List<MutablePage> selected = pages.stream()
                .filter(page -> page.selected)
                .sorted(Comparator.comparingInt(this::priority).reversed()
                        .thenComparing(Comparator.comparingInt(MutablePage::score).reversed())
                        .thenComparingInt(MutablePage::pageNumber))
                .toList();
        if (selected.size() <= MAX_SELECTED_PAGES) {
            return;
        }
        Set<Integer> keep = selected.stream()
                .limit(MAX_SELECTED_PAGES)
                .map(MutablePage::pageNumber)
                .collect(Collectors.toSet());
        for (MutablePage page : pages) {
            if (page.selected && !keep.contains(page.pageNumber)) {
                page.selected = false;
                page.reasons.add("trimmed-by-max-selected-pages");
            }
        }
    }

    private int priority(MutablePage page) {
        if (page.roles.contains(PatentPageRole.Q1_ACTIVITY)) {
            return 110;
        }
        if (page.roles.contains(PatentPageRole.TEST_DEFINITION)) {
            return 100;
        }
        if (page.roles.contains(PatentPageRole.ACTIVITY_TABLE)) {
            return 90;
        }
        if (page.roles.contains(PatentPageRole.COMPOUND_MAP)) {
            return 80;
        }
        if (page.roles.contains(PatentPageRole.ABSTRACT)) {
            return 50;
        }
        if (page.roles.contains(PatentPageRole.CLAIMS_CONTEXT)) {
            return 10;
        }
        return 20;
    }

    private int visibleChars(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)) {
                count++;
            }
        }
        return count;
    }

    private static final class MutablePage {
        private final int pageNumber;
        private final String text;
        private String annotatedText;
        private final int textChars;
        private final EnumSet<PatentPageRole> roles = EnumSet.noneOf(PatentPageRole.class);
        private final Set<String> reasons = new LinkedHashSet<>();
        private int score;
        private boolean selected;

        private MutablePage(int pageNumber, String text, int textChars) {
            this.pageNumber = pageNumber;
            this.text = text;
            this.textChars = textChars;
        }

        private int pageNumber() {
            return pageNumber;
        }

        private int score() {
            return score;
        }

        private PatentPageAssessment toAssessment() {
            return new PatentPageAssessment(
                    pageNumber,
                    score,
                    Set.copyOf(roles),
                    selected,
                    String.join("|", reasons),
                    textChars,
                    annotatedText
            );
        }
    }
}
