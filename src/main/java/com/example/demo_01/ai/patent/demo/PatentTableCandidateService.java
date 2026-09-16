package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentImageRegion;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageAssessment;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageIndexRecord;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentPageRole;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentSubject;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTableCandidate;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTableCandidateSource;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTableKind;
import com.example.demo_01.ai.patent.demo.PatentDemoModels.PatentTriageResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PatentTableCandidateService {

    private static final Pattern CAPTION = Pattern.compile("(?im)^\\s*(?:续\\s*)?(?:表\\s*\\d+(?:[-－]\\d+)?|table\\s+[\\dIVX]+)\\b?.{0,120}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MATRIX = Pattern.compile(
            "(?:EC\\s*50|IC\\s*50|MIC|CTC|共毒系数|抑制率|防效).{0,80}(?:处理|药剂|compound|cmpd|treatment|ratio|配比|比例)"
                    + "|(?:处理|药剂|compound|cmpd|treatment|ratio|配比|比例).{0,80}(?:EC\\s*50|IC\\s*50|MIC|CTC|共毒系数|抑制率|防效)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVITY = Pattern.compile("EC\\s*50|IC\\s*50|MIC|CTC|共毒系数|毒力|抑制|防效|活性|fungicidal|activity|inhibition",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMULATION = Pattern.compile("配方|制剂|悬浮剂|乳油|可湿性粉剂|水分散粒剂|formulation|composition",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STRUCTURE = Pattern.compile("结构|通式|取代基|合成|制备|formula|structure|synthesis|preparation",
            Pattern.CASE_INSENSITIVE);

    public List<PatentTableCandidate> candidates(PatentTriageResult triage,
                                                  List<PatentPageIndexRecord> pageIndex,
                                                  PatentSubject subject) {
        Map<Integer, PatentPageIndexRecord> index = pageIndex.stream()
                .collect(Collectors.toMap(PatentPageIndexRecord::pageNumber, page -> page,
                        (left, right) -> left));
        List<PatentTableCandidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int sequence = 1;
        for (PatentPageAssessment page : triage.pages().stream()
                .sorted(Comparator.comparingInt(PatentPageAssessment::pageNumber)).toList()) {
            boolean selectedContext = page.selected()
                    || page.roles().contains(PatentPageRole.TEST_DEFINITION)
                    || page.roles().contains(PatentPageRole.ACTIVITY_TABLE)
                    || page.roles().contains(PatentPageRole.Q1_ACTIVITY);
            for (String line : lines(page.text())) {
                if (CAPTION.matcher(line).find()) {
                    PatentTableKind kind = kind(line, page);
                    boolean read = selectedContext && kind != PatentTableKind.FORMULATION
                            && kind != PatentTableKind.STRUCTURE;
                    sequence = add(result, seen, sequence, page.pageNumber(), PatentTableCandidateSource.TEXT_CAPTION,
                            kind, read, line.strip(), reason("caption", subject), null);
                }
                if (MATRIX.matcher(line).find()) {
                    PatentTableKind kind = kind(line, page);
                    sequence = add(result, seen, sequence, page.pageNumber(), PatentTableCandidateSource.TEXT_MATRIX,
                            kind, selectedContext, preview(line), reason("matrix", subject), null);
                }
            }
            PatentPageIndexRecord pageRecord = index.get(page.pageNumber());
            if (pageRecord == null || pageRecord.imageRegions().isEmpty()) {
                continue;
            }
            for (PatentImageRegion region : pageRecord.imageRegions()) {
                if (region.width() < 100 || region.height() < 30) {
                    continue;
                }
                boolean read = selectedContext && (page.roles().contains(PatentPageRole.ACTIVITY_TABLE)
                        || page.reason().contains("table-image-read-required"));
                sequence = add(result, seen, sequence, page.pageNumber(), PatentTableCandidateSource.PDF_IMAGE,
                        read ? PatentTableKind.ACTIVITY : PatentTableKind.UNKNOWN,
                        read, null, read ? "image-near-assay-context" : "image-region-indexed", region);
            }
        }
        return List.copyOf(result);
    }

    private int add(List<PatentTableCandidate> result, Set<String> seen, int sequence,
                    int page, PatentTableCandidateSource source, PatentTableKind kind,
                    boolean selectedForRead, String caption, String reason, PatentImageRegion region) {
        String key = page + "|" + source + "|" + normal(caption) + "|"
                + (region == null ? "" : region.x() + "," + region.y() + "," + region.width() + "," + region.height());
        if (!seen.add(key)) {
            return sequence;
        }
        result.add(new PatentTableCandidate("TC" + sequence, page, source, kind, selectedForRead,
                caption, reason, region));
        return sequence + 1;
    }

    private PatentTableKind kind(String text, PatentPageAssessment page) {
        String value = text == null ? "" : text;
        if (ACTIVITY.matcher(value).find() || page.roles().contains(PatentPageRole.ACTIVITY_TABLE)
                || page.roles().contains(PatentPageRole.Q1_ACTIVITY)) {
            return PatentTableKind.ACTIVITY;
        }
        if (FORMULATION.matcher(value).find()) {
            return PatentTableKind.FORMULATION;
        }
        if (STRUCTURE.matcher(value).find()) {
            return PatentTableKind.STRUCTURE;
        }
        return PatentTableKind.UNKNOWN;
    }

    private String reason(String signal, PatentSubject subject) {
        String suffix = subject == null || subject.subjectType() == null
                ? "" : ";subject=" + subject.subjectType();
        return signal + suffix;
    }

    private List<String> lines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return text.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
    }

    private String preview(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private String normal(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
