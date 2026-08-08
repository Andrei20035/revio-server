-- ============================================================
-- Admin moderation: ADMIN role grant, user ban fields, violation
-- history, in-app notifications, admin audit log, and a retry
-- queue for storage objects that failed to delete.
-- ============================================================

-- 1. Grant ADMIN role to the personal account (no public endpoint for this).
UPDATE users SET role = 'ADMIN'
WHERE auth_credential_id = (SELECT id FROM auth_credentials WHERE lower(email) = 'amrusu2@gmail.com');

-- 2. Ban fields on users.
--    An active ban = ban_permanent OR banned_until > now() — expiry is implicit,
--    no scheduled job needed to lift a temporary ban.
ALTER TABLE users
    ADD COLUMN banned_until  TIMESTAMP NULL,
    ADD COLUMN ban_permanent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ban_reason    TEXT NULL,
    ADD COLUMN banned_at     TIMESTAMP NULL,
    ADD COLUMN banned_by     UUID NULL REFERENCES users(id) ON DELETE SET NULL;

-- 3. Moderation violations.
--    post_id is deliberately NOT a foreign key: posts.id rows are removed by the
--    moderation takedown itself (and all post-referencing tables cascade-delete on
--    post removal), so a FK here would erase the violation record at the exact
--    moment it needs to persist. Caption/image key are snapshotted for audit context.
CREATE TABLE moderation_violations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id        UUID NOT NULL,
    post_image_key TEXT NULL,
    post_caption   TEXT NULL,
    reason         VARCHAR(40) NOT NULL,
    reason_details TEXT NULL,
    admin_id       UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    revoked_at     TIMESTAMP NULL,
    revoked_by     UUID NULL REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX ix_violations_user_active ON moderation_violations (user_id) WHERE revoked_at IS NULL;

-- 4. In-app notifications (pull-based, no push infrastructure exists yet).
CREATE TABLE user_notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       VARCHAR(32) NOT NULL,
    title      TEXT NOT NULL,
    body       TEXT NOT NULL,
    blocking   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    read_at    TIMESTAMP NULL
);

CREATE INDEX ix_notifications_user_unread ON user_notifications (user_id, created_at DESC) WHERE read_at IS NULL;

-- 5. Admin audit log.
CREATE TABLE admin_audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action      VARCHAR(40) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id   UUID NOT NULL,
    metadata    JSONB NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- 6. Orphaned storage objects: recorded when deleteImage fails after a moderation
--    takedown commits, so cleanup can be retried without ever restoring the post.
CREATE TABLE orphaned_storage_objects (
    object_key      TEXT PRIMARY KEY,
    attempts        INT NOT NULL DEFAULT 1,
    last_error      TEXT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMP NOT NULL DEFAULT now()
);

-- 7. Extend auth_sessions.revoked_reason to allow ACCOUNT_SUSPENDED (V13's CHECK
--    constraint predates the ban feature and must be widened before it can be used).
ALTER TABLE auth_sessions DROP CONSTRAINT chk_revoked_reason;
ALTER TABLE auth_sessions ADD CONSTRAINT chk_revoked_reason CHECK (
    revoked_reason IS NULL OR revoked_reason IN (
        'SUPERSEDED', 'LOGOUT', 'LOGOUT_ALL', 'PASSWORD_CHANGED', 'ACCOUNT_DELETED',
        'REFRESH_TOKEN_REUSED', 'IDLE_EXPIRED', 'ABSOLUTE_EXPIRED', 'ACCOUNT_SUSPENDED'
    )
);
