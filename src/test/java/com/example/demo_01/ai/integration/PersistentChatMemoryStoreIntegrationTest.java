package com.example.demo_01.ai.integration;

import com.example.demo_01.ai.memory.PersistentChatMemoryStore;
import com.example.demo_01.ai.memory.UserConversationKey;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentChatMemoryStoreIntegrationTest extends PostgresIntegrationTestSupport {

    @Test
    void shouldPersistSnapshotAndAppendOnlyNewHistoryMessages() {
        String userId = "user-1";
        String conversationId = "conversation-1";
        prepareUserAndConversation(userId, conversationId);

        PersistentChatMemoryStore store = new PersistentChatMemoryStore(jdbcTemplate);
        String memoryKey = UserConversationKey.compose(userId, conversationId);

        List<ChatMessage> firstWindow = List.of(
                UserMessage.from("hello"),
                AiMessage.from("hi there")
        );
        store.updateMessages(memoryKey, firstWindow);

        List<ChatMessage> secondWindow = List.of(
                AiMessage.from("hi there"),
                UserMessage.from("what can you do?")
        );
        store.updateMessages(memoryKey, secondWindow);

        List<ChatMessage> persistedWindow = store.getMessages(memoryKey);
        assertEquals(
                secondWindow.stream().map(ChatMessageSerializer::messageToJson).toList(),
                persistedWindow.stream().map(ChatMessageSerializer::messageToJson).toList()
        );

        Integer historyCount = jdbcTemplate.queryForObject(
                "select count(*) from ai_chat_message_history where user_id = ? and conversation_id = ?",
                Integer.class,
                userId,
                conversationId
        );
        assertEquals(3, historyCount);
    }

    @Test
    void deleteMessagesShouldDeleteConversationAndCascadeMemoryData() {
        String userId = "user-2";
        String conversationId = "conversation-2";
        prepareUserAndConversation(userId, conversationId);

        PersistentChatMemoryStore store = new PersistentChatMemoryStore(jdbcTemplate);
        String memoryKey = UserConversationKey.compose(userId, conversationId);
        store.updateMessages(memoryKey, List.of(UserMessage.from("hello")));

        store.deleteMessages(memoryKey);

        assertTrue(store.getMessages(memoryKey).isEmpty());
        Integer historyCount = jdbcTemplate.queryForObject(
                "select count(*) from ai_chat_message_history where user_id = ? and conversation_id = ?",
                Integer.class,
                userId,
                conversationId
        );
        assertEquals(0, historyCount);
        Integer conversationCount = jdbcTemplate.queryForObject(
                "select count(*) from ai_chat_conversation where user_id = ? and conversation_id = ?",
                Integer.class,
                userId,
                conversationId
        );
        assertEquals(0, conversationCount);
    }

    @Test
    void sameConversationIdShouldBeIsolatedAcrossUsers() {
        String conversationId = "shared-conversation";
        String userA = "user-a";
        String userB = "user-b";
        prepareUserAndConversation(userA, conversationId);
        prepareUserAndConversation(userB, conversationId);

        PersistentChatMemoryStore store = new PersistentChatMemoryStore(jdbcTemplate);
        store.updateMessages(UserConversationKey.compose(userA, conversationId), List.of(UserMessage.from("hello from A")));
        store.updateMessages(UserConversationKey.compose(userB, conversationId), List.of(UserMessage.from("hello from B")));

        List<ChatMessage> userAMessages = store.getMessages(UserConversationKey.compose(userA, conversationId));
        List<ChatMessage> userBMessages = store.getMessages(UserConversationKey.compose(userB, conversationId));

        assertEquals(1, userAMessages.size());
        assertEquals(1, userBMessages.size());
        assertEquals(
                ChatMessageSerializer.messageToJson(userAMessages.get(0)),
                ChatMessageSerializer.messageToJson(UserMessage.from("hello from A"))
        );
        assertEquals(
                ChatMessageSerializer.messageToJson(userBMessages.get(0)),
                ChatMessageSerializer.messageToJson(UserMessage.from("hello from B"))
        );
    }

    private void prepareUserAndConversation(String userId, String conversationId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into app_user (user_id, username, created_at, updated_at)
                values (?, ?, ?, ?)
                """, userId, userId, now, now);
        jdbcTemplate.update("""
                insert into ai_chat_conversation (user_id, conversation_id, title, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                """, userId, conversationId, "test", now, now);
    }
}