-- Supports leaderboard rank/position queries ordered by LEADERBOARD_ORDER
-- (users.spot_score DESC, users.id ASC) — see LeaderboardDAO.kt. Without it,
-- getUserRank() runs a full table scan/count on every call.
CREATE INDEX ix_users_spot_score_id ON users (spot_score DESC, id ASC);
