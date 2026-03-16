-- User table
CREATE TABLE IF NOT EXISTS app_user (
    user_id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(128) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO app_user (user_id, username, created_at, updated_at)
VALUES ('legacy', 'legacy', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (user_id) DO NOTHING;

-- Conversation table
CREATE TABLE IF NOT EXISTS ai_chat_conversation (
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, conversation_id)
);

ALTER TABLE ai_chat_memory_snapshot
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);

UPDATE ai_chat_memory_snapshot
SET user_id = 'legacy'
WHERE user_id IS NULL;

ALTER TABLE ai_chat_memory_snapshot
    ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE ai_chat_message_history
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);

UPDATE ai_chat_message_history
SET user_id = 'legacy'
WHERE user_id IS NULL;

ALTER TABLE ai_chat_message_history
    ALTER COLUMN user_id SET NOT NULL;

INSERT INTO ai_chat_conversation (user_id, conversation_id, title, created_at, updated_at)
SELECT user_id,
       conversation_id,
       LEFT(CONCAT('Legacy-', conversation_id), 255),
       MIN(event_time),
       MAX(event_time)
FROM (
    SELECT user_id, conversation_id, updated_at AS event_time
    FROM ai_chat_memory_snapshot
    UNION ALL
    SELECT user_id, conversation_id, created_at AS event_time
    FROM ai_chat_message_history
) events
GROUP BY user_id, conversation_id
ON CONFLICT (user_id, conversation_id) DO NOTHING;

ALTER TABLE ai_chat_memory_snapshot
    DROP CONSTRAINT IF EXISTS ai_chat_memory_snapshot_pkey;

ALTER TABLE ai_chat_memory_snapshot
    ADD CONSTRAINT ai_chat_memory_snapshot_pkey PRIMARY KEY (user_id, conversation_id);

ALTER TABLE ai_chat_message_history
    DROP CONSTRAINT IF EXISTS uk_ai_chat_message_history_conversation_seq;

ALTER TABLE ai_chat_message_history
    ADD CONSTRAINT uk_ai_chat_message_history_user_conversation_seq UNIQUE (user_id, conversation_id, seq_no);

DROP INDEX IF EXISTS idx_ai_chat_message_history_conversation_id;

CREATE INDEX IF NOT EXISTS idx_ai_chat_message_history_user_conversation_id
    ON ai_chat_message_history (user_id, conversation_id, seq_no);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_ai_chat_conversation_user'
    ) THEN
        ALTER TABLE ai_chat_conversation
            ADD CONSTRAINT fk_ai_chat_conversation_user
                FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE CASCADE;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_ai_chat_memory_snapshot_conversation'
    ) THEN
        ALTER TABLE ai_chat_memory_snapshot
            ADD CONSTRAINT fk_ai_chat_memory_snapshot_conversation
                FOREIGN KEY (user_id, conversation_id)
                    REFERENCES ai_chat_conversation (user_id, conversation_id)
                    ON DELETE CASCADE;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_ai_chat_message_history_conversation'
    ) THEN
        ALTER TABLE ai_chat_message_history
            ADD CONSTRAINT fk_ai_chat_message_history_conversation
                FOREIGN KEY (user_id, conversation_id)
                    REFERENCES ai_chat_conversation (user_id, conversation_id)
                    ON DELETE CASCADE;
    END IF;
END $$;