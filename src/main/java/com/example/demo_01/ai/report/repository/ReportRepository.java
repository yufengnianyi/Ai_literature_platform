package com.example.demo_01.ai.report.repository;

import com.example.demo_01.ai.report.model.ReportModels.RankedEvidence;
import com.example.demo_01.ai.report.model.ReportModels.ReportRunRecord;
import com.example.demo_01.ai.report.model.ReportModels.ReportStatus;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReportRepository {

    private static final String QUEUED_MESSAGE = "Report 已提交，正在重写问题。";
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ReportRunRecord submit(UUID reportId, String userId, String conversationId, String question) {
        List<String> conversations = jdbcTemplate.query("""
                select conversation_id
                from ai_chat_conversation
                where user_id = ? and conversation_id = ?
                for update
                """, (rs, rowNum) -> rs.getString("conversation_id"), userId, conversationId);
        if (conversations.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "conversation not found");
        }

        Long maxSeq = jdbcTemplate.queryForObject("""
                select coalesce(max(seq_no), 0)
                from ai_chat_message_history
                where user_id = ? and conversation_id = ?
                """, Long.class, userId, conversationId);
        long userSeq = (maxSeq == null ? 0L : maxSeq) + 1L;
        long assistantSeq = userSeq + 1L;
        Timestamp now = Timestamp.from(Instant.now());

        try {
            jdbcTemplate.update("""
                    insert into report_run (
                        report_id, user_id, conversation_id, question, status,
                        user_message_seq_no, assistant_message_seq_no, created_at, updated_at
                    ) values (?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?)
                    """, reportId, userId, conversationId, question, userSeq, assistantSeq, now, now);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "another report is already running in this conversation");
        }

        insertMessage(userId, conversationId, userSeq, "USER",
                ChatMessageSerializer.messageToJson(UserMessage.from(question)), now);
        insertMessage(userId, conversationId, assistantSeq, "AI",
                ChatMessageSerializer.messageToJson(AiMessage.from(QUEUED_MESSAGE)), now);

        String title = normalizeTitle(question);
        jdbcTemplate.update("""
                update ai_chat_conversation
                set title = case when title_initialized = FALSE then ? else title end,
                    title_initialized = TRUE,
                    updated_at = ?
                where user_id = ? and conversation_id = ?
                """, title, now, userId, conversationId);

        return find(reportId).orElseThrow();
    }

    public void updateStatus(UUID reportId, ReportStatus status, String rewrittenQuestion) {
        updateProgress(reportId, status, rewrittenQuestion, null, null, null, null, null);
    }

    public void updateProgress(UUID reportId,
                               ReportStatus status,
                               String rewrittenQuestion,
                               String phaseMessage,
                               Integer progressPercent,
                               Integer selectedDocumentCount,
                               Integer analyzedDocumentCount,
                               List<String> warnings) {
        jdbcTemplate.update("""
                update report_run
                set status = ?,
                    rewritten_question = coalesce(?, rewritten_question),
                    phase_message = coalesce(?, phase_message),
                    progress_percent = coalesce(?, progress_percent),
                    selected_document_count = coalesce(?, selected_document_count),
                    analyzed_document_count = coalesce(?, analyzed_document_count),
                    warnings_json = coalesce(cast(? as jsonb), warnings_json),
                    updated_at = CURRENT_TIMESTAMP
                where report_id = ?
                """, status.name(), rewrittenQuestion, phaseMessage, progressPercent,
                selectedDocumentCount, analyzedDocumentCount, toJson(warnings), reportId);
    }

    @Transactional
    public void complete(UUID reportId,
                         String rewrittenQuestion,
                         List<RankedEvidence> evidence,
                         String attachmentFileName,
                         String attachmentRelativePath,
                         String answerMarkdown,
                         boolean partial,
                         List<String> warnings,
                         int selectedDocumentCount,
                         int analyzedDocumentCount,
                         long totalMs) {
        jdbcTemplate.update("delete from report_evidence_link where report_id = ?", reportId);
        for (RankedEvidence item : evidence) {
            jdbcTemplate.update("""
                    insert into report_evidence_link (
                        report_id, evidence_id, match_score, rank, conflict_group
                    ) values (?, ?, ?, ?, ?)
                    """, reportId, item.evidence().evidenceId(), item.matchScore(),
                    item.rank(), item.conflictGroup());
        }

        Instant finishedAt = Instant.now();
        jdbcTemplate.update("""
                update report_run
                set status = ?,
                    rewritten_question = ?,
                    evidence_count = ?,
                    attachment_file_name = ?,
                    attachment_relative_path = ?,
                    answer_markdown = ?,
                    error_code = NULL,
                    error_message = NULL,
                    phase_message = ?,
                    progress_percent = 100,
                    selected_document_count = ?,
                    analyzed_document_count = ?,
                    warnings_json = cast(? as jsonb),
                    total_ms = ?,
                    finished_at = ?,
                    updated_at = ?
                where report_id = ?
                """, partial ? ReportStatus.PARTIAL_COMPLETED.name() : ReportStatus.COMPLETED.name(),
                rewrittenQuestion, evidence.size(), attachmentFileName, attachmentRelativePath,
                answerMarkdown, partial ? "报告已完成，部分文献分析失败" : "报告已完成",
                selectedDocumentCount, analyzedDocumentCount, toJson(warnings),
                totalMs, Timestamp.from(finishedAt), Timestamp.from(finishedAt), reportId);
        updateAssistantMessage(reportId, answerMarkdown, finishedAt);
    }

    @Transactional
    public void fail(UUID reportId, String errorCode, String errorMessage, long totalMs) {
        Instant finishedAt = Instant.now();
        String safeMessage = truncate(errorMessage, 4000);
        jdbcTemplate.update("""
                update report_run
                set status = 'FAILED',
                    error_code = ?,
                    error_message = ?,
                    total_ms = ?,
                    finished_at = ?,
                    updated_at = ?
                where report_id = ?
                """, errorCode, safeMessage, totalMs,
                Timestamp.from(finishedAt), Timestamp.from(finishedAt), reportId);
        updateAssistantMessage(reportId,
                "Report 生成失败：" + (safeMessage == null ? "未知错误" : safeMessage),
                finishedAt);
    }

    public Optional<ReportRunRecord> find(UUID reportId) {
        return jdbcTemplate.query("""
                select *
                from report_run
                where report_id = ?
                """, this::mapRun, reportId).stream().findFirst();
    }

    public Optional<ReportRunRecord> findOwned(UUID reportId, String userId) {
        return jdbcTemplate.query("""
                select *
                from report_run
                where report_id = ? and user_id = ?
                """, this::mapRun, reportId, userId).stream().findFirst();
    }

    public List<ReportRunRecord> findByConversation(String userId, String conversationId) {
        return jdbcTemplate.query("""
                select *
                from report_run
                where user_id = ? and conversation_id = ?
                order by created_at desc
                """, this::mapRun, userId, conversationId);
    }

    private void insertMessage(String userId, String conversationId, long seqNo, String role,
                               String messageJson, Timestamp now) {
        jdbcTemplate.update("""
                insert into ai_chat_message_history (
                    user_id, conversation_id, seq_no, role, message_json, created_at
                ) values (?, ?, ?, ?, cast(? as jsonb), ?)
                """, userId, conversationId, seqNo, role, messageJson, now);
    }

    private void updateAssistantMessage(UUID reportId, String content, Instant updatedAt) {
        jdbcTemplate.update("""
                update ai_chat_message_history m
                set message_json = cast(? as jsonb)
                from report_run r
                where r.report_id = ?
                  and m.user_id = r.user_id
                  and m.conversation_id = r.conversation_id
                  and m.seq_no = r.assistant_message_seq_no
                """, ChatMessageSerializer.messageToJson(AiMessage.from(content)), reportId);
        jdbcTemplate.update("""
                update ai_chat_conversation c
                set updated_at = ?
                from report_run r
                where r.report_id = ?
                  and c.user_id = r.user_id
                  and c.conversation_id = r.conversation_id
                """, Timestamp.from(updatedAt), reportId);
    }

    private ReportRunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new ReportRunRecord(
                rs.getObject("report_id", UUID.class),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                rs.getString("question"),
                rs.getString("rewritten_question"),
                ReportStatus.valueOf(rs.getString("status")),
                rs.getInt("evidence_count"),
                rs.getString("attachment_file_name"),
                rs.getString("attachment_relative_path"),
                rs.getString("answer_markdown"),
                rs.getLong("user_message_seq_no"),
                rs.getLong("assistant_message_seq_no"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("phase_message"),
                rs.getInt("progress_percent"),
                rs.getInt("selected_document_count"),
                rs.getInt("analyzed_document_count"),
                parseStringList(rs.getString("warnings_json")),
                (Long) rs.getObject("total_ms"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "finished_at")
        );
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String normalizeTitle(String question) {
        String normalized = question.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String toJson(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize report warnings", e);
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
