ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS template_id VARCHAR(128) NOT NULL DEFAULT 'antimicrobial_compound';

ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS selected_document_id UUID;

ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS selected_document_title TEXT;

CREATE INDEX IF NOT EXISTS ix_review_task_selected_document
    ON review_task (selected_document_id);
