package com.example.demo_01.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class PersistentChatMemoryStore implements ChatMemoryStore {

    private static final String SELECT_SNAPSHOT_SQL = """
            select messages_json::text
            from ai_chat_memory_snapshot
            where user_id = ? and conversation_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PersistentChatMemoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(Object memoryId) {
        UserConversationKey key = normalize(memoryId);
        List<String> rows = jdbcTemplate.query(
                SELECT_SNAPSHOT_SQL,
                (rs, rowNum) -> rs.getString(1),
                key.userId(),
                key.conversationId()
        );
        if (rows.isEmpty()) {
            return List.of();
        }
        return ChatMessageDeserializer.messagesFromJson(rows.get(0));
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        UserConversationKey key = normalize(memoryId);
        List<ChatMessage> previousMessages = getMessages(memoryId);
        List<ChatMessage> appendedMessages = extractAppendedMessages(previousMessages, messages);

        String snapshotJson = ChatMessageSerializer.messagesToJson(messages);
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                insert into ai_chat_memory_snapshot (user_id, conversation_id, messages_json, updated_at)
                values (?, ?, cast(? as jsonb), ?)
                on conflict (user_id, conversation_id) do update
                set messages_json = excluded.messages_json,
                    updated_at = excluded.updated_at
                """, key.userId(), key.conversationId(), snapshotJson, now);

        if (!appendedMessages.isEmpty()) {
            Long currentMaxSeq = jdbcTemplate.queryForObject("""
                    select coalesce(max(seq_no), 0)
                    from ai_chat_message_history
                    where user_id = ? and conversation_id = ?
                    """, Long.class, key.userId(), key.conversationId());

            long nextSeq = currentMaxSeq == null ? 1L : currentMaxSeq + 1L;
            for (ChatMessage message : appendedMessages) {
                jdbcTemplate.update("""
                        insert into ai_chat_message_history (
                            user_id,
                            conversation_id,
                            seq_no,
                            role,
                            message_json,
                            created_at
                        ) values (?, ?, ?, ?, cast(? as jsonb), ?)
                        """,
                        key.userId(),
                        key.conversationId(),
                        nextSeq++,
                        message.type().name(),
                        ChatMessageSerializer.messageToJson(message),
                        now
                );
            }
        }

        jdbcTemplate.update("""
                update ai_chat_conversation
                set updated_at = ?
                where user_id = ? and conversation_id = ?
                """, now, key.userId(), key.conversationId());
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        UserConversationKey key = normalize(memoryId);
        int deleted = jdbcTemplate.update("""
                delete from ai_chat_conversation
                where user_id = ? and conversation_id = ?
                """, key.userId(), key.conversationId());

        if (deleted == 0) {
            jdbcTemplate.update("""
                    delete from ai_chat_message_history
                    where user_id = ? and conversation_id = ?
                    """, key.userId(), key.conversationId());
            jdbcTemplate.update("""
                    delete from ai_chat_memory_snapshot
                    where user_id = ? and conversation_id = ?
                    """, key.userId(), key.conversationId());
        }
    }

    private List<ChatMessage> extractAppendedMessages(List<ChatMessage> previousMessages, List<ChatMessage> newMessages) {
        if (previousMessages.isEmpty()) {
            return new ArrayList<>(newMessages);
        }

        List<String> previousJson = previousMessages.stream()
                .map(ChatMessageSerializer::messageToJson)
                .toList();
        List<String> newJson = newMessages.stream()
                .map(ChatMessageSerializer::messageToJson)
                .toList();

        int maxOverlap = Math.min(previousJson.size(), newJson.size());
        for (int overlap = maxOverlap; overlap >= 0; overlap--) {
            boolean matches = true;
            for (int i = 0; i < overlap; i++) {
                String previous = previousJson.get(previousJson.size() - overlap + i);
                String current = newJson.get(i);
                if (!Objects.equals(previous, current)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return new ArrayList<>(newMessages.subList(overlap, newMessages.size()));
            }
        }

        return new ArrayList<>(newMessages);
    }

    private UserConversationKey normalize(Object memoryId) {
        return UserConversationKey.parse(memoryId);
    }
}