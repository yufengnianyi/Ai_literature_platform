package com.example.demo_01.ai.review.service;

import com.example.demo_01.ai.review.config.ReviewProperties;
import com.example.demo_01.ai.review.model.ReviewModels.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ReportGeneratorService {

    private static final String SECTION_SYSTEM_PROMPT = """
            You are writing one chapter of a systematic review report.
            Write in the same language as the main question.
            
            Requirements:
            1. Write 800-1500 words for this chapter
            2. Structure: Key Points → Detailed Analysis → Evidence Support
            3. Every key conclusion must include a citation: {source=document title; chunk=chunk_id}
            4. Clearly mark evidence consistency or conflicts
            5. Note evidence limitations for this sub-question
            6. Use Markdown formatting with ## for section heading
            """;

    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            You are a senior editor for a systematic review report.
            Combine the chapter drafts below into a complete, cohesive systematic review report.
            Write in the same language as the main question.
            
            Required output structure (use Markdown):
            # [Report Title]
            ## Executive Summary
            (300-500 words, core findings)
            ## [Chapter sections - edit/merge/reorder the drafts as needed]
            ## Cross-Analysis
            (Cross-chapter analysis and connections)
            ## Limitations and Uncertainties
            ## Future Research Directions
            ## References
            (List all cited sources)
            
            Rules:
            - Preserve all citations from the drafts
            - Ensure logical flow between chapters
            - Remove redundancy across chapters
            - Add cross-references between related findings
            """;

    @Resource(name = "myqwenChatModel")
    private ChatModel chatModel;

    @Resource(name = "reviewReportChatModel")
    private ChatModel reportChatModel;

    @Resource(name = "reviewReportStreamingChatModel")
    private StreamingChatModel reportStreamingChatModel;

    @Resource(name = "reviewTaskExecutor")
    private TaskExecutor reviewTaskExecutor;

    @Resource
    private ReviewProperties reviewProperties;

    private final LlmBatchProcessor batchProcessor = new LlmBatchProcessor();

    public String generateReport(String mainQuestion, List<FusedEvidenceGroup> groups) {
        // Map: generate section drafts in parallel
        List<String> sectionDrafts = batchProcessor.processInBatches(
                groups, 1,
                batch -> {
                    FusedEvidenceGroup group = batch.get(0);
                    return List.of(generateSectionDraft(mainQuestion, group));
                },
                reviewTaskExecutor
        );
        log.info("Generated {} section drafts", sectionDrafts.size());

        // Reduce: synthesize final report
        String report = synthesizeReport(mainQuestion, sectionDrafts);
        log.info("Final report generated ({} chars)", report.length());
        return report;
    }

    public Flux<String> generateReportStreaming(String mainQuestion, List<FusedEvidenceGroup> groups) {
        return Flux.create(sink -> {
            CompletableFuture.runAsync(() -> {
                try {
                    List<String> sectionDrafts = batchProcessor.processInBatches(
                            groups, 1,
                            batch -> List.of(generateSectionDraft(mainQuestion, batch.get(0))),
                            reviewTaskExecutor
                    );
                    synthesizeReportStreaming(mainQuestion, sectionDrafts, sink);
                } catch (Exception e) {
                    sink.error(e);
                }
            }, reviewTaskExecutor);
        });
    }

    private String generateSectionDraft(String mainQuestion, FusedEvidenceGroup group) {
        StringBuilder userMsg = new StringBuilder();
        userMsg.append("Main question: ").append(mainQuestion).append("\n\n");
        userMsg.append("Sub-question for this chapter: ").append(group.subQuestion()).append("\n\n");
        userMsg.append("Evidence summary:\n").append(group.groupSummary()).append("\n\n");

        if (!group.clusters().isEmpty()) {
            userMsg.append("Evidence clusters:\n");
            for (int i = 0; i < group.clusters().size(); i++) {
                EvidenceCluster cluster = group.clusters().get(i);
                userMsg.append(i + 1).append(". Claim: ").append(cluster.claimSummary())
                        .append(" [").append(cluster.consistency()).append("]")
                        .append(" Sources: ").append(String.join(", ", cluster.sourceDocuments()))
                        .append("\n");
            }
        }

        if (!group.consistencyNotes().isEmpty()) {
            userMsg.append("\nConsistency notes: ")
                    .append(String.join("; ", group.consistencyNotes()));
        }

        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(SECTION_SYSTEM_PROMPT),
                    UserMessage.from(userMsg.toString())
            );
            AiMessage ai = response.aiMessage();
            String text = (ai != null) ? ai.text() : null;
            if (text != null && !text.isBlank()) {
                return text;
            }
            log.warn("Section draft: AI returned empty text for '{}'", group.subQuestion());
            return "## " + group.subQuestion() + "\n\n" + group.groupSummary();
        } catch (Exception e) {
            log.warn("Section draft generation failed for '{}': {}", group.subQuestion(), e.getMessage());
            return "## " + group.subQuestion() + "\n\n" + group.groupSummary();
        }
    }

    private String synthesizeReport(String mainQuestion, List<String> sectionDrafts) {
        String fallback = "# Systematic Review Report\n\n" + String.join("\n\n---\n\n", sectionDrafts);
        String draftsText = buildDraftsText(mainQuestion, sectionDrafts);
        try {
            ChatResponse response = reportChatModel.chat(
                    SystemMessage.from(SYNTHESIS_SYSTEM_PROMPT),
                    UserMessage.from(draftsText)
            );
            AiMessage ai = response.aiMessage();
            String text = (ai != null) ? ai.text() : null;
            if (text != null && !text.isBlank()) {
                return text;
            }
            log.warn("Report synthesis: AI returned empty text, using concatenated drafts");
            return fallback;
        } catch (Exception e) {
            log.warn("Report synthesis failed: {}", e.getMessage());
            return fallback;
        }
    }

    private void synthesizeReportStreaming(String mainQuestion, List<String> sectionDrafts,
                                           reactor.core.publisher.FluxSink<String> sink) {
        String draftsText = buildDraftsText(mainQuestion, sectionDrafts);
        try {
            reportStreamingChatModel.chat(
                    List.of(
                            SystemMessage.from(SYNTHESIS_SYSTEM_PROMPT),
                            UserMessage.from(draftsText)
                    ),
                    new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            sink.next(partialResponse);
                        }

                        @Override
                        public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse chatResponse) {
                            sink.complete();
                        }

                        @Override
                        public void onError(Throwable error) {
                            sink.error(error);
                        }
                    }
            );
        } catch (Exception e) {
            sink.next(String.join("\n\n", sectionDrafts));
            sink.complete();
        }
    }

    private String buildDraftsText(String mainQuestion, List<String> sectionDrafts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original question: ").append(mainQuestion).append("\n\n");
        sb.append("--- Chapter Drafts ---\n\n");
        for (int i = 0; i < sectionDrafts.size(); i++) {
            sb.append("=== Draft ").append(i + 1).append(" ===\n");
            sb.append(sectionDrafts.get(i)).append("\n\n");
        }
        return sb.toString();
    }
}
