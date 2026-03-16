package com.example.demo_01.ai.memory;

public record UserConversationKey(String userId, String conversationId) {

    private static final String DELIMITER = "::";

    public static String compose(String userId, String conversationId) {
        if (isBlank(userId) || isBlank(conversationId)) {
            throw new IllegalArgumentException("userId and conversationId are required");
        }
        return userId + DELIMITER + conversationId;
    }

    public static UserConversationKey parse(Object memoryId) {
        String value = String.valueOf(memoryId);
        int delimiterIndex = value.indexOf(DELIMITER);
        if (delimiterIndex < 0) {
            throw new IllegalArgumentException("Invalid memory key. Expected format: userId::conversationId");
        }
        String userId = value.substring(0, delimiterIndex);
        String conversationId = value.substring(delimiterIndex + DELIMITER.length());
        if (isBlank(userId) || isBlank(conversationId)) {
            throw new IllegalArgumentException("Invalid memory key. Expected format: userId::conversationId");
        }
        return new UserConversationKey(userId, conversationId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}