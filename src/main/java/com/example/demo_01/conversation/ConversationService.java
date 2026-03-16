package com.example.demo_01.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ConversationService {

    private static final Pattern SAFE_CONVERSATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,255}");
    private static final int TITLE_MAX_LENGTH = 255;
    private static final DateTimeFormatter TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));

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
        return new ConversationResponse(conversationId, title, now.toInstant(), now.toInstant());
    }

    public List<ConversationResponse> listConversations(String userId) {
        return jdbcTemplate.query("""
                select conversation_id, title, created_at, updated_at
                from ai_chat_conversation
                where user_id = ?
                order by updated_at desc
                """, (rs, rowNum) -> new ConversationResponse(
                rs.getString("conversation_id"),
                rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ), userId);
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
                returning conversation_id, title, created_at, updated_at
                """, (rs, rowNum) -> new ConversationResponse(
                rs.getString("conversation_id"),
                rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ), normalizedTitle, now, userId, normalizedConversationId);

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
        return "\u65B0\u4F1A\u8BDD-" + TITLE_TIME_FORMATTER.format(Instant.now());
    }

    public record CreateConversationRequest(String title) {
    }

    public record RenameConversationRequest(String title) {
    }

    public record ConversationResponse(String conversationId, String title, Instant createdAt, Instant updatedAt) {
    }
}
