package com.revio.server.features.moderation

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

interface IOrphanedStorageObjectDAO {
    /**
     * Records a failed [com.revio.server.core.storage.IStorageService.deleteImage] attempt so it
     * can be retried later. Upserts on [objectKey] (the table's primary key): a first failure
     * inserts a row, a repeat failure bumps `attempts` and refreshes `last_error`.
     */
    suspend fun recordFailure(objectKey: String, error: String?)

    /** Every queued object key, oldest first. */
    suspend fun listAll(): List<String>

    /** Removes [objectKey] once its deletion has succeeded. */
    suspend fun remove(objectKey: String)
}

class OrphanedStorageObjectDAO : IOrphanedStorageObjectDAO {

    override suspend fun recordFailure(objectKey: String, error: String?): Unit = transaction {
        val existing = OrphanedStorageObjectTable
            .selectAll()
            .where { OrphanedStorageObjectTable.objectKey eq objectKey }
            .singleOrNull()

        if (existing == null) {
            OrphanedStorageObjectTable.insert {
                it[OrphanedStorageObjectTable.objectKey] = objectKey
                it[OrphanedStorageObjectTable.lastError] = error
            }
        } else {
            OrphanedStorageObjectTable.update({ OrphanedStorageObjectTable.objectKey eq objectKey }) {
                it[OrphanedStorageObjectTable.attempts] = existing[OrphanedStorageObjectTable.attempts] + 1
                it[OrphanedStorageObjectTable.lastError] = error
                it[OrphanedStorageObjectTable.lastAttemptAt] = Instant.now()
            }
        }
    }

    override suspend fun listAll(): List<String> = transaction {
        OrphanedStorageObjectTable
            .selectAll()
            .orderBy(OrphanedStorageObjectTable.createdAt to SortOrder.ASC)
            .map { it[OrphanedStorageObjectTable.objectKey] }
    }

    override suspend fun remove(objectKey: String): Unit = transaction {
        OrphanedStorageObjectTable.deleteWhere { OrphanedStorageObjectTable.objectKey eq objectKey }
        Unit
    }
}
