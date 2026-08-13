-- ============================================================
-- Challenge finalization.
--
-- Marks when a SCHEDULED challenge's rewards have been reconciled after
-- ends_at, so the finalization job knows what still needs processing.
-- finalized_at is set exactly once, after finalization, and left NULL
-- until then — challenges that are DRAFT/CANCELLED, or not yet ended,
-- are never candidates.
-- ============================================================

ALTER TABLE challenges
    ADD COLUMN finalized_at TIMESTAMPTZ NULL;

CREATE INDEX idx_challenges_pending_finalization
    ON challenges (ends_at)
    WHERE status = 'SCHEDULED' AND finalized_at IS NULL;

-- Grandfather challenges that already ended before this migration ran: they were rewarded
-- under the old immediate-grant behavior, so there is nothing left for finalization to do.
-- Anything still active or in the future is left NULL and picked up by finalization normally.
UPDATE challenges
   SET finalized_at = ends_at
 WHERE status = 'SCHEDULED' AND ends_at <= now();
