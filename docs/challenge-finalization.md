# Challenge finalization

Reward points for a weekend challenge are **not** granted the instant a participant hits
`requiredPosts`. They're granted only after the challenge's window has ended, by a finalization
pass that re-evaluates every participant's final contribution count. There is no scheduler inside
the server process — a challenge only gets finalized when something calls one of the endpoints
below, or via the lazy catch-up probe described further down.

Finalization is idempotent and safe to re-run: it can be triggered manually, by an external cron,
and by the app itself opportunistically, all sharing the same underlying engine
(`ChallengeFinalizationService.finalize`).

## What finalization does

For a given challenge, once `now >= endsAt`:

1. Skips anything not actually due: the challenge must be `SCHEDULED` (not `DRAFT`/`CANCELLED`),
   its window must have ended, and it must not already be finalized (`finalized_at IS NULL`).
2. Re-counts each participant's contributions and reconciles their reward:
   - threshold reached, not yet granted → **grant** the reward.
   - previously granted but no longer at threshold (e.g. a contributing post was removed) →
     **revoke** the reward.
3. Writes `finalized_at = now()` on the challenge — only after all participants have been
   reconciled, so a crash mid-way leaves it `NULL` and the next trigger (cron, lazy probe, or a
   manual retry) picks the challenge back up automatically.

## Endpoints

### Manual / debug finalize

```
POST /api/admin/challenges/{id}/finalize
Auth: JWT "admin" realm (Authorization: Bearer <token>, isAdmin claim)
```

- Finalizes one specific challenge by id.
- **Idempotent no-op** if the challenge is still `DRAFT`/`CANCELLED`/active, or already finalized
  — responds `200 OK` with zero counts rather than an error.
- `404 Not Found` only if the challenge id doesn't exist.
- **Response:** `200 OK` with `{ "grantedCount": 2, "revokedCount": 0 }`.

### Cron sweep (due challenges)

```
POST /api/admin/challenges/finalize-due
Header: X-Cron-Secret: <CRON_SECRET>
```

- Finalizes every `SCHEDULED` challenge whose window has ended and that isn't finalized yet (up
  to 100 per call), using the same partial index (`idx_challenges_pending_finalization`, migration
  `V28`) the lazy catch-up probe uses. This is the authoritative, scheduled trigger.
- **Auth:** requires the `X-Cron-Secret` header to exactly match the `CRON_SECRET` environment
  variable. Missing/blank/mismatched secret → `401 Unauthorized`. If `CRON_SECRET` itself isn't
  set on the server, the endpoint always rejects (fails closed) — same pattern as
  `AdminLeaderboardRoutes.kt`'s `/snapshot/today`.
- **Response:** `200 OK` with
  `{ "finalizedChallenges": 3, "grantedRewards": 11, "revokedRewards": 1 }`.

### Lazy catch-up (no endpoint — automatic)

`GET /api/challenges/current` and `GET /api/challenges/me` each run a cheap probe against the
same partial index before responding: if a due-but-unfinalized challenge exists, one is finalized
on the application's own coroutine scope, fire-and-forget. This never blocks or can fail the
triggering request (wrapped in `runCatching`, same best-effort principle as post-creation's
challenge contribution evaluation) — it only exists so a challenge still gets finalized eventually
even if the cron sweep is misconfigured or down, at the cost of unpredictable timing.

## Idempotency guarantees

- **Ledger:** every grant/revoke writes a row to `challenge_reward_ledger` keyed by
  `idempotencyKey = "chg:{challengeId}:usr:{userId}:grant|revoke:{awardEpoch}"`. A retried call for
  an already-processed participant hits the same key and is a no-op.
- **award_epoch:** revoking bumps the participant's `award_epoch`, so if the reward is later
  re-granted (e.g. the user re-contributes and the challenge is finalized again), it gets a fresh
  idempotency key rather than colliding with the revoked grant.
- **Per-participant transaction + `SELECT ... FOR UPDATE`:** each participant is reconciled in its
  own transaction under a row lock, so two finalizations racing on the same challenge (cron and a
  manual call, or two cron ticks) serialize correctly and produce the same end result without
  double-granting or double-revoking.
- **Advisory lock:** `finalize()` also takes a Postgres advisory lock on the challenge id for the
  duration of the call. This is a work-avoidance optimization only, not a correctness requirement
  — the per-participant locking above is what actually guarantees correctness.
- **`finalized_at` written last:** a crash between granting/revoking rewards and marking the
  challenge finalized leaves `finalized_at = NULL`. The next trigger re-runs finalization; already
  -reconciled participants are skipped because their `reward_state` no longer matches the
  grant/revoke condition, so nothing is double-applied.

## Environment variables

| Variable | Purpose |
|---|---|
| `CRON_SECRET` | Shared secret the external cron sends in `X-Cron-Secret` on `/finalize-due`. Required for that endpoint to accept any request. Also used by the existing leaderboard snapshot cron endpoint. |

## Local testing with curl

```bash
# Manual finalize of one challenge (admin JWT required)
curl -i -X POST http://localhost:8080/api/admin/challenges/<challenge-id>/finalize \
     -H "Authorization: Bearer $ADMIN_JWT"

# Cron sweep — missing secret → 401
curl -i -X POST http://localhost:8080/api/admin/challenges/finalize-due

# Cron sweep — wrong secret → 401
curl -i -X POST http://localhost:8080/api/admin/challenges/finalize-due \
     -H "X-Cron-Secret: wrong-secret"

# Cron sweep — correct secret → 200 + counts
curl -i -X POST http://localhost:8080/api/admin/challenges/finalize-due \
     -H "X-Cron-Secret: $CRON_SECRET"

# Idempotency check: running it again immediately should report zero new grants/revokes
curl -i -X POST http://localhost:8080/api/admin/challenges/finalize-due \
     -H "X-Cron-Secret: $CRON_SECRET"
```

## Future: GitHub Actions schedule

Not yet configured — this section documents the intended setup for when it's wired up (see the
plan's Bloc K2), following the same shape as `leaderboard-snapshot-cron.md`'s scheduled workflow.

A scheduled workflow will run periodically and call the cron sweep endpoint above:

```yaml
on:
  schedule:
    - cron: "*/15 * * * *"   # every 15 minutes, adjust to how quickly a finalized reward should land
  workflow_dispatch: {}       # allow manual runs

jobs:
  finalize:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger challenge finalization sweep
        run: |
          curl -fsS -X POST "$CARSPOTTER_API_URL/api/admin/challenges/finalize-due" \
               -H "X-Cron-Secret: $CRON_SECRET"
        env:
          CARSPOTTER_API_URL: ${{ secrets.CARSPOTTER_API_URL }}
          CRON_SECRET: ${{ secrets.CRON_SECRET }}
```

`CARSPOTTER_API_URL` and `CRON_SECRET` are the same GitHub repository secrets the leaderboard
snapshot workflow uses (or will use), set separately when this workflow is actually added.
`curl -f` makes the step fail loudly on a non-2xx response (wrong secret, server down), so a
broken cron shows up as a failed workflow run.

## Operational note

A challenge stuck `ends_at < now() - 6h` with `finalized_at IS NULL` means finalization isn't
running for it — see the plan's Bloc K3 for the intended alert on that condition. This document
only covers triggering and verifying finalization by hand; it does not configure that alert.
