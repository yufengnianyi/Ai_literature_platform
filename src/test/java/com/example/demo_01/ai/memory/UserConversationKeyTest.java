package com.example.demo_01.ai.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserConversationKeyTest {

    @Test
    void shouldComposeAndParse() {
        String key = UserConversationKey.compose("u1", "c1");
        UserConversationKey parsed = UserConversationKey.parse(key);
        assertEquals("u1", parsed.userId());
        assertEquals("c1", parsed.conversationId());
    }

    @Test
    void shouldRejectInvalidKey() {
        assertThrows(IllegalArgumentException.class, () -> UserConversationKey.parse("invalid"));
    }
}