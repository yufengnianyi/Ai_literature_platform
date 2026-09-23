package com.example.demo_01.ai.evidence.multiprofile;

import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.EvidenceRecordTranslation;
import com.example.demo_01.ai.evidence.multiprofile.MultiProfileEvidenceModels.TranslationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EvidenceRecordTranslationRepositoryTest {

    @Test
    void upsertsTranslationWithoutChangingTheSourceEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EvidenceRecordTranslationRepository repository = new EvidenceRecordTranslationRepository();
        ReflectionTestUtils.setField(repository, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(repository, "objectMapper", new ObjectMapper());
        UUID translationId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        repository.save(new EvidenceRecordTranslation(
                translationId, recordId, "zh-CN", MultiProfileEvidenceModels.EXPERT_PROFILE_VERSION,
                "qwen", "prompt-hash", Map.of("common.evidence_text", "中文证据"),
                TranslationStatus.COMPLETED, null, null, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO evidence_record_translation")
                .contains("ON CONFLICT (record_id, language_code, prompt_hash)");
        assertThat(arguments.getValue())
                .containsExactly(translationId, recordId, "zh-CN",
                        MultiProfileEvidenceModels.EXPERT_PROFILE_VERSION, "qwen", "prompt-hash",
                        "{\"common.evidence_text\":\"中文证据\"}", "COMPLETED", null);
    }
}
