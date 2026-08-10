package com.example.demo_01.ai.evidence.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Resolves which {@link EvidenceProperties} the extraction agents should obey right now.
 *
 * <p>Normally that is the globally configured instance. A single-question extraction run may
 * instead pin a per-run override so two experiments (e.g. table loading on vs off) can execute
 * against the same corpus without touching global configuration. The override is bound to the
 * worker thread for the duration of one document, mirroring how Spring scopes transactions.
 */
@Component
public class EvidenceConfigScope {

    /**
     * Process-wide knobs that must not vary per run: thread pool sizing is fixed at startup and
     * the output root anchors on-disk layout.
     */
    private static final Set<String> NON_OVERRIDABLE =
            Set.of("enabled", "asyncThreads", "outputRoot");

    private static final ThreadLocal<EvidenceProperties> ACTIVE = new ThreadLocal<>();

    @Resource
    private EvidenceProperties global;

    @Resource
    private ObjectMapper objectMapper;

    /** The configuration the caller should obey; the run override when one is bound. */
    public EvidenceProperties current() {
        EvidenceProperties active = ACTIVE.get();
        return active == null ? global : active;
    }

    public EvidenceProperties global() {
        return global;
    }

    /**
     * Merges a partial {@code app.ai.evidence} tree over the global configuration.
     *
     * @param overrides may be null or empty, in which case the global configuration is copied
     */
    public EvidenceProperties resolve(JsonNode overrides) {
        ObjectNode base = objectMapper.valueToTree(global);
        if (overrides != null && overrides.isObject()) {
            ObjectNode sanitized = overrides.deepCopy();
            NON_OVERRIDABLE.forEach(sanitized::remove);
            merge(base, sanitized);
        }
        return objectMapper.convertValue(base, EvidenceProperties.class);
    }

    /**
     * Canonical JSON of the settings that actually change extraction output. Used both as the
     * run's stored snapshot and as the input to its config hash, so runs that differ only in
     * irrelevant settings still reuse each other.
     */
    public String snapshot(EvidenceProperties properties) {
        ObjectNode node = objectMapper.valueToTree(properties);
        NON_OVERRIDABLE.forEach(node::remove);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(sorted(node));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize evidence config snapshot", e);
        }
    }

    /** Rebuilds an {@link EvidenceProperties} instance from a previously stored snapshot. */
    public EvidenceProperties fromSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return resolve(null);
        }
        try {
            return objectMapper.readValue(snapshotJson, EvidenceProperties.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse evidence config snapshot", e);
        }
    }

    public <T> T call(EvidenceProperties override, Supplier<T> action) {
        if (override == null) {
            return action.get();
        }
        EvidenceProperties previous = ACTIVE.get();
        ACTIVE.set(override);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    public void run(EvidenceProperties override, Runnable action) {
        call(override, () -> {
            action.run();
            return null;
        });
    }

    private void merge(ObjectNode target, ObjectNode patch) {
        Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode existing = target.get(field.getKey());
            if (existing != null && existing.isObject() && field.getValue().isObject()) {
                merge((ObjectNode) existing, (ObjectNode) field.getValue());
            } else {
                target.set(field.getKey(), field.getValue());
            }
        }
    }

    /** Stable key ordering so semantically identical configs hash identically. */
    private ObjectNode sorted(ObjectNode node) {
        Map<String, JsonNode> ordered = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            ordered.put(field.getKey(), field.getValue());
        }
        ObjectNode result = objectMapper.createObjectNode();
        ordered.forEach((key, value) -> result.set(
                key, value.isObject() ? sorted((ObjectNode) value) : value));
        return result;
    }
}
