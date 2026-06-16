package com.example.demo_01.conversation;

import com.example.demo_01.ai.report.service.ReportAttachmentStorage;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ConversationService {

    private static final Pattern SAFE_CONVERSATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,255}");
    private static final int TITLE_MAX_LENGTH = 255;
    private static final DateTimeFormatter TITLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final String RAG_CONTENT_INJECTION_DELIMITER = "\n\nAnswer using the following information:\n";
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    @Resource
    private ReportAttachmentStorage reportAttachmentStorage;

    public ConversationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ConversationResponse createConversation(String userId, CreateConversationRequest request) {
        String conversationId = UUID.randomUUID().toString();
        ConversationMode mode = request == null || request.mode() == null ? ConversationMode.CHAT : request.mode();
        boolean titleInitialized = request != null && request.title() != null && !request.title().isBlank();
        String title = titleInitialized ? resolveTitle(request.title()) : defaultTitle(mode);
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into ai_chat_conversation (
                    user_id, conversation_id, title, mode, title_initialized, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, userId, conversationId, title, mode.name(), titleInitialized, now, now);
        return new ConversationResponse(conversationId, title, false, mode, now.toInstant(), now.toInstant());
    }

    public List<ConversationResponse> listConversations(String userId) {
        return jdbcTemplate.query("""
                select conversation_id, title, pinned, mode, created_at, updated_at
                from ai_chat_conversation
                where user_id = ?
                order by pinned desc, updated_at desc
                """, (rs, rowNum) -> mapConversationResponse(rs), userId);
    }

    public List<ConversationMessageResponse> listConversationMessages(String userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        assertConversationExists(userId, normalizedConversationId);

        Map<Long, ReportMessageMetadata> reportMetadata = new HashMap<>();
        jdbcTemplate.query("""
                select report_id, assistant_message_seq_no, question, status, evidence_count,
                       attachment_file_name, error_message, phase_message, progress_percent,
                       selected_document_count, analyzed_document_count, warnings_json::text, updated_at
                from report_run
                where user_id = ? and conversation_id = ?
                """, rs -> {
            long seqNo = rs.getLong("assistant_message_seq_no");
            reportMetadata.put(seqNo, new ReportMessageMetadata(
                    rs.getObject("report_id", UUID.class),
                    rs.getString("question"),
                    rs.getString("status"),
                    rs.getInt("evidence_count"),
                    rs.getString("attachment_file_name"),
                    rs.getString("error_message"),
                    rs.getString("phase_message"),
                    rs.getInt("progress_percent"),
                    rs.getInt("selected_document_count"),
                    rs.getInt("analyzed_document_count"),
                    parseWarnings(rs.getString("warnings_json")),
                    rs.getTimestamp("updated_at").toInstant()
            ));
        }, userId, normalizedConversationId);

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
                .map(raw -> toConversationMessageResponse(raw, reportMetadata.get(raw.seqNo())))
                .filter(Objects::nonNull)
                .toList();
    }

    public void deleteConversation(String userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        List<String> attachmentPaths = jdbcTemplate.query("""
                select attachment_relative_path
                from report_run
                where user_id = ? and conversation_id = ? and attachment_relative_path is not null
                """, (rs, rowNum) -> rs.getString("attachment_relative_path"),
                userId, normalizedConversationId);
        int deleted = jdbcTemplate.update("""
                delete from ai_chat_conversation
                where user_id = ? and conversation_id = ?
                """, userId, normalizedConversationId);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "conversation not found");
        }
        if (reportAttachmentStorage != null) {
            attachmentPaths.forEach(reportAttachmentStorage::deleteReportAttachment);
        }
    }

    public ConversationResponse renameConversation(String userId, String conversationId, RenameConversationRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "request body is required");
        }
        String normalizedConversationId = normalizeConversationId(conversationId);
        String normalizedTitle = normalizeRenameTitle(request.title());
        Timestamp now = Timestamp.from(Instant.now());

        List<ConversationResponse> rows = jdbcTemplate.query("""
                update ai_chat_conversation
                set title = ?, title_initialized = TRUE, updated_at = ?
                where user_id = ? and conversation_id = ?
                returning conversation_id, title, pinned, mode, created_at, updated_at
                """, (rs, rowNum) -> mapConversationResponse(rs), normalizedTitle, now, userId, normalizedConversationId);

        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "conversation not found");
        }
        return rows.get(0);
    }

    public ConversationResponse pinConversation(String userId, String conversationId, PinConversationRequest request) {
        if (request == null || request.pinned() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "pinned is required");
        }
        String normalizedConversationId = normalizeConversationId(conversationId);

        List<ConversationResponse> rows = jdbcTemplate.query("""
                update ai_chat_conversation
                set pinned = ?
                where user_id = ? and conversation_id = ?
                returning conversation_id, title, pinned, mode, created_at, updated_at
                """, (rs, rowNum) -> mapConversationResponse(rs), request.pinned(), userId, normalizedConversationId);

        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "conversation not found");
        }
        return rows.get(0);
    }

    public String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId is required");
        }
        String normalized = conversationId.trim();
        if (!SAFE_CONVERSATION_ID.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId is invalid");
        }
        return normalized;
    }

    public void createConversationIfAbsent(String userId, String conversationId) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        Timestamp now = Timestamp.from(Instant.now());
        String defaultTitle = defaultTitle(ConversationMode.CHAT);
        jdbcTemplate.update("""
                insert into ai_chat_conversation (
                    user_id, conversation_id, title, mode, title_initialized, created_at, updated_at
                ) values (?, ?, ?, 'CHAT', FALSE, ?, ?)
                on conflict (user_id, conversation_id) do nothing
                """, userId, normalizedConversationId, defaultTitle, now, now);
    }

    public void setMode(String userId, String conversationId, ConversationMode mode) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        int updated = jdbcTemplate.update("""
                update ai_chat_conversation
                set mode = ?
                where user_id = ? and conversation_id = ?
                """, mode.name(), userId, normalizedConversationId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "conversation not found");
        }
    }

    public void requireConversation(String userId, String conversationId) {
        assertConversationExists(userId, normalizeConversationId(conversationId));
    }

    public List<ChatMessage> listRecentMessagesBefore(
            String userId,
            String conversationId,
            long beforeSeqNo,
            int requestedLimit) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        int limit = Math.min(Math.max(1, requestedLimit), 100);
        return jdbcTemplate.query("""
                select message_json::text as message_json
                from (
                    select seq_no, message_json
                    from ai_chat_message_history
                    where user_id = ? and conversation_id = ? and seq_no < ?
                    order by seq_no desc
                    limit ?
                ) recent
                order by seq_no
                """, (rs, rowNum) -> ChatMessageDeserializer.messageFromJson(
                rs.getString("message_json")),
                userId, normalizedConversationId, beforeSeqNo, limit);
    }

    public void initializeTitleFromFirstQuestion(String userId, String conversationId, String question) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String title = normalizeFirstQuestionTitle(question);
        jdbcTemplate.update("""
                update ai_chat_conversation
                set title = ?, title_initialized = TRUE, updated_at = CURRENT_TIMESTAMP
                where user_id = ? and conversation_id = ? and title_initialized = FALSE
                """, title, userId, normalizedConversationId);
    }

    private String resolveTitle(String title) {
        if (title == null || title.isBlank()) {
            return defaultTitle(ConversationMode.CHAT);
        }
        String normalized = title.trim();
        if (normalized.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "title length must be <= 255");
        }
        return normalized;
    }

    private String normalizeRenameTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "title is required");
        }
        String normalized = title.trim();
        if (normalized.length() > TITLE_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "title length must be <= 255");
        }
        return normalized;
    }

    private String normalizeFirstQuestionTitle(String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "question is required");
        }
        String normalized = question.trim().replaceAll("\\s+", " ");
        return normalized.length() <= TITLE_MAX_LENGTH
                ? normalized
                : normalized.substring(0, TITLE_MAX_LENGTH);
    }

    private String defaultTitle(ConversationMode mode) {
        String prefix = mode == ConversationMode.REPORT ? "New Report-" : "New Chat-";
        return prefix + TITLE_TIME_FORMATTER.format(Instant.now());
    }

    private void assertConversationExists(String userId, String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from ai_chat_conversation
                where user_id = ? and conversation_id = ?
                """, Integer.class, userId, conversationId);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "conversation not found");
        }
    }

    private ConversationMessageResponse toConversationMessageResponse(
            RawConversationMessage rawMessage,
            ReportMessageMetadata reportMetadata) {
        ChatMessage chatMessage = ChatMessageDeserializer.messageFromJson(rawMessage.messageJson());
        String role = toFrontendRole(chatMessage.type());
        if (role == null) {
            return null;
        }

        String content = extractContent(chatMessage);
        if (content == null || content.isBlank()) {
            return null;
        }

        String thinking = chatMessage instanceof AiMessage aiMessage ? aiMessage.thinking() : null;
        return new ConversationMessageResponse(
                rawMessage.seqNo(), role, content, thinking, rawMessage.createdAt(), reportMetadata);
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
                ConversationMode.valueOf(rs.getString("mode")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    public enum ConversationMode {
        CHAT, REPORT
    }

    public record CreateConversationRequest(String title, ConversationMode mode) {
        public CreateConversationRequest(String title) {
            this(title, null);
        }
    }

    public record RenameConversationRequest(String title) {
    }

    public record PinConversationRequest(Boolean pinned) {
    }

    public record ConversationResponse(String conversationId, String title, boolean pinned, ConversationMode mode,
                                       Instant createdAt, Instant updatedAt) {
        public ConversationResponse(String conversationId, String title, boolean pinned, Instant createdAt,
                                    Instant updatedAt) {
            this(conversationId, title, pinned, ConversationMode.CHAT, createdAt, updatedAt);
        }
    }

    public record ConversationMessageResponse(Long seqNo, String role, String content, String thinking,
                                              Instant createdAt, ReportMessageMetadata report) {
        public ConversationMessageResponse(Long seqNo, String role, String content, String thinking,
                                           Instant createdAt) {
            this(seqNo, role, content, thinking, createdAt, null);
        }
    }

    public record ReportMessageMetadata(UUID reportId, String question, String status, int evidenceCount,
                                        String attachmentFileName, String errorMessage, String phaseMessage,
                                        int progressPercent, int selectedDocumentCount,
                                        int analyzedDocumentCount, List<String> warnings,
                                        Instant updatedAt) {
    }

    private List<String> parseWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private record RawConversationMessage(Long seqNo, String messageJson, Instant createdAt) {
    }
}
