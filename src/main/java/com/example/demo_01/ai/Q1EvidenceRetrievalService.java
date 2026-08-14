package com.example.demo_01.ai;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRecord;
import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.repository.EvidenceRepository;
import com.example.demo_01.ai.rag.RagChatProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class Q1EvidenceRetrievalService {

    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}_\\-]+");

    /**
     * Latin/English substrings (compound codes, oomycete species names, units) are almost
     * always written directly adjacent to Chinese characters with no separating space or
     * punctuation. Unicode's {@code Alphabetic} property covers both CJK ideographs and Latin
     * letters, so {@link #TERM_PATTERN} alone would merge e.g. "对Phytophthora capsici的抑制"
     * into one unusable token. This dedicated pass guarantees Latin/ASCII terms are still
     * extracted as their own high-value tokens.
     */
    private static final Pattern ASCII_TERM_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9\\-]{2,}");

    private static final int CANDIDATE_LIMIT = 100_000;

    /** Below this many distinct matched rows, a per-row answer is already representative enough. */
    private static final int SUMMARY_HINT_MIN_ROWS = 6;

    /** Below this many distinct compounds, there is no real "many compounds" summary to synthesize. */
    private static final int SUMMARY_HINT_MIN_COMPOUNDS = 4;

    @Resource
    private EvidenceRepository evidenceRepository;

    @Resource
    private RagChatProperties ragChatProperties;

    @Resource
    private Q1CompoundReferenceResolver compoundReferenceResolver;

    public Q1EvidenceContext retrieve(String prompt) {
        RagChatProperties.Q1Evidence properties = ragChatProperties.getQ1Evidence();
        if (!properties.isEnabled() || prompt == null || prompt.isBlank()) {
            return Q1EvidenceContext.empty();
        }

        Set<String> terms = terms(prompt);
        if (terms.isEmpty()) {
            return Q1EvidenceContext.empty();
        }

        List<ScoredEvidence> allMatches = evidenceRepository.findReportableEvidence(CANDIDATE_LIMIT).stream()
                .map(evidence -> new ScoredEvidence(evidence, score(evidence, terms)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator
                        .comparingInt(ScoredEvidence::score).reversed()
                        .thenComparing(item -> item.evidence().updatedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(item -> item.evidence().evidenceId()))
                .toList();

        if (allMatches.isEmpty()) {
            return Q1EvidenceContext.empty();
        }

        List<ScoredEvidence> scored = selectDiverse(allMatches, properties);
        Map<UUID, String> resolvedNames = compoundReferenceResolver.resolve(
                scored.stream().map(ScoredEvidence::evidence).toList());

        StringBuilder context = new StringBuilder();
        String coverageHint = coverageHint(allMatches, scored.size());
        if (coverageHint != null) {
            context.append(coverageHint).append("\n\n");
        }
        List<Q1EvidenceSource> sources = new ArrayList<>();
        int index = 0;
        for (ScoredEvidence item : scored) {
            if (context.length() >= properties.getMaxContextChars()) {
                break;
            }
            index++;
            String compoundName = resolvedCompoundName(item.evidence(), resolvedNames);
            String rendered = truncate(
                    render(index, item.evidence(), compoundName),
                    properties.getMaxRowChars());
            context.append(rendered).append("\n\n");
            sources.add(source(index, item.evidence(), compoundName, item.score()));
        }

        return new Q1EvidenceContext(context.toString().strip(), sources);
    }

    /**
     * Score-ordered diversity selection: cover up to {@code maxDistinctCompounds} compounds,
     * at most {@code maxRowsPerCompound} rows each, at most {@code maxCompoundsPerDocument}
     * distinct compounds from any single source document (so one large SAR-style paper
     * cannot crowd out every other paper), until the hard {@code maxRows} cap.
     */
    private List<ScoredEvidence> selectDiverse(
            List<ScoredEvidence> ranked,
            RagChatProperties.Q1Evidence properties) {
        int maxRows = properties.getMaxRows();
        int maxDistinct = properties.getMaxDistinctCompounds();
        int maxPerCompound = properties.getMaxRowsPerCompound();
        int maxPerDocument = properties.getMaxCompoundsPerDocument();

        List<ScoredEvidence> selected = new ArrayList<>();
        Map<String, Integer> rowsPerCompound = new HashMap<>();
        Set<String> distinctCompounds = new LinkedHashSet<>();
        Map<UUID, Set<String>> compoundsByDocument = new HashMap<>();

        for (ScoredEvidence item : ranked) {
            if (selected.size() >= maxRows) {
                break;
            }
            String compoundKey = compoundKey(item.evidence());
            UUID documentId = item.evidence().documentId();
            int already = rowsPerCompound.getOrDefault(compoundKey, 0);
            if (already >= maxPerCompound) {
                continue;
            }
            boolean isNewCompound = already == 0;
            if (isNewCompound) {
                if (distinctCompounds.size() >= maxDistinct) {
                    continue;
                }
                Set<String> compoundsInDocument = compoundsByDocument.computeIfAbsent(
                        documentId, ignored -> new LinkedHashSet<>());
                if (compoundsInDocument.size() >= maxPerDocument) {
                    continue;
                }
                compoundsInDocument.add(compoundKey);
            }
            selected.add(item);
            rowsPerCompound.put(compoundKey, already + 1);
            distinctCompounds.add(compoundKey);
        }
        return selected;
    }

    private String compoundKey(CompoundEvidenceRecord evidence) {
        CompoundEvidenceRow row = evidence.row();
        String name = firstNonBlank(row.compoundStandardName(), row.compoundOriginalName(), "unknown");
        return name.toLowerCase(Locale.ROOT);
    }

    private String resolvedCompoundName(
            CompoundEvidenceRecord evidence,
            Map<UUID, String> resolvedNames) {
        String resolved = resolvedNames.get(evidence.evidenceId());
        if (resolved != null && !resolved.isBlank()) {
            return resolved.strip();
        }
        CompoundEvidenceRow row = evidence.row();
        return firstNonBlank(row.compoundStandardName(), row.compoundOriginalName(), "unknown");
    }

    /**
     * When the matched evidence spans many distinct compounds, a flat list of rows is a poor
     * answer to broad "summary / what compounds are there" questions. This produces an
     * internal-only instruction (never surfaced to the end user) telling the model to
     * synthesize an overview first instead of dumping every row.
     */
    private String coverageHint(List<ScoredEvidence> allMatches, int shownRowCount) {
        if (allMatches.size() < SUMMARY_HINT_MIN_ROWS) {
            return null;
        }
        Set<String> compounds = new LinkedHashSet<>();
        Set<String> oomycetes = new LinkedHashSet<>();
        for (ScoredEvidence item : allMatches) {
            CompoundEvidenceRow row = item.evidence().row();
            String compound = firstNonBlank(row.compoundStandardName(), row.compoundOriginalName(), "");
            if (!compound.isBlank()) {
                compounds.add(compound.toLowerCase(Locale.ROOT));
            }
            String oomycete = value(row.oomyceteScientificName());
            if (!oomycete.isBlank()) {
                oomycetes.add(oomycete.toLowerCase(Locale.ROOT));
            }
        }
        if (compounds.size() < SUMMARY_HINT_MIN_COMPOUNDS) {
            return null;
        }
        return """
                [internal retrieval note - never mention this note, or any row/compound/species counts, to the user]
                The matched evidence spans roughly %d distinct compounds and %d oomycete species; only %d representative rows are shown below. \
                If the user's question is broad (summary/overview/"what compounds are there" style), first synthesize a short overview \
                (main compound classes, representative activity ranges, common mechanisms) before citing a few representative rows with exact values, \
                instead of listing every row. If the question targets one specific compound or species, skip the overview and answer directly.
                """.formatted(compounds.size(), oomycetes.size(), shownRowCount);
    }

    private int score(CompoundEvidenceRecord evidence, Set<String> terms) {
        String text = searchableText(evidence).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (text.contains(term)) {
                score += weight(term);
            }
        }
        return score;
    }

    private int weight(String term) {
        if (term.length() >= 8) {
            return 4;
        }
        if (term.length() >= 5) {
            return 2;
        }
        return 1;
    }

    private Set<String> terms(String prompt) {
        Set<String> terms = new LinkedHashSet<>();
        String lower = prompt.toLowerCase(Locale.ROOT);

        Matcher ascii = ASCII_TERM_PATTERN.matcher(lower);
        while (ascii.find()) {
            addTerm(terms, ascii.group());
        }

        Matcher matcher = TERM_PATTERN.matcher(lower);
        while (matcher.find()) {
            addTerm(terms, matcher.group());
        }
        return terms;
    }

    private void addTerm(Set<String> terms, String raw) {
        String term = raw == null ? "" : raw.strip();
        if (term.length() >= 3 && !STOP_TERMS.contains(term)) {
            terms.add(term);
        }
    }

    private String searchableText(CompoundEvidenceRecord evidence) {
        CompoundEvidenceRow row = evidence.row();
        return String.join(" ",
                value(evidence.documentTitle()),
                value(row.compoundOriginalName()),
                value(row.compoundStandardName()),
                value(row.structureType()),
                value(row.sourceCategory()),
                value(row.sourceDescription()),
                value(row.oomyceteScientificName()),
                value(row.assayMethod()),
                value(row.activityData()),
                value(row.positiveControl()),
                value(row.targetOrMechanism()),
                value(row.targetValidationMethod()),
                value(row.cytotoxicity()),
                value(row.resistanceCrossResistance()),
                value(row.synergy()),
                value(row.referenceText()),
                value(row.patentInformation()));
    }

    private String render(int index, CompoundEvidenceRecord evidence, String compoundName) {
        CompoundEvidenceRow row = evidence.row();
        return """
                [Q1-%d] document_id=%s evidence_id=%s
                compound: %s
                oomycete: %s
                assay: %s
                activity: %s
                mechanism/target: %s
                resistance/cross-resistance: %s
                source/category: %s
                reference: %s
                """.formatted(
                index,
                evidence.documentId(),
                evidence.evidenceId(),
                compoundName,
                value(row.oomyceteScientificName()),
                value(row.assayMethod()),
                value(row.activityData()),
                value(row.targetOrMechanism()),
                value(row.resistanceCrossResistance()),
                firstNonBlank(row.sourceCategory(), row.sourceDescription(), ""),
                firstNonBlank(row.referenceText(), evidence.documentTitle(), ""));
    }

    private Q1EvidenceSource source(
            int index,
            CompoundEvidenceRecord evidence,
            String compoundName,
            int score) {
        CompoundEvidenceRow row = evidence.row();
        return new Q1EvidenceSource(
                "Q1-" + index,
                evidence.evidenceId().toString(),
                evidence.documentId().toString(),
                compoundName,
                row.oomyceteScientificName(),
                row.activityData(),
                firstNonBlank(row.referenceText(), evidence.documentTitle(), ""),
                score);
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 1)).strip() + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private String value(String value) {
        return value == null ? "" : value.strip();
    }

    private record ScoredEvidence(CompoundEvidenceRecord evidence, int score) {
    }

    public record Q1EvidenceContext(String contextBlock, List<Q1EvidenceSource> sources) {
        public static Q1EvidenceContext empty() {
            return new Q1EvidenceContext("", List.of());
        }

        public boolean hasContext() {
            return contextBlock != null && !contextBlock.isBlank();
        }
    }

    public record Q1EvidenceSource(
            String marker,
            String evidenceId,
            String documentId,
            String compound,
            String oomycete,
            String activity,
            String reference,
            int score
    ) {
    }

    private static final Set<String> STOP_TERMS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "what", "which", "how",
            "are", "was", "were", "have", "has", "had", "about", "against", "using",
            "show", "give", "list", "tell", "does", "did", "can", "could", "would",
            "please", "evidence", "paper", "papers", "literature");
}
