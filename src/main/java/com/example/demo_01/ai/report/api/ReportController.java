package com.example.demo_01.ai.report.api;

import com.example.demo_01.ai.report.model.ReportModels.ReportRunResponse;
import com.example.demo_01.ai.report.model.ReportModels.SubmitReportRequest;
import com.example.demo_01.ai.report.service.ReportService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.conversation.ConversationService;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@RestController
public class ReportController {

    @Resource
    private ReportService reportService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private UserService userService;

    @PostMapping("/report/runs")
    public BaseResponse<ReportRunResponse> submit(
            @RequestBody SubmitReportRequest request,
            HttpServletRequest httpRequest) {
        if (request == null || request.conversationId() == null
                || request.question() == null || request.question().isBlank()) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR, "conversationId and question are required");
        }
        User user = userService.getLoginUser(httpRequest);
        String conversationId = conversationService.normalizeConversationId(request.conversationId());
        conversationService.setMode(
                user.getUserId(), conversationId, ConversationService.ConversationMode.REPORT);
        return ResultUtils.success(
                reportService.submit(user.getUserId(), conversationId, request.question()));
    }

    @GetMapping("/report/runs/{reportId}")
    public BaseResponse<ReportRunResponse> get(
            @PathVariable UUID reportId,
            HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        return ResultUtils.success(reportService.getOwned(reportId, user.getUserId()));
    }

    @GetMapping("/conversations/{conversationId}/report-runs")
    public BaseResponse<List<ReportRunResponse>> listByConversation(
            @PathVariable String conversationId,
            HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        String normalized = conversationService.normalizeConversationId(conversationId);
        conversationService.requireConversation(user.getUserId(), normalized);
        return ResultUtils.success(reportService.listByConversation(user.getUserId(), normalized));
    }

    @GetMapping("/report/runs/{reportId}/attachment")
    public ResponseEntity<FileSystemResource> attachment(
            @PathVariable UUID reportId,
            HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        ReportRunResponse run = reportService.getOwned(reportId, user.getUserId());
        Path path = reportService.attachment(reportId, user.getUserId());
        String fileName = run.attachmentFileName() == null
                ? "compound-evidence.xlsx"
                : run.attachmentFileName();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(path.toFile().length())
                .body(new FileSystemResource(path));
    }
}
