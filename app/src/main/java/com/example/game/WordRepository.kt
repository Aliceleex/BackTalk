package com.example.game

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

private val Context.backTalkDataStore by preferencesDataStore(name = "backtalk_prefs")

data class DailyChallengeState(
    val date: LocalDate = LocalDate.now(),
    val dailyWord: String = "backtalk",
    val streakCount: Int = 0,
    val completedToday: Boolean = false,
    val todayScore: Int? = null,
    val highScore: Int = 0
)

/**
 * Repository abstracting Room word packs, custom words, round history, and DataStore daily streaks.
 */
class WordRepository(
    private val context: Context,
    private val wordPackDao: WordPackDao,
    private val gameRoundDao: GameRoundDao,
    private val customWordDao: CustomWordDao
) {
    companion object {
        private val KEY_STREAK = intPreferencesKey("daily_streak")
        private val KEY_LAST_DAILY_DATE = stringPreferencesKey("last_daily_date")
        private val KEY_TODAY_DAILY_SCORE = intPreferencesKey("today_daily_score")
        private val KEY_HIGH_SCORE = intPreferencesKey("party_high_score")

        /**
         * Curated default packs seeded into Room on first launch.
         */
        fun defaultSeedPacks(nowEpochMillis: Long = Instant.now().toEpochMilli()): List<WordPack> = listOf(
            WordPack(
                name = "Animals",
                difficulty = PackDifficulty.EASY,
                words = WordJsonConverter.toJsonArray(
                    listOf("cat", "dog", "frog", "duck", "lion", "panda", "koala", "shark", "penguin", "octopus")
                ),
                createdAt = nowEpochMillis
            ),
            WordPack(
                name = "Food",
                difficulty = PackDifficulty.EASY,
                words = WordJsonConverter.toJsonArray(
                    listOf("pizza", "taco", "sushi", "burger", "banana", "pretzel", "pancake", "avocado", "popcorn", "waffle")
                ),
                createdAt = nowEpochMillis
            ),
            WordPack(
                name = "Movies",
                difficulty = PackDifficulty.MEDIUM,
                words = WordJsonConverter.toJsonArray(
                    listOf("avatar", "titanic", "inception", "matrix", "frozen", "gladiator", "jaws", "shrek", "alien", "interstellar")
                ),
                createdAt = nowEpochMillis
            ),
            WordPack(
                name = "Slang",
                difficulty = PackDifficulty.MEDIUM,
                words = WordJsonConverter.toJsonArray(
                    listOf("vibe", "ghosting", "salty", "flex", "lowkey", "slay", "cringe", "goat", "helicopter", "hamburger")
                ),
                createdAt = nowEpochMillis
            ),
            WordPack(
                name = "Brain Twisters",
                difficulty = PackDifficulty.HARD,
                words = WordJsonConverter.toJsonArray(
                    listOf(
                        "onomatopoeia",
                        "Worcestershire",
                        "Christmas",
                        "chrysanthemum",
                        "serendipity",
                        "squirrel",
                        "archipelago",
                        "phenomenon",
                        "labyrinth",
                        "boulevard"
                    )
                ),
                createdAt = nowEpochMillis
            ),
            WordPack(
                name = "Random",
                difficulty = PackDifficulty.MEDIUM,
                words = WordJsonConverter.toJsonArray(
                    listOf("cat", "pizza", "helicopter", "hamburger", "Christmas", "rainbow", "bicycle", "umbrella", "astronaut", "microphone")
                ),
                createdAt = nowEpochMillis
            )
        )

        private val DAILY_POOL = listOf(
            "Christmas", "pineapple", "helicopter", "microphone", "avocado",
            "spaghetti", "butterfly", "tornado", "jellyfish", "trampoline",
            "watermelon", "kazoo", "saxophone", "kangaroo", "cinnamon",
            "boomerang", "volcano", "hamburger", "chocolate", "chameleon"
        )

        /**
         * Deterministic word of the day so every player gets the same challenge word for a calendar date.
         */
        fun dailyWordForDate(date: LocalDate = LocalDate.now()): String {
            val idx = abs(date.toEpochDay().toInt()) % DAILY_POOL.size
            return DAILY_POOL[idx]
        }
    }

    val wordPacksFlow: Flow<List<WordPack>> = wordPackDao.observeAllPacks()
    val recentRoundsFlow: Flow<List<GameRound>> = gameRoundDao.observeRecentRounds()
    val customWordsFlow: Flow<List<CustomWord>> = customWordDao.observeCustomWords()

    val dailyChallengeFlow: Flow<DailyChallengeState> = context.backTalkDataStore.data.map { prefs ->
        val today = LocalDate.now()
        val lastDateStr = prefs[KEY_LAST_DAILY_DATE]
        val lastDate = lastDateStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val completedToday = lastDate == today
        val rawStreak = prefs[KEY_STREAK] ?: 0
        // Keep streak alive if completed today or yesterday; otherwise streak resets to 0
        val effectiveStreak = when {
            lastDate == null -> 0
            lastDate == today || lastDate == today.minusDays(1) -> rawStreak
            else -> 0
        }
        DailyChallengeState(
            date = today,
            dailyWord = dailyWordForDate(today),
            streakCount = effectiveStreak,
            completedToday = completedToday,
            todayScore = if (completedToday) prefs[KEY_TODAY_DAILY_SCORE] else null,
            highScore = prefs[KEY_HIGH_SCORE] ?: 0
        )
    }

    /**
     * Ensures default WordPacks exist in Room on first launch.
     */
    suspend fun ensureSeeded() {
        if (wordPackDao.countPacks() == 0) {
            wordPackDao.insertAll(defaultSeedPacks())
        }
    }

    /**
     * Draws [count] unique words from the selected pack (or mixed across all packs + custom words for Random).
     */
    suspend fun drawWordsForGame(packId: Long?, count: Int = 5): Pair<Long, List<String>> {
        ensureSeeded()
        val allPacks = wordPackDao.getAllPacksOnce()
        val customWords = customWordDao.getAllCustomWordsOnce().map { it.word }
        val chosenPack = packId?.let { id -> allPacks.firstOrNull { it.id == id } }
            ?: allPacks.firstOrNull { it.name.equals("Random", ignoreCase = true) }
            ?: allPacks.first()

        val pool = if (chosenPack.name.equals("Random", ignoreCase = true)) {
            (allPacks.flatMap { WordJsonConverter.fromJsonArray(it.words) } + customWords).distinct()
        } else {
            (WordJsonConverter.fromJsonArray(chosenPack.words) +
                customWords.filter { it.isNotBlank() }).distinct()
        }

        val selected = pool.shuffled().take(count)
        return chosenPack.id to selected
    }

    suspend fun addCustomWord(word: String, category: String): Boolean {
        val clean = word.trim()
        if (clean.isEmpty()) return false
        customWordDao.insertCustomWord(
            CustomWord(
                word = clean,
                category = category.trim().ifEmpty { "Custom" },
                timesUsed = 0,
                avgScore = 0f
            )
        )
        return true
    }

    suspend fun deleteCustomWord(id: Long) {
        customWordDao.deleteById(id)
    }

    suspend fun saveGameRound(round: GameRound) {
        gameRoundDao.insertRound(round)
        // Update custom word usage stats if the word came from CustomWord
        val match = customWordDao.getAllCustomWordsOnce()
            .firstOrNull { it.word.equals(round.originalWord, ignoreCase = true) }
        if (match != null) {
            val scoreUsed = maxOf(round.playerAScore, round.playerBScore).toFloat()
            val nextCount = match.timesUsed + 1
            val nextAvg = ((match.avgScore * match.timesUsed) + scoreUsed) / nextCount
            customWordDao.updateStats(match.id, nextCount, nextAvg)
        }
    }

    suspend fun recordPartyHighScore(totalScore: Int): Boolean {
        var isNewBest = false
        context.backTalkDataStore.edit { prefs ->
            val currentBest = prefs[KEY_HIGH_SCORE] ?: 0
            if (totalScore > currentBest) {
                prefs[KEY_HIGH_SCORE] = totalScore
                isNewBest = true
            }
        }
        return isNewBest
    }

    suspend fun recordDailyChallengeResult(score: Int) {
        val today = LocalDate.now()
        context.backTalkDataStore.edit { prefs ->
            val lastDateStr = prefs[KEY_LAST_DAILY_DATE]
            val lastDate = lastDateStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val currentStreak = prefs[KEY_STREAK] ?: 0
            val updatedStreak = when (lastDate) {
                today -> currentStreak.coerceAtLeast(1)
                today.minusDays(1) -> currentStreak + 1
                else -> 1
            }
            prefs[KEY_STREAK] = updatedStreak
            prefs[KEY_LAST_DAILY_DATE] = today.toString()
            prefs[KEY_TODAY_DAILY_SCORE] = score
        }
    }

    suspend fun getHighScoreSnapshot(): Int {
        return context.backTalkDataStore.data.first()[KEY_HIGH_SCORE] ?: 0
    }
}
