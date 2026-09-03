-- ============================================================
-- Adds challenges.notified_started_at — the "has the 'challenge is live' push
-- fan-out already run for this challenge" marker (push-notifications plan,
-- "challenge is live" work). Mirrors finalized_at's own shape: written only
-- once the fan-out has fully completed (see ChallengeStartJob), so a crash
-- mid-run leaves it NULL and the next cron tick picks the challenge back up
-- — the same crash-recovery guarantee finalized_at already gives
-- ChallengeFinalizationService.
--
-- The partial index only covers rows the detection query can ever match
-- (SCHEDULED, not yet notified) — same shape as V37__notification_outbox.sql's
-- drainer index, kept small by never indexing rows the query will never touch.
-- ============================================================

ALTER TABLE challenges ADD COLUMN notified_started_at TIMESTAMPTZ NULL;

CREATE INDEX idx_challenges_start_notification
  ON challenges (starts_at)
  WHERE status = 'SCHEDULED' AND notified_started_at IS NULL;
