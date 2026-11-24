package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.game.BackTalkViewModel
import com.example.game.GameState
import com.example.ui.GameSummaryScreen
import com.example.ui.GuessScreen
import com.example.ui.HomeScreen
import com.example.ui.PassDeviceScreen
import com.example.ui.RecordScreen
import com.example.ui.ResultScreen
import com.example.ui.WordPackScreen
import com.example.ui.theme.BackTalkTheme

object BackTalkRoutes {
    const val ROUTE_HOME = "home"
    const val ROUTE_WORD_PACKS = "word_packs"
    const val ROUTE_RECORD = "record"
    const val ROUTE_PASS = "pass"
    const val ROUTE_GUESS = "guess"
    const val ROUTE_RESULT = "result"
    const val ROUTE_SUMMARY = "summary"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        setContent {
            BackTalkTheme {
                BackTalkApp()
            }
        }
    }
}

@Composable
fun BackTalkApp(viewModel: BackTalkViewModel = viewModel()) {
    val navController = rememberNavController()
    val gameState by viewModel.gameState.collectAsStateWithLifecycle()
    val wordPacks by viewModel.wordPacks.collectAsStateWithLifecycle()
    val selectedPackId by viewModel.selectedPackId.collectAsStateWithLifecycle()
    val recentRounds by viewModel.recentRounds.collectAsStateWithLifecycle()
    val customWords by viewModel.customWords.collectAsStateWithLifecycle()
    val dailyChallenge by viewModel.dailyChallenge.collectAsStateWithLifecycle()
    val nanoStatus by viewModel.nanoStatus.collectAsStateWithLifecycle()
    val nanoDownloadMb by viewModel.nanoDownloadMb.collectAsStateWithLifecycle()

    // Synchronize Navigation Compose destination cleanly with sealed GameState
    val targetRoute = when (gameState) {
        GameState.Home -> BackTalkRoutes.ROUTE_HOME
        GameState.WordPacks -> BackTalkRoutes.ROUTE_WORD_PACKS
        is GameState.Recording -> BackTalkRoutes.ROUTE_RECORD
        is GameState.PassDevice -> BackTalkRoutes.ROUTE_PASS
        is GameState.Guessing -> BackTalkRoutes.ROUTE_GUESS
        is GameState.RoundResult -> BackTalkRoutes.ROUTE_RESULT
        is GameState.GameSummary -> BackTalkRoutes.ROUTE_SUMMARY
    }

    LaunchedEffect(targetRoute) {
        val currentRoute = navController.currentDestination?.route
        if (currentRoute != targetRoute) {
            navController.navigate(targetRoute) {
                popUpTo(BackTalkRoutes.ROUTE_HOME) { inclusive = (targetRoute == BackTalkRoutes.ROUTE_HOME) }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = BackTalkRoutes.ROUTE_HOME
    ) {
        composable(BackTalkRoutes.ROUTE_HOME) {
            HomeScreen(
                wordPacks = wordPacks,
                selectedPackId = selectedPackId,
                recentRounds = recentRounds,
                dailyChallenge = dailyChallenge,
                nanoStatus = nanoStatus,
                nanoDownloadMb = nanoDownloadMb,
                onSelectPack = viewModel::selectPack,
                onStartNewGame = { viewModel.startNewGame() },
                onStartDailyChallenge = viewModel::startDailyChallenge,
                onOpenWordPacks = viewModel::openWordPacks,
                onDownloadNano = viewModel::downloadGeminiNano
            )
        }

        composable(BackTalkRoutes.ROUTE_WORD_PACKS) {
            WordPackScreen(
                wordPacks = wordPacks,
                selectedPackId = selectedPackId,
                customWords = customWords,
                onSelectAndPlayPack = { packId ->
                    viewModel.selectPack(packId)
                    viewModel.startNewGame(packId = packId)
                },
                onAddCustomWord = viewModel::addCustomWord,
                onDeleteCustomWord = viewModel::deleteCustomWord,
                onBackHome = viewModel::returnHome
            )
        }

        composable(BackTalkRoutes.ROUTE_RECORD) {
            val state = gameState as? GameState.Recording
            if (state != null) {
                AnimatedContent(
                    targetState = state.roundIndex,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "record_round_transition"
                ) {
                    RecordScreen(
                        state = state,
                        onPlayBackwardsGuide = viewModel::playBackwardsWordGuide,
                        onStartRecording = viewModel::startRecording,
                        onStopRecordingEarly = viewModel::stopRecordingEarly,
                        onReplayReversedPreview = viewModel::replayRecordedReversedPreview,
                        onRerecord = viewModel::rerecordCurrentRound,
                        onConfirmAndNext = viewModel::confirmRecordingAndContinue,
                        onBackHome = viewModel::returnHome
                    )
                }
            }
        }

        composable(BackTalkRoutes.ROUTE_PASS) {
            val state = gameState as? GameState.PassDevice
            if (state != null) {
                PassDeviceScreen(
                    state = state,
                    onReadyToGuess = viewModel::startGuessingPhase,
                    onBackHome = viewModel::returnHome
                )
            }
        }

        composable(BackTalkRoutes.ROUTE_GUESS) {
            val state = gameState as? GameState.Guessing
            if (state != null) {
                GuessScreen(
                    state = state,
                    onPlayReversed = viewModel::playGuessReversedAudio,
                    onGuessTextChange = viewModel::updateGuessText,
                    onSubmitGuess = viewModel::submitGuess,
                    onSkip = viewModel::skipRound,
                    onBackHome = viewModel::returnHome
                )
            }
        }

        composable(BackTalkRoutes.ROUTE_RESULT) {
            val state = gameState as? GameState.RoundResult
            if (state != null) {
                ResultScreen(
                    state = state,
                    onPlayAudio = viewModel::playResultAudio,
                    onNextRound = viewModel::advanceFromResult,
                    onBackHome = viewModel::returnHome
                )
            }
        }

        composable(BackTalkRoutes.ROUTE_SUMMARY) {
            val state = gameState as? GameState.GameSummary
            if (state != null) {
                GameSummaryScreen(
                    state = state,
                    onSwapRolesPlayAgain = {
                        viewModel.startNewGame(packId = state.packId, swapRoles = true)
                    },
                    onPlayAgainSameRoles = {
                        viewModel.startNewGame(packId = state.packId, swapRoles = false)
                    },
                    onBackHome = viewModel::returnHome
                )
            }
        }
    }
}
