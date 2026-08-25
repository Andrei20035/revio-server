package features.comment

import com.revio.server.features.comment.CommentTable
import com.revio.server.features.user.UserTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

interface ICommentDAO {
    suspend fun addComment(userId: UUID, postId: UUID, commentText: String): Comment
    suspend fun deleteComment(commentId: UUID): Int
    suspend fun getCommentsForPost(postId: UUID): List<Comment>
    suspend fun getCommentById(commentId: UUID): Comment?

    /** Batched comment counts for a set of posts. Returns a map postId -> count (posts with no comments are absent). */
    suspend fun getCommentCountsForPosts(postIds: List<UUID>): Map<UUID, Long>

    /** Returns true if [userId] has at least one comment on [postId]. */
    suspend fun hasUserCommentedOnPost(userId: UUID, postId: UUID): Boolean

    /**
     * True if [userId] has a comment on [postId] created at/after [windowStart], other than
     * [excludingCommentId] — used to decide whether a just-inserted comment is that user's first
     * contribution to the current notification aggregation window (plan §18, step 4.2), so a
     * repeat commenter within the same 15-minute window doesn't inflate the distinct-actor count.
     */
    suspend fun hasUserCommentedOnPostInWindow(
        userId: UUID,
        postId: UUID,
        windowStart: Instant,
        excludingCommentId: UUID,
    ): Boolean
}

class CommentDAO : ICommentDAO {

    /** Coloane pentru join cu users — selectate o singură dată ca să evităm tipare. */
    private val joinedColumns = listOf(
        CommentTable.id,
        CommentTable.userId,
        CommentTable.postId,
        CommentTable.commentText,
        CommentTable.createdAt,
        CommentTable.updatedAt,
        UserTable.username,
        UserTable.profilePicturePath,
    )

    private fun ResultRow.toComment(): Comment = Comment(
        id = this[CommentTable.id].value,
        userId = this[CommentTable.userId],
        postId = this[CommentTable.postId],
        commentText = this[CommentTable.commentText],
        username = this[UserTable.username],
        profilePicturePath = this[UserTable.profilePicturePath],
        createdAt = this[CommentTable.createdAt],
        updatedAt = this[CommentTable.updatedAt],
    )

    override suspend fun addComment(userId: UUID, postId: UUID, commentText: String): Comment = transaction {
        val newId = CommentTable
            .insertReturning(listOf(CommentTable.id)) {
                it[CommentTable.userId] = userId
                it[CommentTable.postId] = postId
                it[CommentTable.commentText] = commentText
            }.singleOrNull()?.get(CommentTable.id)?.value
            ?: error("INSERT did not return id")

        // Citește înapoi cu join pentru a popula username/avatar
        (CommentTable innerJoin UserTable)
            .select(joinedColumns)
            .where { CommentTable.id eq newId }
            .single()
            .toComment()
    }

    override suspend fun deleteComment(commentId: UUID): Int = transaction {
        CommentTable.deleteWhere { id eq commentId }
    }

    override suspend fun getCommentsForPost(postId: UUID): List<Comment> = transaction {
        (CommentTable innerJoin UserTable)
            .select(joinedColumns)
            .where { CommentTable.postId eq postId }
            .orderBy(CommentTable.createdAt to SortOrder.ASC)
            .map { it.toComment() }
    }

    override suspend fun getCommentById(commentId: UUID): Comment? = transaction {
        (CommentTable innerJoin UserTable)
            .select(joinedColumns)
            .where { CommentTable.id eq commentId }
            .singleOrNull()
            ?.toComment()
    }

    override suspend fun getCommentCountsForPosts(postIds: List<UUID>): Map<UUID, Long> = transaction {
        if (postIds.isEmpty()) return@transaction emptyMap()
        val countExpr = CommentTable.id.count()
        CommentTable
            .select(CommentTable.postId, countExpr)
            .where { CommentTable.postId inList postIds }
            .groupBy(CommentTable.postId)
            .associate { it[CommentTable.postId] to it[countExpr] }
    }

    override suspend fun hasUserCommentedOnPost(userId: UUID, postId: UUID): Boolean = transaction {
        CommentTable
            .select(CommentTable.id)
            .where { (CommentTable.userId eq userId) and (CommentTable.postId eq postId) }
            .limit(1)
            .any()
    }

    override suspend fun hasUserCommentedOnPostInWindow(
        userId: UUID,
        postId: UUID,
        windowStart: Instant,
        excludingCommentId: UUID,
    ): Boolean = transaction {
        CommentTable
            .select(CommentTable.id)
            .where {
                (CommentTable.userId eq userId) and
                    (CommentTable.postId eq postId) and
                    (CommentTable.createdAt greaterEq windowStart) and
                    (CommentTable.id neq excludingCommentId)
            }
            .limit(1)
            .any()
    }
}