package service

import com.revio.server.features.car_family.ICarFamilyDAO
import com.revio.server.features.challenge.Challenge
import com.revio.server.features.challenge.ChallengeAlreadyEndedException
import com.revio.server.features.challenge.ChallengeNotEditableException
import com.revio.server.features.challenge.ChallengeNotFoundException
import com.revio.server.features.challenge.ChallengeService
import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.challenge.EffectiveChallengeStatus
import com.revio.server.features.challenge.IChallengeDAO
import com.revio.server.features.challenge.IChallengeProgressDAO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * ChallengeService.updateDraft (plan §9-E3): reuses createDraft's validations verbatim and is
 * only ever permitted while the challenge is still DRAFT. Also covers listChallenges's pagination
 * plumbing (the DAO-level pagination/filter correctness itself is dao.ChallengeDaoListAllTest).
 */
class ChallengeServiceUpdateDraftTest {

    private val challengeId = UUID.randomUUID()
    private val familyId = UUID.randomUUID()
    private val startsAtLocal: LocalDateTime = LocalDateTime.parse("2026-08-08T09:00:00")
    private val endsAtLocal: LocalDateTime = LocalDateTime.parse("2026-08-10T09:00:00")

    private fun draftChallenge(id: UUID = challengeId, status: ChallengeStatus = ChallengeStatus.DRAFT) = Challenge(
        id = id,
        title = "Old title",
        description = null,
        targetFamilyId = UUID.randomUUID(),
        requiredPosts = 3,
        rewardPoints = 100,
        startsAt = Instant.now(),
        endsAt = Instant.now().plusSeconds(3600),
        adminTimezone = "UTC",
        status = status,
        createdBy = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        publishedAt = null,
        cancelledAt = null,
        finalizedAt = null,
        notifiedStartedAt = null,
    )

    private fun service(challengeDao: IChallengeDAO, carFamilyDao: ICarFamilyDAO = mockk { coEvery { exists(any()) } returns true }) =
        ChallengeService(challengeDao, mockk<IChallengeProgressDAO>(relaxed = true), carFamilyDao)

    @Test
    fun `updateDraft replaces every field and returns the reloaded challenge`() = runTest {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returnsMany listOf(draftChallenge(), draftChallenge().copy(title = "New title"))
        coEvery { challengeDao.updateDraftFields(challengeId, "New title", "New description", familyId, 5, 300, any(), any(), "Europe/Bucharest") } returns 1

        val result = service(challengeDao).updateDraft(
            challengeId, "New title", "New description", familyId, 5, 300, startsAtLocal, endsAtLocal, "Europe/Bucharest",
        )

        assertEquals("New title", result.title)
        coVerify(exactly = 1) { challengeDao.updateDraftFields(challengeId, "New title", "New description", familyId, 5, 300, any(), any(), "Europe/Bucharest") }
    }

    @Test
    fun `updateDraft throws ChallengeNotFoundException for an unknown id`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns null

        assertThrows(ChallengeNotFoundException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "T", null, familyId, 5, 300, startsAtLocal, endsAtLocal, "UTC")
            }
        }
    }

    @Test
    fun `updateDraft rejects a SCHEDULED challenge without calling updateDraftFields`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge(status = ChallengeStatus.SCHEDULED)

        assertThrows(ChallengeNotEditableException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "T", null, familyId, 5, 300, startsAtLocal, endsAtLocal, "UTC")
            }
        }
        coVerify(exactly = 0) { challengeDao.updateDraftFields(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateDraft rejects a CANCELLED challenge`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge(status = ChallengeStatus.CANCELLED)

        assertThrows(ChallengeNotEditableException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "T", null, familyId, 5, 300, startsAtLocal, endsAtLocal, "UTC")
            }
        }
    }

    @Test
    fun `updateDraft reports not-editable when the row left DRAFT between the check and the write`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge()
        // updateDraftFields' own WHERE clause is the real race guard — 0 rows updated means it
        // was no longer DRAFT by the time the UPDATE ran (e.g. published concurrently).
        coEvery { challengeDao.updateDraftFields(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0

        assertThrows(ChallengeNotEditableException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "T", null, familyId, 5, 300, startsAtLocal, endsAtLocal, "UTC")
            }
        }
    }

    @Test
    fun `updateDraft rejects a blank title without reaching the DAO`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "   ", null, familyId, 5, 300, startsAtLocal, endsAtLocal, "UTC")
            }
        }
        coVerify(exactly = 0) { challengeDao.updateDraftFields(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateDraft rejects a non-positive requiredPosts, same as createDraft`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "T", null, familyId, 0, 300, startsAtLocal, endsAtLocal, "UTC")
            }
        }
    }

    @Test
    fun `updateDraft rejects a non-positive rewardPoints, same as createDraft`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "T", null, familyId, 5, 0, startsAtLocal, endsAtLocal, "UTC")
            }
        }
    }

    @Test
    fun `updateDraft rejects endsAt not after startsAt, same as createDraft`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "T", null, familyId, 5, 300, endsAtLocal, startsAtLocal, "UTC")
            }
        }
    }

    @Test
    fun `updateDraft rejects an invalid IANA timezone strictly, same as createDraft`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(challengeDao).updateDraft(challengeId, "T", null, familyId, 5, 300, startsAtLocal, endsAtLocal, "Europe/Bucuresti")
            }
        }
    }

    @Test
    fun `updateDraft rejects a target family that doesn't exist, same as createDraft`() {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.findById(challengeId) } returns draftChallenge()
        val carFamilyDao = mockk<ICarFamilyDAO> { coEvery { exists(familyId) } returns false }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(challengeDao, carFamilyDao).updateDraft(challengeId, "T", null, familyId, 5, 300, startsAtLocal, endsAtLocal, "UTC")
            }
        }
    }

    // ---------- listChallenges ----------

    @Test
    fun `listChallenges fetches one extra row to compute hasMore and trims the page`() = runTest {
        val challengeDao = mockk<IChallengeDAO>()
        val now = Instant.now()
        val three = (1..3).map { draftChallenge(id = UUID.randomUUID()).copy(createdAt = now.minusSeconds(it.toLong())) }
        coEvery { challengeDao.listAll(3, null, null, null, any()) } returns three

        val page = service(challengeDao).listChallenges(limit = 2, cursorCreatedAt = null, cursorId = null, effectiveStatusFilter = null)

        assertEquals(2, page.challenges.size)
        assertEquals(true, page.hasMore)
        assertEquals(page.challenges.last().createdAt, page.nextCursorCreatedAt)
        assertEquals(page.challenges.last().id, page.nextCursorId)
    }

    @Test
    fun `listChallenges reports hasMore=false and a null cursor on the last page`() = runTest {
        val challengeDao = mockk<IChallengeDAO>()
        val one = listOf(draftChallenge())
        coEvery { challengeDao.listAll(3, null, null, null, any()) } returns one

        val page = service(challengeDao).listChallenges(limit = 2, cursorCreatedAt = null, cursorId = null, effectiveStatusFilter = null)

        assertEquals(1, page.challenges.size)
        assertEquals(false, page.hasMore)
        assertEquals(null, page.nextCursorCreatedAt)
        assertEquals(null, page.nextCursorId)
    }

    @Test
    fun `listChallenges passes the effective-status filter straight through to the DAO`() = runTest {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.listAll(any(), any(), any(), EffectiveChallengeStatus.ACTIVE, any()) } returns emptyList()

        service(challengeDao).listChallenges(limit = 10, cursorCreatedAt = null, cursorId = null, effectiveStatusFilter = EffectiveChallengeStatus.ACTIVE)

        coVerify(exactly = 1) { challengeDao.listAll(any(), any(), any(), EffectiveChallengeStatus.ACTIVE, any()) }
    }

    @Test
    fun `listChallenges rejects a cursor with only one of its two parts`() {
        val challengeDao = mockk<IChallengeDAO>(relaxed = true)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(challengeDao).listChallenges(limit = 10, cursorCreatedAt = Instant.now(), cursorId = null, effectiveStatusFilter = null)
            }
        }
    }

    @Test
    fun `listChallenges rejects a limit above the maximum`() {
        val challengeDao = mockk<IChallengeDAO>(relaxed = true)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service(challengeDao).listChallenges(limit = 1000, cursorCreatedAt = null, cursorId = null, effectiveStatusFilter = null)
            }
        }
    }

    @Test
    fun `listChallenges treats limit=0 as the default`() = runTest {
        val challengeDao = mockk<IChallengeDAO>()
        coEvery { challengeDao.listAll(21, null, null, null, any()) } returns emptyList()

        service(challengeDao).listChallenges(limit = 0, cursorCreatedAt = null, cursorId = null, effectiveStatusFilter = null)

        coVerify(exactly = 1) { challengeDao.listAll(21, null, null, null, any()) }
    }
}
