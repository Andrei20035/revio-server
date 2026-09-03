package service

import com.revio.server.features.car_family.ICarFamilyDAO
import com.revio.server.features.challenge.Challenge
import com.revio.server.features.challenge.ChallengeAlreadyEndedException
import com.revio.server.features.challenge.ChallengeNotEditableException
import com.revio.server.features.challenge.ChallengeNotFoundException
import com.revio.server.features.challenge.ChallengeOverlapException
import com.revio.server.features.challenge.ChallengeService
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.challenge.EffectiveChallengeStatus
import com.revio.server.features.challenge.IChallengeDAO
import com.revio.server.features.challenge.IChallengeProgressDAO
import com.revio.server.features.challenge.LedgerReason
import com.revio.server.features.challenge.ParticipantProgress
import com.revio.server.features.challenge.ParticipantState
import com.revio.server.features.challenge.RewardState
import com.revio.server.features.challenge.canCancelNormally
import com.revio.server.features.challenge.effectiveStatus
import com.revio.server.features.challenge.participantState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransition
import java.util.UUID

/**
 * ChallengeService, minus updateDraft/listChallenges (dao.ChallengeServiceUpdateDraftTest.kt).
 * Covers the plan's §8 matrix sections "Timp și timezone", "Lifecycle și publicare", and most of
 * "Anulare" at the service layer (the mass-revoke engine itself is dao.ChallengeProgressDaoTest.kt).
 */
class ChallengeServiceTest {

    private val challengeId = UUID.randomUUID()
    private val familyId = UUID.randomUUID()

    private fun challenge(
        id: UUID = challengeId,
        status: ChallengeStatus = ChallengeStatus.DRAFT,
        startsAt: Instant = Instant.now(),
        endsAt: Instant = Instant.now().plusSeconds(3600),
        requiredPosts: Int = 5,
        finalizedAt: Instant? = null,
    ) = Challenge(
        id = id,
        title = "Weekend Golf Hunt",
        description = null,
        targetFamilyId = familyId,
        requiredPosts = requiredPosts,
        rewardPoints = 300,
        startsAt = startsAt,
        endsAt = endsAt,
        adminTimezone = "Europe/Bucharest",
        status = status,
        createdBy = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        publishedAt = null,
        cancelledAt = null,
        finalizedAt = finalizedAt,
        notifiedStartedAt = null,
    )

    private fun service(
        challengeDao: IChallengeDAO,
        challengeProgressDao: IChallengeProgressDAO = mockk(relaxed = true),
        carFamilyDao: ICarFamilyDAO = mockk { coEvery { exists(any()) } returns true },
    ) = ChallengeService(challengeDao, challengeProgressDao, carFamilyDao)

    // ---------- effectiveStatus / canCancelNormally — pure functions ----------

    @Test
    fun `effectiveStatus is DRAFT for a persisted DRAFT challenge regardless of window`() {
        val c = challenge(status = ChallengeStatus.DRAFT, startsAt = Instant.now().minusSeconds(100), endsAt = Instant.now().minusSeconds(1))
        assertEquals(EffectiveChallengeStatus.DRAFT, effectiveStatus(c, Instant.now()))
    }

    @Test
    fun `effectiveStatus is CANCELLED for a persisted CANCELLED challenge regardless of window`() {
        val c = challenge(status = ChallengeStatus.CANCELLED, startsAt = Instant.now().minusSeconds(100), endsAt = Instant.now().plusSeconds(100))
        assertEquals(EffectiveChallengeStatus.CANCELLED, effectiveStatus(c, Instant.now()))
    }

    @Test
    fun `effectiveStatus is SCHEDULED for a SCHEDULED challenge whose window hasn't started`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.plusSeconds(100), endsAt = now.plusSeconds(200))
        assertEquals(EffectiveChallengeStatus.SCHEDULED, effectiveStatus(c, now))
    }

    @Test
    fun `effectiveStatus is ACTIVE for a SCHEDULED challenge inside its window`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(100), endsAt = now.plusSeconds(100))
        assertEquals(EffectiveChallengeStatus.ACTIVE, effectiveStatus(c, now))
    }

    @Test
    fun `effectiveStatus is ENDED for a SCHEDULED challenge whose window has passed`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(200), endsAt = now.minusSeconds(100))
        assertEquals(EffectiveChallengeStatus.ENDED, effectiveStatus(c, now))
    }

    @Test
    fun `canCancelNormally is true for every effective state except ENDED`() {
        val now = Instant.now()
        assertTrue(canCancelNormally(challenge(status = ChallengeStatus.DRAFT), now))
        assertTrue(canCancelNormally(challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.plusSeconds(10), endsAt = now.plusSeconds(20)), now))
        assertTrue(canCancelNormally(challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(10), endsAt = now.plusSeconds(10)), now))
        assertTrue(canCancelNormally(challenge(status = ChallengeStatus.CANCELLED), now))
        assertFalse(canCancelNormally(challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(20), endsAt = now.minusSeconds(10)), now))
    }

    // ---------- participantState — pure function (plan §7.2) ----------

    private fun progress(count: Int, rewardState: RewardState = RewardState.NONE) =
        ParticipantProgress(contributionCount = count, rewardState = rewardState)

    @Test
    fun `participantState is NOT_STARTED when the challenge hasn't started yet`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.plusSeconds(100), endsAt = now.plusSeconds(200))
        assertEquals(ParticipantState.NOT_STARTED, participantState(c, progress(0), now))
    }

    @Test
    fun `participantState is CANCELLED when the challenge was cancelled, regardless of progress`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.CANCELLED, startsAt = now.minusSeconds(100), endsAt = now.plusSeconds(100))
        assertEquals(ParticipantState.CANCELLED, participantState(c, progress(5, RewardState.GRANTED), now))
    }

    @Test
    fun `participantState is IN_PROGRESS while active and below the threshold`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(100), endsAt = now.plusSeconds(100), requiredPosts = 5)
        assertEquals(ParticipantState.IN_PROGRESS, participantState(c, progress(4), now))
    }

    @Test
    fun `participantState is COMPLETED_PENDING exactly at the threshold, before finalization`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(100), endsAt = now.plusSeconds(100), requiredPosts = 5)
        assertEquals(ParticipantState.COMPLETED_PENDING, participantState(c, progress(5), now))
    }

    @Test
    fun `participantState is COMPLETED_PENDING after ends_at but before finalization has run`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(200), endsAt = now.minusSeconds(100), requiredPosts = 5, finalizedAt = null)
        assertEquals(ParticipantState.COMPLETED_PENDING, participantState(c, progress(5), now))
    }

    @Test
    fun `participantState is REWARDED once reward_state is GRANTED, even before finalized_at is set`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(100), endsAt = now.plusSeconds(100), requiredPosts = 5, finalizedAt = null)
        assertEquals(ParticipantState.REWARDED, participantState(c, progress(5, RewardState.GRANTED), now))
    }

    @Test
    fun `participantState is NOT_COMPLETED after finalization when the threshold was never reached`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(200), endsAt = now.minusSeconds(100), requiredPosts = 5, finalizedAt = now.minusSeconds(50))
        assertEquals(ParticipantState.NOT_COMPLETED, participantState(c, progress(3), now))
    }

    @Test
    fun `participantState is NOT_COMPLETED one post short of the threshold, at the boundary`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(200), endsAt = now.minusSeconds(100), requiredPosts = 5, finalizedAt = now.minusSeconds(50))
        assertEquals(ParticipantState.NOT_COMPLETED, participantState(c, progress(4), now))
    }

    @Test
    fun `participantState is REVOKED after finalization when reward_state was reset to NONE despite meeting the threshold`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(200), endsAt = now.minusSeconds(100), requiredPosts = 5, finalizedAt = now.minusSeconds(50))
        assertEquals(ParticipantState.REVOKED, participantState(c, progress(5, RewardState.NONE), now))
    }

    @Test
    fun `participantState is REWARDED after finalization when the threshold was met and never revoked`() {
        val now = Instant.now()
        val c = challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(200), endsAt = now.minusSeconds(100), requiredPosts = 5, finalizedAt = now.minusSeconds(50))
        assertEquals(ParticipantState.REWARDED, participantState(c, progress(5, RewardState.GRANTED), now))
    }

    // ---------- createDraft — validation ----------

    private val validStartLocal: LocalDateTime = LocalDateTime.parse("2026-08-08T09:00:00")
    private val validEndLocal: LocalDateTime = LocalDateTime.parse("2026-08-10T09:00:00")

    private fun draftDao(): IChallengeDAO {
        val dao = mockk<IChallengeDAO>()
        val insertedId = UUID.randomUUID()
        coEvery { dao.insert(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns insertedId
        coEvery { dao.findById(insertedId) } returns challenge(id = insertedId)
        return dao
    }

    @Test
    fun `createDraft rejects a blank title`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(draftDao()).createDraft("   ", null, familyId, 5, 300, validStartLocal, validEndLocal, "UTC", null)
            }
        }
    }

    @Test
    fun `createDraft rejects a non-positive requiredPosts`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(draftDao()).createDraft("T", null, familyId, 0, 300, validStartLocal, validEndLocal, "UTC", null)
            }
        }
    }

    @Test
    fun `createDraft rejects a non-positive rewardPoints`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(draftDao()).createDraft("T", null, familyId, 5, 0, validStartLocal, validEndLocal, "UTC", null)
            }
        }
    }

    @Test
    fun `createDraft rejects a target family that doesn't exist`() {
        val carFamilyDao = mockk<ICarFamilyDAO> { coEvery { exists(familyId) } returns false }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(draftDao(), carFamilyDao = carFamilyDao).createDraft("T", null, familyId, 5, 300, validStartLocal, validEndLocal, "UTC", null)
            }
        }
    }

    @Test
    fun `createDraft rejects endsAt not after startsAt`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(draftDao()).createDraft("T", null, familyId, 5, 300, validEndLocal, validStartLocal, "UTC", null)
            }
        }
    }

    @Test
    fun `createDraft rejects an invalid IANA timezone strictly, never falling back to UTC`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(draftDao()).createDraft("T", null, familyId, 5, 300, validStartLocal, validEndLocal, "Europe/Bucuresti", null)
            }
        }
    }

    @Test
    fun `createDraft rejects a blank timezone`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(draftDao()).createDraft("T", null, familyId, 5, 300, validStartLocal, validEndLocal, "", null)
            }
        }
    }

    @Test
    fun `createDraft converts the same local window to different instants under different timezones`() = runTest {
        val bucharestDao = draftDao()
        val startsAtBucharest = slot<Instant>()
        coEvery { bucharestDao.insert(any(), any(), any(), any(), any(), capture(startsAtBucharest), any(), any(), any()) } returns UUID.randomUUID()
        coEvery { bucharestDao.findById(any()) } returns challenge()

        val newYorkDao = draftDao()
        val startsAtNewYork = slot<Instant>()
        coEvery { newYorkDao.insert(any(), any(), any(), any(), any(), capture(startsAtNewYork), any(), any(), any()) } returns UUID.randomUUID()
        coEvery { newYorkDao.findById(any()) } returns challenge()

        service(bucharestDao).createDraft("T", null, familyId, 5, 300, validStartLocal, validEndLocal, "Europe/Bucharest", null)
        service(newYorkDao).createDraft("T", null, familyId, 5, 300, validStartLocal, validEndLocal, "America/New_York", null)

        assertNotEquals(startsAtBucharest.captured, startsAtNewYork.captured, "Same wall-clock time, different zones, must produce different absolute instants")
    }

    @Test
    fun `createDraft converts 2026-08-08 09-00 Europe-Bucharest to the correct UTC instant`() = runTest {
        val dao = draftDao()
        val startsAtSlot = slot<Instant>()
        coEvery { dao.insert(any(), any(), any(), any(), any(), capture(startsAtSlot), any(), any(), any()) } returns UUID.randomUUID()
        coEvery { dao.findById(any()) } returns challenge()

        service(dao).createDraft("T", null, familyId, 5, 300, validStartLocal, validEndLocal, "Europe/Bucharest", null)

        // Europe/Bucharest is UTC+3 in August (EEST, summer time).
        assertEquals(Instant.parse("2026-08-08T06:00:00Z"), startsAtSlot.captured)
    }

    // ---------- createDraft — DST correctness ----------

    private val bucharestZone: ZoneId = ZoneId.of("Europe/Bucharest")

    private fun findTransition(after: Instant, gap: Boolean): ZoneOffsetTransition {
        var t = bucharestZone.rules.nextTransition(after)
        var guard = 0
        while (t != null && t.isGap != gap && guard < 20) {
            t = bucharestZone.rules.nextTransition(t.instant)
            guard++
        }
        return requireNotNull(t) { "No ${if (gap) "gap" else "overlap"} transition found" }
    }

    @Test
    fun `createDraft window spanning a spring-forward transition is DST-correct - one hour shorter than the nominal wall-clock span`() = runTest {
        val transition = findTransition(Instant.parse("2020-01-01T00:00:00Z"), gap = true)
        val startsAtLocal = transition.dateTimeBefore.minusHours(1)
        val endsAtLocal = transition.dateTimeAfter.plusHours(1)
        val nominalWallClockDuration = Duration.between(startsAtLocal, endsAtLocal)

        val dao = draftDao()
        val startsAtSlot = slot<Instant>()
        val endsAtSlot = slot<Instant>()
        coEvery { dao.insert(any(), any(), any(), any(), any(), capture(startsAtSlot), capture(endsAtSlot), any(), any()) } returns UUID.randomUUID()
        coEvery { dao.findById(any()) } returns challenge()

        service(dao).createDraft("T", null, familyId, 5, 300, startsAtLocal, endsAtLocal, "Europe/Bucharest", null)

        val actualDuration = Duration.between(startsAtSlot.captured, endsAtSlot.captured)
        assertEquals(
            nominalWallClockDuration.minusHours(1),
            actualDuration,
            "LocalDateTime.atZone must be DST-aware (via ZonedDateTime), not a fixed offset — the skipped hour must shrink the absolute duration",
        )
    }

    @Test
    fun `createDraft resolves an ambiguous fall-back local time using the earlier offset, matching Java's documented default`() = runTest {
        val transition = findTransition(Instant.parse("2020-01-01T00:00:00Z"), gap = false)
        // transition.dateTimeBefore is the first LOCAL value after the fold completes (it only
        // occurs once); the actually-repeated, ambiguous local values are the half-open range
        // [dateTimeAfter, dateTimeBefore) — pick one squarely inside it.
        val ambiguousLocal = transition.dateTimeBefore.minusMinutes(30)
        val startsAtLocal = ambiguousLocal.minusHours(2)

        val dao = draftDao()
        val endsAtSlot = slot<Instant>()
        coEvery { dao.insert(any(), any(), any(), any(), any(), any(), capture(endsAtSlot), any(), any()) } returns UUID.randomUUID()
        coEvery { dao.findById(any()) } returns challenge()

        service(dao).createDraft("T", null, familyId, 5, 300, startsAtLocal, ambiguousLocal, "Europe/Bucharest", null)

        val expectedInstant = ambiguousLocal.atOffset(transition.offsetBefore).toInstant()
        assertEquals(expectedInstant, endsAtSlot.captured)
    }

    // ---------- findById / findActive / findCurrentOrNext — passthrough ----------

    @Test
    fun `findById passes through to the DAO`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge()

        val result = service(dao).findById(challengeId)

        assertEquals(challengeId, result?.id)
    }

    @Test
    fun `findActive passes the given instant through to the DAO`() = runTest {
        val dao = mockk<IChallengeDAO>()
        val now = Instant.now()
        coEvery { dao.findActive(now) } returns challenge()

        service(dao).findActive(now)

        coVerify(exactly = 1) { dao.findActive(now) }
    }

    @Test
    fun `findCurrentOrNext passes the given instant through to the DAO`() = runTest {
        val dao = mockk<IChallengeDAO>()
        val now = Instant.now()
        coEvery { dao.findCurrentOrNext(now) } returns null

        val result = service(dao).findCurrentOrNext(now)

        assertEquals(null, result)
        coVerify(exactly = 1) { dao.findCurrentOrNext(now) }
    }

    // ---------- findPublicById — DRAFT is never publicly discoverable ----------

    @Test
    fun `findPublicById returns null for a DRAFT challenge`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.DRAFT)

        val result = service(dao).findPublicById(challengeId)

        assertEquals(null, result)
    }

    @Test
    fun `findPublicById returns the challenge for a SCHEDULED challenge`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.SCHEDULED)

        val result = service(dao).findPublicById(challengeId)

        assertEquals(challengeId, result?.id)
    }

    @Test
    fun `findPublicById returns the challenge for a CANCELLED challenge`() = runTest {
        // Non-DRAFT is the visibility rule (plan §5.2) — CANCELLED still exists publicly, it's
        // only DRAFT (never published) that must stay invisible.
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.CANCELLED)

        val result = service(dao).findPublicById(challengeId)

        assertEquals(challengeId, result?.id)
    }

    @Test
    fun `findPublicById returns null for an unknown id`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns null

        val result = service(dao).findPublicById(challengeId)

        assertEquals(null, result)
    }

    // ---------- updateTitleAndDescription ----------

    @Test
    fun `updateTitleAndDescription succeeds while DRAFT`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.DRAFT)
        coEvery { dao.updateEditableFields(challengeId, "New", null) } returns 1

        service(dao).updateTitleAndDescription(challengeId, "New", null)

        coVerify(exactly = 1) { dao.updateEditableFields(challengeId, "New", null) }
    }

    @Test
    fun `updateTitleAndDescription succeeds while SCHEDULED`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.SCHEDULED)
        coEvery { dao.updateEditableFields(challengeId, "New", null) } returns 1

        service(dao).updateTitleAndDescription(challengeId, "New", null)

        coVerify(exactly = 1) { dao.updateEditableFields(challengeId, "New", null) }
    }

    @Test
    fun `updateTitleAndDescription rejects a CANCELLED challenge`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.CANCELLED)

        assertThrows(ChallengeNotEditableException::class.java) {
            runBlocking { service(dao).updateTitleAndDescription(challengeId, "New", null) }
        }
    }

    @Test
    fun `updateTitleAndDescription rejects a blank title`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.DRAFT)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service(dao).updateTitleAndDescription(challengeId, "   ", null) }
        }
    }

    @Test
    fun `updateTitleAndDescription throws for an unknown id`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns null

        assertThrows(ChallengeNotFoundException::class.java) {
            runBlocking { service(dao).updateTitleAndDescription(challengeId, "New", null) }
        }
    }

    // ---------- publish ----------

    @Test
    fun `publish moves DRAFT to SCHEDULED`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returnsMany listOf(challenge(status = ChallengeStatus.DRAFT), challenge(status = ChallengeStatus.SCHEDULED))
        coEvery { dao.updateStatus(challengeId, ChallengeStatus.SCHEDULED, publishedAt = any(), cancelledAt = null) } returns 1

        val result = service(dao).publish(challengeId)

        assertEquals(ChallengeStatus.SCHEDULED, result.status)
    }

    @Test
    fun `publish rejects a non-DRAFT challenge`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.SCHEDULED)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service(dao).publish(challengeId) }
        }
    }

    @Test
    fun `publish throws for an unknown id`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns null

        assertThrows(ChallengeNotFoundException::class.java) {
            runBlocking { service(dao).publish(challengeId) }
        }
    }

    @Test
    fun `publish maps an exclusion-constraint violation to ChallengeOverlapException`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.DRAFT)
        coEvery { dao.updateStatus(challengeId, ChallengeStatus.SCHEDULED, publishedAt = any(), cancelledAt = null) } throws
            ExposedSQLException(SQLException("overlap", "23P01"), emptyList(), mockk(relaxed = true))

        assertThrows(ChallengeOverlapException::class.java) {
            runBlocking { service(dao).publish(challengeId) }
        }
    }

    @Test
    fun `publish rethrows an unrelated database error`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.DRAFT)
        coEvery { dao.updateStatus(challengeId, ChallengeStatus.SCHEDULED, publishedAt = any(), cancelledAt = null) } throws
            ExposedSQLException(SQLException("boom", "08006"), emptyList(), mockk(relaxed = true))

        assertThrows(ExposedSQLException::class.java) {
            runBlocking { service(dao).publish(challengeId) }
        }
    }

    // ---------- cancel ----------

    @Test
    fun `cancel sets status to CANCELLED and revokes with reason CHALLENGE_CANCELLED`() = runTest {
        val now = Instant.now()
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(10), endsAt = now.plusSeconds(1000))
        coEvery { dao.updateStatus(challengeId, ChallengeStatus.CANCELLED, publishedAt = null, cancelledAt = any()) } returns 1
        val progressDao = mockk<IChallengeProgressDAO>()
        coEvery { progressDao.revokeAllGrantedRewards(challengeId, LedgerReason.CHALLENGE_CANCELLED) } returns 3

        val revoked = service(dao, progressDao).cancel(challengeId)

        assertEquals(3, revoked)
        coVerify(exactly = 1) { dao.updateStatus(challengeId, ChallengeStatus.CANCELLED, publishedAt = null, cancelledAt = any()) }
        coVerify(exactly = 1) { progressDao.revokeAllGrantedRewards(challengeId, LedgerReason.CHALLENGE_CANCELLED) }
    }

    @Test
    fun `cancel throws ChallengeAlreadyEndedException once the challenge has ended, without touching status or revoking`() {
        val now = Instant.now()
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.SCHEDULED, startsAt = now.minusSeconds(1000), endsAt = now.minusSeconds(10))
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)

        assertThrows(ChallengeAlreadyEndedException::class.java) {
            runBlocking { service(dao, progressDao).cancel(challengeId) }
        }
        coVerify(exactly = 0) { dao.updateStatus(any(), any(), any(), any()) }
        coVerify(exactly = 0) { progressDao.revokeAllGrantedRewards(any(), any()) }
    }

    @Test
    fun `cancel on an already-CANCELLED challenge does not call updateStatus again but still re-runs the (idempotent) revoke pass`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.CANCELLED)
        val progressDao = mockk<IChallengeProgressDAO>()
        coEvery { progressDao.revokeAllGrantedRewards(challengeId, LedgerReason.CHALLENGE_CANCELLED) } returns 0

        val revoked = service(dao, progressDao).cancel(challengeId)

        assertEquals(0, revoked)
        coVerify(exactly = 0) { dao.updateStatus(any(), any(), any(), any()) }
        coVerify(exactly = 1) { progressDao.revokeAllGrantedRewards(challengeId, LedgerReason.CHALLENGE_CANCELLED) }
    }

    @Test
    fun `cancel throws for an unknown id`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns null

        assertThrows(ChallengeNotFoundException::class.java) {
            runBlocking { service(dao).cancel(challengeId) }
        }
    }

    // ---------- revokeAll ----------

    @Test
    fun `revokeAll delegates with reason ADMIN_REVOKE_ALL and never touches status`() = runTest {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns challenge(status = ChallengeStatus.SCHEDULED, startsAt = Instant.now().minusSeconds(1000), endsAt = Instant.now().minusSeconds(10))
        val progressDao = mockk<IChallengeProgressDAO>()
        coEvery { progressDao.revokeAllGrantedRewards(challengeId, LedgerReason.ADMIN_REVOKE_ALL) } returns 5

        val revoked = service(dao, progressDao).revokeAll(challengeId)

        assertEquals(5, revoked)
        coVerify(exactly = 0) { dao.updateStatus(any(), any(), any(), any()) }
    }

    @Test
    fun `revokeAll throws for an unknown id`() {
        val dao = mockk<IChallengeDAO>()
        coEvery { dao.findById(challengeId) } returns null

        assertThrows(ChallengeNotFoundException::class.java) {
            runBlocking { service(dao).revokeAll(challengeId) }
        }
    }
}
