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

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ReportGeneratorService {

    private static final String DIRECT_ANSWER_SYSTEM_PROMPT = """
            You are a scientific research advisor. Given a user's research question and synthesized
            evidence from multiple literature sources, provide a clear and direct answer.
            Write in the same language as the question.
            
            Your output must contain TWO parts in Markdown:
            
            PART 1 — Direct Answer (200-400 words):
            - Answer the user's question directly and concisely in the opening paragraph
            - Support each key conclusion with a citation in the format [Paper Title]
            - Highlight points of consensus and any notable conflicts among sources
            - End with a one-sentence take-away
            
            PART 2 — Key Findings Table:
            Immediately after the direct answer, output a Markdown table:
            | Key Finding | Source | Evidence Strength |
            |---|---|---|
            | ... | [Paper Title] | Strong / Moderate / Weak |
            
            Rules:
            - Evidence Strength: Strong = multiple consistent sources; Moderate = single high-quality source or partial consensus; Weak = limited or conflicting evidence
            - Keep the table to 5-10 rows covering the most important findings
            - Do not include markdown fences or extra explanations outside the two parts
            """;

    private static final String SECTION_SYSTEM_PROMPT = """
            You are writing one chapter of a systematic review report.
            Write in the same language as the main question.
            
            Requirements:
            1. Write 800-1500 words for this chapter
            2. Organize the narrative naturally based on the evidence — you may structure by timeline,
               methodology, research subject, or any dimension that best fits the content.
               Do NOT force a rigid template.
            3. Every key conclusion must include a citation: {source=document title; chunk=chunk_id}
            4. Clearly mark evidence consistency or conflicts
            5. Note evidence limitations for this sub-question
            6. Use Markdown formatting with ## for section heading
            7. End the chapter with a bold **Chapter Conclusion** paragraph (2-3 sentences) that
               directly summarizes the answer to this sub-question and cites the key supporting papers
            """;

    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            You are a senior editor for a systematic review report.
            Combine the Direct Answer and the chapter drafts below into a complete, cohesive report.
            Write in the same language as the main question.
            
            Required output structure (use Markdown):
            # [Report Title]
            ## Direct Answer
            (Copy the provided Direct Answer section here verbatim, including the Key Findings Table.
             You may lightly polish wording but do NOT remove any cited sources or table rows.)
            ## [Chapter sections — edit/merge/reorder the drafts as needed]
            (Preserve each chapter's **Chapter Conclusion** paragraph.)
            ## Cross-Analysis
            (Cross-chapter analysis, connections, and synthesis across sub-questions)
            ## Limitations and Uncertainties
            ## Future Research Directions
            ## References
            (List all cited sources in order of first appearance in the text)
            
            Rules:
            - The Direct Answer section MUST appear first, right after the title
            - Preserve all citations from every section
            - Ensure logical flow between chapters
            - Remove redundancy across chapters while keeping each Chapter Conclusion intact
            - Add cross-references between related findings in different chapters
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
        CompletableFuture<String> directAnswerFuture = CompletableFuture.supplyAsync(
                () -> generateDirectAnswer(mainQuestion, groups), reviewTaskExecutor);

        List<String> sectionDrafts = batchProcessor.processInBatches(
                groups, 1,
                batch -> {
                    FusedEvidenceGroup group = batch.get(0);
                    return List.of(generateSectionDraft(mainQuestion, group));
                },
                reviewTaskExecutor
        );
        log.info("Generated {} section drafts", sectionDrafts.size());

        String directAnswer = directAnswerFuture.join();
        log.info("Direct answer generated ({} chars)", directAnswer.length());

        String report = synthesizeReport(mainQuestion, directAnswer, sectionDrafts);
        log.info("Final report generated ({} chars)", report.length());
        return report;
    }

    public Flux<String> generateReportStreaming(String mainQuestion, List<FusedEvidenceGroup> groups,
                                                String userGuidance, List<String> focusSubQuestions) {
        return Flux.create(sink -> {
            CompletableFuture.runAsync(() -> {
                try {
                    CompletableFuture<String> directAnswerFuture = CompletableFuture.supplyAsync(
                            () -> generateDirectAnswer(mainQuestion, groups), reviewTaskExecutor);

                    List<String> sectionDrafts = batchProcessor.processInBatches(
                            groups, 1,
                            batch -> List.of(generateSectionDraft(mainQuestion, batch.get(0))),
                            reviewTaskExecutor
                    );

                    String directAnswer = directAnswerFuture.join();
                    synthesizeReportStreaming(mainQuestion, directAnswer, sectionDrafts,
                            userGuidance, focusSubQuestions, sink);
                } catch (Exception e) {
                    sink.error(e);
                }
            }, reviewTaskExecutor);
        });
    }

    private String generateDirectAnswer(String mainQuestion, List<FusedEvidenceGroup> groups) {
        StringBuilder userMsg = new StringBuilder();
        userMsg.append("Research question: ").append(mainQuestion).append("\n\n");
        userMsg.append("Synthesized evidence from literature:\n\n");

        for (int g = 0; g < groups.size(); g++) {
            FusedEvidenceGroup group = groups.get(g);
            userMsg.append("### Sub-question ").append(g + 1).append(": ").append(group.subQuestion()).append("\n");
            userMsg.append("Summary: ").append(group.groupSummary()).append("\n");
            for (EvidenceCluster cluster : group.clusters()) {
                userMsg.append("- ").append(cluster.claimSummary())
                        .append(" [").append(cluster.consistency()).append("]")
                        .append(" (Sources: ").append(String.join(", ", cluster.sourceDocuments()))
                        .append(")\n");
            }
            userMsg.append("\n");
        }

        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(DIRECT_ANSWER_SYSTEM_PROMPT),
                    UserMessage.from(userMsg.toString())
            );
            AiMessage ai = response.aiMessage();
            String text = (ai != null) ? ai.text() : null;
            if (text != null && !text.isBlank()) {
                return text;
            }
            log.warn("Direct answer generation returned empty text");
            return "";
        } catch (Exception e) {
            log.warn("Direct answer generation failed: {}", e.getMessage());
            return "";
        }
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

    private String synthesizeReport(String mainQuestion, String directAnswer, List<String> sectionDrafts) {
        String fallback = "# Systematic Review Report\n\n" + directAnswer + "\n\n---\n\n"
                + String.join("\n\n---\n\n", sectionDrafts);
        String draftsText = buildDraftsText(mainQuestion, directAnswer, sectionDrafts, null, null);
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

    private void synthesizeReportStreaming(String mainQuestion, String directAnswer,
                                           List<String> sectionDrafts,
                                           String userGuidance, List<String> focusSubQuestions,
                                           reactor.core.publisher.FluxSink<String> sink) {
        String draftsText = buildDraftsText(mainQuestion, directAnswer, sectionDrafts,
                userGuidance, focusSubQuestions);
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

    private String buildDraftsText(String mainQuestion, String directAnswer, List<String> sectionDrafts,
                                   String userGuidance, List<String> focusSubQuestions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original question: ").append(mainQuestion).append("\n\n");

        if (userGuidance != null && !userGuidance.isBlank()) {
            sb.append("--- User Guidance ---\n");
            sb.append("The user specifically requested: ").append(userGuidance).append("\n");
            sb.append("Prioritize this guidance when organizing and emphasizing content.\n\n");
        }

        if (focusSubQuestions != null && !focusSubQuestions.isEmpty()) {
            sb.append("--- Priority Sub-questions ---\n");
            sb.append("The user wants the following sub-questions to be covered in greater depth:\n");
            for (String sq : focusSubQuestions) {
                sb.append("- ").append(sq).append("\n");
            }
            sb.append("\n");
        }

        if (directAnswer != null && !directAnswer.isBlank()) {
            sb.append("--- Direct Answer (include this as the first section of the report) ---\n\n");
            sb.append(directAnswer).append("\n\n");
        }

        sb.append("--- Chapter Drafts ---\n\n");
        for (int i = 0; i < sectionDrafts.size(); i++) {
            sb.append("=== Draft ").append(i + 1).append(" ===\n");
            sb.append(sectionDrafts.get(i)).append("\n\n");
        }
        return sb.toString();
    }
}
