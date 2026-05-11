package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.api.ReviewController;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewDocumentCandidate;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewPaperEvidenceTable;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewStage;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewSummaryTable;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewTaskRecord;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewTaskStatus;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.ReviewPipelineService;
import com.example.demo_01.ai.review.service.ReviewXlsxService;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerTest {

    @Test
    void streamReportShouldReusePathTaskId() {
        ReviewController controller = new ReviewController();
        ReviewPipelineService reviewPipelineService = mock(ReviewPipelineService.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        UserService userService = mock(UserService.class);

        ReflectionTestUtils.setField(controller, "reviewPipelineService", reviewPipelineService);
        ReflectionTestUtils.setField(controller, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(controller, "userService", userService);

        UUID taskId = UUID.randomUUID();
        String question = "What is the evidence?";
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-1");
        when(userService.getLoginUser(request)).thenReturn(user);
        when(reviewPipelineService.submitStreaming("user-1", taskId, question))
                .thenReturn(Flux.just("chunk-1"));

        Flux<ServerSentEvent<String>> response = controller.streamReport(taskId, question, request);
        List<ServerSentEvent<String>> events = response.collectList().block();

        assertEquals(2, events == null ? 0 : events.size());
        assertEquals("message", events.get(0).event());
        assertEquals("chunk-1", events.get(0).data());
        assertEquals("complete", events.get(1).event());

        verify(reviewPipelineService).submitStreaming("user-1", taskId, question);
    }

    @Test
    void getDocumentsShouldReturnRepositoryPayload() {
        ReviewController controller = new ReviewController();
        ReviewPipelineService reviewPipelineService = mock(ReviewPipelineService.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        UserService userService = mock(UserService.class);

        ReflectionTestUtils.setField(controller, "reviewPipelineService", reviewPipelineService);
        ReflectionTestUtils.setField(controller, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(controller, "userService", userService);

        UUID taskId = UUID.randomUUID();
        List<ReviewDocumentCandidate> documents = List.of(
                new ReviewDocumentCandidate(null, taskId, UUID.randomUUID(), "Paper A", 2,
                        List.of("chunk-1"), 0.9, 0.8, 1.0, 0.7, 0.6, 0.73,
                        null, "reason", "summary", List.of("innovation"), List.of("finding"),
                        true, true)
        );
        when(reviewRepository.findDocumentCandidates(taskId)).thenReturn(documents);

        assertEquals(documents, controller.getDocuments(taskId).getData());
        verify(reviewRepository).findDocumentCandidates(taskId);
    }

    @Test
    void getPaperEvidenceTablesShouldReturnRepositoryPayload() {
        ReviewController controller = new ReviewController();
        ReviewPipelineService reviewPipelineService = mock(ReviewPipelineService.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        UserService userService = mock(UserService.class);

        ReflectionTestUtils.setField(controller, "reviewPipelineService", reviewPipelineService);
        ReflectionTestUtils.setField(controller, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(controller, "userService", userService);

        UUID taskId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-1");
        List<ReviewPaperEvidenceTable> tables = List.of(
                new ReviewPaperEvidenceTable(taskId, UUID.randomUUID(), "Paper A",
                        "question", "summary", List.of("Finding", "Evidence"),
                        List.of(List.of("finding", "evidence")), List.of("chunk-1"),
                        2, 0.8, List.of(), Instant.now())
        );
        when(userService.getLoginUser(request)).thenReturn(user);
        when(reviewRepository.findTask(taskId)).thenReturn(Optional.of(task(taskId, "user-1")));
        when(reviewRepository.findPaperEvidenceTablesByTask(taskId)).thenReturn(tables);

        assertEquals(tables, controller.getPaperEvidenceTables(taskId, request).getData());
        verify(reviewRepository).findPaperEvidenceTablesByTask(taskId);
    }

    @Test
    void getPaperEvidenceTablesShouldRejectOtherUsersTask() {
        ReviewController controller = new ReviewController();
        ReviewPipelineService reviewPipelineService = mock(ReviewPipelineService.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        UserService userService = mock(UserService.class);

        ReflectionTestUtils.setField(controller, "reviewPipelineService", reviewPipelineService);
        ReflectionTestUtils.setField(controller, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(controller, "userService", userService);

        UUID taskId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-2");
        when(userService.getLoginUser(request)).thenReturn(user);
        when(reviewRepository.findTask(taskId)).thenReturn(Optional.of(task(taskId, "user-1")));

        assertThrows(BusinessException.class, () -> controller.getPaperEvidenceTables(taskId, request));
    }

    @Test
    void getSummaryTablesShouldPreferPaperEvidenceTables() {
        ReviewController controller = new ReviewController();
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        UserService userService = mock(UserService.class);
        ReviewXlsxService reviewXlsxService = mock(ReviewXlsxService.class);

        ReflectionTestUtils.setField(controller, "reviewRepository", reviewRepository);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "reviewXlsxService", reviewXlsxService);

        UUID taskId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-1");
        List<ReviewPaperEvidenceTable> paperTables = List.of(
                new ReviewPaperEvidenceTable(taskId, UUID.randomUUID(), "Paper A",
                        "question", "summary", List.of("Finding", "Evidence"),
                        List.of(List.of("finding", "evidence")), List.of("chunk-1"),
                        2, 0.8, List.of(), Instant.now())
        );
        List<ReviewSummaryTable> summaryTables = List.of(
                new ReviewSummaryTable("paper-evidence-summary", "Paper Evidence Summary",
                        List.of("Paper", "Evidence Row"), List.of(List.of("Paper A", "finding")))
        );
        ReviewTaskRecord task = task(taskId, "user-1");

        when(userService.getLoginUser(request)).thenReturn(user);
        when(reviewRepository.findTask(taskId)).thenReturn(Optional.of(task));
        when(reviewRepository.findPaperEvidenceTablesByTask(taskId)).thenReturn(paperTables);
        when(reviewXlsxService.buildPaperEvidenceSummaryTables(task, paperTables)).thenReturn(summaryTables);

        assertEquals(summaryTables, controller.getSummaryTables(taskId, request).getData());
        verify(reviewXlsxService).buildPaperEvidenceSummaryTables(task, paperTables);
        verify(reviewRepository, never()).findEvidenceByTask(any());
        verify(reviewRepository, never()).findSynthesizedCompoundsByTask(any());
    }

    private ReviewTaskRecord task(UUID taskId, String userId) {
        return new ReviewTaskRecord(
                taskId,
                userId,
                "question",
                ReviewTaskStatus.COMPLETED,
                ReviewStage.COMPLETED,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                Instant.now()
        );
    }
}
