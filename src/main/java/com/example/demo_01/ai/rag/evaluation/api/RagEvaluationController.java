package com.example.demo_01.ai.rag.evaluation.api;

import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import com.example.demo_01.ai.rag.evaluation.service.AntimicrobialSummaryExperimentService;
import com.example.demo_01.ai.rag.evaluation.service.RagEvaluationService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/rag-evaluation")
public class RagEvaluationController {

    @Resource
    private RagEvaluationService ragEvaluationService;

    @Resource
    private AntimicrobialSummaryExperimentService antimicrobialSummaryExperimentService;

    @Resource
    private UserService userService;

    @PostMapping("/experiments")
    public BaseResponse<RagEvaluationAcceptedResponse> submitExperiment(
            @RequestBody(required = false) RagEvaluationExperimentRequest request,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        return ResultUtils.success(ragEvaluationService.submit(user.getUserId(), request));
    }

    @PostMapping("/experiment-suites/required")
    public BaseResponse<RagEvaluationSuiteAcceptedResponse> submitRequiredExperimentSuite(
            @RequestBody(required = false) RagEvaluationExperimentRequest request,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        String question = request == null ? null : request.question();
        return ResultUtils.success(ragEvaluationService.submitRequiredSuite(user.getUserId(), question));
    }

    @PostMapping("/experiments/antimicrobial-summary")
    public BaseResponse<RagEvaluationAcceptedResponse> submitAntimicrobialSummaryExperiment(
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        return ResultUtils.success(antimicrobialSummaryExperimentService.submit(user.getUserId()));
    }

    @GetMapping("/experiments/{experimentId}")
    public BaseResponse<RagEvaluationExperimentRecord> getExperiment(@PathVariable UUID experimentId,
                                                                     HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        RagEvaluationExperimentRecord experiment = ownedExperiment(experimentId, user);
        return ResultUtils.success(experiment);
    }

    @GetMapping("/experiments/{experimentId}/judgments")
    public BaseResponse<List<RagEvaluationDocumentJudgment>> getJudgments(@PathVariable UUID experimentId,
                                                                          HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ownedExperiment(experimentId, user);
        return ResultUtils.success(ragEvaluationService.findJudgments(experimentId));
    }

    @GetMapping("/experiments/{experimentId}/metrics")
    public BaseResponse<RagEvaluationMetrics> getMetrics(@PathVariable UUID experimentId,
                                                         HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ownedExperiment(experimentId, user);
        return ResultUtils.success(ragEvaluationService.findMetrics(experimentId));
    }

    @GetMapping("/experiments/{experimentId}/antimicrobial-results")
    public BaseResponse<List<AntimicrobialPaperResult>> getAntimicrobialResults(
            @PathVariable UUID experimentId,
            HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ownedExperiment(experimentId, user);
        return ResultUtils.success(antimicrobialSummaryExperimentService.findResults(experimentId));
    }

    @PostMapping("/experiments/{experimentId}/judgments/{documentId}/override")
    public BaseResponse<RagEvaluationMetrics> overrideJudgment(@PathVariable UUID experimentId,
                                                               @PathVariable UUID documentId,
                                                               @RequestBody RagEvaluationOverrideRequest request,
                                                               HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        ownedExperiment(experimentId, user);
        if (request == null || request.label() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "label is required");
        }
        return ResultUtils.success(ragEvaluationService.overrideJudgment(experimentId, documentId, request));
    }

    private RagEvaluationExperimentRecord ownedExperiment(UUID experimentId, User user) {
        RagEvaluationExperimentRecord experiment = ragEvaluationService.findExperiment(experimentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                        "RAG evaluation experiment not found: " + experimentId));
        if (!experiment.userId().equals(user.getUserId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Not authorized");
        }
        return experiment;
    }
}
