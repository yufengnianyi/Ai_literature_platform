package com.example.demo_01.ai.evidence.table;

import com.example.demo_01.ai.evidence.model.EvidenceModels.EvidenceChunk;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the "codes" that pollute scientific tables so the extractor can fill primary fields:
 * <ul>
 *   <li>footnote markers ({@code a) b) c) ...}) &rarr; kept verbatim as legend lines;</li>
 *   <li>abbreviated genus names ({@code A. camptosporum}) &rarr; expanded from the full binomial
 *       found elsewhere in the paper;</li>
 *   <li>explicit abbreviation definitions ({@code Pc = Phytophthora capsici}).</li>
 * </ul>
 *
 * <p>Resolution is deterministic and grounded: every expansion cites the context chunk that
 * supports it, so the extractor can anchor the completed value to real text.</p>
 */
@Component
public class TableLegendResolver {

    private static final Pattern ABBREVIATED_BINOMIAL =
            Pattern.compile("\\b([A-Z])\\.\\s*([a-z][a-z-]{2,})\\b");
    private static final Pattern EXPLICIT_DEFINITION =
            Pattern.compile("\\b([A-Z][A-Za-z]{0,4})\\s*=\\s*([A-Z][a-z]+\\s+[a-z]+)");

    /**
     * @param legendText          synthesized {@code code = meaning} lines (for readability)
     * @param supportingChunkIds  IDs of context chunks that define the codes
     * @param supportingQuotes    the verbatim source sentences that define the codes; these are
     *                            embedded into the table chunk so the definition is always
     *                            co-located with the table and remains anchorable after batching
     */
    public record TableLegend(String legendText,
                              Set<String> supportingChunkIds,
                              List<String> supportingQuotes) {
    }

    public TableLegend resolve(ParsedTable table, List<EvidenceChunk> contextChunks) {
        Map<String, String> resolved = new LinkedHashMap<>();
        Set<String> supporting = new LinkedHashSet<>();
        Set<String> quotes = new LinkedHashSet<>();

        String haystack = tableSurfaceText(table);
        Matcher abbreviated = ABBREVIATED_BINOMIAL.matcher(haystack);
        while (abbreviated.find()) {
            String initial = abbreviated.group(1);
            String species = abbreviated.group(2);
            String key = initial + ". " + species;
            if (resolved.containsKey(key)) {
                continue;
            }
            expandFromContext(initial, species, contextChunks).ifPresent(hit -> {
                resolved.put(key, hit.fullName());
                if (hit.chunkId() != null) {
                    supporting.add(hit.chunkId());
                }
                quotes.add(hit.quote());
            });
        }

        for (EvidenceChunk chunk : contextChunks) {
            String text = value(chunk.text());
            Matcher definition = EXPLICIT_DEFINITION.matcher(text);
            while (definition.find()) {
                String code = definition.group(1);
                String meaning = definition.group(2).trim();
                if (resolved.putIfAbsent(code, meaning) == null) {
                    if (chunk.chunkId() != null) {
                        supporting.add(chunk.chunkId());
                    }
                    quotes.add(sentenceContaining(text, definition.start(), definition.end()));
                }
            }
        }

        StringBuilder legend = new StringBuilder();
        if (!resolved.isEmpty()) {
            legend.append("\nLegend (resolved from the paper's context):");
            for (Map.Entry<String, String> entry : resolved.entrySet()) {
                legend.append("\n- ").append(entry.getKey()).append(" = ").append(entry.getValue());
            }
        }
        return new TableLegend(legend.toString(), Set.copyOf(supporting), List.copyOf(quotes));
    }

    private record Expansion(String fullName, String chunkId, String quote) {
    }

    private java.util.Optional<Expansion> expandFromContext(String initial,
                                                            String species,
                                                            List<EvidenceChunk> contextChunks) {
        Pattern fullBinomial = Pattern.compile(
                "\\b(" + Pattern.quote(initial) + "[a-z]+)\\s+" + Pattern.quote(species) + "\\b");
        for (EvidenceChunk chunk : contextChunks) {
            String text = value(chunk.text());
            Matcher matcher = fullBinomial.matcher(text);
            if (matcher.find()) {
                return java.util.Optional.of(new Expansion(
                        matcher.group(1) + " " + species,
                        chunk.chunkId(),
                        sentenceContaining(text, matcher.start(), matcher.end())));
            }
        }
        return java.util.Optional.empty();
    }

    /** Return the verbatim sentence in {@code text} that spans [start, end). */
    private String sentenceContaining(String text, int start, int end) {
        int from = start;
        while (from > 0 && ".!?。".indexOf(text.charAt(from - 1)) < 0) {
            from--;
        }
        int to = end;
        while (to < text.length() && ".!?。".indexOf(text.charAt(to)) < 0) {
            to++;
        }
        if (to < text.length()) {
            to++; // include the terminating punctuation
        }
        return text.substring(from, to).trim();
    }

    private String tableSurfaceText(ParsedTable table) {
        StringBuilder builder = new StringBuilder(value(table.caption()));
        for (String header : table.headers()) {
            builder.append(' ').append(value(header));
        }
        for (List<String> row : table.rows()) {
            if (!row.isEmpty()) {
                builder.append(' ').append(value(row.getFirst()));
            }
        }
        return builder.toString();
    }

    private String value(String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
