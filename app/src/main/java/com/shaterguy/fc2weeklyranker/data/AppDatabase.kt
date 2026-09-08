package com.shaterguy.fc2weeklyranker.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val STOPPED = "STOPPED"
    const val FINALIZING = "FINALIZING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "posts", indices = [Index("snapshotKey"), Index("postedAtEpochMillis")])
data class PostEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val postedAtEpochMillis: Long,
    val recommendationCount: Int,
    val dailyRate: Double,
    val snapshotKey: String,
    val fetchedAtEpochMillis: Long,
)

@Entity(
    tableName = "favorites",
    foreignKeys = [ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["postId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("postId")],
)
data class FavoriteEntity(@PrimaryKey val postId: String, val createdAtEpochMillis: Long)

@Entity(
    tableName = "videos",
    foreignKeys = [ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["postId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("postId")],
)
data class VideoEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val url: String,
    val referer: String,
    val userAgent: String,
    val sourceKind: String,
    val ordinal: Int,
    val discoveredAtEpochMillis: Long,
)

@Entity(
    tableName = "downloads",
    foreignKeys = [ForeignKey(entity = VideoEntity::class, parentColumns = ["id"], childColumns = ["videoId"], onDelete = ForeignKey.CASCADE)],
)
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val status: String,
    val contentUri: String?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val errorCode: String?,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "0") val enqueueOrder: Long = 0L,
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
)

@Entity(
    tableName = "rank_observations",
    primaryKeys = ["datasetKey", "postId", "observedBucketEpochMillis"],
    indices = [Index(value = ["datasetKey", "postId"]), Index(value = ["observedAtEpochMillis"])],
)
data class RankObservationEntity(
    val datasetKey: String,
    val postId: String,
    val postedAtEpochMillis: Long,
    val commentCount: Int,
    val observedAtEpochMillis: Long,
    val observedBucketEpochMillis: Long,
)

@Dao
interface PostDao {
    @Query("SELECT * FROM posts WHERE snapshotKey = :snapshotKey ORDER BY dailyRate DESC, recommendationCount DESC, postedAtEpochMillis DESC, id DESC")
    fun postsForSnapshot(snapshotKey: String): Flow<List<PostEntity>>

    @Query("SELECT COUNT(*) FROM posts WHERE snapshotKey = :snapshotKey")
    suspend fun snapshotCount(snapshotKey: String): Int

    @Query("SELECT * FROM posts WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PostEntity?

    @Query("SELECT * FROM posts WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<PostEntity?>

    @Query("SELECT p.* FROM posts p INNER JOIN favorites f ON p.id = f.postId ORDER BY f.createdAtEpochMillis DESC")
    fun favorites(): Flow<List<PostEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE postId = :postId)")
    suspend fun isFavorite(postId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE postId = :postId)")
    fun observeFavorite(postId: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(posts: List<PostEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE postId = :postId")
    suspend fun removeFavorite(postId: String)
}

@Dao
interface RankObservationDao {
    @Query("SELECT * FROM rank_observations WHERE datasetKey = :datasetKey ORDER BY observedAtEpochMillis ASC")
    fun observeDataset(datasetKey: String): Flow<List<RankObservationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(observations: List<RankObservationEntity>)

    @Query("DELETE FROM rank_observations WHERE observedAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long)

    @Query(
        """
        DELETE FROM rank_observations
        WHERE rowid IN (
            SELECT rowid FROM rank_observations
            WHERE datasetKey = :datasetKey AND postId = :postId
            ORDER BY observedAtEpochMillis DESC
            LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimPost(datasetKey: String, postId: String, keep: Int)

    @Query(
        """
        DELETE FROM rank_observations
        WHERE rowid IN (
            SELECT rowid FROM rank_observations
            WHERE datasetKey = :datasetKey
            ORDER BY observedAtEpochMillis DESC
            LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimDataset(datasetKey: String, keep: Int)
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE postId = :postId ORDER BY ordinal ASC, discoveredAtEpochMillis ASC")
    fun forPost(postId: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE postId = :postId ORDER BY ordinal ASC, discoveredAtEpochMillis ASC")
    suspend fun currentForPost(postId: String): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): VideoEntity?

    @Upsert
    suspend fun upsert(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE postId = :postId AND sourceKind = 'IFRAME'")
    suspend fun deleteResolversForPost(postId: String)

    @Transaction
    suspend fun reconcileForPost(postId: String, videos: List<VideoEntity>) {
        deleteResolversForPost(postId)
        if (videos.isNotEmpty()) upsert(videos)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE videoId = :videoId LIMIT 1")
    fun observe(videoId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId LIMIT 1")
    suspend fun byVideoId(videoId: String): DownloadEntity?

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query(
        """UPDATE downloads
        SET status = :toStatus, errorCode = :errorCode, updatedAtEpochMillis = :updatedAt
        WHERE videoId = :videoId AND status IN (:fromStatuses)""",
    )
    suspend fun transitionStatus(
        videoId: String,
        fromStatuses: List<String>,
        toStatus: String,
        errorCode: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """UPDATE downloads
        SET contentUri = :contentUri, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes,
            errorCode = NULL, updatedAtEpochMillis = :updatedAt
        WHERE videoId = :videoId AND status = :requiredStatus""",
    )
    suspend fun updateProgressIfStatus(
        videoId: String,
        requiredStatus: String,
        contentUri: String?,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long,
    ): Int
}

@Database(
    entities = [PostEntity::class, FavoriteEntity::class, VideoEntity::class, DownloadEntity::class, RankObservationEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun rankObservationDao(): RankObservationDao
    abstract fun videoDao(): VideoDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN enqueueOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE downloads SET enqueueOrder = rowid WHERE enqueueOrder = 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS rank_observations (
                        datasetKey TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        postedAtEpochMillis INTEGER NOT NULL,
                        commentCount INTEGER NOT NULL,
                        observedAtEpochMillis INTEGER NOT NULL,
                        observedBucketEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(datasetKey, postId, observedBucketEpochMillis)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rank_observations_datasetKey_postId ON rank_observations (datasetKey, postId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_rank_observations_observedAtEpochMillis ON rank_observations (observedAtEpochMillis)")
            }
        }
    }
}
