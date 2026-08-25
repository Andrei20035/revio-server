-- ============================================================
-- Push notification device registry.
--
-- One row per (user, device_id) pair — device_id is the stable per-install
-- identifier generated client-side (Android: DeviceIdentity.installation_id).
-- Registration is an upsert on (user_id, device_id): re-registering the same
-- device (token rotation, app restart) updates the existing row instead of
-- creating a new one, per the "single active session per credential" model
-- (see auth_sessions.uq_active_session_per_credential) — the multi-row
-- history this table keeps is across devices/reinstalls, not concurrent
-- sessions for the same device.
--
-- fcm_token is globally UNIQUE among non-null values: FCM tokens are 1:1
-- with an app install, so a token reappearing under a different user/device
-- means the previous owner's row is stale (reinstall, account switch) and
-- must give up the token to avoid delivering push notifications to the
-- wrong account. The column is nullable so the stale row can be deactivated
-- and stripped of the token (freeing the UNIQUE slot — Postgres treats NULLs
-- as distinct) instead of being deleted, preserving it as device history.
-- ============================================================

CREATE TABLE user_devices (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id         VARCHAR(128) NOT NULL,
    fcm_token         VARCHAR(4096),
    firebase_project  VARCHAR(16) NOT NULL,
    platform          VARCHAR(16) NOT NULL,
    app_version       VARCHAR(32) NOT NULL,
    timezone          VARCHAR(64),
    locale            VARCHAR(16),
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_user_devices_firebase_project CHECK (firebase_project IN ('DEBUG', 'RELEASE')),
    CONSTRAINT chk_user_devices_platform CHECK (platform IN ('ANDROID')),
    CONSTRAINT chk_user_devices_active_has_token CHECK (NOT is_active OR fcm_token IS NOT NULL),
    CONSTRAINT uq_user_devices_user_device UNIQUE (user_id, device_id),
    CONSTRAINT uq_user_devices_fcm_token UNIQUE (fcm_token)
);

CREATE INDEX ix_user_devices_user_active ON user_devices (user_id) WHERE is_active;
