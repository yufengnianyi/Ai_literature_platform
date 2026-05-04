package com.example.demo_01.ai.rag.api;

import com.example.demo_01.annotation.AuthCheck;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagUploadAcceptedResponse;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
import com.example.demo_01.ai.rag.service.RagIngestionFromArtifactService;
import com.example.demo_01.common.BaseResponse;
import com.example.demo_01.common.ResultUtils;
import com.example.demo_01.user.constant.UserConstant;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/rag/documents")
public class RagDocumentController {

    @Resource
    private RagDocumentIngestionService ragDocumentIngestionService;

    @Resource
    private RagIngestionFromArtifactService ragIngestionFromArtifactService;

    @PostMapping(consumes = "multipart/form-data")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public ResponseEntity<BaseResponse<RagUploadAcceptedResponse>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.accepted().body(ResultUtils.success(ragDocumentIngestionService.upload(file)));
    }

    @PostMapping("/{documentId}/ingest")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public ResponseEntity<BaseResponse<RagUploadAcceptedResponse>> ingest(@PathVariable UUID documentId) {
        return ResponseEntity.accepted().body(ResultUtils.success(ragIngestionFromArtifactService.enqueueDocument(documentId, null)));
    }

    @GetMapping("/{documentId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RagDocumentRecord> getDocument(@PathVariable UUID documentId) {
        return ResultUtils.success(ragDocumentIngestionService.getDocument(documentId));
    }
}
