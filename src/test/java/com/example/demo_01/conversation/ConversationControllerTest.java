package com.example.demo_01.conversation;

import com.example.demo_01.ai.memory.PersistentChatMemoryStore;
import com.example.demo_01.ai.memory.UserConversationKey;
import com.example.demo_01.exception.BusinessException;
import com.example.demo_01.exception.ErrorCode;
import com.example.demo_01.exception.GlobalExceptionHandler;
import com.example.demo_01.user.UserService;
import com.example.demo_01.user.mapper.UserMapper;
import com.example.demo_01.user.model.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConversationController.class)
@org.springframework.context.annotation.Import(GlobalExceptionHandler.class)
class ConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private UserService userService;

    @MockBean
    private UserMapper userMapper;

    private User mockLoginUser() {
        User user = new User();
        user.setUserId("u-1");
        user.setUserRole("user");
        return user;
    }

    @Test
    void shouldCreateConversation() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        ConversationService.ConversationResponse response = new ConversationService.ConversationResponse(
                "c-1", "New conversation-20260312120000", false,
                Instant.parse("2026-03-12T04:00:00Z"), Instant.parse("2026-03-12T04:00:00Z"));
        when(conversationService.createConversation("u-1", new ConversationService.CreateConversationRequest("my title")))
                .thenReturn(response);

        mockMvc.perform(post("/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConversationService.CreateConversationRequest("my title"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.conversationId").value("c-1"));
    }

    @Test
    void shouldListConversations() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        when(conversationService.listConversations("u-1")).thenReturn(List.of(
                new ConversationService.ConversationResponse("c-1", "title", true,
                        Instant.parse("2026-03-12T04:00:00Z"), Instant.parse("2026-03-12T04:00:00Z"))
        ));

        mockMvc.perform(get("/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].conversationId").value("c-1"))
                .andExpect(jsonPath("$.data[0].pinned").value(true));
    }

    @Test
    void shouldListConversationMessages() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        when(conversationService.listConversationMessages("u-1", "c-1")).thenReturn(List.of(
                new ConversationService.ConversationMessageResponse(1L, "user", "hello", Instant.parse("2026-03-12T04:00:00Z")),
                new ConversationService.ConversationMessageResponse(2L, "assistant", "hi", Instant.parse("2026-03-12T04:00:02Z"))
        ));

        mockMvc.perform(get("/conversations/c-1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].seqNo").value(1))
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[1].role").value("assistant"));
    }

    @Test
    void shouldReturnEmptyConversationMessages() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        when(conversationService.listConversationMessages("u-1", "c-empty")).thenReturn(List.of());

        mockMvc.perform(get("/conversations/c-empty/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void shouldReturnNotFoundWhenConversationMessagesMissing() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        doThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "conversation not found"))
                .when(conversationService).listConversationMessages("u-1", "missing");

        mockMvc.perform(get("/conversations/missing/messages"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND_ERROR.getCode()));
    }

    @Test
    void shouldRenameConversation() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        ConversationService.RenameConversationRequest request = new ConversationService.RenameConversationRequest("new title");
        ConversationService.ConversationResponse response = new ConversationService.ConversationResponse(
                "c-1", "new title", false, Instant.parse("2026-03-12T04:00:00Z"),
                Instant.parse("2026-03-12T05:00:00Z"));

        when(conversationService.renameConversation("u-1", "c-1", request)).thenReturn(response);

        mockMvc.perform(patch("/conversations/c-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("new title"));
    }

    @Test
    void shouldPinConversation() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        ConversationService.PinConversationRequest request = new ConversationService.PinConversationRequest(true);
        ConversationService.ConversationResponse response = new ConversationService.ConversationResponse(
                "c-1", "title", true, Instant.parse("2026-03-12T04:00:00Z"),
                Instant.parse("2026-03-12T05:00:00Z"));

        when(conversationService.pinConversation("u-1", "c-1", request)).thenReturn(response);

        mockMvc.perform(patch("/conversations/c-1/pin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pinned").value(true));
    }

    @Test
    void shouldReturnNotFoundWhenPinningMissingConversation() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        ConversationService.PinConversationRequest request = new ConversationService.PinConversationRequest(true);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "conversation not found"))
                .when(conversationService).pinConversation("u-1", "missing", request);

        mockMvc.perform(patch("/conversations/missing/pin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND_ERROR.getCode()));
    }

    @Test
    void shouldDeleteConversation() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(mockLoginUser());
        mockMvc.perform(delete("/conversations/c-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(conversationService).deleteConversation("u-1", "c-1");
    }

    @Test
    void shouldRejectWhenNotLoggedIn() throws Exception {
        doThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "login required"))
                .when(userService).getLoginUser(any());
        mockMvc.perform(get("/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));
    }
}

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationHistoryPersistenceIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("demo_01")
            .withUsername("demo_01")
            .withPassword("demo_01");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setUpDatabase() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "vectorTable", "embedding_store",
                        "embeddingDimension", "1024"
                ))
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    @AfterEach
    void cleanTables() {
        jdbcTemplate.execute("truncate table ai_chat_message_history, ai_chat_memory_snapshot, ai_chat_conversation, app_user, rag_ingestion_state, embedding_store restart identity cascade");
    }

    @Test
    void shouldReturnFullConversationHistoryBeyondSnapshotWindow() {
        String userId = "user-1";
        String conversationId = "conversation-1";
        prepareUserAndConversation(userId, conversationId);

        PersistentChatMemoryStore store = new PersistentChatMemoryStore(jdbcTemplate);
        String memoryKey = UserConversationKey.compose(userId, conversationId);
        List<ChatMessage> fullHistory = createConversationHistory(22);

        store.updateMessages(memoryKey, fullHistory.subList(0, 20));
        store.updateMessages(memoryKey, fullHistory.subList(2, 22));

        org.junit.jupiter.api.Assertions.assertEquals(20, store.getMessages(memoryKey).size());

        ConversationService service = new ConversationService(jdbcTemplate);
        List<ConversationService.ConversationMessageResponse> messages =
                service.listConversationMessages(userId, conversationId);

        org.junit.jupiter.api.Assertions.assertEquals(22, messages.size());
        org.junit.jupiter.api.Assertions.assertEquals(1L, messages.get(0).seqNo());
        org.junit.jupiter.api.Assertions.assertEquals("user", messages.get(0).role());
        org.junit.jupiter.api.Assertions.assertEquals("message-1", messages.get(0).content());
        org.junit.jupiter.api.Assertions.assertEquals(22L, messages.get(21).seqNo());
        org.junit.jupiter.api.Assertions.assertEquals("assistant", messages.get(21).role());
        org.junit.jupiter.api.Assertions.assertEquals("message-22", messages.get(21).content());
    }

    @Test
    void shouldStripRagInjectedChunksFromPersistedUserMessage() {
        String userId = "user-rag";
        String conversationId = "conversation-rag";
        prepareUserAndConversation(userId, conversationId);

        PersistentChatMemoryStore store = new PersistentChatMemoryStore(jdbcTemplate);
        String memoryKey = UserConversationKey.compose(userId, conversationId);

        List<ChatMessage> history = List.of(
                UserMessage.from("""
                        拟南芥中有多少RLK

                        Answer using the following information:
                        Paper: metaRLK 2.0
                        Section: Introduction
                        content: retrieved chunk
                        """),
                AiMessage.from("assistant reply")
        );
        store.updateMessages(memoryKey, history);

        ConversationService service = new ConversationService(jdbcTemplate);
        List<ConversationService.ConversationMessageResponse> messages =
                service.listConversationMessages(userId, conversationId);

        org.junit.jupiter.api.Assertions.assertEquals(2, messages.size());
        org.junit.jupiter.api.Assertions.assertEquals("user", messages.get(0).role());
        org.junit.jupiter.api.Assertions.assertEquals("拟南芥中有多少RLK", messages.get(0).content());
        org.junit.jupiter.api.Assertions.assertEquals("assistant reply", messages.get(1).content());
    }

    @Test
    void shouldCascadeDeleteConversationHistoryAndSnapshot() {
        String userId = "user-delete";
        String conversationId = "conversation-delete";
        prepareUserAndConversation(userId, conversationId);

        PersistentChatMemoryStore store = new PersistentChatMemoryStore(jdbcTemplate);
        String memoryKey = UserConversationKey.compose(userId, conversationId);
        store.updateMessages(memoryKey, List.of(
                UserMessage.from("question"),
                AiMessage.from("answer")
        ));

        ConversationService service = new ConversationService(jdbcTemplate);
        service.deleteConversation(userId, conversationId);

        org.junit.jupiter.api.Assertions.assertEquals(0, countRows("ai_chat_conversation", userId, conversationId));
        org.junit.jupiter.api.Assertions.assertEquals(0, countRows("ai_chat_memory_snapshot", userId, conversationId));
        org.junit.jupiter.api.Assertions.assertEquals(0, countRows("ai_chat_message_history", userId, conversationId));
    }

    @Test
    void shouldListPinnedConversationsBeforeRecentOnes() {
        String userId = "user-pin";
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                insert into app_user (user_id, username, created_at, updated_at)
                values (?, ?, ?, ?)
                """, userId, userId, now, now);
        jdbcTemplate.update("""
                insert into ai_chat_conversation (user_id, conversation_id, title, pinned, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?)
                """,
                userId, "recent-unpinned", "Recent", false, now, Timestamp.from(Instant.parse("2026-03-18T12:00:00Z")),
                userId, "older-pinned", "Pinned older", true, now, Timestamp.from(Instant.parse("2026-03-18T09:00:00Z")),
                userId, "newer-pinned", "Pinned newer", true, now, Timestamp.from(Instant.parse("2026-03-18T13:00:00Z")));

        ConversationService service = new ConversationService(jdbcTemplate);
        List<ConversationService.ConversationResponse> conversations = service.listConversations(userId);

        org.junit.jupiter.api.Assertions.assertEquals(3, conversations.size());
        org.junit.jupiter.api.Assertions.assertEquals("newer-pinned", conversations.get(0).conversationId());
        org.junit.jupiter.api.Assertions.assertTrue(conversations.get(0).pinned());
        org.junit.jupiter.api.Assertions.assertEquals("older-pinned", conversations.get(1).conversationId());
        org.junit.jupiter.api.Assertions.assertTrue(conversations.get(1).pinned());
        org.junit.jupiter.api.Assertions.assertEquals("recent-unpinned", conversations.get(2).conversationId());
        org.junit.jupiter.api.Assertions.assertFalse(conversations.get(2).pinned());
    }

    private List<ChatMessage> createConversationHistory(int count) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String content = "message-" + i;
            messages.add(i % 2 == 1 ? UserMessage.from(content) : AiMessage.from(content));
        }
        return messages;
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

    private int countRows(String tableName, String userId, String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from %s
                where user_id = ? and conversation_id = ?
                """.formatted(tableName), Integer.class, userId, conversationId);
        return count == null ? 0 : count;
    }
}
