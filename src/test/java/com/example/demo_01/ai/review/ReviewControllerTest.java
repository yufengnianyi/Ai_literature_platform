package com.example.demo_01.ai.review;

import com.example.demo_01.ai.review.api.ReviewController;
import com.example.demo_01.ai.review.model.ReviewModels.ReviewDocumentCandidate;
import com.example.demo_01.ai.review.repository.ReviewRepository;
import com.example.demo_01.ai.review.service.ReviewPipelineService;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
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
}
