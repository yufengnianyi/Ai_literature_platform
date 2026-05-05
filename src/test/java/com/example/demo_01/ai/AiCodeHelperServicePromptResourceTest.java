package com.example.demo_01.ai;

import com.example.demo_01.ai.prompt.PromptCatalog;
import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodeHelperServicePromptResourceTest {

    @Test
    void serviceMethodsUseCanonicalSystemPromptResource() {
        for (Method method : annotatedServiceMethods()) {
            SystemMessage annotation = method.getAnnotation(SystemMessage.class);

            assertNotNull(annotation);
            assertEquals(PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM, annotation.fromResource());
            assertResourceResolvesLikeLangChain4j(annotation.fromResource());
        }
    }

    @Test
    void reportTypeUsesCanonicalSystemPromptResource() {
        SystemMessage annotation = AiCodeHelperService.Report.class.getAnnotation(SystemMessage.class);

        assertNotNull(annotation);
        assertEquals(PromptCatalog.AI_CODE_HELPER_SERVICE_SYSTEM, annotation.fromResource());
        assertResourceResolvesLikeLangChain4j(annotation.fromResource());
    }

    private static List<Method> annotatedServiceMethods() {
        return Arrays.stream(AiCodeHelperService.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(SystemMessage.class) != null)
                .toList();
    }

    private static void assertResourceResolvesLikeLangChain4j(String path) {
        InputStream stream = AiCodeHelperService.class.getResourceAsStream(path);
        if (stream == null) {
            stream = AiCodeHelperService.class.getResourceAsStream("/" + path);
        }

        assertNotNull(stream, "Resource must resolve through Class.getResourceAsStream fallback: " + path);
        try (InputStream resolvedStream = stream) {
            assertTrue(resolvedStream.readAllBytes().length > 0, "Resource must be non-empty: " + path);
        } catch (Exception e) {
            throw new AssertionError("Failed to read resource: " + path, e);
        }
    }
}
