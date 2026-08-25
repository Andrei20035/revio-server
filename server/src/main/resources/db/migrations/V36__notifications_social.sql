-- ============================================================
-- Extends user_notifications (previously moderation-only, see V27) to also
-- carry social notifications (likes, comments) and broadcast ones
-- (community discovery, leaderboard/inactivity reminders).
--
-- category classifies every row, existing and new. Existing rows are all
-- moderation notifications, so they backfill to 'ACCOUNT' via the column
-- default — the same category future moderation inserts keep getting for
-- free, since none of that insert code is being changed here.
--
-- dedupe_key + UNIQUE (user_id, dedupe_key) is the aggregation/idempotency
-- mechanism for social events (e.g. repeated likes on the same post within a
-- window collapse onto one row via upsert instead of inserting a new one).
-- It stays NULL for existing/ACCOUNT rows, which have no such key.
--
-- post_id/comment_id point at the notification's target. Both are
-- ON DELETE SET NULL rather than CASCADE: posts and comments are hard-deleted
-- (see PostService.removePost / CommentDAO), and a notification about a
-- since-deleted spot or comment should keep existing as an inert entry in
-- the inbox rather than disappearing outright.
--
-- last_actor_user_id is likewise ON DELETE SET NULL (an actor's account can
-- be deleted independently of the notification recipient's).
-- last_actor_username is denormalized so the notification's copy survives
-- the actor renaming or the actor row going away.
-- ============================================================

ALTER TABLE user_notifications
    ADD COLUMN category             VARCHAR(16) NOT NULL DEFAULT 'ACCOUNT',
    ADD COLUMN dedupe_key           VARCHAR(200),
    ADD COLUMN target_type          VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN post_id              UUID REFERENCES posts(id) ON DELETE SET NULL,
    ADD COLUMN comment_id           UUID REFERENCES comments(id) ON DELETE SET NULL,
    ADD COLUMN actor_count          INT NOT NULL DEFAULT 1,
    ADD COLUMN last_actor_user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN last_actor_username  VARCHAR(50),
    ADD COLUMN deep_link            TEXT,
    ADD COLUMN updated_at           TIMESTAMP NOT NULL DEFAULT now(),
    ADD COLUMN push_state           VARCHAR(16) NOT NULL DEFAULT 'NOT_SENT',

    ADD CONSTRAINT chk_user_notifications_category
        CHECK (category IN ('ACCOUNT', 'LIKES', 'COMMENTS', 'DISCOVERY', 'REMINDERS')),
    ADD CONSTRAINT chk_user_notifications_target_type
        CHECK (target_type IN ('NONE', 'POST', 'COMMENT')),
    ADD CONSTRAINT chk_user_notifications_push_state
        CHECK (push_state IN ('NOT_SENT', 'SENT', 'SUPPRESSED')),

    ADD CONSTRAINT uq_user_notifications_user_dedupe_key
        UNIQUE (user_id, dedupe_key);

-- Existing rows were never "updated" after creation — align updated_at with
-- created_at rather than leaving it at this migration's execution time.
UPDATE user_notifications SET updated_at = created_at;

CREATE INDEX ix_user_notifications_category ON user_notifications (user_id, category, created_at DESC);
