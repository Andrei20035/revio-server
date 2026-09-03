package com.revio.server.features.notification

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

interface IChallengeStartDAO {
    /**
     * One page of distinct user ids with at least one currently active, token-bearing device —
     * the "challenge is live" broadcast's fan-out candidate set (push-notifications plan, §7:
     * "toți utilizatorii" means "every user reachable through at least one device", not every row
     * in `users`). Ordered by user id so repeated calls with [cursor] set to the previous page's
     * last id walk the whole candidate set page by page, never loading it all into memory at
     * once — unlike [DiscoveryDAO.findCandidates].
     *
     * @param cursor null for the first page; otherwise the last user id returned by the previous
     *   page, so this page starts strictly after it.
     */
    suspend fun findEligibleUserIdsPage(cursor: UUID?, limit: Int): List<UUID>
}

class ChallengeStartDAO : IChallengeStartDAO {

    override suspend fun findEligibleUserIdsPage(cursor: UUID?, limit: Int): List<UUID> = transaction {
        val query = UserDeviceTable
            .select(UserDeviceTable.userId)
            .where { (UserDeviceTable.isActive eq true) and (UserDeviceTable.fcmToken.isNotNull()) }

        if (cursor != null) {
            query.andWhere { UserDeviceTable.userId greater cursor }
        }

        query
            .withDistinct()
            .orderBy(UserDeviceTable.userId, SortOrder.ASC)
            .limit(limit)
            .map { it[UserDeviceTable.userId].value }
    }
}
