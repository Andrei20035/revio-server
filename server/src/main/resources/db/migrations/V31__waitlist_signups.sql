-- ============================================================
-- Local copy of the Supabase waitlist_signups table.
--
-- id is the Supabase row id (not locally generated) — this makes upsert-by-id
-- naturally idempotent: the same row synced N times is still exactly one row.
--
-- email_normalized is a generated column (lower(trim(email))), and is the
-- ONLY column auth lookups query against — uq_waitlist_email_normalized is
-- the critical index for that lookup.
--
-- Waitlist membership does NOT reserve Early Spotter numbers and does not
-- participate in early_spotter_counter/users.early_spotter_number in any
-- way — no FK to users, no reservation columns.
--
-- Deletions in Supabase are never propagated here: this table is
-- append/update-only. Reconciliation reports missing rows as a metric, it
-- never deletes.
-- ============================================================

CREATE TABLE waitlist_signups (
    id                  UUID PRIMARY KEY,
    email               TEXT NOT NULL,
    email_normalized    TEXT GENERATED ALWAYS AS (lower(trim(email))) STORED,
    username            TEXT,
    platform            TEXT,
    country             TEXT,
    source_created_at   TIMESTAMPTZ NOT NULL,
    source_updated_at   TIMESTAMPTZ,
    synced_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_waitlist_email_normalized_not_blank
        CHECK (LENGTH(TRIM(email_normalized)) > 0)
);

CREATE UNIQUE INDEX uq_waitlist_email_normalized
    ON waitlist_signups (email_normalized);

CREATE INDEX idx_waitlist_source_updated_at
    ON waitlist_signups (source_updated_at);
