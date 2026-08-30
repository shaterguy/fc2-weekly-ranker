package com.shaterguy.fc2weeklyranker.data

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
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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
)

@Dao
interface PostDao {
    @Query("SELECT * FROM posts WHERE snapshotKey = :snapshotKey ORDER BY dailyRate DESC, recommendationCount DESC, postedAtEpochMillis DESC, id DESC")
    fun postsForSnapshot(snapshotKey: String): Flow<List<PostEntity>>

    @Query("SELECT COUNT(*) FROM posts WHERE snapshotKey = :snapshotKey")
    suspend fun snapshotCount(snapshotKey: String): Int

    @Query("SELECT * FROM posts WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PostEntity?

    @Query("SELECT p.* FROM posts p INNER JOIN favorites f ON p.id = f.postId ORDER BY f.createdAtEpochMillis DESC")
    fun favorites(): Flow<List<PostEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE postId = :postId)")
    suspend fun isFavorite(postId: String): Boolean

    @Upsert
    suspend fun upsert(posts: List<PostEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE postId = :postId")
    suspend fun removeFavorite(postId: String)
}

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE postId = :postId ORDER BY ordinal ASC, discoveredAtEpochMillis ASC")
    fun forPost(postId: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): VideoEntity?

    @Upsert
    suspend fun upsert(videos: List<VideoEntity>)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads WHERE videoId = :videoId LIMIT 1")
    fun observe(videoId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId LIMIT 1")
    suspend fun byVideoId(videoId: String): DownloadEntity?

    @Upsert
    suspend fun upsert(entity: DownloadEntity)
}

@Database(entities = [PostEntity::class, FavoriteEntity::class, VideoEntity::class, DownloadEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun videoDao(): VideoDao
    abstract fun downloadDao(): DownloadDao
}
