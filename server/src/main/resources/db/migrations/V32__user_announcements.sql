-- ============================================================
-- One-time, per-user announcements (e.g. Early Spotter welcome / bonus
-- cards). A row is created PENDING at the moment the underlying event is
-- granted (see the Early Spotter allocation in UserDao.createUser) and
-- moves to SEEN once the client acknowledges it.
--
-- PRIMARY KEY (user_id, announcement_key) is the idempotency guard: the
-- same key can never be inserted twice for the same user, mirroring the
-- pattern used by feedback_prompt_state and early_spotter_bonus_ledger.
--
-- payload is a JSON-encoded string stored as TEXT rather than JSONB: the
-- project has no exposed-json module dependency (see AdminAuditLogTable's
-- metadata column for the same convention), and Exposed's plain text bind
-- does not implicitly cast to jsonb on insert. Nothing queries into its
-- structure at the SQL level — it is opaque to Postgres, serialized and
-- deserialized entirely in application code.
--
-- No backfill: only announcements created after this migration exist.
-- ============================================================

CREATE TABLE user_announcements (
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    announcement_key    VARCHAR(40) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payload             TEXT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    seen_at             TIMESTAMPTZ NULL,

    PRIMARY KEY (user_id, announcement_key),
    CONSTRAINT chk_user_announcements_status
        CHECK (status IN ('PENDING', 'SEEN'))
);
