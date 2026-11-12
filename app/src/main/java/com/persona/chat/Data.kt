package com.persona.chat
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class TriggerRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "", val keywords: String = "",      // keywords/aliases: one per line
    val mode: String = "WORD",                              // EXACT | WORD | PHRASE | CONTAINS
    val responses: String = "",                             // alternatives: one per line
    val minDelayMs: Long = 1000, val maxDelayMs: Long = 3000,
    val priority: Int = 0, val enabled: Boolean = true,
    val caseSensitive: Boolean = false, val ignorePunct: Boolean = true,
    val noRepeat: Boolean = true, val media: String = "")  // media: file paths, one per line

@Entity data class Msg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromMe: Boolean, val text: String,
    val ts: Long = System.currentTimeMillis(), val triggerId: Long? = null, val media: String? = null)

@Entity data class Schedule(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String = "", val text: String = "",
    val hour: Int = 7, val minute: Int = 30, val days: Int = 127, val enabled: Boolean = true)  // days: bit0=Sun..bit6=Sat

@Dao interface AppDao {
    @Query("SELECT * FROM TriggerRule ORDER BY priority DESC, id") fun triggers(): Flow<List<TriggerRule>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(t: TriggerRule)
    @Delete suspend fun delete(t: TriggerRule)
    @Query("SELECT * FROM Msg ORDER BY ts, id") fun messages(): Flow<List<Msg>>
    @Insert suspend fun insert(m: Msg)
    @Query("DELETE FROM Msg") suspend fun clearChat()
    @Query("SELECT * FROM Schedule ORDER BY hour, minute") fun schedules(): Flow<List<Schedule>>
    @Query("SELECT * FROM Schedule") suspend fun allSchedules(): List<Schedule>
    @Query("SELECT * FROM Schedule WHERE id=:id") suspend fun schedule(id: Long): Schedule?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSchedule(s: Schedule): Long
    @Delete suspend fun deleteSchedule(s: Schedule)
}

@Database(entities = [TriggerRule::class, Msg::class, Schedule::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var i: AppDb? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "persona.db").fallbackToDestructiveMigration().build().also { i = it }
        }
    }
}
