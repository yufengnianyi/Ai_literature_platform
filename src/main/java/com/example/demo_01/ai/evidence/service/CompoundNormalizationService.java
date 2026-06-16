package com.example.demo_01.ai.evidence.service;

import com.example.demo_01.ai.evidence.model.EvidenceModels.CompoundEvidenceRow;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NameKind;
import com.example.demo_01.ai.evidence.model.EvidenceModels.NormalizedEvidenceRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CompoundNormalizationService {

    private static final Pattern LOCAL_LABEL_PATTERN = Pattern.compile(
            "^(?:compound|deriv(?:ative)?|analog(?:ue)?|化合物|衍生物|类似物)\\s*"
                    + "(?:no\\.?|#|number)?\\s*[0-9]+[a-zA-Z]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_LOCAL_NUMBER = Pattern.compile(
            "\\s*\\(\\s*\\d+\\s*\\)\\s*$");
    private static final Pattern EXTRACT_INDICATOR = Pattern.compile(
            "(?i)(extract|fraction|提取物|精油|essential\\s+oil|crude|tincture|decoction|infusion)");
    private static final Pattern MIXTURE_INDICATOR = Pattern.compile(
            "(?i)(混合物|mixture|精油|essential\\s+oil|crude)");

    private final MarkdownEvidenceTableParser tableParser;

    public CompoundNormalizationService(MarkdownEvidenceTableParser tableParser) {
        this.tableParser = tableParser;
    }

    public List<NormalizedEvidenceRow> normalize(UUID documentId, List<CompoundEvidenceRow> rows) {
        List<NormalizedEvidenceRow> normalized = new ArrayList<>();
        for (CompoundEvidenceRow row : rows) {
            normalized.add(normalizeRow(documentId, row));
        }
        return List.copyOf(normalized);
    }

    public NormalizedEvidenceRow normalizeRow(UUID documentId, CompoundEvidenceRow row) {
        String fingerprint = tableParser.fingerprint(row);
        NameKind nameKind = classify(row);
        String dedupKey = dedupKey(documentId, row, nameKind);
        return new NormalizedEvidenceRow(row, fingerprint, nameKind, dedupKey);
    }

    NameKind classify(CompoundEvidenceRow row) {
        if (isLocalLabel(row)) {
            return NameKind.LOCAL_LABEL;
        }
        if (isNaturalExtract(row)) {
            return NameKind.NATURAL_EXTRACT;
        }
        return NameKind.PURE_COMPOUND;
    }

    String dedupKey(UUID documentId, CompoundEvidenceRow row, NameKind nameKind) {
        return switch (nameKind) {
            case LOCAL_LABEL -> "local:" + documentId + ":" + normalizeKey(localLabelKey(row));
            case NATURAL_EXTRACT -> "extract:" + normalizeKey(extractKey(row));
            case PURE_COMPOUND -> "compound:" + normalizeKey(compoundKey(row));
        };
    }

    boolean isLocalLabel(CompoundEvidenceRow row) {
        if (hasText(row.compoundStandardName())) {
            return false;
        }
        String original = value(row.compoundOriginalName());
        if (original.isBlank()) {
            return false;
        }
        return LOCAL_LABEL_PATTERN.matcher(original.trim()).matches();
    }

    boolean isNaturalExtract(CompoundEvidenceRow row) {
        if (containsPlantSource(row.sourceCategory())
                && (matches(EXTRACT_INDICATOR, row.compoundOriginalName())
                || matches(EXTRACT_INDICATOR, row.sourceDescription())
                || matches(MIXTURE_INDICATOR, row.structureType()))) {
            return true;
        }
        return matches(EXTRACT_INDICATOR, row.compoundOriginalName())
                && !hasText(row.compoundStandardName());
    }

    private String compoundKey(CompoundEvidenceRow row) {
        String standard = cleanName(row.compoundStandardName());
        if (!standard.isBlank()) {
            return standard;
        }
        return cleanName(row.compoundOriginalName());
    }

    private String localLabelKey(CompoundEvidenceRow row) {
        return cleanName(row.compoundOriginalName());
    }

    private String extractKey(CompoundEvidenceRow row) {
        String species = extractLatinSpecies(row);
        String part = extractPlantPart(row);
        String solvent = extractSolvent(row);
        return joinNonBlank(species, part, solvent);
    }

    private String extractLatinSpecies(CompoundEvidenceRow row) {
        String fromDescription = value(row.sourceDescription());
        if (!fromDescription.isBlank()) {
            return fromDescription.split("[,，;；]")[0].trim();
        }
        String fromName = value(row.compoundOriginalName());
        if (fromName.toLowerCase(Locale.ROOT).contains(" of ")) {
            String[] parts = fromName.split("(?i)\\s+of\\s+", 2);
            if (parts.length == 2) {
                return parts[1].trim();
            }
        }
        return fromName;
    }

    private String extractPlantPart(CompoundEvidenceRow row) {
        String description = value(row.sourceDescription());
        if (description.contains("，")) {
            String[] parts = description.split("，", 2);
            if (parts.length == 2) {
                return parts[1].trim();
            }
        }
        if (description.contains(",")) {
            String[] parts = description.split(",", 2);
            if (parts.length == 2) {
                return parts[1].trim();
            }
        }
        return "";
    }

    private String extractSolvent(CompoundEvidenceRow row) {
        String combined = (value(row.compoundOriginalName()) + " " + value(row.sourceDescription()))
                .toLowerCase(Locale.ROOT);
        if (combined.contains("aqueous") || combined.contains("水提") || combined.contains("水煎")) {
            return "aqueous";
        }
        if (combined.contains("methanol") || combined.contains("甲醇")) {
            return "methanol";
        }
        if (combined.contains("ethanol") || combined.contains("乙醇")) {
            return "ethanol";
        }
        if (combined.contains("ethyl acetate") || combined.contains("乙酸乙酯")) {
            return "ethyl_acetate";
        }
        if (combined.contains("hexane") || combined.contains("正己烷")) {
            return "hexane";
        }
        if (combined.contains("chloroform") || combined.contains("氯仿")) {
            return "chloroform";
        }
        if (combined.contains("essential oil") || combined.contains("精油")) {
            return "essential_oil";
        }
        return "unspecified";
    }

    private String cleanName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return TRAILING_LOCAL_NUMBER.matcher(value.trim()).replaceAll("").trim();
    }

    private String normalizeKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private boolean containsPlantSource(String sourceCategory) {
        String normalized = value(sourceCategory).toLowerCase(Locale.ROOT);
        return normalized.contains("植物") || normalized.contains("plant");
    }

    private boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).find();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String joinNonBlank(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('|');
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }
}
