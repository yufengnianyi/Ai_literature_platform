package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Paragraph roles and assay boundaries inferred from the complete text layer. */
public final class PatentEvidenceSections {

    public enum EvidenceRole {
        BACKGROUND, CLAIMS, CLAIMED_USE, METHOD, RESULT, OTHER
    }

    /**
     * text is an unchanged slice of one source page. assayId is document-local;
     * zero means no identified assay (including an unresolved Test-column matrix).
     * q1Eligible denotes an assay candidate, not validated or complete Q1 evidence.
     */
    public record Section(int pageNumber, EvidenceRole role, int assayId, String text,
                          boolean activityTable, boolean imageReadRequired, boolean q1Eligible) {
    }

    private static final Pattern BOUNDARY = Pattern.compile(
            "(?im)^(?=\\h*(?:\\[\\d{4,}\\]|TEST\\s+[A-H]\\b|(?:\u7eed\\s*)?\u8868\\s*\\d|Table\\s+\\d|"
                    + "\u80cc\u666f\u6280\u672f|\u6280\u672f\u9886\u57df|\u53d1\u660e\u5185\u5bb9|\u5177\u4f53\u5b9e\u65bd\u65b9\u5f0f|\u6743\\h*\u5229\\h*\u8981\\h*\u6c42|"
                    + "Background\\b|Claims\\b|What is claimed|Summary\\b|Detailed description\\b|"
                    + "Biological (?:examples?|tests?)\\b|Bioassay\\b|"
                    + "\u8bd5\u9a8c\u4f8b|\u5b9e\u9a8c\u4f8b|\u751f\u7269(?:\u6d3b\u6027)?\u6d4b\u5b9a|\u751f\u6d4b\u8bd5\u9a8c|\u6bd2\u529b\u6d4b\u5b9a|\u836f\u6548\u8bd5\u9a8c|"
                    + "[^\\r\\n\u3002]{1,60}(?:\u6548\u679c\u5b9e\u9a8c|\u6548\u679c\u8bd5\u9a8c)|"
                    + "CN\\s*\\d+\\s*[A-Z]\\b|\\d+\\h*$))|"
                    + "(?<=\\n)(?=\\h*\\r?$)|"
                    + "(?<=[\u3002\uff1b])(?=\u7ed3\u679c[\uff0c,:\uff1a])");
    private static final Pattern PARAGRAPH_NUMBER = Pattern.compile("^\\s*\\[\\d{4,}\\]\\s*");
    private static final Pattern PAGE_HEADER_LINE = Pattern.compile(
            "(?im)^\\h*CN\\s*\\d+\\s*[A-Z]\\b[^\\r\\n]*(?:\\R|$)");
    private static final Pattern FURNITURE = Pattern.compile(
            "^(?:CN\\s*\\d+\\s*[A-Z]\\b.*|\\d+|\u8bf4\\s*\u660e\\s*\u4e66(?:\u9644\u56fe)?.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BACKGROUND = pattern("^(?:\u80cc\u666f\u6280\u672f|\u6280\u672f\u80cc\u666f|Background\\b)");
    private static final Pattern CLAIMS = pattern("^(?:\u6743\u5229\u8981\u6c42(?:\u4e66)?|Claims?\\b|What is claimed\\b)");
    private static final Pattern DISCLOSURE = pattern(
            "^(?:\u53d1\u660e\u5185\u5bb9|\u53d1\u660e\u6982\u8981|Summary\\b)|\u6709\u76ca\u6548\u679c(?:\u5728\u4e8e|\u4e3a|\u5982\u4e0b|[:\uff1a])");
    private static final Pattern OTHER_HEADING = pattern(
            "^(?:\u6280\u672f\u9886\u57df|\u5177\u4f53\u5b9e\u65bd\u65b9\u5f0f|\u4ea7\u4e1a\u4e0a\u7684\u53ef\u5229\u7528\u6027|\u5de5\u4e1a\u5b9e\u7528\u6027|"
                    + "Detailed description\\b|Industrial applicability\\b|"
                    + "(?:\u5236\u9020\u4f8b|\u5236\u5907\u4f8b|\u5408\u6210\u4f8b|\u5236\u5242\u4f8b|\u5bf9\u6bd4\u4f8b|\u5b9e\u65bd\u4f8b)\\s*[\\d\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]+|"
                    + "(?:Synthesis|Preparation|Formulation)\\s+(?:example|of)\\b)");
    private static final Pattern ASSAY_HEADING = pattern(
            "^(?:\\d+(?:\\.\\d+)*[.\u3001:\uff1a]?\\s*)?(?:"
                    + "(?:\u8bd5\u9a8c\u4f8b|\u5b9e\u9a8c\u4f8b)\\s*[\\d\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]*|"
                    + "(?:\u8bd5\u9a8c|\u5b9e\u9a8c)(?:\u65b9\u6cd5|\u6982\u51b5|\u6750\u6599|\u8bbe\u8ba1|\u6761\u4ef6|\u5bf9\u8c61)|"
                    + "\u751f\u7269(?:\u6d3b\u6027)?\u6d4b\u5b9a|\u751f\u6d4b(?:\u8bd5\u9a8c)?|(?:\u5ba4\u5185)?\u6bd2\u529b\u6d4b\u5b9a|"
                    + "\u836f\u6548\u8bd5\u9a8c|\u9632\u6548\u8bd5\u9a8c|\u6291\u83cc\u8bd5\u9a8c|\u6297\u83cc\u8bd5\u9a8c|\u6d3b\u6027\u6d4b\u8bd5|"
                    + "TEST\\s+[A-H]\\b|Bioassay\\b|[^\u3002]{1,60}(?:\u6548\u679c\u5b9e\u9a8c|\u6548\u679c\u8bd5\u9a8c)|"
                    + "(?:Biological|Fungicidal)\\s+(?:tests?|examples?|assays?)\\b)");
    private static final Pattern NUMBERED_ASSAY = pattern(
            "^(?:(?:\u8bd5\u9a8c\u4f8b|\u5b9e\u9a8c\u4f8b)\\s*[\\d\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341]+|TEST\\s+([A-H])\\b)");
    private static final Pattern PROCEDURE = pattern(
            "\u8bd5\u9a8c(?:\u91c7\u7528|\u8bbe\u7f6e|\u76ee\u6807\u7269|\u8bbe\u8ba1|\u65b9\u6cd5|\u6761\u4ef6)|\u5b9e\u9a8c(?:\u91c7\u7528|\u8bbe\u7f6e|\u65b9\u6cd5)|"
                    + "\u4f9b\u8bd5(?:\u83cc\u682a|\u75c5\u539f|\u836f\u5242)|\u63a5\u79cd|\u83cc\u843d\u76f4\u5f84\u6cd5|\u6bd2\u529b\u56de\u5f52|"
                    + "\u5438\u5149\u5ea6|\u5b9e\u9a8c\u7ec4|\u5bf9\u7167\u7ec4|\\binoculat\\w*|\\bincubat\\w*|"
                    + "were\\s+(?:treated|sprayed|measured)|disease ratings?");
    private static final Pattern RESULT = pattern(
            "^(?:\\d+[\u3001.\uff1a:]\\s*)?(?:\u7ed3\u679c(?:\u53ca\u5206\u6790|\u4e0e\u5206\u6790|[\uff0c,:\uff1a])|"
                    + "(?:\u8bd5\u9a8c|\u5b9e\u9a8c|\u6d4b\u5b9a)\u7ed3\u679c|\u4ece\u8868\\s*\\d|\u7531\u8868\\s*\\d|"
                    + "Results?\\b)|\u7ed3\u679c(?:\u89c1|\u5217\u4e8e|\u5982)|\\bresults?\\s+(?:are|were|shown|in)\\b");
    private static final Pattern CLAIMED_USE = pattern(
            "\u53ef(?:\u7528\u4e8e|\u9632\u6cbb|\u6291\u5236|\u5ef6\u7f13)|\u80fd\u591f\u9632\u9664|\u6709\u76ca\u6548\u679c|"
                    + "\u5728\u9632\u6cbb.{0,100}\u4e2d\u7684\u5e94\u7528|(?:\u53ef\u4e3e\u51fa|\u5305\u62ec).{0,40}(?:\u4ee5\u4e0b|\u4e0b\u5217).{0,20}(?:\u75c5\u5bb3|\u4f8b\u5b50)|"
                    + "(?:\u672c\u53d1\u660e|\u6240\u8ff0).{0,120}(?:\u7528\u4e8e\u9632\u6cbb|\u5177\u6709\u6548\u529b|\u5177\u6709.{0,15}\u4f5c\u7528)|"
                    + "\\b(?:can|may)\\s+(?:be used|control|inhibit)|"
                    + "\\b(?:useful|suitable)\\s+for\\b");
    private static final Pattern OOMYCETE = pattern(
            "oomyc|phytophthora|pythium|plasmopara|peronospora|bremia|albugo|"
                    + "downy mildew|late blight|\u75ab\u9709|\u8150\u9709|\u971c\u9709|\u5375\u83cc|\u665a\u75ab\u75c5");
    private static final Pattern ACTIVITY = pattern(
            "\\b(?:ic\\s*50|ec\\s*50|mic|ctc)\\b|inhibi|\\bactivity\\b|efficacy|"
                    + "disease rating|fungicidal|percent(?:age)? control|"
                    + "\u9632\u6548|\u6291\u5236\u7387|\u6291\u83cc|\u6297\u83cc|\u6bd2\u529b|\u836f\u6548|\u751f\u7269\u6d3b\u6027|\u6740\u83cc\u6d3b\u6027|"
                    + "\u83cc\u4e1d\u751f\u957f|\u751f\u957f\u6291\u5236|\u9632\u9664\u8bd5\u9a8c");
    private static final Pattern CAPTION = pattern("^(?:\u7eed\\s*)?(?:\u8868\\s*\\d+(?:[-\uff0d]\\d+)?|Table\\s+[\\dIVX]+)");
    private static final Pattern TABLE_HEADER = pattern(
            "^(?:Cmpd\\s*(?:No\\.?|Number)|Compound\\s*(?:No\\.?|Number)|"
                    + "\u5316\u5408\u7269(?:\u7f16\u53f7|\u5e8f\u53f7|\u53f7)?|\u5904\u7406\u836f\u5242|\u4f9b\u8bd5\u836f\u5242|\u836f\u5242\u540d\u79f0)\\s*");
    private static final Pattern TEST_COLUMN = pattern("\\bTest\\s+([A-H])\\b");
    private static final Pattern ENDPOINT_COLUMN = pattern(
            "(?:\\b(?:EC\\s*50|IC\\s*50|MIC|CTC)\\b|\u6291\u5236\u7387|\u9632\u6548)"
                    + "\\s*(?:[\uff08(][^\uff09)]{0,24}[\uff09)])?\\s*$");
    private static final Pattern DATA_ROW = Pattern.compile(
            "(?im)^\\h*(?:(?:\u5b9e\u65bd\u4f8b|\u5bf9\u6bd4\u4f8b|\u5316\u5408\u7269|Compound|Cmpd)\\h*)?"
                    + "\\d{1,5}\\h+(?:[-+]?\\d|\\*{1,3}|[-+\uff0d\u2014]|[<>\u2264\u2265])");
    private static final Pattern NON_ACTIVITY_TABLE = pattern(
            "\u914d\u65b9|\u539f\u6599|\u7ed3\u6784|\u5408\u6210|\u5236\u5907|\u571f\u58e4|\u4ea7\u91cf|\u53ef\u6eb6\u6027\u94a0|formulation|synthesis|structure|yield|soil");

    private PatentEvidenceSections() {
    }

    public static List<Section> analyze(List<PatentPageText> pages) {
        List<Section> sections = new ArrayList<>();
        Map<Integer, Boolean> targets = new HashMap<>();
        Map<Integer, Boolean> activities = new HashMap<>();
        Map<String, Integer> testDefinitions = new HashMap<>();
        Map<Integer, Set<String>> tableTests = new HashMap<>();
        EvidenceRole context = EvidenceRole.OTHER;
        EvidenceRole phase = EvidenceRole.METHOD;
        int nextAssay = 0;
        int activeAssay = 0;
        int previousPage = -1;
        boolean continuingTable = false;
        Set<String> continuingTests = Set.of();

        for (PatentPageText page : pages.stream().sorted(Comparator.comparingInt(PatentPageText::pageNumber)).toList()) {
            if (previousPage != -1 && page.pageNumber() != previousPage + 1) {
                context = EvidenceRole.OTHER;
                activeAssay = 0;
                continuingTable = false;
                continuingTests = Set.of();
            }
            previousPage = page.pageNumber();
            for (String original : paragraphs(page.text())) {
                String text = normalized(original);
                EvidenceRole role;
                boolean table = false;
                boolean furniture = text.isEmpty() || FURNITURE.matcher(text).matches();
                boolean claimsHeader = text.contains("\u6743\u5229\u8981\u6c42\u4e66") && furniture;
                boolean newAssay = NUMBERED_ASSAY.matcher(text).find();
                boolean assayHeading = ASSAY_HEADING.matcher(text).find();
                boolean procedure = PROCEDURE.matcher(text).find();
                boolean result = RESULT.matcher(text).find();
                boolean caption = CAPTION.matcher(text).find();
                Set<String> columns = testColumns(text);
                boolean matrix = TABLE_HEADER.matcher(text).find() && !columns.isEmpty();

                if (claimsHeader || CLAIMS.matcher(text).find()) {
                    context = EvidenceRole.CLAIMS;
                    activeAssay = 0;
                    role = context;
                } else if (furniture) {
                    role = EvidenceRole.OTHER;
                } else if (BACKGROUND.matcher(text).find()) {
                    context = EvidenceRole.BACKGROUND;
                    activeAssay = 0;
                    role = context;
                } else if (DISCLOSURE.matcher(text).find()) {
                    context = EvidenceRole.CLAIMED_USE;
                    activeAssay = 0;
                    role = context;
                } else if (OTHER_HEADING.matcher(text).find() && !assayHeading
                        && !(continuingTable && DATA_ROW.matcher(original).find())) {
                    context = EvidenceRole.OTHER;
                    activeAssay = 0;
                    role = context;
                } else if (context == EvidenceRole.BACKGROUND || context == EvidenceRole.CLAIMS) {
                    role = context;
                } else if (!result && !assayHeading && CLAIMED_USE.matcher(text).find()) {
                    role = EvidenceRole.CLAIMED_USE;
                    // A use list is not an extension of the preceding assay.
                    context = role;
                    activeAssay = 0;
                } else {
                    boolean activity = hasActivity(text);
                    table = matrix
                            || caption && (activity || activeAssay > 0
                            && activities.getOrDefault(activeAssay, false)
                            && !NON_ACTIVITY_TABLE.matcher(text).find())
                            || TABLE_HEADER.matcher(text).find() && activity
                            && (DATA_ROW.matcher(original).find() || ENDPOINT_COLUMN.matcher(text).find())
                            || continuingTable && DATA_ROW.matcher(original).find();
                    if (matrix) {
                        activeAssay = 0;
                    } else if (newAssay || assayHeading && phase == EvidenceRole.RESULT
                            || activeAssay == 0 && (assayHeading || procedure)) {
                        activeAssay = ++nextAssay;
                        phase = EvidenceRole.METHOD;
                        Matcher named = NUMBERED_ASSAY.matcher(text);
                        if (named.find() && named.group(1) != null) {
                            testDefinitions.put(named.group(1).toUpperCase(Locale.ROOT), activeAssay);
                        }
                    }
                    if (table || activeAssay > 0 && (result || caption)) {
                        role = EvidenceRole.RESULT;
                        phase = role;
                    } else if (activeAssay > 0) {
                        role = procedure || assayHeading ? EvidenceRole.METHOD : phase;
                    } else {
                        role = context;
                    }
                    if (activeAssay > 0 && (role == EvidenceRole.METHOD || role == EvidenceRole.RESULT)) {
                        targets.merge(activeAssay, OOMYCETE.matcher(text).find(), Boolean::logicalOr);
                        activities.merge(activeAssay, activity, Boolean::logicalOr);
                    }
                    if (table) {
                        Set<String> referencedTests = !columns.isEmpty() ? columns
                                : continuingTable && !caption ? continuingTests : Set.of();
                        if (!referencedTests.isEmpty()) {
                            tableTests.put(sections.size(), referencedTests);
                        }
                        continuingTests = referencedTests;
                    }
                }
                if (!furniture) {
                    continuingTable = table;
                }
                int assay = role == EvidenceRole.METHOD || role == EvidenceRole.RESULT ? activeAssay : 0;
                sections.add(new Section(page.pageNumber(), role, assay, original, table, false, false));
            }
        }

        // Resolve targets after the whole assay has been read, including page continuations.
        Set<Integer> eligible = new HashSet<>();
        targets.forEach((id, target) -> {
            if (target && activities.getOrDefault(id, false)) {
                eligible.add(id);
            }
        });
        List<Section> resolved = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            boolean q1 = eligible.contains(section.assayId());
            if (tableTests.containsKey(i)) {
                q1 = tableTests.get(i).stream().anyMatch(test -> eligible.contains(testDefinitions.get(test)));
            }
            resolved.add(new Section(section.pageNumber(), section.role(), section.assayId(), section.text(),
                    section.activityTable(), section.activityTable() && !hasTableBody(sections, i), q1));
        }
        return List.copyOf(resolved);
    }

    /** Adds metadata before each unchanged source slice; callers should pass one page's sections. */
    public static String annotate(List<Section> sections) {
        StringBuilder annotated = new StringBuilder();
        for (Section section : sections) {
            if (!annotated.isEmpty()) {
                annotated.append('\n');
            }
            annotated.append("[evidence_role=").append(section.role()).append(']');
            if (section.assayId() > 0) {
                annotated.append("[assay_id=").append(section.assayId()).append(']');
            }
            annotated.append("[q1_eligible=").append(section.q1Eligible()).append(']');
            if (section.imageReadRequired()) {
                annotated.append("[table_status=IMAGE_READ_REQUIRED]");
            }
            annotated.append('\n').append(section.text());
        }
        return annotated.toString();
    }

    private static List<String> paragraphs(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> paragraphs = new ArrayList<>();
        Set<Integer> boundaries = new TreeSet<>();
        Matcher matcher = BOUNDARY.matcher(text);
        while (matcher.find()) {
            boundaries.add(matcher.start());
        }
        Matcher headers = PAGE_HEADER_LINE.matcher(text);
        while (headers.find()) {
            boundaries.add(headers.end());
        }
        boundaries.add(text.length());
        int start = 0;
        for (int end : boundaries) {
            if (end > start) {
                paragraphs.add(text.substring(start, end));
                start = end;
            }
        }
        return paragraphs;
    }

    private static String normalized(String text) {
        String normalized = PARAGRAPH_NUMBER.matcher(text).replaceFirst("")
                .replaceAll("[\\s\\p{Z}]+", " ").strip();
        return normalized.replaceAll("(?<=\\p{IsHan}) (?=\\p{IsHan})", "");
    }

    private static boolean hasActivity(String text) {
        return ACTIVITY.matcher(text.replaceAll(
                "(?i)\u975e\u6d3b\u6027\u62c5\u8f7d\u4f53|\u975e\u6d3b\u6027\u8f7d\u4f53|\u8868\u9762\u6d3b\u6027\u5242|\u6d3b\u6027\u6210\u5206|\u6d3b\u6027\u70ad|"
                        + "inactive carrier|surfactants?|active ingredients?", "")).find();
    }

    private static Set<String> testColumns(String text) {
        Set<String> columns = new HashSet<>();
        Matcher matcher = TEST_COLUMN.matcher(text);
        while (matcher.find()) {
            columns.add(matcher.group(1).toUpperCase(Locale.ROOT));
        }
        return columns;
    }

    private static boolean hasTableBody(List<Section> sections, int index) {
        Section table = sections.get(index);
        if (DATA_ROW.matcher(table.text()).find()) {
            return true;
        }
        int previousPage = table.pageNumber();
        for (int i = index + 1; i < sections.size(); i++) {
            Section next = sections.get(i);
            if (next.pageNumber() > previousPage + 1) {
                break;
            }
            previousPage = next.pageNumber();
            String text = normalized(next.text());
            if (text.isEmpty() || FURNITURE.matcher(text).matches()) {
                continue;
            }
            if (CAPTION.matcher(text).find() || next.assayId() != table.assayId()
                    || next.role() != EvidenceRole.RESULT) {
                break;
            }
            if (DATA_ROW.matcher(next.text()).find()) {
                return true;
            }
            break;
        }
        return false;
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
