-- ============================================================
-- notification_outbox — one row per (notification, device) send attempt.
--
-- A single notification event (a row in user_notifications) can fan out to
-- multiple devices for the same user (see user_devices, multi-row device
-- history per D4). Each fan-out target gets its own outbox row so retry,
-- delivery state, and TTL are tracked per device rather than per event.
--
-- UNIQUE (notification_id, device_id) is the send-idempotency mechanism: a
-- dispatcher that runs twice (two loop iterations racing, or two replicas
-- before the advisory lock is held) cannot create two sends for the same
-- (event, device) pair — the second insert attempt is rejected outright.
--
-- notification_id cascades on delete: if the parent notification is gone
-- (e.g. account deletion cascades through user_notifications), the pending
-- sends for it are meaningless and should disappear with it. device_id
-- also cascades: user_devices rows are only ever soft-deactivated in normal
-- operation (never deleted), so this only fires on account deletion, where
-- the outbox rows have nothing left to send to anyway.
--
-- next_attempt_at drives retry backoff; not_before holds a dispatch time
-- deferred past quiet hours. Both are read together by the drainer query
-- (state IN ('PENDING','FAILED') AND next_attempt_at <= now()), hence the
-- composite index below. expires_at is the freshness TTL (e.g. a stale
-- like-digest abandoned rather than sent hours late) — checked by the
-- dispatcher at send time, not indexed here since it is a small fraction of
-- the drainer's WHERE clause, not the primary selectivity filter.
-- ============================================================

CREATE TABLE notification_outbox (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id   UUID NOT NULL REFERENCES user_notifications(id) ON DELETE CASCADE,
    device_id         UUID NOT NULL REFERENCES user_devices(id) ON DELETE CASCADE,
    state             VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts          INT NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    not_before        TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    fcm_message_id    VARCHAR(256),
    last_error_code   VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_notification_outbox_state
        CHECK (state IN ('PENDING', 'SENT', 'ACCEPTED', 'FAILED', 'DEAD', 'DROPPED')),
    CONSTRAINT uq_notification_outbox_notification_device
        UNIQUE (notification_id, device_id)
);

CREATE INDEX ix_notification_outbox_drainer
    ON notification_outbox (state, next_attempt_at)
    WHERE state IN ('PENDING', 'FAILED');
