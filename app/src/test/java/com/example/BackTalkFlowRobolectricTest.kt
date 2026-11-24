package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioRecorder
import com.example.audio.reverseAudio
import com.example.game.BackTalkDatabase
import com.example.game.DailyChallengeState
import com.example.game.GameState
import com.example.game.WordRepository
import com.example.gemini.GuessRating
import com.example.gemini.NanoModelStatus
import com.example.ui.GuessScreen
import com.example.ui.HomeScreen
import com.example.ui.RecordScreen
import com.example.ui.ResultScreen
import com.example.ui.theme.BackTalkTheme
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end Robolectric flow test verifying:
 * - Room seeding & word pack drawing
 * - Audio reversal + local PCM save/load round-trip (record -> reverse -> playback flow)
 * - Compose UI flow across Home -> Record -> Guess -> Result inside a single setContent root
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackTalkFlowRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `record reverse save load playback and Room word pack flow`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, BackTalkDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val repo = WordRepository(
                context = context,
                wordPackDao = db.wordPackDao(),
                gameRoundDao = db.gameRoundDao(),
                customWordDao = db.customWordDao()
            )
            repo.ensureSeeded()
            val (packId, drawnWords) = repo.drawWordsForGame(packId = null, count = 5)
            assertEquals(5, drawnWords.size)
            assertTrue(packId > 0L)

            // Simulate captured 16-bit PCM audio -> reverse -> save locally -> reload for AudioTrack
            val recorder = AudioRecorder(context)
            val simulatedRecording = shortArrayOf(120, -450, 980, 14500, -22000, 6400)
            val reversed = reverseAudio(simulatedRecording)
            val savedPath = recorder.saveReversedAudio(reversed, roundTag = "test")
            assertTrue(File(savedPath).exists())

            val loaded = recorder.loadPcmFromFile(savedPath)
            assertArrayEquals(reversed, loaded)
            File(savedPath).delete()
        } finally {
            db.close()
        }
    }

    @Test
    fun `compose ui screens render and transition through BackTalk game loop`() {
        var step by mutableIntStateOf(0)

        composeRule.setContent {
            BackTalkTheme {
                when (step) {
                    0 -> HomeScreen(
                        wordPacks = WordRepository.defaultSeedPacks(1L),
                        selectedPackId = 1L,
                        recentRounds = emptyList(),
                        dailyChallenge = DailyChallengeState(),
                        nanoStatus = NanoModelStatus.AVAILABLE,
                        nanoDownloadMb = null,
                        onSelectPack = {},
                        onStartNewGame = { step = 1 },
                        onStartDailyChallenge = {},
                        onOpenWordPacks = {},
                        onDownloadNano = {}
                    )

                    1 -> RecordScreen(
                        state = GameState.Recording(
                            roundIndex = 0,
                            totalRounds = 5,
                            word = "pizza",
                            packId = 1L,
                            packName = "Food",
                            guideReversedPcm = shortArrayOf(9, 8, 7),
                            recordedPcm = shortArrayOf(1, 2, 3),
                            reversedPcm = shortArrayOf(3, 2, 1),
                            amplitudes = listOf(0.2f, 0.8f, 0.4f)
                        ),
                        onPlayBackwardsGuide = {},
                        onStartRecording = {},
                        onStopRecordingEarly = {},
                        onReplayReversedPreview = {},
                        onRerecord = {},
                        onConfirmAndNext = { step = 2 },
                        onBackHome = {}
                    )

                    2 -> GuessScreen(
                        state = GameState.Guessing(
                            roundIndex = 0,
                            totalRounds = 5,
                            replaysRemaining = 3,
                            guessText = "pizza",
                            amplitudes = listOf(0.3f, 0.7f)
                        ),
                        onPlayReversed = {},
                        onGuessTextChange = {},
                        onSubmitGuess = { step = 3 },
                        onSkip = {},
                        onBackHome = {}
                    )

                    else -> ResultScreen(
                        state = GameState.RoundResult(
                            roundIndex = 0,
                            totalRounds = 5,
                            originalWord = "pizza",
                            playerGuess = "pizza",
                            rating = GuessRating.EXACT,
                            score = 100,
                            reason = "Exact spelling match!",
                            usedGeminiNano = true,
                            amplitudes = listOf(0.5f, 0.9f)
                        ),
                        onPlayAudio = {},
                        onNextRound = {},
                        onBackHome = {}
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_game_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("pizza").assertIsDisplayed()
        composeRule.onNodeWithTag("hear_backwards_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("rerecord_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("pass_to_friend_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("submit_guess_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("original_word_reveal").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("share_result_button").performScrollTo().assertIsDisplayed()
    }
}
