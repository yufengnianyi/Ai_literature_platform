package com.example.demo_01.ai.rag.evaluation;

import com.example.demo_01.ai.rag.evaluation.api.RagEvaluationController;
import com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.*;
import com.example.demo_01.ai.rag.evaluation.service.AntimicrobialSummaryExperimentService;
import com.example.demo_01.ai.rag.evaluation.service.RagEvaluationService;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.example.demo_01.ai.rag.evaluation.model.RagEvaluationModels.DEFAULT_QUESTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RagEvaluationControllerTest {

    @Test
    void submitExperimentShouldUseDefaultQuestionWhenBodyIsMissing() {
        RagEvaluationController controller = newController();
        RagEvaluationService service = field(controller, "ragEvaluationService");
        UserService userService = field(controller, "userService");
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-1");
        UUID experimentId = UUID.randomUUID();

        when(userService.getLoginUser(request)).thenReturn(user);
        when(service.submit(eq("user-1"), isNull(RagEvaluationExperimentRequest.class)))
                .thenReturn(new RagEvaluationAcceptedResponse(experimentId, ExperimentStatus.QUEUED));

        RagEvaluationAcceptedResponse response = controller.submitExperiment(null, request).getData();

        assertEquals(experimentId, response.experimentId());
        verify(service).submit(eq("user-1"), isNull(RagEvaluationExperimentRequest.class));
    }

    @Test
    void getJudgmentsShouldRejectOtherUsersExperiment() {
        RagEvaluationController controller = newController();
        RagEvaluationService service = field(controller, "ragEvaluationService");
        UserService userService = field(controller, "userService");
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-2");
        UUID experimentId = UUID.randomUUID();

        when(userService.getLoginUser(request)).thenReturn(user);
        when(service.findExperiment(experimentId)).thenReturn(Optional.of(experiment(experimentId, "user-1")));

        assertThrows(BusinessException.class, () -> controller.getJudgments(experimentId, request));
        verify(service, never()).findJudgments(experimentId);
    }

    @Test
    void submitAntimicrobialSummaryShouldUseLoggedInUser() {
        RagEvaluationController controller = newController();
        AntimicrobialSummaryExperimentService service =
                field(controller, "antimicrobialSummaryExperimentService");
        UserService userService = field(controller, "userService");
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-1");
        UUID experimentId = UUID.randomUUID();

        when(userService.getLoginUser(request)).thenReturn(user);
        when(service.submit("user-1"))
                .thenReturn(new RagEvaluationAcceptedResponse(experimentId, ExperimentStatus.QUEUED));

        RagEvaluationAcceptedResponse response =
                controller.submitAntimicrobialSummaryExperiment(request).getData();

        assertEquals(experimentId, response.experimentId());
        verify(service).submit("user-1");
    }

    @Test
    void getAntimicrobialResultsShouldRejectOtherUsersExperiment() {
        RagEvaluationController controller = newController();
        RagEvaluationService evaluationService = field(controller, "ragEvaluationService");
        AntimicrobialSummaryExperimentService summaryService =
                field(controller, "antimicrobialSummaryExperimentService");
        UserService userService = field(controller, "userService");
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-2");
        UUID experimentId = UUID.randomUUID();

        when(userService.getLoginUser(request)).thenReturn(user);
        when(evaluationService.findExperiment(experimentId))
                .thenReturn(Optional.of(experiment(experimentId, "user-1")));

        assertThrows(BusinessException.class,
                () -> controller.getAntimicrobialResults(experimentId, request));
        verify(summaryService, never()).findResults(experimentId);
    }

    @Test
    void overrideShouldRequireLabel() {
        RagEvaluationController controller = newController();
        RagEvaluationService service = field(controller, "ragEvaluationService");
        UserService userService = field(controller, "userService");
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User();
        user.setUserId("user-1");
        UUID experimentId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        when(userService.getLoginUser(request)).thenReturn(user);
        when(service.findExperiment(experimentId)).thenReturn(Optional.of(experiment(experimentId, "user-1")));

        assertThrows(BusinessException.class,
                () -> controller.overrideJudgment(experimentId, documentId,
                        new RagEvaluationOverrideRequest(null, List.of(), null), request));
        verify(service, never()).overrideJudgment(any(), any(), any());
    }

    private RagEvaluationController newController() {
        RagEvaluationController controller = new RagEvaluationController();
        ReflectionTestUtils.setField(controller, "ragEvaluationService", mock(RagEvaluationService.class));
        ReflectionTestUtils.setField(controller, "antimicrobialSummaryExperimentService",
                mock(AntimicrobialSummaryExperimentService.class));
        ReflectionTestUtils.setField(controller, "userService", mock(UserService.class));
        return controller;
    }

    private RagEvaluationExperimentRecord experiment(UUID experimentId, String userId) {
        Instant now = Instant.now();
        return new RagEvaluationExperimentRecord(
                experimentId, userId, DEFAULT_QUESTION, ExperimentStatus.COMPLETED,
                Map.of(), RagEvaluationMetrics.empty(), "data/rag-evaluation",
                null, null, now, now, now);
    }

    @SuppressWarnings("unchecked")
    private <T> T field(RagEvaluationController controller, String name) {
        return (T) ReflectionTestUtils.getField(controller, name);
    }
}
