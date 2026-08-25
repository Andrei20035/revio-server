-- ============================================================
-- notification_like_participation — plan §18, step 5.2: the "announced
-- likers cursor" backing the 60-minute like-notification aggregation
-- window and the unlike/re-like rule from plan §8.1.
--
-- One row per (post, liker) that is currently contributing to (or has
-- contributed to and had that contribution close out on) that post's like
-- notification. window_started_at is the 60-minute calendar bucket
-- (LikeService.windowStartFor) the liker's like fell into.
--
-- committed = false: this liker's like is still within the open window
-- their row was created in — reversible. An unlike found here still
-- withdraws the actor from the aggregated user_notifications row (and
-- deletes this row), mirroring "unlike before dispatch decrements the
-- counter, cancels at 0" (plan §8.1).
--
-- committed = true: the window this liker contributed to has since rolled
-- over (a later like/unlike on the same post observed a newer window),
-- so this liker is permanently treated as already announced for this
-- post — a later like from the same user is a silent no-op (plan §8.1:
-- "re-like de același user care a fost deja anunțat -> niciodată un al
-- doilea push"). Never deleted by an unlike once committed, matching
-- "unlike după trimitere -> nu retragem push-ul".
--
-- UNIQUE (post_id, liker_id): idempotency is on the pair (post, liker),
-- exactly as the plan specifies, not on the notification event/window.
-- ============================================================

CREATE TABLE notification_like_participation (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id            UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    liker_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    window_started_at  TIMESTAMPTZ NOT NULL,
    committed          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_notification_like_participation_post_liker
        UNIQUE (post_id, liker_id)
);
