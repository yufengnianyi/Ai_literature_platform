package com.example.demo_01.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private final JdbcTemplate jdbcTemplate;

    public UserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserResponse createUser(CreateUserRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        String username = normalizeUsername(request.username());
        String userId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbcTemplate.update("""
                    insert into app_user (user_id, username, created_at, updated_at)
                    values (?, ?, ?, ?)
                    """, userId, username, now, now);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
        }
        return new UserResponse(userId, username, now.toInstant(), now.toInstant());
    }

    public UserResponse getUser(String userId) {
        List<UserResponse> users = jdbcTemplate.query("""
                select user_id, username, created_at, updated_at
                from app_user
                where user_id = ?
                """, (rs, rowNum) -> new UserResponse(
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ), userId);
        if (users.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        return users.get(0);
    }

    public void assertUserExists(String userId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from app_user where user_id = ?", Integer.class, userId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username is required");
        }
        return username.trim();
    }

    public record CreateUserRequest(String username) {
    }

    public record UserResponse(String userId, String username, Instant createdAt, Instant updatedAt) {
    }
}