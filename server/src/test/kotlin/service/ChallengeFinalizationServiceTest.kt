package service

import com.revio.server.features.challenge.Challenge
import com.revio.server.features.challenge.ChallengeFinalizationService
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.challenge.FinalizationResult
import com.revio.server.features.challenge.IAdvisoryLock
import com.revio.server.features.challenge.IChallengeDAO
import com.revio.server.features.challenge.IChallengeProgressDAO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ChallengeFinalizationService.finalize — the guards that decide whether a challenge is actually
 * due (plan §9-C3): SCHEDULED, ended, not yet finalized. Everything else (DRAFT, CANCELLED,
 * still-active, already-finalized) must be left untouched — no participant reconciliation, no
 * write to finalized_at.
 */
class ChallengeFinalizationServiceTest {

    private val challengeId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-08T12:00:00Z")

    private fun challenge(
        status: ChallengeStatus = ChallengeStatus.SCHEDULED,
        startsAt: Instant = now.minusSeconds(7200),
        endsAt: Instant = now.minusSeconds(3600),
        finalizedAt: Instant? = null,
    ) = Challenge(
        id = challengeId,
        title = "Weekend Golf Hunt",
        description = null,
        targetFamilyId = UUID.randomUUID(),
        requiredPosts = 5,
        rewardPoints = 300,
        startsAt = startsAt,
        endsAt = endsAt,
        adminTimezone = "Europe/Bucharest",
        status = status,
        createdBy = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        publishedAt = Instant.now(),
        cancelledAt = null,
        finalizedAt = finalizedAt,
    )

    private fun service(
        challengeDao: IChallengeDAO,
        progressDao: IChallengeProgressDAO = mockk(relaxed = true),
        advisoryLock: IAdvisoryLock = mockk(relaxed = true) { every { tryAcquire(any()) } returns true },
    ) = ChallengeFinalizationService(challengeDao, progressDao, advisoryLock)

    @Test
    fun `returns null and does nothing when the challenge doesn't exist`() = runTest {
        val challengeDao = mockk<IChallengeDAO> { coEvery { findById(challengeId) } returns null }
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)

        val result = service(challengeDao, progressDao).finalize(challengeId, now)

        assertNull(result)
        coVerify(exactly = 0) { progressDao.finalizeParticipants(any()) }
        coVerify(exactly = 0) { challengeDao.markFinalized(any(), any()) }
    }

    @Test
    fun `skips a DRAFT challenge even if its window has passed`() = runTest {
        val challengeDao = mockk<IChallengeDAO> { coEvery { findById(challengeId) } returns challenge(status = ChallengeStatus.DRAFT) }
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)

        val result = service(challengeDao, progressDao).finalize(challengeId, now)

        assertNull(result)
        coVerify(exactly = 0) { progressDao.finalizeParticipants(any()) }
        coVerify(exactly = 0) { challengeDao.markFinalized(any(), any()) }
    }

    @Test
    fun `skips a CANCELLED challenge even if its window has passed`() = runTest {
        val challengeDao = mockk<IChallengeDAO> { coEvery { findById(challengeId) } returns challenge(status = ChallengeStatus.CANCELLED) }
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)

        val result = service(challengeDao, progressDao).finalize(challengeId, now)

        assertNull(result)
        coVerify(exactly = 0) { progressDao.finalizeParticipants(any()) }
        coVerify(exactly = 0) { challengeDao.markFinalized(any(), any()) }
    }

    @Test
    fun `skips a SCHEDULED challenge that is still active`() = runTest {
        val challengeDao = mockk<IChallengeDAO> {
            coEvery { findById(challengeId) } returns challenge(startsAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600))
        }
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)

        val result = service(challengeDao, progressDao).finalize(challengeId, now)

        assertNull(result)
        coVerify(exactly = 0) { progressDao.finalizeParticipants(any()) }
        coVerify(exactly = 0) { challengeDao.markFinalized(any(), any()) }
    }

    @Test
    fun `skips a SCHEDULED, ended challenge that is already finalized`() = runTest {
        val challengeDao = mockk<IChallengeDAO> {
            coEvery { findById(challengeId) } returns challenge(finalizedAt = now.minusSeconds(60))
        }
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)

        val result = service(challengeDao, progressDao).finalize(challengeId, now)

        assertNull(result)
        coVerify(exactly = 0) { progressDao.finalizeParticipants(any()) }
        coVerify(exactly = 0) { challengeDao.markFinalized(any(), any()) }
    }

    @Test
    fun `finalizes a SCHEDULED, ended, not-yet-finalized challenge exactly at endsAt`() = runTest {
        val challengeDao = mockk<IChallengeDAO> {
            coEvery { findById(challengeId) } returns challenge(endsAt = now)
            coEvery { markFinalized(challengeId, now) } returns 1
        }
        val progressDao = mockk<IChallengeProgressDAO> {
            coEvery { finalizeParticipants(challengeId) } returns FinalizationResult(grantedCount = 2, revokedCount = 1)
        }

        val result = service(challengeDao, progressDao).finalize(challengeId, now)

        assertEquals(FinalizationResult(grantedCount = 2, revokedCount = 1), result)
        coVerify(exactly = 1) { progressDao.finalizeParticipants(challengeId) }
        coVerify(exactly = 1) { challengeDao.markFinalized(challengeId, now) }
    }

    @Test
    fun `writes finalized_at only after participants have been reconciled, never before`() = runTest {
        val challengeDao = mockk<IChallengeDAO> {
            coEvery { findById(challengeId) } returns challenge()
            coEvery { markFinalized(any(), any()) } returns 1
        }
        val progressDao = mockk<IChallengeProgressDAO> {
            coEvery { finalizeParticipants(challengeId) } returns FinalizationResult(grantedCount = 0, revokedCount = 0)
        }

        service(challengeDao, progressDao).finalize(challengeId, now)

        coVerifyOrder {
            progressDao.finalizeParticipants(challengeId)
            challengeDao.markFinalized(challengeId, now)
        }
    }

    // ---------- advisory lock (plan §9-C4) ----------

    @Test
    fun `returns null and does nothing when the advisory lock isn't acquired`() = runTest {
        val challengeDao = mockk<IChallengeDAO>(relaxed = true)
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)
        val advisoryLock = mockk<IAdvisoryLock> { every { tryAcquire(any()) } returns false }

        val result = service(challengeDao, progressDao, advisoryLock).finalize(challengeId, now)

        assertNull(result)
        coVerify(exactly = 0) { challengeDao.findById(any()) }
        coVerify(exactly = 0) { progressDao.finalizeParticipants(any()) }
        verify(exactly = 0) { advisoryLock.release(any()) }
    }

    @Test
    fun `acquires and releases the advisory lock keyed by the challenge id's most significant bits`() = runTest {
        val challengeDao = mockk<IChallengeDAO> {
            coEvery { findById(challengeId) } returns challenge()
            coEvery { markFinalized(any(), any()) } returns 1
        }
        val progressDao = mockk<IChallengeProgressDAO> {
            coEvery { finalizeParticipants(challengeId) } returns FinalizationResult(grantedCount = 0, revokedCount = 0)
        }
        val advisoryLock = mockk<IAdvisoryLock>(relaxed = true) { every { tryAcquire(any()) } returns true }

        service(challengeDao, progressDao, advisoryLock).finalize(challengeId, now)

        val expectedKey = challengeId.mostSignificantBits
        verify(exactly = 1) { advisoryLock.tryAcquire(expectedKey) }
        verify(exactly = 1) { advisoryLock.release(expectedKey) }
    }

    @Test
    fun `releases the advisory lock even when reconciliation throws`() {
        val challengeDao = mockk<IChallengeDAO> { coEvery { findById(challengeId) } throws RuntimeException("boom") }
        val progressDao = mockk<IChallengeProgressDAO>(relaxed = true)
        val advisoryLock = mockk<IAdvisoryLock>(relaxed = true) { every { tryAcquire(any()) } returns true }

        assertThrows(RuntimeException::class.java) {
            runBlocking { service(challengeDao, progressDao, advisoryLock).finalize(challengeId, now) }
        }

        verify(exactly = 1) { advisoryLock.release(any()) }
    }
}
