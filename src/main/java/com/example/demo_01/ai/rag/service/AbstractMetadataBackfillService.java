package com.example.demo_01.ai.rag.service;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentMetadata;
import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.parser.TeiDocumentParser;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
public class AbstractMetadataBackfillService {

    @Resource
    private RagDocumentRepository ragDocumentRepository;

    @Resource
    private TeiDocumentParser teiDocumentParser;

    public AbstractBackfillSummary backfill(int maxDocuments) {
        List<RagDocumentRecord> documents = ragDocumentRepository.findAllCanonical();
        if (maxDocuments > 0) {
            documents = documents.stream().limit(maxDocuments).toList();
        }

        int alreadyPresent = 0;
        int updated = 0;
        int missingArtifact = 0;
        int noAbstract = 0;
        int failed = 0;
        for (RagDocumentRecord document : documents) {
            if (!isBlank(document.abstractText())) {
                alreadyPresent++;
                continue;
            }
            try {
                String abstractText = readAbstract(document);
                if (isBlank(abstractText)) {
                    noAbstract++;
                } else if (ragDocumentRepository.updateMissingAbstract(document.documentId(), abstractText)) {
                    updated++;
                }
            } catch (MissingArtifactException ignored) {
                missingArtifact++;
            } catch (Exception ex) {
                failed++;
                log.warn("Failed to backfill abstract for document {}: {}", document.documentId(), ex.getMessage());
            }
        }
        return new AbstractBackfillSummary(documents.size(), alreadyPresent, updated, missingArtifact, noAbstract, failed);
    }

    private String readAbstract(RagDocumentRecord document) throws Exception {
        if (isBlank(document.storageRoot())) {
            throw new MissingArtifactException();
        }
        Path storageRoot = Path.of(document.storageRoot());
        String abstractText = readAbstract(storageRoot.resolve("header.tei.xml"));
        return !isBlank(abstractText) ? abstractText : readAbstract(storageRoot.resolve("document.tei.xml"));
    }

    private String readAbstract(Path teiPath) throws Exception {
        if (!Files.isRegularFile(teiPath)) {
            return null;
        }
        RagDocumentMetadata metadata = teiDocumentParser.parseMetadata(Files.readString(teiPath));
        return metadata == null ? null : metadata.abstractText();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record AbstractBackfillSummary(int scanned,
                                          int alreadyPresent,
                                          int updated,
                                          int missingArtifact,
                                          int noAbstract,
                                          int failed) {
    }

    private static final class MissingArtifactException extends Exception {
    }
}
