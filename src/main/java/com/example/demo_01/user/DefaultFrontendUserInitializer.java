package com.example.demo_01.user;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Configuration
@Slf4j
public class DefaultFrontendUserInitializer {

    static final String DEFAULT_USER_ID = "69544454-d59e-4baa-8bbb-95b117f12335";
    static final String DEFAULT_USERNAME = "alice";

    @Bean
    ApplicationRunner defaultFrontendUserSeeder(JdbcTemplate jdbcTemplate) {
        return args -> {
            List<String> existingUserIds = jdbcTemplate.query(
                    """
                            select user_id
                            from app_user
                            where user_id = ? or username = ?
                            """,
                    (rs, rowNum) -> rs.getString("user_id"),
                    DEFAULT_USER_ID,
                    DEFAULT_USERNAME
            );

            if (existingUserIds.isEmpty()) {
                Timestamp now = Timestamp.from(Instant.now());
                jdbcTemplate.update(
                        """
                                insert into app_user (user_id, username, created_at, updated_at)
                                values (?, ?, ?, ?)
                                """,
                        DEFAULT_USER_ID,
                        DEFAULT_USERNAME,
                        now,
                        now
                );
                log.info("Seeded default frontend user {}", DEFAULT_USER_ID);
                return;
            }

            if (existingUserIds.contains(DEFAULT_USER_ID)) {
                return;
            }

            log.warn("Skipping default frontend user seed because username '{}' already exists under a different id", DEFAULT_USERNAME);
        };
    }
}
