package com.phonedisk.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object TaskStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELED = "canceled"
}

@Entity(tableName = "tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val filePath: String,
    val status: String,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val speedBps: Long = 0,
    val wifiOnly: Boolean = true,
    val userNamed: Boolean = false,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = 'queued' ORDER BY createdAt ASC LIMIT 1")
    suspend fun nextQueued(): DownloadTaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): DownloadTaskEntity?

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Delete
    suspend fun delete(task: DownloadTaskEntity)

    @Query("SELECT * FROM tasks WHERE status = 'running'")
    suspend fun running(): List<DownloadTaskEntity>
}

@Database(entities = [DownloadTaskEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "phonedisk.db",
                ).build().also { instance = it }
            }
        }
    }
}

class TaskRepository private constructor(context: Context) {
    private val dao = AppDatabase.get(context).tasks()

    fun observeAll(): Flow<List<DownloadTaskEntity>> = dao.observeAll()

    suspend fun get(id: Long) = dao.get(id)

    suspend fun insert(task: DownloadTaskEntity): Long = dao.insert(task)

    suspend fun update(task: DownloadTaskEntity) = dao.update(task)

    suspend fun delete(task: DownloadTaskEntity) = dao.delete(task)

    suspend fun nextQueued() = dao.nextQueued()

    suspend fun recoverInterrupted() {
        dao.running().forEach { row ->
            dao.update(row.copy(status = TaskStatus.QUEUED, speedBps = 0))
        }
    }

    companion object {
        @Volatile
        private var instance: TaskRepository? = null

        fun get(context: Context): TaskRepository {
            return instance ?: synchronized(this) {
                instance ?: TaskRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
