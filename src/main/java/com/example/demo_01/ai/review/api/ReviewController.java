package com.example.demo_01.ai.review.api;

import com.example.demo_01.ai.review.model.ReviewModels.*;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.QueryAnalyzerService;
import com.example.demo_01.ai.review.service.ReviewPipelineService;
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
                user.getUserId(), request.question());
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

    @GetMapping("/tasks/{taskId}/evidence")
    public BaseResponse<List<ReviewEvidenceRecord>> getEvidence(@PathVariable UUID taskId) {
        return ResultUtils.success(reviewRepository.findEvidenceByTask(taskId));
    }

    // ── Interactive checkpoint endpoints ──

    @PostMapping("/tasks/{taskId}/retrieve")
    public BaseResponse<ReviewTaskAcceptedResponse> startRetrieval(
            @PathVariable UUID taskId,
            @RequestBody ReviewGenerateRequest request,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        if (request.question() == null || request.question().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question is required");
        }
        var existing = reviewRepository.findTask(taskId);
        if (existing.isEmpty()) {
            reviewRepository.insertTask(taskId, user.getUserId(), request.question());
        } else if (!existing.get().userId().equals(user.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Not authorized");
        }
        reviewPipelineService.executeRetrievalSegment(taskId, request.question(), request.toFilteredAnalysis());
        return ResultUtils.success(new ReviewTaskAcceptedResponse(taskId, ReviewTaskStatus.RUNNING));
    }

    @PostMapping("/tasks/{taskId}/extract")
    public BaseResponse<ReviewTaskAcceptedResponse> startExtraction(
            @PathVariable UUID taskId,
            @RequestBody CandidateReviewRequest request,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));
        if (!task.userId().equals(user.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Not authorized");
        }
        if (task.status() != ReviewTaskStatus.AWAITING_USER) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Task is not awaiting user review, current status: " + task.status());
        }
        reviewPipelineService.executeEvidenceSegment(taskId, request);
        return ResultUtils.success(new ReviewTaskAcceptedResponse(taskId, ReviewTaskStatus.RUNNING));
    }

    @PostMapping("/tasks/{taskId}/generate")
    public Flux<ServerSentEvent<String>> startGeneration(
            @PathVariable UUID taskId,
            @RequestBody EvidenceReviewRequest request,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));
        if (!task.userId().equals(user.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Not authorized");
        }
        if (task.status() != ReviewTaskStatus.AWAITING_USER) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Task is not awaiting user review, current status: " + task.status());
        }

        Flux<ServerSentEvent<String>> messageEvents = reviewPipelineService
                .executeReportSegment(taskId, request)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(chunk)
                        .build());

        Flux<ServerSentEvent<String>> xlsxReadyEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("xlsx_ready")
                        .data("{\"downloadUrl\":\"/review/tasks/" + taskId + "/xlsx\"}")
                        .build());

        Flux<ServerSentEvent<String>> completeEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("complete")
                        .build());

        return messageEvents.concatWith(xlsxReadyEvent).concatWith(completeEvent);
    }

    // ── Original endpoints ──

    @GetMapping("/tasks/{taskId}/stream")
    public Flux<ServerSentEvent<String>> streamReport(
            @PathVariable UUID taskId,
            @RequestParam String question,
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

    @PostMapping("/tasks/{taskId}/stream")
    public Flux<ServerSentEvent<String>> streamReportWithSelections(
            @PathVariable UUID taskId,
            @RequestBody ReviewGenerateRequest request,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        if (request.question() == null || request.question().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question is required");
        }

        Flux<ServerSentEvent<String>> messageEvents = reviewPipelineService
                .submitStreamingWithSelections(user.getUserId(), taskId, request)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(chunk)
                        .build());

        Flux<ServerSentEvent<String>> xlsxReadyEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("xlsx_ready")
                        .data("{\"downloadUrl\":\"/review/tasks/" + taskId + "/xlsx\"}")
                        .build());

        Flux<ServerSentEvent<String>> completeEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("complete")
                        .build());

        return messageEvents.concatWith(xlsxReadyEvent).concatWith(completeEvent);
    }

    @GetMapping("/tasks/{taskId}/xlsx")
    public ResponseEntity<byte[]> downloadXlsx(@PathVariable UUID taskId,
                                               HttpServletRequest httpRequest) {
        userService.getLoginUser(httpRequest);
        ReviewTaskRecord task = reviewRepository.findTask(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "Review task not found: " + taskId));

        List<ReviewEvidenceRecord> evidence = reviewRepository.findEvidenceByTask(taskId);
        byte[] xlsxBytes = reviewXlsxService.generateXlsx(task, evidence);

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
