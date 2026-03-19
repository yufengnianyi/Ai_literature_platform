package com.example.demo_01.conversation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ConversationService {

    private static final Pattern SAFE_CONVERSATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,255}");
    private static final int TITLE_MAX_LENGTH = 255;
    private static final DateTimeFormatter TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final String RAG_CONTENT_INJECTION_DELIMITER = "\n\nAnswer using the following information:\n";

    private final JdbcTemplate jdbcTemplate;

    public ConversationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ConversationResponse createConversation(String userId, CreateConversationRequest request) {
        String conversationId = UUID.randomUUID().toString();
        String title = resolveTitle(request == null ? null : request.title());
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into ai_chat_conversation (user_id, conversation_id, title, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                """, userId, conversationId, title, now, now);
        return new ConversationResponse(conversationId, title, false, now.toInstant(), now.toInstant());
    }

    public List<ConversationResponse> listConversations(String userId) {
        return jdbcTemplate.query("""
                select conversation_id, title, pinned, created_at, updated_at
                from ai_chat_conversation
                where user_id = ?
                order by pinned desc, updated_at desc
                """, (rs, rowNum) -> mapConversationResponse(rs), userId);
    }

    public List<ConversationMessageResponse> listConversationMessages(String userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        assertConversationExists(userId, normalizedConversationId);

        return jdbcTemplate.query("""
                select seq_no, message_json::text as message_json, created_at
                from ai_chat_message_history
                where user_id = ? and conversation_id = ? and role in ('USER', 'AI')
                order by seq_no asc
                """, (rs, rowNum) -> new RawConversationMessage(
                rs.getLong("seq_no"),
                rs.getString("message_json"),
                rs.getTimestamp("created_at").toInstant()
        ), userId, normalizedConversationId).stream()
                .map(this::toConversationMessageResponse)
                .filter(Objects::nonNull)
                .toList();
    }

    public void deleteConversation(String userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        int deleted = jdbcTemplate.update("""
                delete from ai_chat_conversation
                where user_id = ? and conversation_id = ?
                """, userId, normalizedConversationId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }
    }

    public ConversationResponse renameConversation(String userId, String conversationId, RenameConversationRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedTitle = normalizeRenameTitle(request.title());
        Timestamp now = Timestamp.from(Instant.now());

        List<ConversationResponse> rows = jdbcTemplate.query("""
                update ai_chat_conversation
                set title = ?, updated_at = ?
                where user_id = ? and conversation_id = ?
                returning conversation_id, title, pinned, created_at, updated_at
                """, (rs, rowNum) -> mapConversationResponse(rs), normalizedTitle, now, userId, normalizedConversationId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }
        return rows.get(0);
    }

    public ConversationResponse pinConversation(String userId, String conversationId, PinConversationRequest request) {
        if (request == null || request.pinned() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pinned is required");
        }
        String normalizedConversationId = normalizeConversationId(conversationId);

        List<ConversationResponse> rows = jdbcTemplate.query("""
                update ai_chat_conversation
                set pinned = ?
                where user_id = ? and conversation_id = ?
                returning conversation_id, title, pinned, created_at, updated_at
                """, (rs, rowNum) -> mapConversationResponse(rs), request.pinned(), userId, normalizedConversationId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }
        return rows.get(0);
    }

    public String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId is required");
        }
        String normalized = conversationId.trim();
        if (!SAFE_CONVERSATION_ID.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId is invalid");
        }
        return normalized;
    }

    public void createConversationIfAbsent(String userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        Timestamp now = Timestamp.from(Instant.now());
        String defaultTitle = defaultTitle();
        jdbcTemplate.update("""
                insert into ai_chat_conversation (user_id, conversation_id, title, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                on conflict (user_id, conversation_id) do nothing
                """, userId, normalizedConversationId, defaultTitle, now, now);
    }

    private String resolveTitle(String title) {
        if (title == null || title.isBlank()) {
            return defaultTitle();
        }
        String normalized = title.trim();
        if (normalized.length() > TITLE_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title length must be <= 255");
        }
        return normalized;
    }

    private String normalizeRenameTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        String normalized = title.trim();
        if (normalized.length() > TITLE_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title length must be <= 255");
        }
        return normalized;
    }

    private String defaultTitle() {
        return "New conversation-" + TITLE_TIME_FORMATTER.format(Instant.now());
    }

    private void assertConversationExists(String userId, String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from ai_chat_conversation
                where user_id = ? and conversation_id = ?
                """, Integer.class, userId, conversationId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }
    }

    private ConversationMessageResponse toConversationMessageResponse(RawConversationMessage rawMessage) {
        ChatMessage chatMessage = ChatMessageDeserializer.messageFromJson(rawMessage.messageJson());
        String role = toFrontendRole(chatMessage.type());
        if (role == null) {
            return null;
        }

        String content = extractContent(chatMessage);
        if (content == null || content.isBlank()) {
            return null;
        }

        return new ConversationMessageResponse(rawMessage.seqNo(), role, content, rawMessage.createdAt());
    }

    private String toFrontendRole(ChatMessageType type) {
        if (type == ChatMessageType.USER) {
            return "user";
        }
        if (type == ChatMessageType.AI) {
            return "assistant";
        }
        return null;
    }

    private String extractContent(ChatMessage chatMessage) {
        if (chatMessage instanceof UserMessage userMessage) {
            return userMessage.hasSingleText() ? extractOriginalUserPrompt(userMessage.singleText()) : null;
        }
        if (chatMessage instanceof AiMessage aiMessage) {
            return aiMessage.text();
        }
        return null;
    }

    private String extractOriginalUserPrompt(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        int delimiterIndex = content.indexOf(RAG_CONTENT_INJECTION_DELIMITER);
        if (delimiterIndex < 0) {
            return content;
        }

        String prompt = content.substring(0, delimiterIndex).trim();
        return prompt.isEmpty() ? content : prompt;
    }

    private ConversationResponse mapConversationResponse(ResultSet rs) throws SQLException {
        return new ConversationResponse(
                rs.getString("conversation_id"),
                rs.getString("title"),
                rs.getBoolean("pinned"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    public record CreateConversationRequest(String title) {
    }

    public record RenameConversationRequest(String title) {
    }

    public record PinConversationRequest(Boolean pinned) {
    }

    public record ConversationResponse(String conversationId, String title, boolean pinned, Instant createdAt,
                                       Instant updatedAt) {
    }

    public record ConversationMessageResponse(Long seqNo, String role, String content, Instant createdAt) {
    }

    private record RawConversationMessage(Long seqNo, String messageJson, Instant createdAt) {
    }
}
