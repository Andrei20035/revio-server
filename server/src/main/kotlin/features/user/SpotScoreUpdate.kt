package com.revio.server.features.user

import com.revio.server.features.post.PostTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.update
import java.util.UUID

private fun greatestZero(column: Column<Int>, delta: Int) =
    CustomFunction("GREATEST", IntegerColumnType(), column + delta, intLiteral(0))

/**
 * spot_score = GREATEST(0, spot_score + delta), a single atomic SQL statement.
 * Must run inside a transaction opened at READ COMMITTED — under REPEATABLE READ (the app's
 * default isolation, see config/Databases.kt), two concurrent updates to the same row abort
 * with SQLSTATE 40001 instead of composing.
 */
internal fun addSpotScore(userId: UUID, delta: Int): Int =
    UserTable.update({ UserTable.id eq userId }) {
        it[spotScore] = greatestZero(UserTable.spotScore, delta)
    }

/**
 * posts.points = GREATEST(0, points + delta), same atomicity rationale as [addSpotScore].
 */
internal fun addPostPoints(postId: UUID, delta: Int): Int =
    PostTable.update({ PostTable.id eq postId }) {
        it[points] = greatestZero(PostTable.points, delta)
    }
