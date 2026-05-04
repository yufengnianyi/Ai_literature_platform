package com.example.demo_01.ai.rag.api;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagIngestionJobRecord;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.user.constant.UserConstant;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/rag/jobs")
public class RagJobController {

    @Resource
    private RagDocumentIngestionService ragDocumentIngestionService;

    @GetMapping("/{jobId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RagIngestionJobRecord> getJob(@PathVariable UUID jobId) {
        return ResultUtils.success(ragDocumentIngestionService.getJob(jobId));
    }
}
