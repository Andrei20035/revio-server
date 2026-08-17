package com.revio.server.features.scoring

import com.revio.server.features.post.PostTable
import com.revio.server.features.user.addPostPoints
import com.revio.server.features.user.addSpotScore
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.util.UUID

interface IScoringDao {
    /**
     * Camera-post creation award: set posts.points = [points] AND spot_score += [points], atomically.
     * Only called when [points] > 0 (i.e. under the daily cap).
     */
    suspend fun applyCreationPoints(userId: UUID, postId: UUID, points: Int)

    /**
     * Engagement delta (like +1 / unlike -1 / first comment +5):
     * posts.points += delta AND spot_score += delta, both floored at 0, atomically.
     */
    suspend fun applyEngagementPoints(ownerId: UUID, postId: UUID, delta: Int)

    /**
     * Delete reversal: spot_score -= points (floored at 0) AND delete the post row, atomically.
     * Cascade removes associated likes and comments.
     * Returns the number of deleted post rows.
     */
    suspend fun reverseAndDeletePost(ownerId: UUID, postId: UUID, points: Int): Int

    /**
     * Same reversal as [reverseAndDeletePost], but running in the CALLER'S already-open
     * transaction instead of one of its own. Exists so post removal can delete the row in the
     * very same transaction that reconciled the post's challenge contributions — see
     * IPostRemovalDAO.removePostAtomically for why those two must be one unit.
     */
    fun reverseAndDeletePostInCurrentTransaction(ownerId: UUID, postId: UUID, points: Int): Int
}

class ScoringDaoImpl : IScoringDao {

    override suspend fun applyCreationPoints(userId: UUID, postId: UUID, points: Int): Unit =
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            PostTable.update({ PostTable.id eq postId }) {
                it[PostTable.points] = points
            }
            addSpotScore(userId, points)
            Unit
        }

    override suspend fun applyEngagementPoints(ownerId: UUID, postId: UUID, delta: Int): Unit =
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            addPostPoints(postId, delta)
            addSpotScore(ownerId, delta)
            Unit
        }

    override suspend fun reverseAndDeletePost(ownerId: UUID, postId: UUID, points: Int): Int =
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            reverseAndDeletePostInCurrentTransaction(ownerId, postId, points)
        }

    override fun reverseAndDeletePostInCurrentTransaction(ownerId: UUID, postId: UUID, points: Int): Int {
        if (points > 0) {
            addSpotScore(ownerId, -points)
        }
        return PostTable.deleteWhere { id eq postId }
    }
}
