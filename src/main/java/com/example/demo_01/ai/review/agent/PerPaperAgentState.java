package com.example.demo_01.ai.review.agent;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.*;

public class PerPaperAgentState extends AgentState {

    public static final String DOCUMENT_ID = "documentId";
    public static final String SEED_CHUNKS = "seedChunks";
    public static final String COMPOUND_QUEUE = "compoundQueue";
    public static final String CURRENT_COMPOUND = "currentCompound";
    public static final String CTX_BY_COMPOUND = "ctxByCompound";
    public static final String CURRENT_PROFILE = "currentProfile";
    public static final String CURRENT_AUDIT = "currentAudit";
    public static final String ITERATIONS = "iterations";
    public static final String PROFILES = "profiles";
    public static final String LLM_CALLS = "llmCalls";
    public static final String BUDGET_EXHAUSTED = "budgetExhausted";
    /** Full map from pipeline; keyed by document UUID */
    public static final String KNOWLEDGE_CONTEXTS = "knowledgeContexts";
    public static final String TASK_ID = "taskId";

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(DOCUMENT_ID, Channels.base(() -> null)),
            Map.entry(SEED_CHUNKS, Channels.appender(ArrayList::new)),
            Map.entry(COMPOUND_QUEUE, Channels.base(() -> new ArrayDeque<CompoundSpec>())),
            Map.entry(CURRENT_COMPOUND, Channels.base(() -> null)),
            Map.entry(CTX_BY_COMPOUND, Channels.base(() -> new HashMap<String, List<RetrievedChunk>>())),
            Map.entry(CURRENT_PROFILE, Channels.base(() -> null)),
            Map.entry(CURRENT_AUDIT, Channels.base(() -> null)),
            Map.entry(ITERATIONS, Channels.base(() -> new HashMap<String, Integer>())),
            Map.entry(PROFILES, Channels.base(() -> new ArrayList<SynthesizedCompoundRecord>())),
            Map.entry(LLM_CALLS, Channels.base(() -> 0)),
            Map.entry(BUDGET_EXHAUSTED, Channels.base(() -> false)),
            Map.entry(KNOWLEDGE_CONTEXTS, Channels.base(() -> new HashMap<UUID, DocumentKnowledgeContext>())),
            Map.entry(TASK_ID, Channels.base(() -> null))
    );

    public PerPaperAgentState(Map<String, Object> initData) {
        super(initData);
    }

    @SuppressWarnings("unchecked")
    public UUID documentId() {
        return this.<UUID>value(DOCUMENT_ID).orElse(null);
    }

    @SuppressWarnings("unchecked")
    public List<RetrievedChunk> seedChunks() {
        return this.<List<RetrievedChunk>>value(SEED_CHUNKS).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    public ArrayDeque<CompoundSpec> compoundQueue() {
        return this.<ArrayDeque<CompoundSpec>>value(COMPOUND_QUEUE).orElse(new ArrayDeque<>());
    }

    public Optional<CompoundSpec> currentCompound() {
        return this.value(CURRENT_COMPOUND);
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<RetrievedChunk>> ctxByCompound() {
        return this.<Map<String, List<RetrievedChunk>>>value(CTX_BY_COMPOUND).orElse(Map.of());
    }

    public Optional<SynthesizedCompoundRecord> currentProfile() {
        return this.value(CURRENT_PROFILE);
    }

    public Optional<AuditResult> currentAudit() {
        return this.value(CURRENT_AUDIT);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Integer> iterations() {
        return this.<Map<String, Integer>>value(ITERATIONS).orElse(new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    public List<SynthesizedCompoundRecord> profiles() {
        return this.<List<SynthesizedCompoundRecord>>value(PROFILES).orElse(List.of());
    }

    public int llmCalls() {
        return this.<Integer>value(LLM_CALLS).orElse(0);
    }

    public boolean budgetExhausted() {
        return this.<Boolean>value(BUDGET_EXHAUSTED).orElse(false);
    }

    @SuppressWarnings("unchecked")
    public Map<UUID, DocumentKnowledgeContext> knowledgeContextsMap() {
        return this.<Map<UUID, DocumentKnowledgeContext>>value(KNOWLEDGE_CONTEXTS).orElse(Map.of());
    }

    public UUID taskId() {
        return this.<UUID>value(TASK_ID).orElse(null);
    }

    public record CompoundSpec(
            String canonicalName,
            List<String> localLabels,
            String role,
            List<String> paradigmHints
    ) {}

    public record AuditResult(
            SynthesizedCompoundRecord recordWithWarnings,
            List<String> warnings,
            boolean shouldResynthesize,
            List<RetrievalDirective> retrievalDirectives,
            List<String> promptHints
    ) {}

    public record RetrievalDirective(
            String reason,
            List<String> queries,
            String paradigmHint
    ) {}
}
