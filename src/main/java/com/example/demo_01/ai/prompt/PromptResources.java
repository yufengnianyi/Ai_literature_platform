package com.example.demo_01.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PromptResources {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptResources() {
    }

    public static String load(String path) {
        return CACHE.computeIfAbsent(path, PromptResources::read);
    }

    public static String format(String path, Object... args) {
        return load(path).formatted(args);
    }

    private static String read(String path) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PromptResources.class.getClassLoader();
        }
        try (InputStream inputStream = classLoader.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalStateException("Prompt resource not found: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + path, e);
        }
    }
}
