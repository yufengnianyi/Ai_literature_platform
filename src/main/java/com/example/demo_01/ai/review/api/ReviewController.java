package com.example.demo_01.ai.review.api;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.QueryAnalyzerService;
import com.example.demo_01.ai.review.service.ReviewPipelineService;
import com.example.demo_01.ai.review.service.ReviewScopePreviewService;
import com.example.demo_01.ai.review.service.ReviewXlsxService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/review")
public class ReviewController {

    @Resource
    private ReviewPipelineService reviewPipelineService;

    @Resource
    private ReviewRepository reviewRepository;

    @Resource
    private UserService userService;

    @Resource
    private QueryAnalyzerService queryAnalyzerService;

    @Resource
    private ReviewScopePreviewService reviewScopePreviewService;

    @Resource
    private ReviewXlsxService reviewXlsxService;

    @PostMapping("/tasks")
    public BaseResponse<ReviewTaskAcceptedResponse> submitTask(
            @RequestBody ReviewTaskSubmitRequest request,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        if (request.question() == null || request.question().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question is required");
        }
        ReviewTaskAcceptedResponse response = reviewPipelineService.submit(
                user.getUserId(), request.question(), request.templateId());
        return ResultUtils.success(response);
    }

    @PostMapping("/analyze")
    public BaseResponse<QueryAnalysis> analyzeQuestion(
            @RequestBody ReviewTaskSubmitRequest request,
            HttpServletRequest httpRequest) {
        userService.getLoginUser(httpRequest);
        if (request.question() == null || request.question().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question is required");
        }
        QueryAnalysis analysis = queryAnalyzerService.analyze(request.question());
        return ResultUtils.success(analysis);
    }

    @PostMapping("/preview")
    public BaseResponse<ReviewScopePreview> previewScope(
            @RequestBody ReviewTaskSubmitRequest request,
            HttpServletRequest httpRequest) {
        userService.getLoginUser(httpRequest);
        if (request.question() == null || request.question().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question is required");
        }
        return ResultUtils.success(reviewScopePreviewService.buildInitialPreview(request.question()));
    }

    @GetMapping("/tasks/{taskId}")
    public BaseResponse<ReviewTaskRecord> getTask(@PathVariable UUID taskId) {
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));
        return ResultUtils.success(task);
    }

    @GetMapping("/tasks/{taskId}/report")
    public BaseResponse<String> getReport(@PathVariable UUID taskId) {
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));
        if (task.reportMarkdown() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Report not yet generated");
        }
        return ResultUtils.success(task.reportMarkdown());
    }

    @GetMapping("/tasks/{taskId}/candidates")
    public BaseResponse<List<ReviewCandidate>> getCandidates(@PathVariable UUID taskId) {
        return ResultUtils.success(reviewRepository.findAllCandidates(taskId));
    }

    @GetMapping("/tasks/{taskId}/documents")
    public BaseResponse<List<ReviewDocumentCandidate>> getDocuments(@PathVariable UUID taskId) {
        return ResultUtils.success(reviewRepository.findDocumentCandidates(taskId));
    }

    @GetMapping("/tasks/{taskId}/scope-preview")
    public BaseResponse<ReviewScopePreview> getScopePreview(@PathVariable UUID taskId) {
        return ResultUtils.success(reviewScopePreviewService.buildTaskPreview(taskId));
    }

    @GetMapping("/tasks/{taskId}/summary-tables")
    public BaseResponse<List<ReviewSummaryTable>> getSummaryTables(@PathVariable UUID taskId,
                                                                   HttpServletRequest httpRequest) {
        userService.getLoginUser(httpRequest);
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));
        List<ReviewPaperEvidenceTable> paperTables = reviewRepository.findPaperEvidenceTablesByTask(taskId);
        if (paperTables != null && !paperTables.isEmpty()) {
            return ResultUtils.success(reviewXlsxService.buildPaperEvidenceSummaryTables(task, paperTables));
        }
        return ResultUtils.success(List.of());
    }

    // ── Interactive checkpoint endpoints ──

    @GetMapping("/tasks/{taskId}/paper-evidence-tables")
    public BaseResponse<List<ReviewPaperEvidenceTable>> getPaperEvidenceTables(@PathVariable UUID taskId,
                                                                               HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));
        if (!task.userId().equals(user.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Not authorized");
        }
        return ResultUtils.success(reviewRepository.findPaperEvidenceTablesByTask(taskId));
    }

    @PostMapping("/tasks/{taskId}/documents/confirm")
    public BaseResponse<ReviewTaskAcceptedResponse> confirmDocuments(@PathVariable UUID taskId,
                                                                     @RequestBody CandidateReviewRequest request,
                                                                     HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));
        if (!task.userId().equals(user.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Not authorized");
        }
        if (request.selectedDocumentIds() == null || request.selectedDocumentIds().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "selectedDocumentIds is required");
        }
        return ResultUtils.success(reviewPipelineService.confirmDocuments(taskId, request.selectedDocumentIds()));
    }

    // ── Original endpoints ──

    @GetMapping("/tasks/{taskId}/stream")
    public Flux<ServerSentEvent<String>> streamReport(
            @PathVariable UUID taskId,
            @RequestParam String question,
            @RequestParam(required = false) String templateId,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);

        Flux<ServerSentEvent<String>> messageEvents = reviewPipelineService
                .submitStreaming(user.getUserId(), taskId, question, templateId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(chunk)
                        .build());

        Flux<ServerSentEvent<String>> completeEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("complete")
                        .build());

        return messageEvents.concatWith(completeEvent);
    }

    public Flux<ServerSentEvent<String>> streamReport(
            UUID taskId,
            String question,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        Flux<ServerSentEvent<String>> messageEvents = reviewPipelineService
                .submitStreaming(user.getUserId(), taskId, question)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(chunk)
                        .build());
        Flux<ServerSentEvent<String>> completeEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("complete")
                        .build());
        return messageEvents.concatWith(completeEvent);
    }

    @GetMapping("/tasks/{taskId}/xlsx")
    public ResponseEntity<byte[]> downloadXlsx(@PathVariable UUID taskId,
                                               HttpServletRequest httpRequest) {
        userService.getLoginUser(httpRequest);
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));

        List<ReviewPaperEvidenceTable> paperTables = reviewRepository.findPaperEvidenceTablesByTask(taskId);
        byte[] xlsxBytes;
        if (paperTables != null && !paperTables.isEmpty()) {
            xlsxBytes = reviewXlsxService.generatePaperEvidenceXlsx(task, paperTables);
        } else {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Paper evidence table not yet generated");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"review-summary-" + taskId + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsxBytes);
    }

    @GetMapping("/tasks")
    public BaseResponse<List<ReviewTaskRecord>> listTasks(HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        return ResultUtils.success(reviewRepository.findTasksByUser(user.getUserId()));
    }

    @DeleteMapping("/tasks/{taskId}")
    public BaseResponse<Boolean> deleteTask(
            @PathVariable UUID taskId,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        boolean deleted = reviewRepository.deleteTask(taskId, user.getUserId());
        if (!deleted) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                    "Review task not found or not owned by current user: " + taskId);
        }
        return ResultUtils.success(true);
    }

    @PostMapping("/tasks/{taskId}/retry")
    public BaseResponse<ReviewTaskAcceptedResponse> retryTask(
            @PathVariable UUID taskId,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));
        if (!task.userId().equals(user.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Not authorized to retry this task");
        }
        ReviewTaskAcceptedResponse response = reviewPipelineService.retry(taskId);
        return ResultUtils.success(response);
    }
}
