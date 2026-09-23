package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.EvidenceProfileRegistry.EvidenceProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source field definitions are retained separately from task-specific prompt instructions. */
final class ExpertQuestionDefinitions {
    static final String RESOURCE = "/prompts/evidence/expert-q8/definitions.json";
    private static final List<String> KEYS = List.of(
            "gene_function", "host_interaction", "host_resistance", "genome_evolution",
            "compound_activity", "fungicide_resistance", "intervention", "diagnosis_surveillance");
    private static final List<List<Integer>> PRIMARY = List.of(
            List.of(0), List.of(0), List.of(1), List.of(0, 1),
            List.of(0), List.of(0), List.of(0, 1), List.of(0));

    static Map<String, EvidenceProfile> load() {
        try (var input = ExpertQuestionDefinitions.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing expert question definitions");
            JsonNode source = new ObjectMapper().readTree(input);
            if (!MultiProfileEvidenceModels.EXPERT_PROFILE_VERSION.equals(source.path("snapshot_id").asText())
                    || source.path("questions").size() != 8) {
                throw new IllegalStateException("Invalid expert question definition version or count");
            }
            Map<String, EvidenceProfile> profiles = new LinkedHashMap<>();
            int index = 0;
            for (JsonNode question : source.path("questions")) {
                String id = question.path("question_id").asText();
                if (!id.equals("Q" + (index + 1))) throw new IllegalStateException("Invalid question order");
                List<String> headers = new ArrayList<>();
                List<String> fields = new ArrayList<>();
                StringBuilder rules = new StringBuilder();
                for (JsonNode field : question.path("fields")) {
                    addField(field, "", headers, fields, rules);
                }
                for (JsonNode field : source.path("common_evidence_fields")) {
                    String key = field.path("field_key").asText();
                    // These identifiers already belong to the persisted record, never to the model.
                    if (!key.equals("record_id") && !key.equals("paper_id")) {
                        addField(field, "common.", headers, fields, rules);
                    }
                }
                String relation = question.path("core_relation").asText();
                profiles.put(id, new EvidenceProfile(id, question.path("title_zh").asText(),
                        List.copyOf(headers), PRIMARY.get(index), relation, relation,
                        "Split independent objects and experimental contexts; preserve value-condition-source correspondence.",
                        rules.toString(), MultiProfileEvidenceModels.EXPERT_PROFILE_VERSION,
                        KEYS.get(index), List.copyOf(fields)));
                index++;
            }
            return Collections.unmodifiableMap(profiles);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read expert question definitions", e);
        }
    }

    private static void addField(JsonNode field, String prefix, List<String> headers,
                                 List<String> keys, StringBuilder rules) {
        String key = prefix + field.path("field_key").asText();
        String label = (prefix.isEmpty() ? "" : "证据:") + field.path("label_zh").asText();
        headers.add(label);
        keys.add(key);
        rules.append("- ").append(label).append(" [").append(key).append("]: ")
                .append(field.path("extraction_rule").asText())
                .append("; type: ").append(field.path("type_in_source").asText("text"))
                .append("; source-required: ").append(field.path("required_in_source").asText("unspecified"))
                .append("; illustrative example only: ").append(field.path("example_in_source").asText())
                .append('\n');
    }
}
