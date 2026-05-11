package com.example.demo_01.ai.review.agent.node;

import com.example.demo_01.ai.review.agent.PerPaperAgentState;
import com.example.demo_01.ai.review.model.ReviewModels.DocumentKnowledgeContext;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewPaperEvidenceTable;
import com.example.demo_01.ai.review.model.ReviewModels.RetrievedChunk;
import com.example.demo_01.ai.review.service.PaperEvidenceTableSynthesisService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.review.agent", name = "enabled", havingValue = "true")
public class PaperEvidenceTableNode implements NodeAction<PerPaperAgentState> {

    @Resource
    private PaperEvidenceTableSynthesisService paperEvidenceTableSynthesisService;

    @Override
    public Map<String, Object> apply(PerPaperAgentState state) {
        UUID documentId = state.documentId();
        List<RetrievedChunk> chunks = state.seedChunks();
        String documentTitle = chunks.stream()
                .map(RetrievedChunk::documentTitle)
                .filter(title -> title != null && !title.isBlank())
                .findFirst()
                .orElse(null);
        DocumentKnowledgeContext knowledgeContext = state.knowledgeContextsMap().get(documentId);
        ReviewPaperEvidenceTable table = paperEvidenceTableSynthesisService.synthesizeBestTable(
                state.taskId(),
                null,
                state.reviewQuestion(),
                documentId,
                documentTitle,
                chunks,
                state.extractedEvidence(),
                knowledgeContext
        );
        log.info("Paper evidence table generated in graph for task {}, document {}", state.taskId(), documentId);
        return Map.of(
                PerPaperAgentState.PAPER_EVIDENCE_TABLE, table,
                PerPaperAgentState.LLM_CALLS, state.llmCalls() + Math.max(1, table.iterations())
        );
    }
}
