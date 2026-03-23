package com.example.demo_01.ai.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * Local token estimator used to avoid remote tokenizer dependency.
 * It keeps ingestion robust when model-side tokenization APIs reject model names.
 */
public class HeuristicTokenCountEstimator implements TokenCountEstimator {

    private final String modelName;

    public HeuristicTokenCountEstimator(String modelName) {
        this.modelName = modelName == null ? "unknown" : modelName;
    }

    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int asciiChars = 0;
        int nonAsciiChars = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 0x7F) {
                asciiChars++;
            } else {
                nonAsciiChars++;
            }
        }
        int asciiTokens = (int) Math.ceil(asciiChars / 4.0);
        int nonAsciiTokens = nonAsciiChars;
        int estimate = asciiTokens + nonAsciiTokens;
        return Math.max(1, estimate);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        return estimateTokenCountInText(message.toString());
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateTokenCountInMessage(message);
        }
        return total;
    }

    @Override
    public String toString() {
        return "HeuristicTokenCountEstimator(model=" + modelName + ")";
    }
}
