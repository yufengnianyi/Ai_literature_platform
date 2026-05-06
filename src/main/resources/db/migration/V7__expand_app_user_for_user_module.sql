ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS user_account VARCHAR(128),
    ADD COLUMN IF NOT EXISTS user_password VARCHAR(255),
    ADD COLUMN IF NOT EXISTS user_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS user_avatar VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS user_profile VARCHAR(512),
    ADD COLUMN IF NOT EXISTS user_role VARCHAR(32) NOT NULL DEFAULT 'user',
    ADD COLUMN IF NOT EXISTS edit_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS is_delete SMALLINT NOT NULL DEFAULT 0;

UPDATE app_user
SET user_account = COALESCE(user_account, username),
    user_name = COALESCE(user_name, username),
    edit_time = COALESCE(edit_time, updated_at)
WHERE user_account IS NULL
   OR user_name IS NULL
   OR edit_time IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_app_user_user_account
    ON app_user (user_account)
    WHERE is_delete = 0;

CREATE INDEX IF NOT EXISTS idx_app_user_user_name
    ON app_user (user_name);
