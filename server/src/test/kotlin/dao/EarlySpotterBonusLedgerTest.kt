package dao

import com.revio.server.features.user.EarlySpotterBonusLedgerTable
import com.revio.server.features.user.EarlySpotterBonusReason
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import testutils.TestDatabaseFactory
import testutils.UserTestSeed

/**
 * Constraint tests for V30 (early_spotter_bonus_ledger) — every test run applies Flyway from
 * scratch via [TestDatabaseFactory.start], so a clean apply of V30 is implicit in all of them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EarlySpotterBonusLedgerTest {

    @BeforeAll
    fun setup() {
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        TestDatabaseFactory.stop()
    }

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
    }

    private fun insertLedgerRow(userId: java.util.UUID, earlySpotterNumber: Int, idempotencyKey: String) {
        EarlySpotterBonusLedgerTable.insert {
            it[EarlySpotterBonusLedgerTable.userId] = userId
            it[EarlySpotterBonusLedgerTable.earlySpotterNumber] = earlySpotterNumber
            it[EarlySpotterBonusLedgerTable.nominalDelta] = 300
            it[EarlySpotterBonusLedgerTable.appliedDelta] = 300
            it[EarlySpotterBonusLedgerTable.reason] = EarlySpotterBonusReason.EARLY_SPOTTER_GRANTED
            it[EarlySpotterBonusLedgerTable.idempotencyKey] = idempotencyKey
        }
    }

    @Test
    fun `a second ledger row for the same user_id is rejected`() {
        val cred = UserTestSeed.seedAuthCredential("bonus1@example.com")
        val userId = UserTestSeed.seedUser(cred.authCredentialId, username = "bonus1")

        transaction {
            insertLedgerRow(userId, earlySpotterNumber = 1, idempotencyKey = "early_spotter_bonus:$userId")
        }

        assertThrows<ExposedSQLException> {
            transaction {
                insertLedgerRow(userId, earlySpotterNumber = 1, idempotencyKey = "early_spotter_bonus:$userId:retry")
            }
        }
    }

    @Test
    fun `a second ledger row with the same idempotency_key is rejected`() {
        val cred1 = UserTestSeed.seedAuthCredential("bonus2@example.com")
        val userId1 = UserTestSeed.seedUser(cred1.authCredentialId, username = "bonus2")
        val cred2 = UserTestSeed.seedAuthCredential("bonus3@example.com")
        val userId2 = UserTestSeed.seedUser(cred2.authCredentialId, username = "bonus3")

        val sharedKey = "early_spotter_bonus:shared"

        transaction {
            insertLedgerRow(userId1, earlySpotterNumber = 1, idempotencyKey = sharedKey)
        }

        assertThrows<ExposedSQLException> {
            transaction {
                insertLedgerRow(userId2, earlySpotterNumber = 2, idempotencyKey = sharedKey)
            }
        }
    }
}
