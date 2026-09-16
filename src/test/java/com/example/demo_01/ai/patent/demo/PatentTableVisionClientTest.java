package com.example.demo_01.ai.patent.demo;

import com.example.demo_01.ai.model.DashScopeModelProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatentTableVisionClientTest {
    private final PatentTableVisionClient client = new PatentTableVisionClient(new ObjectMapper(),
            new DashScopeModelProperties(), "http://localhost", "test-model", 1000);

    @Test
    void preservesSourceStringsAndRejectsUnalignedOrNumericCells() throws Exception {
        var result = client.parse("""
                {"caption":"Activity", "headers":["Treatment","EC50","CTC"],
                 "rows":[["A:B 2:1","0.070","-"],["B",">10","-"]],"uncertainties":[]}
                """);
        assertThat(result.rows().getFirst()).containsExactly("A:B 2:1", "0.070", "-");
        assertThatThrownBy(() -> client.parse("""
                {"headers":["Treatment","EC50"],"rows":[["A",0.07]],"uncertainties":[]}
                """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.parse("""
                {"headers":["Treatment","EC50"],"rows":[["A"]],"uncertainties":[]}
                """)).isInstanceOf(IllegalArgumentException.class);
    }
}
