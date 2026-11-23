package com.example.game

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
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/**
 * Difficulty levels for BackTalk word packs.
 */
enum class PackDifficulty(val stars: Int, val label: String) {
    EASY(1, "Easy"),
    MEDIUM(2, "Medium"),
    HARD(3, "Hard")
}

/**
 * Room Entity storing themed word packs with words serialized as a JSON array string.
 */
@Entity(tableName = "word_packs")
data class WordPack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val difficulty: PackDifficulty,
    val words: String, // JSON array e.g. ["cat","dog","pizza"]
    val createdAt: Long
)

/**
 * Room Entity storing completed game rounds with local reversed audio path and player scores.
 */
@Entity(tableName = "game_rounds")
data class GameRound(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordPackId: Long,
    val originalWord: String,
    val reversedAudioPath: String,
    val playerAGuess: String,
    val playerBGuess: String,
    val playerAScore: Int,
    val playerBScore: Int,
    val completedAt: Long
)

/**
 * Room Entity storing player-added custom words and usage telemetry.
 */
@Entity(tableName = "custom_words")
data class CustomWord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val category: String,
    val timesUsed: Int = 0,
    val avgScore: Float = 0f
)

/**
 * Pure Kotlin JSON string array serializer/parser (works identically on Android and pure JVM tests).
 */
object WordJsonConverter {
    fun toJsonArray(words: List<String>): String {
        return words.joinToString(prefix = "[", postfix = "]", separator = ",") { word ->
            val escaped = buildString(word.length) {
                for (ch in word) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }
            "\"$escaped\""
        }
    }

    fun fromJsonArray(json: String): List<String> {
        val trimmed = json.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return emptyList()
        }
        val result = mutableListOf<String>()
        var i = 1
        while (i < trimmed.length - 1) {
            while (i < trimmed.length - 1 && (trimmed[i].isWhitespace() || trimmed[i] == ',')) {
                i++
            }
            if (i >= trimmed.length - 1) break
            if (trimmed[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < trimmed.length - 1) {
                    val c = trimmed[i]
                    if (c == '\\' && i + 1 < trimmed.length - 1) {
                        val next = trimmed[i + 1]
                        when (next) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> sb.append(next)
                        }
                        i += 2
                    } else if (c == '"') {
                        i++
                        break
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                val item = sb.toString()
                if (item.isNotBlank()) result.add(item)
            } else {
                i++
            }
        }
        return result
    }
}

class BackTalkTypeConverters {
    @TypeConverter
    fun fromDifficulty(difficulty: PackDifficulty): String = difficulty.name

    @TypeConverter
    fun toDifficulty(name: String): PackDifficulty =
        runCatching { PackDifficulty.valueOf(name) }.getOrDefault(PackDifficulty.EASY)
}

@Dao
interface WordPackDao {
    @Query("SELECT * FROM word_packs ORDER BY id ASC")
    fun observeAllPacks(): Flow<List<WordPack>>

    @Query("SELECT * FROM word_packs ORDER BY id ASC")
    suspend fun getAllPacksOnce(): List<WordPack>

    @Query("SELECT * FROM word_packs WHERE id = :id LIMIT 1")
    suspend fun getPackById(id: Long): WordPack?

    @Query("SELECT COUNT(*) FROM word_packs")
    suspend fun countPacks(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(packs: List<WordPack>)
}

@Dao
interface GameRoundDao {
    @Query("SELECT * FROM game_rounds ORDER BY completedAt DESC LIMIT 30")
    fun observeRecentRounds(): Flow<List<GameRound>>

    @Query("SELECT COALESCE(MAX(MAX(playerAScore, playerBScore)), 0) FROM game_rounds")
    fun observeBestRoundScore(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRound(round: GameRound): Long
}

@Dao
interface CustomWordDao {
    @Query("SELECT * FROM custom_words ORDER BY id DESC")
    fun observeCustomWords(): Flow<List<CustomWord>>

    @Query("SELECT * FROM custom_words")
    suspend fun getAllCustomWordsOnce(): List<CustomWord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomWord(word: CustomWord): Long

    @Query("UPDATE custom_words SET timesUsed = :timesUsed, avgScore = :avgScore WHERE id = :id")
    suspend fun updateStats(id: Long, timesUsed: Int, avgScore: Float)

    @Query("DELETE FROM custom_words WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [WordPack::class, GameRound::class, CustomWord::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(BackTalkTypeConverters::class)
abstract class BackTalkDatabase : RoomDatabase() {
    abstract fun wordPackDao(): WordPackDao
    abstract fun gameRoundDao(): GameRoundDao
    abstract fun customWordDao(): CustomWordDao

    companion object {
        @Volatile
        private var INSTANCE: BackTalkDatabase? = null

        fun getInstance(context: Context): BackTalkDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BackTalkDatabase::class.java,
                    "backtalk.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
