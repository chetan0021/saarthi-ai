package com.bharatai.learning.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Local SQLite store (Room). Everything the learner creates stays on the phone.
 * Generated JSON (profile, roadmap, l10n) is stored as validated JSON documents in a
 * key-value table; learning analytics (mastery, quiz attempts, perf runs) are relational.
 */

@Entity(tableName = "documents")
data class DocEntity(@PrimaryKey val key: String, val json: String, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "topic_mastery")
data class MasteryEntity(
    @PrimaryKey val topicId: String,
    val title: String,
    val mastery: Double,
    val attempts: Int,
    val correct: Int,
    val intervalDays: Int,
    val ease: Double,
    val nextReviewAt: Long,
    val lastSeenAt: Long,
)

@Entity(tableName = "quiz_attempts")
data class QuizAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: String,
    val topicTitle: String,
    val score: Double,
    val decision: String,
    val questionsJson: String,
    val chosenCsv: String,
    val at: Long = System.currentTimeMillis(),
)

@Entity(tableName = "chat_messages")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,   // "user" | "assistant"
    val text: String,
    val tps: Double = 0.0,
    val at: Long = System.currentTimeMillis(),
)

@Entity(tableName = "perf_runs")
data class PerfRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val model: String,
    val decodeTps: Double,
    val prefillTps: Double,
    val ttftMs: Long,
    val loadMs: Long,
    val peakRssMb: Double,
    val genTokens: Int,
    val promptTokens: Int,
    val thermal: String,
    val at: Long = System.currentTimeMillis(),
)

@Dao
interface LearningDao {
    @Upsert suspend fun putDoc(d: DocEntity)
    @Query("SELECT * FROM documents WHERE `key` = :key") suspend fun doc(key: String): DocEntity?
    @Query("SELECT * FROM documents WHERE `key` = :key") fun docFlow(key: String): Flow<DocEntity?>
    @Query("DELETE FROM documents WHERE `key` = :key") suspend fun deleteDoc(key: String)

    @Upsert suspend fun putMastery(m: MasteryEntity)
    @Query("SELECT * FROM topic_mastery WHERE topicId = :id") suspend fun mastery(id: String): MasteryEntity?
    @Query("SELECT * FROM topic_mastery ORDER BY mastery ASC") fun masteryFlow(): Flow<List<MasteryEntity>>
    @Query("SELECT * FROM topic_mastery") suspend fun allMastery(): List<MasteryEntity>

    @Insert suspend fun addAttempt(a: QuizAttemptEntity): Long
    @Query("SELECT * FROM quiz_attempts ORDER BY at DESC LIMIT 50") fun attemptsFlow(): Flow<List<QuizAttemptEntity>>
    @Query("SELECT * FROM quiz_attempts WHERE topicId = :id ORDER BY at DESC") suspend fun attemptsFor(id: String): List<QuizAttemptEntity>

    @Insert suspend fun addChat(c: ChatEntity): Long
    @Query("SELECT * FROM chat_messages ORDER BY at ASC") fun chatFlow(): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chat_messages ORDER BY at DESC LIMIT :n") suspend fun lastChats(n: Int): List<ChatEntity>
    @Query("DELETE FROM chat_messages") suspend fun clearChat()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addPerf(p: PerfRunEntity): Long
    @Query("SELECT * FROM perf_runs ORDER BY at DESC LIMIT 100") fun perfFlow(): Flow<List<PerfRunEntity>>
    @Query("SELECT * FROM perf_runs ORDER BY at ASC") suspend fun allPerf(): List<PerfRunEntity>

    @Query("DELETE FROM topic_mastery") suspend fun clearMastery()
    @Query("DELETE FROM quiz_attempts") suspend fun clearAttempts()
}

@Database(
    entities = [DocEntity::class, MasteryEntity::class, QuizAttemptEntity::class, ChatEntity::class, PerfRunEntity::class],
    version = 1, exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LearningDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "saarthi.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
