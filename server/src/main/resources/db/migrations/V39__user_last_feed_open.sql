-- ============================================================
-- users.last_feed_open_at — plan §18, step 6.2.
--
-- Backs the discovery job's 12h "already saw it" gate (§8.3) and, alongside
-- user_devices.last_seen_at (already tracked since V34/step 1.6), the
-- inactivity job's gates (§8.4). last_app_open needs no new column: it's
-- derived from MAX(user_devices.last_seen_at), already updated on every
-- POST /api/devices call (foreground/token refresh).
--
-- last_feed_open_at has no such existing write path — nothing today marks
-- "the user looked at the feed" — so it's tracked directly on users, written
-- by the feed endpoint itself (throttled there; see PostRoutes.kt), not
-- derived from anything else.
-- ============================================================

ALTER TABLE users
    ADD COLUMN last_feed_open_at TIMESTAMP;
