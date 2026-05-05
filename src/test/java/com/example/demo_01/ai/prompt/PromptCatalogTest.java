package com.example.demo_01.ai.prompt;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptCatalogTest {

    @Test
    void everyCatalogConstantUsesCanonicalPromptSubtreeAndResolves() throws IllegalAccessException {
        for (Field field : promptConstants()) {
            String path = (String) field.get(null);

            assertTrue(path.startsWith("prompts/"), field.getName() + " must stay under prompts/");
            assertTrue(resourceExists(path), field.getName() + " must resolve: " + path);
        }
    }

    @Test
    void systemPromptConstantsAreNonBlank() throws IllegalAccessException {
        for (Field field : promptConstants()) {
            String path = (String) field.get(null);
            if (!path.endsWith("-system.txt")) {
                continue;
            }

            assertFalse(PromptResources.load(path).isBlank(), field.getName() + " must be non-blank");
        }
    }

    @Test
    void annotationConstantsRemainPublicStaticFinalStrings() {
        for (Field field : promptConstants()) {
            int modifiers = field.getModifiers();

            assertTrue(Modifier.isPublic(modifiers), field.getName() + " must be public");
            assertTrue(Modifier.isStatic(modifiers), field.getName() + " must be static");
            assertTrue(Modifier.isFinal(modifiers), field.getName() + " must be final");
            assertTrue(field.getType().equals(String.class), field.getName() + " must be a String");
        }
    }

    private static List<Field> promptConstants() {
        return Arrays.stream(PromptCatalog.class.getDeclaredFields())
                .filter(field -> field.getType().equals(String.class))
                .toList();
    }

    private static boolean resourceExists(String path) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PromptCatalogTest.class.getClassLoader();
        }
        return classLoader.getResource(path) != null;
    }
}
