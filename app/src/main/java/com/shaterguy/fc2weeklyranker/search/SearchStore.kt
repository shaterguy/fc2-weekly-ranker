package com.shaterguy.fc2weeklyranker.search

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.shaterguy.fc2weeklyranker.network.RemoteSearchPost
import kotlinx.coroutines.flow.Flow

internal object SearchStatus {
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    const val INTERRUPTED = "INTERRUPTED"
}

@Entity(tableName = "search_session")
internal data class SearchSessionEntity(
    @PrimaryKey val slot: Int = 1,
    val token: String,
    val query: String,
    val baseUrl: String,
    val status: String,
    val nextPage: Int,
    val totalPages: Int,
    val errorMessage: String?,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "search_results",
    primaryKeys = ["sessionToken", "postId"],
    indices = [Index("sessionToken")],
)
internal data class SearchResultEntity(
    val sessionToken: String,
    val postId: String,
    val url: String,
    val title: String,
    val sequence: Long,
) {
    fun toRemote(): RemoteSearchPost = RemoteSearchPost(postId, url, title)
}

@Dao
internal abstract class SearchDao {
    @Query("SELECT * FROM search_session WHERE slot = 1 LIMIT 1")
    abstract fun observeSession(): Flow<SearchSessionEntity?>

    @Query(
        """
        SELECT r.* FROM search_results r
        INNER JOIN search_session s ON s.token = r.sessionToken
        WHERE s.slot = 1
        ORDER BY r.sequence ASC
        """,
    )
    abstract fun observeResults(): Flow<List<SearchResultEntity>>

    @Query("SELECT * FROM search_session WHERE slot = 1 LIMIT 1")
    abstract suspend fun currentSession(): SearchSessionEntity?

    @Upsert
    protected abstract suspend fun upsertSession(session: SearchSessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertResults(results: List<SearchResultEntity>)

    @Query("DELETE FROM search_results")
    protected abstract suspend fun clearResults()

    @Query(
        """
        UPDATE search_session
        SET nextPage = :nextPage, totalPages = :totalPages, errorMessage = NULL,
            updatedAtEpochMillis = :updatedAt
        WHERE slot = 1 AND token = :token AND status = 'RUNNING'
        """,
    )
    protected abstract suspend fun updateProgress(
        token: String,
        nextPage: Int,
        totalPages: Int,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE search_session
        SET status = 'COMPLETED', errorMessage = NULL, updatedAtEpochMillis = :updatedAt
        WHERE slot = 1 AND token = :token AND status = 'RUNNING'
        """,
    )
    abstract suspend fun complete(token: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE search_session
        SET status = 'FAILED', errorMessage = :message, updatedAtEpochMillis = :updatedAt
        WHERE slot = 1 AND token = :token AND status = 'RUNNING'
        """,
    )
    abstract suspend fun fail(token: String, message: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE search_session
        SET status = :status, errorMessage = :message, updatedAtEpochMillis = :updatedAt
        WHERE slot = 1 AND token = :token AND status = 'RUNNING'
        """,
    )
    protected abstract suspend fun finishRunning(
        token: String,
        status: String,
        message: String,
        updatedAt: Long,
    ): Int

    suspend fun cancel(token: String, updatedAt: Long): Int =
        finishRunning(token, SearchStatus.CANCELLED, "검색을 중지했습니다.", updatedAt)

    suspend fun interrupt(token: String, updatedAt: Long): Int =
        finishRunning(
            token,
            SearchStatus.INTERRUPTED,
            "이전 검색 작업이 더 이상 실행 중이 아니어서 종료했습니다.",
            updatedAt,
        )

    @Transaction
    open suspend fun prepareSession(request: SearchRequest): SearchSessionEntity {
        currentSession()?.takeIf { it.token == request.token }?.let { return it }
        clearResults()
        return SearchSessionEntity(
            token = request.token,
            query = request.query,
            baseUrl = request.baseUrl,
            status = SearchStatus.RUNNING,
            nextPage = 1,
            totalPages = 0,
            errorMessage = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        ).also { upsertSession(it) }
    }

    @Transaction
    open suspend fun storePage(
        token: String,
        page: Int,
        totalPages: Int,
        posts: List<RemoteSearchPost>,
        updatedAt: Long,
    ): Boolean {
        val current = currentSession()
        if (current?.token != token || current.status != SearchStatus.RUNNING) return false
        if (posts.isNotEmpty()) {
            val baseSequence = page.toLong() * PAGE_SEQUENCE_STRIDE
            insertResults(
                posts.mapIndexed { index, post ->
                    SearchResultEntity(
                        sessionToken = token,
                        postId = post.id,
                        url = post.url,
                        title = post.title,
                        sequence = baseSequence + index,
                    )
                },
            )
        }
        return updateProgress(token, page + 1, totalPages, updatedAt) == 1
    }

    companion object {
        private const val PAGE_SEQUENCE_STRIDE = 1_000_000L
    }
}

@Database(
    entities = [SearchSessionEntity::class, SearchResultEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class SearchDatabase : RoomDatabase() {
    abstract fun searchDao(): SearchDao
}
