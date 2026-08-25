-- ============================================================
-- user_notifications.enqueued_delta_points — plan §9 / §18, step 6.5.
--
-- Only ever set for a day-7 inactivity reminder's leaderboard-conditioned
-- copy ("You're close to moving up" / "Your next spot could put you past
-- #{rank-1}."). It records the pointsToGuaranteeMoveUp value
-- (LeaderboardDeltaService) at the moment the notification was enqueued, so
-- the outbox processor can compare it against the freshly recomputed value
-- at actual dispatch time and fall back to generic copy if the two have
-- drifted by more than 30% — quiet-hours deferral can put hours between
-- enqueue and dispatch, during which the leaderboard moves.
--
-- NULL for every other notification (likes, comments, discovery, day-3
-- reminders, account/moderation) — this column has no meaning for them.
-- ============================================================

ALTER TABLE user_notifications
    ADD COLUMN enqueued_delta_points INT;
