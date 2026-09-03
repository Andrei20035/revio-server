# Push notifications: environment variables

The push pipeline (device registration → `user_notifications` → `notification_outbox` →
dispatcher → FCM) is gated by several environment variables. Two of them
(`ENABLE_LIKES_PUSH`, `ENABLE_COMMENTS_PUSH`) were previously undocumented — absent from
`.env.example` and from every doc — which made it easy for a deploy to have the dispatcher running
but never actually enqueue anything for likes/comments, with no obvious signal why.

## Environment variables

| Variable | Purpose | Default if unset |
|---|---|---|
| `ENABLE_PUSH_DISPATCHER` | Starts the outbox dispatcher loop (`PushDispatcherLoop`, drains `notification_outbox` every 25s). Must be exactly `"true"` (case-sensitive) — `TRUE`/`1` do not enable it. | Off — dispatcher never starts, rows accumulate in `notification_outbox` indefinitely. |
| `ENABLE_LIKES_PUSH` | Gates whether a like ever gets enqueued into `notification_outbox` (`LikeService.enqueuePushIfEligible`). Must be exactly `"true"`. | Off — likes are still recorded in `user_notifications` (visible in-app), but never pushed. |
| `ENABLE_COMMENTS_PUSH` | Same gate for comments (`CommentService.enqueuePushIfEligible`). Must be exactly `"true"`. | Off — same as above, for comments. |
| `CRON_SECRET` | Shared secret required by `X-Cron-Secret`-protected internal endpoints (e.g. `/api/internal/notifications/inactivity`, `/api/internal/notifications/challenge-start`). Missing → those endpoints fail closed (401). | Endpoints reject every request. |
| `ADMIN_PUSH_TEST_TOKEN` | `X-Admin-Token` required by `/api/internal/notifications/test-send` and `/api/internal/notifications/metrics`. | Those endpoints reject every request. |
| `FCM_SA_JSON_DEBUG` | Firebase service-account JSON (single line) for the DEBUG Firebase project (`revio-debug-47037`). | That project has no usable FCM credential — sends to DEBUG devices are skipped (`FcmSendResult.Unconfigured`), row stays `PENDING`, retried every tick. |
| `FCM_SA_JSON_RELEASE` | Same, for the RELEASE Firebase project (`carspotter-f2b68`). | Same as above, for RELEASE devices. |

## CHALLENGES category — "challenge is live"

Unlike likes/comments, the `CHALLENGES` category ("challenge is live" push, sent when an
admin-scheduled challenge's window opens) has no `ENABLE_*_PUSH` gate of its own — same as
`DISCOVERY`/`REMINDERS`, it's controlled purely by each user's own `challenges_enabled` row in
`user_notification_prefs` (default: on), not by a server-wide environment switch.

Its trigger is `ChallengeStartJob`, run every 5 minutes by the external cron hitting
`POST /api/internal/notifications/challenge-start` (`.github/workflows/challenge-start.yml`) —
gated by the same `CRON_SECRET` as `/api/internal/notifications/inactivity` and
`/api/internal/notifications/discovery` above, with no environment variable of its own. A
challenge whose window has opened but hasn't been notified shows up as `challenges.status =
'SCHEDULED' AND challenges.notified_started_at IS NULL AND challenges.starts_at <= now()` — if
that set isn't emptying out on schedule, check the workflow's run history and that `CRON_SECRET`
matches between the repo secret and the server's own environment.

A separate alerting probe, `GET /api/internal/notifications/challenge-start-health`
(`.github/workflows/challenge-start-health.yml`, every 10 minutes — same `CRON_SECRET` gate),
mirrors `/api/admin/challenges/finalization-health`: it returns `503` with the offending
challenge id(s)/`startsAt` the moment any SCHEDULED, un-notified challenge's window has been open
for more than 30 minutes, and `200` with an empty list otherwise. A scheduled `curl -f` failing on
this endpoint *is* the alert.

All of these are read directly from `System.getenv(...)` by the server process — with
`docker-compose.yml`'s `app` service using `env_file: - .env` (whole-file passthrough, not an
explicit `environment:` allowlist), anything set in the real `.env` reaches the container as-is.
There is nothing to additionally wire in `docker-compose.yml` — the only real risk is a variable
being absent from `.env` itself, which this doc and `.env.example` exist to prevent.

## Verifying a deploy without reading any secret value

```bash
# FCM credentials present and usable for each project — never reveals the credential itself
curl -s http://localhost:8080/health/fcm
# {"fcm":{"DEBUG":{"healthy":true},"RELEASE":{"healthy":true}}}

# Outbox queue depth / age / unconfigured counters (requires ADMIN_PUSH_TEST_TOKEN)
curl -s http://localhost:8080/api/internal/notifications/metrics \
     -H "X-Admin-Token: $ADMIN_PUSH_TEST_TOKEN"

# Send one FCM message directly to a token, bypassing outbox/dispatcher/aggregation entirely —
# isolates whether FCM delivery itself works, independent of ENABLE_LIKES_PUSH/ENABLE_COMMENTS_PUSH
# or the dispatcher loop.
curl -s -X POST http://localhost:8080/api/internal/notifications/test-send \
     -H "X-Admin-Token: $ADMIN_PUSH_TEST_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"firebaseProject":"DEBUG","fcmToken":"<token>","title":"Test","body":"Hello"}'
```

A healthy deploy has both projects `healthy: true` in `/health/fcm`, `outbox_unconfigured{project=...}`
staying at 0 in the metrics output, and `test-send` returning `{"outcome":"ACCEPTED"}`.
