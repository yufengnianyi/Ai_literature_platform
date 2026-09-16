package com.example.demo_01.ai.evidence.multiprofile;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class QuestionExtractionRepositoryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void separatesPredicateAndOrderByForRecords(boolean documentFilter) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        QuestionExtractionRepository repository = new QuestionExtractionRepository();
        ReflectionTestUtils.setField(repository, "jdbcTemplate", jdbc);
        repository.findEvidence(UUID.randomUUID(), documentFilter ? UUID.randomUUID() : null, null, 0, 100);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("?\nORDER BY").doesNotContain("?ORDER BY");
    }
}
