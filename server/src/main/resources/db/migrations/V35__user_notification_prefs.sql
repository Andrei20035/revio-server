-- ============================================================
-- Push/inbox notification preferences, per user.
--
-- A missing row means every category is enabled and quiet hours are the
-- product default (00:00-08:00 local) — this is an opt-out model, matching
-- the analytics-consent default on the Android client (UserPreferences.kt).
-- The row is created lazily on first write, not at user creation.
-- ============================================================

CREATE TABLE user_notification_prefs (
    user_id            UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    likes_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    comments_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    discovery_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    reminders_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    quiet_start        TIME NOT NULL DEFAULT '00:00',
    quiet_end          TIME NOT NULL DEFAULT '08:00',
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
