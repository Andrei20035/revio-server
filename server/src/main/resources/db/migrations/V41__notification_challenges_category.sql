-- ============================================================
-- Adds CHALLENGES as a notification category (push-notifications plan,
-- "challenge is live" work): notifies eligible users when an admin-scheduled
-- challenge's window actually opens (starts_at reached), as opposed to
-- DISCOVERY's community-content digest.
--
-- challenges_enabled follows the same opt-out default as every other
-- category in user_notification_prefs (V35) — a missing row, or an existing
-- row from before this migration, reads as enabled via the column default.
--
-- chk_user_notifications_category (V36) is relaxed to also allow
-- 'CHALLENGES' — no existing row's category changes, this only widens what
-- future inserts may use.
-- ============================================================

ALTER TABLE user_notification_prefs
    ADD COLUMN challenges_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE user_notifications
    DROP CONSTRAINT chk_user_notifications_category;

ALTER TABLE user_notifications
    ADD CONSTRAINT chk_user_notifications_category
        CHECK (category IN ('ACCOUNT', 'LIKES', 'COMMENTS', 'DISCOVERY', 'REMINDERS', 'CHALLENGES'));
