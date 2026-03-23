package com.example.demo_01.ai.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Map;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresIntegrationTestSupport {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("demo_01")
            .withUsername("demo_01")
            .withPassword("demo_01");

    protected DataSource dataSource;
    protected JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.execute("truncate table rag_ingestion_batch, rag_ingestion_job, rag_document, ai_chat_message_history, ai_chat_memory_snapshot, ai_chat_conversation, app_user, rag_ingestion_state, embedding_store restart identity cascade");
    }
}
