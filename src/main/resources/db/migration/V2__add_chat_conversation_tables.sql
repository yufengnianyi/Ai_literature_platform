CREATE TABLE IF NOT EXISTS ai_chat_conversation (
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, conversation_id)
);

CREATE INDEX IF NOT EXISTS ix_ai_chat_conversation_user_updated
    ON ai_chat_conversation(user_id, pinned DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS ai_chat_memory_snapshot (
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    messages_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, conversation_id),
    CONSTRAINT fk_ai_chat_memory_snapshot_conversation
        FOREIGN KEY (user_id, conversation_id)
        REFERENCES ai_chat_conversation(user_id, conversation_id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ai_chat_message_history (
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    seq_no BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    message_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, conversation_id, seq_no),
    CONSTRAINT fk_ai_chat_message_history_conversation
        FOREIGN KEY (user_id, conversation_id)
        REFERENCES ai_chat_conversation(user_id, conversation_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_ai_chat_message_history_conversation_created
    ON ai_chat_message_history(user_id, conversation_id, created_at);
