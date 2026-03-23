package com.example.demo_01.ai.rag;

import com.example.demo_01.ai.config.AiPersistenceProperties;
import com.example.demo_01.ai.rag.artifact.JsonlArtifactWriter;
import com.example.demo_01.ai.rag.chunk.TeiChunker;
import com.example.demo_01.ai.rag.client.GrobidClient;
import com.example.demo_01.ai.rag.model.RagPipelineModels.*;
import com.example.demo_01.ai.rag.parser.TeiDocumentParser;
import com.example.demo_01.ai.rag.repository.RagDocumentRepository;
import com.example.demo_01.ai.rag.repository.RagIngestionJobRepository;
import com.example.demo_01.ai.rag.service.RagDocumentIngestionService;
import com.example.demo_01.ai.rag.service.RagVectorIngestionService;
import com.example.demo_01.ai.rag.support.FailedLiteratureCsvRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagDocumentIngestionServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private RagDocumentRepository documentRepository;

    @Mock
    private RagIngestionJobRepository jobRepository;

    @Mock
    private GrobidClient grobidClient;

    @Mock
    private TeiDocumentParser teiDocumentParser;

    @Mock
    private TeiChunker teiChunker;

    @Mock
    private JsonlArtifactWriter jsonlArtifactWriter;

    @Mock
    private RagVectorIngestionService ragVectorIngestionService;

    @Mock
    private FailedLiteratureCsvRecorder failedLiteratureCsvRecorder;

    private RagDocumentIngestionService service;

    @BeforeEach
    void setUp() {
        service = new RagDocumentIngestionService();
        AiPersistenceProperties properties = new AiPersistenceProperties();
        properties.getRag().setStorageRoot(tempDir.toString());
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "taskExecutor", taskExecutor);
        ReflectionTestUtils.setField(service, "documentRepository", documentRepository);
        ReflectionTestUtils.setField(service, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(service, "grobidClient", grobidClient);
        ReflectionTestUtils.setField(service, "teiDocumentParser", teiDocumentParser);
        ReflectionTestUtils.setField(service, "teiChunker", teiChunker);
        ReflectionTestUtils.setField(service, "jsonlArtifactWriter", jsonlArtifactWriter);
        ReflectionTestUtils.setField(service, "ragVectorIngestionService", ragVectorIngestionService);
        ReflectionTestUtils.setField(service, "failedLiteratureCsvRecorder", failedLiteratureCsvRecorder);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void uploadShouldShortCircuitWhenDoiAlreadyExists() {
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "pdf-data".getBytes());
        when(grobidClient.processHeaderDocument(any(Path.class))).thenReturn("header-tei");
        RagDocumentMetadata metadata = new RagDocumentMetadata("10.1000/test", "10.1000/test", "Paper", List.of(), List.of(), null, null, null, null);
        when(teiDocumentParser.parseMetadata("header-tei")).thenReturn(metadata);
        UUID existingDocumentId = UUID.randomUUID();
        when(documentRepository.findCanonicalByDoi("10.1000/test")).thenReturn(Optional.of(new RagDocumentRecord(
                existingDocumentId, null, null, "doi:10.1000/test", "10.1000/test", "10.1000/test", "sha", "Existing", List.of(), List.of(), null, null, null, null, "existing.pdf", tempDir.toString(), RagDocumentStatus.COMPLETED, null, null
        )));

        service.upload(file);

        verify(documentRepository).markDuplicate(any(UUID.class), eq(existingDocumentId), eq(metadata), any(String.class), eq("doi:10.1000/test"));
        verify(grobidClient, never()).processFulltextDocument(any(Path.class));
        verify(ragVectorIngestionService, never()).ingestChunks(any());
    }

    @Test
    void uploadShouldRunHappyPathAndPersistEmbeddings() {
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "pdf-data".getBytes());
        RagDocumentMetadata headerMetadata = new RagDocumentMetadata(null, null, "Paper", List.of("Alice"), List.of(), null, null, null, null);
        ParsedTeiDocument parsed = new ParsedTeiDocument(headerMetadata, List.of(new ChunkUnit("body", "Intro", 1, 1, "text")));
        List<RagChunk> chunks = List.of(new RagChunk(
                UUID.randomUUID(),
                "pdf_sha256:sha",
                null,
                "chunk-1",
                1,
                "body",
                "Intro",
                1,
                1,
                1,
                "Paper",
                "text",
                tempDir.resolve("source.pdf").toString(),
                tempDir.resolve("document.tei.xml").toString(),
                "v1"
        ));

        when(grobidClient.processHeaderDocument(any(Path.class))).thenReturn("header-tei");
        when(grobidClient.processFulltextDocument(any(Path.class))).thenReturn("full-tei");
        when(teiDocumentParser.parseMetadata("header-tei")).thenReturn(headerMetadata);
        when(teiDocumentParser.parse("full-tei")).thenReturn(parsed);
        when(documentRepository.findCanonicalByPdfSha(any(String.class))).thenReturn(Optional.empty());
        when(teiChunker.chunk(any(UUID.class), any(String.class), any(ParsedTeiDocument.class), any(Path.class), any(Path.class))).thenReturn(chunks);
        when(ragVectorIngestionService.ingestChunks(chunks)).thenReturn(new RagVectorIngestionResult(1, 10L, 12L, 13L, 14L));

        service.upload(file);

        verify(grobidClient).processFulltextDocument(any(Path.class));
        verify(ragVectorIngestionService).ingestChunks(chunks);
        verify(documentRepository).markCompleted(any(UUID.class), any(RagDocumentMetadata.class), any(String.class), any(String.class));
    }

    @Test
    void uploadShouldRecordFailedLiteratureWhenHeaderParsingFails() {
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "pdf-data".getBytes());
        when(grobidClient.processHeaderDocument(any(Path.class))).thenReturn("bad-header-tei");
        doThrow(new IllegalStateException("Failed to parse TEI XML"))
                .when(teiDocumentParser).parseMetadata("bad-header-tei");

        service.upload(file);

        verify(failedLiteratureCsvRecorder).append(any(UUID.class), eq("HEADER_PARSE"), any(Exception.class));
        verify(grobidClient, never()).processFulltextDocument(any(Path.class));
    }
}
