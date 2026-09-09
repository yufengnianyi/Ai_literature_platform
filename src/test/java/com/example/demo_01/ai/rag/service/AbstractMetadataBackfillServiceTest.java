package com.example.demo_01.ai.rag.service;

import com.example.demo_01.ai.rag.model.RagPipelineModels.RagDocumentRecord;
import com.example.demo_01.ai.rag.parser.DoiNormalizer;
import com.example.demo_01.ai.rag.parser.TeiDocumentParser;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractMetadataBackfillServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void backfillsMissingDatabaseAbstractFromHeaderTei() throws Exception {
        UUID documentId = UUID.randomUUID();
        Files.writeString(tempDir.resolve("header.tei.xml"), """
                <TEI xmlns="http://www.tei-c.org/ns/1.0"><teiHeader><profileDesc>
                <abstract><p>Recovered abstract text.</p></abstract>
                </profileDesc></teiHeader></TEI>
                """);
        RagDocumentRecord document = mock(RagDocumentRecord.class);
        when(document.documentId()).thenReturn(documentId);
        when(document.storageRoot()).thenReturn(tempDir.toString());
        when(document.abstractText()).thenReturn(null);

        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        when(repository.findAllCanonical()).thenReturn(List.of(document));
        when(repository.updateMissingAbstract(eq(documentId), eq("Recovered abstract text."))).thenReturn(true);

        TeiDocumentParser parser = new TeiDocumentParser();
        ReflectionTestUtils.setField(parser, "doiNormalizer", new DoiNormalizer());
        AbstractMetadataBackfillService service = new AbstractMetadataBackfillService();
        ReflectionTestUtils.setField(service, "ragDocumentRepository", repository);
        ReflectionTestUtils.setField(service, "teiDocumentParser", parser);

        var summary = service.backfill(0);

        assertThat(summary.updated()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        verify(repository).updateMissingAbstract(documentId, "Recovered abstract text.");
    }
}
