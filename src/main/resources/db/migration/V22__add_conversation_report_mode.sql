ALTER TABLE ai_chat_conversation
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN title_initialized BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE ai_chat_conversation
    ADD CONSTRAINT ck_ai_chat_conversation_mode
        CHECK (mode IN ('CHAT', 'REPORT'));

CREATE TABLE report_run (
    report_id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    question TEXT NOT NULL,
    rewritten_question TEXT,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('QUEUED', 'REWRITING', 'MATCHING', 'GENERATING', 'COMPLETED', 'FAILED')),
    evidence_count INTEGER NOT NULL DEFAULT 0,
    attachment_file_name VARCHAR(255),
    attachment_relative_path TEXT,
    answer_markdown TEXT,
    user_message_seq_no BIGINT NOT NULL,
    assistant_message_seq_no BIGINT NOT NULL,
    error_code VARCHAR(64),
    error_message TEXT,
    total_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT fk_report_run_conversation
        FOREIGN KEY (user_id, conversation_id)
        REFERENCES ai_chat_conversation(user_id, conversation_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_report_run_user_message
        UNIQUE (user_id, conversation_id, user_message_seq_no),
    CONSTRAINT uk_report_run_assistant_message
        UNIQUE (user_id, conversation_id, assistant_message_seq_no)
);

CREATE INDEX ix_report_run_conversation
    ON report_run(user_id, conversation_id, created_at DESC);

CREATE UNIQUE INDEX ux_report_run_active_conversation
    ON report_run(user_id, conversation_id)
    WHERE status IN ('QUEUED', 'REWRITING', 'MATCHING', 'GENERATING');

CREATE TABLE report_evidence_link (
    report_id UUID NOT NULL REFERENCES report_run(report_id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES compound_evidence(evidence_id),
    match_score DOUBLE PRECISION NOT NULL,
    rank INTEGER NOT NULL,
    conflict_group VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (report_id, evidence_id)
);

CREATE INDEX ix_report_evidence_link_rank
    ON report_evidence_link(report_id, rank);
