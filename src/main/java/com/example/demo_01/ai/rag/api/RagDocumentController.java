package com.example.demo_01.ai.rag.api;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagUploadAcceptedResponse;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
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

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<RagUploadAcceptedResponse> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.accepted().body(ragDocumentIngestionService.upload(file));
    }

    @GetMapping("/{documentId}")
    public RagDocumentRecord getDocument(@PathVariable UUID documentId) {
        return ragDocumentIngestionService.getDocument(documentId);
    }
}
