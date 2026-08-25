package com.revio.server.features.user

import com.revio.server.features.announcement.AnnouncementKey
import com.revio.server.features.announcement.AnnouncementStatus
import com.revio.server.features.announcement.UserAnnouncementTable
import com.revio.server.features.auth.AuthTable
import com.revio.server.features.post.PostTable
import com.revio.server.features.scoring.ScoringServiceImpl
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.*

/** Ban snapshot for a user; [isActive] is the single source of truth for "is this ban currently in effect". */
data class BanState(
    val permanent: Boolean,
    val bannedUntil: Instant?,
    val reason: String?,
) {
    fun isActive(now: Instant = Instant.now()): Boolean =
        permanent || (bannedUntil != null && bannedUntil.isAfter(now))
}

/**
 * Result of [IUserDAO.createUser] — surfaces what [UserDao.createUser]'s single transaction
 * already knows about Early Spotter allocation, instead of discarding it behind a bare [UUID].
 * [bonusGrantedNow] is true only when the 300-point ledger row was actually inserted in this
 * call (always true when [isEarlySpotter] is true, since the ledger's idempotency key is derived
 * from the freshly created [userId] and can never already exist).
 * [pendingAnnouncements] mirrors the PENDING rows just written to `user_announcements` in the
 * same transaction — empty unless [bonusGrantedNow] is true.
 */
data class CreateUserProfileResult(
    val userId: UUID,
    val isEarlySpotter: Boolean,
    val earlySpotterNumber: Int?,
    val bonusGrantedNow: Boolean,
    val pendingAnnouncements: List<AnnouncementKey> = emptyList(),
)

interface IUserDAO {
    suspend fun createUser(user: User): CreateUserProfileResult
    suspend fun getUserById(userId: UUID): User?

    /** Current ban fields for [userId]. Null if the user doesn't exist. */
    suspend fun findBanState(userId: UUID): BanState?
    suspend fun getUserByAuthCredentialId(authCredentialId: UUID): User?
    suspend fun usernameExistsIgnoreCase(username: String): Boolean
    suspend fun usernameExistsIgnoreSelf(username: String, excludeUserId: UUID): Boolean
    suspend fun phoneNumberExistsIgnoreSelf(phoneNumber: String, excludeUserId: UUID): Boolean
    suspend fun updateProfilePicture(userId: UUID, imagePath: String): Int
    suspend fun updateUserProfile(
        userId: UUID,
        fullName: String? = null,
        username: String? = null,
        country: String? = null,
        phoneNumber: String? = null,
        setPhoneNull: Boolean = false,
        birthDate: LocalDate? = null,
    ): Int
    suspend fun countPostsByUser(userId: UUID): Long

    /**
     * Atomically add [delta] to spot_score (floored at 0) for [userId], via a single
     * `GREATEST(0, spot_score + delta)` UPDATE run at READ COMMITTED — see [addSpotScore].
     * Opens its own transaction; do not call from within another transaction block.
     */
    suspend fun incrementSpotScore(userId: UUID, delta: Int)

    /**
     * Update streak fields for [userId] based on [localDay].
     * - If lastStreakDate == localDay: no change (already counted today).
     * - If lastStreakDate == localDay - 1: extend streak by 1.
     * - Otherwise: reset streak to 1.
     * Only advances when localDay > lastStreakDate.
     * Stores [timezoneId] as the user's reference zone for streak-reset computation at read time.
     * Must be called inside an existing [transaction] block.
     */
    suspend fun advanceStreak(userId: UUID, localDay: LocalDate, timezoneId: String?)

    /**
     * Marks that [userId] just opened the feed (plan §18, step 6.2 — backs the discovery job's
     * 12h gate, §8.3). A single conditional `UPDATE`: it only actually writes when
     * `last_feed_open_at` is null or older than [staleAfter], so calling this on every feed
     * request (as [features.post] does, but only for a non-paginated request — see PostRoutes.kt)
     * still doesn't add a write on every request, just an inexpensive no-op `UPDATE` most of the
     * time.
     */
    suspend fun updateLastFeedOpenIfStale(userId: UUID, now: Instant = Instant.now(), staleAfter: Duration = Duration.ofMinutes(5))
}

class UserDao : IUserDAO {
    override suspend fun createUser(user: User): CreateUserProfileResult = transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
        exec("SELECT pg_advisory_xact_lock(8123001)")

        val assignedNumber = exec(
            """
            UPDATE early_spotter_counter
               SET last_assigned = last_assigned + 1
             WHERE last_assigned < 1000
            RETURNING last_assigned
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT
        ) { rs -> if (rs.next()) rs.getInt("last_assigned") else null }

        val userId = UserTable.insertReturning(listOf(UserTable.id)) {
            it[authCredentialId] = user.authCredentialId
            it[profilePicturePath] = user.profilePicturePath
            it[fullName] = user.fullName
            it[phoneNumber] = user.phoneNumber
            it[birthDate] = user.birthDate
            it[username] = user.username
            it[country] = user.country
            it[spotScore] = user.spotScore
            it[isEarlySpotter] = assignedNumber != null
            it[earlySpotterNumber] = assignedNumber
        }.singleOrNull()?.get(UserTable.id)?.value ?: throw UserCreationException("Failed to insert user")

        var bonusGrantedNow = false
        val pendingAnnouncements = mutableListOf<AnnouncementKey>()
        if (assignedNumber != null) {
            val bonusPoints = ScoringServiceImpl.EARLY_SPOTTER_BONUS_POINTS
            val ledgerInsertedRows = EarlySpotterBonusLedgerTable.insertIgnore {
                it[EarlySpotterBonusLedgerTable.userId] = userId
                it[EarlySpotterBonusLedgerTable.earlySpotterNumber] = assignedNumber
                it[EarlySpotterBonusLedgerTable.nominalDelta] = bonusPoints
                it[EarlySpotterBonusLedgerTable.appliedDelta] = bonusPoints
                it[EarlySpotterBonusLedgerTable.reason] = EarlySpotterBonusReason.EARLY_SPOTTER_GRANTED
                it[EarlySpotterBonusLedgerTable.idempotencyKey] = "early_spotter_bonus:$userId"
            }.insertedCount

            if (ledgerInsertedRows > 0) {
                addSpotScore(userId, bonusPoints)
                bonusGrantedNow = true

                // Same transaction as the allocation above — the welcome/bonus announcements
                // must exist iff the Early Spotter status and the ledger row do.
                UserAnnouncementTable.insert {
                    it[UserAnnouncementTable.userId] = userId
                    it[announcementKey] = AnnouncementKey.EARLY_SPOTTER_WELCOME.name
                    it[status] = AnnouncementStatus.PENDING.name
                    it[payload] = """{"earlySpotterNumber":$assignedNumber}"""
                }
                UserAnnouncementTable.insert {
                    it[UserAnnouncementTable.userId] = userId
                    it[announcementKey] = AnnouncementKey.EARLY_SPOTTER_BONUS.name
                    it[status] = AnnouncementStatus.PENDING.name
                    it[payload] = """{"points":$bonusPoints}"""
                }
                pendingAnnouncements += AnnouncementKey.EARLY_SPOTTER_WELCOME
                pendingAnnouncements += AnnouncementKey.EARLY_SPOTTER_BONUS
            }
        }

        CreateUserProfileResult(
            userId = userId,
            isEarlySpotter = assignedNumber != null,
            earlySpotterNumber = assignedNumber,
            bonusGrantedNow = bonusGrantedNow,
            pendingAnnouncements = pendingAnnouncements,
        )
    }

    override suspend fun getUserById(userId: UUID): User? = transaction {
        UserTable
            .selectAll()
            .where { UserTable.id eq userId }
            .mapNotNull { it.toUser() }
            .singleOrNull()
    }

    override suspend fun findBanState(userId: UUID): BanState? = transaction {
        UserTable
            .select(UserTable.banPermanent, UserTable.bannedUntil, UserTable.banReason)
            .where { UserTable.id eq userId }
            .singleOrNull()
            ?.let {
                BanState(
                    permanent = it[UserTable.banPermanent],
                    bannedUntil = it[UserTable.bannedUntil],
                    reason = it[UserTable.banReason],
                )
            }
    }

    override suspend fun getUserByAuthCredentialId(authCredentialId: UUID): User? = transaction {
        UserTable
            .selectAll()
            .where { UserTable.authCredentialId eq authCredentialId }
            .mapNotNull { it.toUser() }
            .singleOrNull()
    }

    override suspend fun usernameExistsIgnoreCase(username: String): Boolean = transaction {
        UserTable
            .select(UserTable.id)
            .where { UserTable.username.lowerCase() eq username.lowercase() }
            .limit(1)
            .any()
    }

    override suspend fun usernameExistsIgnoreSelf(username: String, excludeUserId: UUID): Boolean = transaction {
        UserTable
            .select(UserTable.id)
            .where { (UserTable.username.lowerCase() eq username.lowercase()) and (UserTable.id neq excludeUserId) }
            .limit(1)
            .any()
    }

    override suspend fun phoneNumberExistsIgnoreSelf(phoneNumber: String, excludeUserId: UUID): Boolean = transaction {
        UserTable
            .select(UserTable.id)
            .where { (UserTable.phoneNumber eq phoneNumber) and (UserTable.id neq excludeUserId) }
            .limit(1)
            .any()
    }

    override suspend fun updateProfilePicture(userId: UUID, imagePath: String): Int = transaction {
        UserTable.update({ UserTable.id eq userId }) {
            it[profilePicturePath] = imagePath
        }
    }

    override suspend fun updateUserProfile(
        userId: UUID,
        fullName: String?,
        username: String?,
        country: String?,
        phoneNumber: String?,
        setPhoneNull: Boolean,
        birthDate: LocalDate?,
    ): Int = transaction {
        UserTable.update({ UserTable.id eq userId }) {
            val now = Instant.now()
            if (fullName != null) {
                it[UserTable.fullName] = fullName
                it[UserTable.fullNameChangedAt] = now
            }
            if (username != null) {
                it[UserTable.username] = username
                it[UserTable.usernameChangedAt] = now
            }
            if (country != null) {
                it[UserTable.country] = country
                it[UserTable.countryChangedAt] = now
            }
            if (setPhoneNull) {
                it[UserTable.phoneNumber] = null
                it[UserTable.phoneNumberChangedAt] = now
            } else if (phoneNumber != null) {
                it[UserTable.phoneNumber] = phoneNumber
                it[UserTable.phoneNumberChangedAt] = now
            }
            if (birthDate != null) {
                it[UserTable.birthDate] = birthDate
                it[UserTable.birthDateChangedAt] = now
            }
            it[UserTable.updatedAt] = CurrentTimestamp
        }
    }

    override suspend fun countPostsByUser(userId: UUID): Long = transaction {
        PostTable.selectAll().where { PostTable.userId eq userId }.count()
    }

    override suspend fun incrementSpotScore(userId: UUID, delta: Int): Unit =
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            addSpotScore(userId, delta)
            Unit
        }

    override suspend fun advanceStreak(userId: UUID, localDay: LocalDate, timezoneId: String?) = transaction {
        val row = UserTable.select(
            listOf(UserTable.currentStreak, UserTable.longestStreak, UserTable.lastStreakDate)
        ).where { UserTable.id eq userId }.singleOrNull() ?: return@transaction

        val lastDate = row[UserTable.lastStreakDate]

        // Guard: only advance if localDay is strictly after lastStreakDate.
        if (lastDate != null && !localDay.isAfter(lastDate)) return@transaction

        val prevStreak = row[UserTable.currentStreak]
        val prevLongest = row[UserTable.longestStreak]

        val newStreak = when {
            lastDate == null -> 1
            localDay == lastDate.plusDays(1) -> prevStreak + 1
            else -> 1
        }
        val newLongest = maxOf(prevLongest, newStreak)

        UserTable.update({ UserTable.id eq userId }) {
            it[currentStreak] = newStreak
            it[longestStreak] = newLongest
            it[lastStreakDate] = localDay
            it[lastStreakTimezone] = timezoneId
        }
    }

    override suspend fun updateLastFeedOpenIfStale(userId: UUID, now: Instant, staleAfter: Duration): Unit = transaction {
        val threshold = now.minus(staleAfter)
        UserTable.update({
            (UserTable.id eq userId) and
                ((UserTable.lastFeedOpenAt.isNull()) or (UserTable.lastFeedOpenAt less threshold))
        }) {
            it[lastFeedOpenAt] = now
        }
        Unit
    }

    private fun ResultRow.toUser() = User(
        id = this[UserTable.id].value,
        authCredentialId = this[UserTable.authCredentialId],
        profilePicturePath = this[UserTable.profilePicturePath],
        fullName = this[UserTable.fullName],
        phoneNumber = this[UserTable.phoneNumber],
        birthDate = this[UserTable.birthDate],
        username = this[UserTable.username],
        country = this[UserTable.country],
        spotScore = this[UserTable.spotScore],
        currentStreak = this[UserTable.currentStreak],
        longestStreak = this[UserTable.longestStreak],
        lastStreakDate = this[UserTable.lastStreakDate],
        lastStreakTimezone = this[UserTable.lastStreakTimezone],
        isEarlySpotter = this[UserTable.isEarlySpotter],
        earlySpotterNumber = this[UserTable.earlySpotterNumber],
        role = this[UserTable.role],
        createdAt = this[UserTable.createdAt],
        updatedAt = this[UserTable.updatedAt],
        fullNameChangedAt = this[UserTable.fullNameChangedAt],
        countryChangedAt = this[UserTable.countryChangedAt],
        birthDateChangedAt = this[UserTable.birthDateChangedAt],
        usernameChangedAt = this[UserTable.usernameChangedAt],
        phoneNumberChangedAt = this[UserTable.phoneNumberChangedAt],
        bannedUntil = this[UserTable.bannedUntil],
        banPermanent = this[UserTable.banPermanent],
        banReason = this[UserTable.banReason],
        bannedAt = this[UserTable.bannedAt],
        bannedBy = this[UserTable.bannedBy],
    )
}

class UserCreationException(message: String) : Exception(message)
