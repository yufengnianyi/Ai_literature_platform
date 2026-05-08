package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.model.ReviewModels.AntimicrobialActivityItem;
import com.example.demo_01.ai.review.model.ReviewModels.KeyMetric;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts legacy antimicrobialActivity List&lt;String&gt; entries into structured
 * AntimicrobialActivityItem records for backward compatibility during the v2→v3 transition.
 */
public final class LegacyAntimicrobialActivityMigrator {

    private static final Pattern P_METRIC =
            Pattern.compile("\\b(MIC|MFC|EC50|IC50|LD50)\\s*[:=]?\\s*([\\d.]+\\s*[µμ]?g\\s*[/·]?\\s*mL|[\\d.]+\\s*[µμnm]M)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern P_PARADIGM =
            Pattern.compile("(mycelial growth|micro[- ]?well dilution|zoosporogenesis|plate inhibition|XTT|pot experiment|morphological)",
                    Pattern.CASE_INSENSITIVE);

    private LegacyAntimicrobialActivityMigrator() {}

    public static List<AntimicrobialActivityItem> migrate(List<String> legacyActivities) {
        if (legacyActivities == null || legacyActivities.isEmpty()) return List.of();
        List<AntimicrobialActivityItem> items = new ArrayList<>();
        for (String activity : legacyActivities) {
            if (activity == null || activity.isBlank()) continue;
            items.add(parseOne(activity));
        }
        return items;
    }

    private static AntimicrobialActivityItem parseOne(String text) {
        KeyMetric keyMetric = null;
        Matcher metricMatcher = P_METRIC.matcher(text);
        if (metricMatcher.find()) {
            keyMetric = new KeyMetric(metricMatcher.group(1).toUpperCase(), metricMatcher.group(2).trim(), null);
        }

        String paradigm = null;
        Matcher paradigmMatcher = P_PARADIGM.matcher(text);
        if (paradigmMatcher.find()) {
            paradigm = paradigmMatcher.group(1);
        }

        boolean doseDependent = text.toLowerCase().contains("dose-dependent")
                || text.toLowerCase().contains("concentration-dependent");

        return new AntimicrobialActivityItem(
                paradigm, List.of(), keyMetric, doseDependent ? true : null,
                null, null, null, text, text);
    }
}
