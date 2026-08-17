-- ============================================================
-- Early Spotter bonus ledger.
--
-- One row per user who was granted the one-time 300-point Early Spotter
-- bonus. UNIQUE (user_id) is the primary idempotency guard — a user can
-- never have more than one grant. idempotency_key gives the same
-- absorb-the-retry guarantee as challenge_reward_ledger, built from
-- (user_id) since the bonus never repeats or gets revoked.
--
-- No backfill: only profiles created after this migration are eligible.
-- Existing users backfilled as Early Spotters by V21 do not receive it.
-- ============================================================

CREATE TABLE early_spotter_bonus_ledger (
                                             id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                             user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                             early_spotter_number   INT NOT NULL,
                                             nominal_delta          INT NOT NULL,
                                             applied_delta          INT NOT NULL,
                                             reason                 VARCHAR(32) NOT NULL,
                                             idempotency_key        VARCHAR(128) NOT NULL,
                                             created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

                                             CONSTRAINT chk_early_spotter_bonus_ledger_reason
                                                 CHECK (reason IN ('EARLY_SPOTTER_GRANTED')),
                                             UNIQUE (user_id),
                                             UNIQUE (idempotency_key)
);
