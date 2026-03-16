CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ai_chat_memory_snapshot (
    conversation_id VARCHAR(255) PRIMARY KEY,
    messages_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_chat_message_history (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    seq_no BIGINT NOT NULL,
    role VARCHAR(64) NOT NULL,
    message_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_chat_message_history_conversation_seq UNIQUE (conversation_id, seq_no)
);

CREATE INDEX IF NOT EXISTS idx_ai_chat_message_history_conversation_id
    ON ai_chat_message_history (conversation_id, seq_no);

CREATE TABLE IF NOT EXISTS rag_ingestion_state (
    dataset_key VARCHAR(128) PRIMARY KEY,
    dataset_hash VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ${vectorTable} (
    id UUID PRIMARY KEY,
    embedding VECTOR(${embeddingDimension}) NOT NULL,
    text TEXT,
    metadata JSONB
);

CREATE INDEX IF NOT EXISTS idx_${vectorTable}_embedding
    ON ${vectorTable}
    USING hnsw (embedding vector_cosine_ops);