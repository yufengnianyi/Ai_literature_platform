package com.example.demo_01.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeEnvironmentValidationConfigTest {

    private final DashScopeEnvironmentValidationConfig config = new DashScopeEnvironmentValidationConfig();

    @Test
    void shouldRejectMissingApiKey() {
        ApplicationRunner runner = config.dashScopeEnvironmentValidator("", "", "");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void shouldRejectPlaceholderApiKey() {
        ApplicationRunner runner = config.dashScopeEnvironmentValidator("demo-key", "demo-key", "demo-key");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder value");
    }

    @Test
    void shouldAcceptConfiguredApiKeys() {
        ApplicationRunner runner = config.dashScopeEnvironmentValidator("valid-key", "valid-key", "valid-key");

        assertThatCode(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
    }
}
