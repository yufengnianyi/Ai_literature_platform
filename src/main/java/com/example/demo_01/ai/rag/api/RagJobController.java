package com.example.demo_01.ai.rag.api;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagIngestionJobRecord;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
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
    public RagIngestionJobRecord getJob(@PathVariable UUID jobId) {
        return ragDocumentIngestionService.getJob(jobId);
    }
}
