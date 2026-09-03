-- ============================================================
-- Adds CHALLENGE as a notification target type, symmetric with the existing
-- post_id/comment_id targets (V36__notifications_social.sql) — for the
-- "challenge is live" push notification (push-notifications plan), whose
-- deep link needs to carry which challenge it points at.
--
-- challenge_id is ON DELETE SET NULL, same tombstone behavior as post_id and
-- comment_id: a notification about a since-deleted challenge keeps existing
-- as an inert entry rather than disappearing outright. No backfill —
-- existing rows have no challenge target.
--
-- chk_user_notifications_target_type (V36) is relaxed to also allow
-- 'CHALLENGE'.
-- ============================================================

ALTER TABLE user_notifications
    ADD COLUMN challenge_id UUID REFERENCES challenges(id) ON DELETE SET NULL;

ALTER TABLE user_notifications
    DROP CONSTRAINT chk_user_notifications_target_type;

ALTER TABLE user_notifications
    ADD CONSTRAINT chk_user_notifications_target_type
        CHECK (target_type IN ('NONE', 'POST', 'COMMENT', 'CHALLENGE'));
