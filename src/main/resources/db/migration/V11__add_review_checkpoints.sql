-- ====== 为交互式 Review Pipeline 添加 Checkpoint 支持 ======

-- 候选文献增加用户审查字段
ALTER TABLE review_candidate
    ADD COLUMN IF NOT EXISTS user_excluded BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS user_prioritized BOOLEAN DEFAULT FALSE;

-- 证据增加用户排除字段
ALTER TABLE review_evidence
    ADD COLUMN IF NOT EXISTS user_excluded BOOLEAN DEFAULT FALSE;

-- 任务增加用户指导语和重点子问题
ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS user_guidance TEXT,
    ADD COLUMN IF NOT EXISTS focus_sub_questions JSONB;
