package com.example.game

import com.example.gemini.GuessRating

/**
 * Sealed hierarchy modeling every screen/stage in the BackTalk 5-round pass-and-play game flow.
 */
sealed class GameState {
    data object Home : GameState()

    data object WordPacks : GameState()

    data class Recording(
        val roundIndex: Int,
        val totalRounds: Int,
        val word: String,
        val packId: Long,
        val packName: String,
        val isRecording: Boolean = false,
        val elapsedMs: Long = 0L,
        val amplitudes: List<Float> = emptyList(),
        val recordedPcm: ShortArray? = null,
        val reversedPcm: ShortArray? = null,
        val reversedAudioPath: String = "",
        val isPlayingPreview: Boolean = false,
        val playbackProgress: Float = 0f,
        val isDailyChallenge: Boolean = false,
        val recorderLabel: String = "Player A",
        val guesserLabel: String = "Player B",
        val errorMessage: String? = null
    ) : GameState()

    data class PassDevice(
        val recordedRoundsCount: Int,
        val nextPlayerLabel: String,
        val recorderLabel: String,
        val isDailyChallenge: Boolean
    ) : GameState()

    data class Guessing(
        val roundIndex: Int,
        val totalRounds: Int,
        val replaysRemaining: Int = 3,
        val maxReplays: Int = 3,
        val guessText: String = "",
        val isPlayingAudio: Boolean = false,
        val playbackProgress: Float = 0f,
        val amplitudes: List<Float> = emptyList(),
        val isChecking: Boolean = false,
        val isDailyChallenge: Boolean = false,
        val guesserLabel: String = "Player B"
    ) : GameState()

    data class RoundResult(
        val roundIndex: Int,
        val totalRounds: Int,
        val originalWord: String,
        val playerGuess: String,
        val rating: GuessRating,
        val score: Int,
        val reason: String,
        val usedGeminiNano: Boolean,
        val isPlayingReversed: Boolean = false,
        val isPlayingOriginal: Boolean = false,
        val playbackProgress: Float = 0f,
        val amplitudes: List<Float> = emptyList(),
        val isDailyChallenge: Boolean = false,
        val guesserLabel: String = "Player B"
    ) : GameState()

    data class GameSummary(
        val totalScore: Int,
        val maxPossibleScore: Int,
        val roundResults: List<CompletedRoundSummary>,
        val highScore: Int,
        val isNewHighScore: Boolean,
        val recorderLabel: String,
        val guesserLabel: String,
        val packId: Long,
        val packName: String
    ) : GameState()
}

data class RecordedRoundClip(
    val originalWord: String,
    val packId: Long,
    val originalPcm: ShortArray,
    val reversedPcm: ShortArray,
    val reversedAudioPath: String,
    val amplitudes: List<Float>
)

data class CompletedRoundSummary(
    val roundNumber: Int,
    val originalWord: String,
    val guess: String,
    val rating: GuessRating,
    val score: Int,
    val reason: String
)
