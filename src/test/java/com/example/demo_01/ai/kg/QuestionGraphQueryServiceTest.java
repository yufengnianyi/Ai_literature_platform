package com.example.demo_01.ai.kg;

import com.example.demo_01.ai.kg.model.QuestionGraphModels.QuestionGraphView;
import com.example.demo_01.ai.kg.service.QuestionGraphQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestionGraphQueryServiceTest {

    private ObjectProvider<Driver> driverProvider;
    private com.example.demo_01.ai.kg.KgProperties properties;

    @BeforeEach
    void setUp() {
        driverProvider = mock(ObjectProvider.class);
        properties = new com.example.demo_01.ai.kg.KgProperties();
        properties.setEnabled(true);
    }

    @Test
    void queryShouldReturnEmptyForBlankPrompt() {
        QuestionGraphQueryService service = new QuestionGraphQueryService(driverProvider, properties);

        QuestionGraphView result = service.query("   ");

        assertEquals("EMPTY", result.status());
        assertEquals(0, result.nodes().size());
    }

    @Test
    void queryShouldReturnUnavailableWhenDriverMissing() {
        when(driverProvider.getIfAvailable()).thenReturn(null);
        QuestionGraphQueryService service = new QuestionGraphQueryService(driverProvider, properties);

        QuestionGraphView result = service.query("Phytophthora");

        assertEquals("UNAVAILABLE", result.status());
        assertEquals(0, result.edges().size());
    }

    @Test
    void queryShouldReturnUnavailableWhenSessionFails() {
        Driver driver = mock(Driver.class);
        when(driverProvider.getIfAvailable()).thenReturn(driver);
        when(driver.session()).thenThrow(new IllegalStateException("boom"));
        QuestionGraphQueryService service = new QuestionGraphQueryService(driverProvider, properties);

        QuestionGraphView result = service.query("Phytophthora");

        assertEquals("UNAVAILABLE", result.status());
        assertEquals("Phytophthora", result.prompt());
    }
}
