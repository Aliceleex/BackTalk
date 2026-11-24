package com.example.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioRecorder
import com.example.audio.WordAudioSynthesizer
import com.example.audio.extractWaveformAmplitudes
import com.example.audio.reverseAudio
import com.example.gemini.EvaluatedGuess
import com.example.gemini.GuessChecker
import com.example.gemini.GuessRating
import com.example.gemini.NanoModelStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Central ViewModel managing BackTalk's 5-round game loop, audio recording/reversal/playback,
 * Gemini Nano scoring, Room persistence, and role swapping between games.
 */
class BackTalkViewModel(application: Application) : AndroidViewModel(application) {

    private val database = BackTalkDatabase.getInstance(application)
    private val repository = WordRepository(
        context = application,
        wordPackDao = database.wordPackDao(),
        gameRoundDao = database.gameRoundDao(),
        customWordDao = database.customWordDao()
    )
    private val audioRecorder = AudioRecorder(application)
    private val wordAudioSynthesizer = WordAudioSynthesizer(application)
    private val guessChecker = GuessChecker()

    val wordPacks: StateFlow<List<WordPack>> = repository.wordPacksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentRounds: StateFlow<List<GameRound>> = repository.recentRoundsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customWords: StateFlow<List<CustomWord>> = repository.customWordsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyChallenge: StateFlow<DailyChallengeState> = repository.dailyChallengeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DailyChallengeState())

    val nanoStatus: StateFlow<NanoModelStatus> = guessChecker.modelStatus
    val nanoDownloadMb: StateFlow<Long?> = guessChecker.downloadProgressMb

    private val _selectedPackId = MutableStateFlow<Long?>(null)
    val selectedPackId: StateFlow<Long?> = _selectedPackId.asStateFlow()

    private val _gameState = MutableStateFlow<GameState>(GameState.Home)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    // Active game session state
    private var sessionWords: List<String> = emptyList()
    private var sessionPackId: Long = 1L
    private var sessionPackName: String = "Random"
    private var sessionIsDaily: Boolean = false
    private var recorderPlayer: String = "Player A"
    private var guesserPlayer: String = "Player B"
    private val recordedClips = mutableListOf<RecordedRoundClip>()
    private val completedSummaries = mutableListOf<CompletedRoundSummary>()

    private var activeRecordingJob: Job? = null
    private var activePlaybackJob: Job? = null
    private var activeGuideJob: Job? = null

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
            val firstPack = wordPacks.value.firstOrNull()
            if (_selectedPackId.value == null && firstPack != null) {
                _selectedPackId.value = firstPack.id
            }
        }
        viewModelScope.launch {
            guessChecker.refreshStatus()
        }
    }

    fun selectPack(packId: Long) {
        _selectedPackId.value = packId
    }

    fun downloadGeminiNano() {
        viewModelScope.launch {
            guessChecker.downloadNanoModel()
        }
    }

    fun openWordPacks() {
        stopAudioJobs()
        _gameState.value = GameState.WordPacks
    }

    fun returnHome() {
        stopAudioJobs()
        _gameState.value = GameState.Home
    }

    fun addCustomWord(word: String, category: String) {
        viewModelScope.launch {
            repository.addCustomWord(word, category)
        }
    }

    fun deleteCustomWord(id: Long) {
        viewModelScope.launch {
            repository.deleteCustomWord(id)
        }
    }

    /**
     * Starts a standard 5-round party game (Player A records all 5, then passes to Player B).
     */
    fun startNewGame(packId: Long? = _selectedPackId.value, swapRoles: Boolean = false) {
        stopAudioJobs()
        viewModelScope.launch {
            if (swapRoles) {
                val prevRecorder = recorderPlayer
                recorderPlayer = guesserPlayer
                guesserPlayer = prevRecorder
            } else {
                recorderPlayer = "Player A"
                guesserPlayer = "Player B"
            }

            val (resolvedId, words) = repository.drawWordsForGame(packId, count = 5)
            val packName = wordPacks.value.firstOrNull { it.id == resolvedId }?.name ?: "Random"
            _selectedPackId.value = resolvedId

            sessionPackId = resolvedId
            sessionPackName = packName
            sessionWords = words
            sessionIsDaily = false
            recordedClips.clear()
            completedSummaries.clear()

            val firstWord = words.firstOrNull() ?: "pizza"
            _gameState.value = GameState.Recording(
                roundIndex = 0,
                totalRounds = words.size,
                word = firstWord,
                packId = resolvedId,
                packName = packName,
                isPreparingGuide = true,
                isDailyChallenge = false,
                recorderLabel = recorderPlayer,
                guesserLabel = guesserPlayer
            )
            prepareAndPlayBackwardsGuide(roundIndex = 0, word = firstWord)
        }
    }

    /**
     * Starts the 1-round Daily Challenge for today's global word.
     */
    fun startDailyChallenge() {
        stopAudioJobs()
        val dailyWord = dailyChallenge.value.dailyWord
        sessionPackId = 0L
        sessionPackName = "Daily Challenge"
        sessionWords = listOf(dailyWord)
        sessionIsDaily = true
        recorderPlayer = "Player A"
        guesserPlayer = "Player B"
        recordedClips.clear()
        completedSummaries.clear()

        _gameState.value = GameState.Recording(
            roundIndex = 0,
            totalRounds = 1,
            word = dailyWord,
            packId = 0L,
            packName = "Daily Challenge",
            isPreparingGuide = true,
            isDailyChallenge = true,
            recorderLabel = recorderPlayer,
            guesserLabel = guesserPlayer
        )
        prepareAndPlayBackwardsGuide(roundIndex = 0, word = dailyWord)
    }

    private fun prepareAndPlayBackwardsGuide(roundIndex: Int, word: String) {
        activeGuideJob?.cancel()
        activeGuideJob = viewModelScope.launch {
            val synthesized = wordAudioSynthesizer.synthesizeReversedWord(word)
            val latest = _gameState.value as? GameState.Recording ?: return@launch
            if (latest.roundIndex != roundIndex || latest.isRecording) return@launch

            if (synthesized == null) {
                _gameState.value = latest.copy(
                    isPreparingGuide = false,
                    guideUnavailable = true
                )
            } else {
                _gameState.value = latest.copy(
                    isPreparingGuide = false,
                    guideReversedPcm = synthesized.reversedPcm,
                    guideAmplitudes = synthesized.reversedAmplitudes,
                    guideUnavailable = false
                )
                playGuideInternal(roundIndex, synthesized.reversedPcm)
            }
        }
    }

    fun playBackwardsWordGuide() {
        val current = _gameState.value as? GameState.Recording ?: return
        if (current.isRecording) return
        val guidePcm = current.guideReversedPcm
        if (guidePcm == null) {
            _gameState.value = current.copy(isPreparingGuide = true, guideUnavailable = false)
            prepareAndPlayBackwardsGuide(current.roundIndex, current.word)
            return
        }
        activePlaybackJob?.cancel()
        audioRecorder.requestStopPlayback()
        activePlaybackJob = viewModelScope.launch {
            playGuideInternal(current.roundIndex, guidePcm)
        }
    }

    private suspend fun playGuideInternal(roundIndex: Int, guidePcm: ShortArray) {
        val stateBefore = _gameState.value as? GameState.Recording ?: return
        if (stateBefore.roundIndex != roundIndex || stateBefore.isRecording) return
        _gameState.value = stateBefore.copy(
            isPlayingGuide = true,
            isPlayingPreview = false,
            playbackProgress = 0f
        )
        try {
            audioRecorder.playPcm(guidePcm) { progress ->
                val latest = _gameState.value as? GameState.Recording
                if (latest != null && latest.isPlayingGuide && latest.roundIndex == roundIndex) {
                    _gameState.value = latest.copy(playbackProgress = progress)
                }
            }
        } finally {
            val latest = _gameState.value as? GameState.Recording
            if (latest != null && latest.roundIndex == roundIndex) {
                _gameState.value = latest.copy(isPlayingGuide = false, playbackProgress = 0f)
            }
        }
    }

    /**
     * Starts capturing 16-bit PCM audio via AudioRecord (auto-stops at 3.0 seconds).
     * Automatically reverses PCM and plays reversed audio preview when finished.
     */
    fun startRecording() {
        val current = _gameState.value as? GameState.Recording ?: return
        if (current.isRecording) return
        stopAudioJobs()

        activeRecordingJob = viewModelScope.launch {
            val liveBars = MutableList(36) { 0.08f }
            _gameState.value = current.copy(
                isPlayingGuide = false,
                isRecording = true,
                elapsedMs = 0L,
                amplitudes = liveBars.toList(),
                recordedPcm = null,
                reversedPcm = null,
                isPlayingPreview = false,
                playbackProgress = 0f,
                errorMessage = null
            )

            try {
                var barCursor = 0
                val rawPcm = audioRecorder.recordPcm { elapsedMs, amp ->
                    liveBars[barCursor % liveBars.size] = amp
                    barCursor++
                    val latest = _gameState.value as? GameState.Recording
                    if (latest != null && latest.isRecording) {
                        _gameState.value = latest.copy(
                            elapsedMs = elapsedMs,
                            amplitudes = liveBars.toList()
                        )
                    }
                }

                if (rawPcm.isEmpty()) {
                    val latest = _gameState.value as? GameState.Recording ?: return@launch
                    _gameState.value = latest.copy(
                        isRecording = false,
                        errorMessage = "No audio captured. Tap mic and speak clearly."
                    )
                    return@launch
                }

                val reversed = reverseAudio(rawPcm)
                val waveform = extractWaveformAmplitudes(reversed, barCount = 36)
                val savedPath = audioRecorder.saveReversedAudio(
                    pcm = reversed,
                    roundTag = "r${current.roundIndex + 1}"
                )

                val updated = (_gameState.value as? GameState.Recording)?.copy(
                    isRecording = false,
                    elapsedMs = AudioRecorder.MAX_DURATION_MS,
                    amplitudes = waveform,
                    recordedPcm = rawPcm,
                    reversedPcm = reversed,
                    reversedAudioPath = savedPath,
                    isPlayingPreview = true,
                    playbackProgress = 0f,
                    errorMessage = null
                ) ?: return@launch
                _gameState.value = updated

                // Auto-play reversed audio preview immediately after recording
                playPreviewReversedInternal(reversed)
            } catch (se: SecurityException) {
                val latest = _gameState.value as? GameState.Recording ?: return@launch
                _gameState.value = latest.copy(
                    isRecording = false,
                    errorMessage = "Microphone permission is required to record."
                )
            } catch (t: Throwable) {
                val latest = _gameState.value as? GameState.Recording ?: return@launch
                _gameState.value = latest.copy(
                    isRecording = false,
                    errorMessage = t.message ?: "Unable to access microphone."
                )
            }
        }
    }

    fun stopRecordingEarly() {
        audioRecorder.requestStopRecording()
    }

    fun replayRecordedReversedPreview() {
        val current = _gameState.value as? GameState.Recording ?: return
        val reversed = current.reversedPcm ?: return
        activePlaybackJob?.cancel()
        activePlaybackJob = viewModelScope.launch {
            playPreviewReversedInternal(reversed)
        }
    }

    private suspend fun playPreviewReversedInternal(reversed: ShortArray) {
        val stateBefore = _gameState.value as? GameState.Recording ?: return
        _gameState.value = stateBefore.copy(
            isPlayingPreview = true,
            isPlayingGuide = false,
            playbackProgress = 0f
        )
        try {
            audioRecorder.playPcm(reversed) { progress ->
                val latest = _gameState.value as? GameState.Recording
                if (latest != null && latest.isPlayingPreview) {
                    _gameState.value = latest.copy(playbackProgress = progress)
                }
            }
        } finally {
            val latest = _gameState.value as? GameState.Recording
            if (latest != null) {
                _gameState.value = latest.copy(isPlayingPreview = false, playbackProgress = 0f)
            }
        }
    }

    fun rerecordCurrentRound() {
        val current = _gameState.value as? GameState.Recording ?: return
        stopAudioJobs()
        _gameState.value = current.copy(
            isPlayingGuide = false,
            isRecording = false,
            elapsedMs = 0L,
            amplitudes = emptyList(),
            recordedPcm = null,
            reversedPcm = null,
            reversedAudioPath = "",
            isPlayingPreview = false,
            playbackProgress = 0f,
            errorMessage = null
        )
    }

    /**
     * Locks in the current recording and advances either to the next recording word
     * or to the PassDevice handoff screen so Player B can start guessing.
     */
    fun confirmRecordingAndContinue(passImmediately: Boolean = false) {
        val current = _gameState.value as? GameState.Recording ?: return
        val original = current.recordedPcm ?: return
        val reversed = current.reversedPcm ?: return
        stopAudioJobs()

        recordedClips.add(
            RecordedRoundClip(
                originalWord = current.word,
                packId = current.packId,
                originalPcm = original,
                reversedPcm = reversed,
                guideReversedPcm = current.guideReversedPcm,
                reversedAudioPath = current.reversedAudioPath,
                amplitudes = current.amplitudes
            )
        )

        val nextIdx = current.roundIndex + 1
        if (!passImmediately && nextIdx < sessionWords.size) {
            val nextWord = sessionWords[nextIdx]
            _gameState.value = GameState.Recording(
                roundIndex = nextIdx,
                totalRounds = sessionWords.size,
                word = nextWord,
                packId = sessionPackId,
                packName = sessionPackName,
                isPreparingGuide = true,
                isDailyChallenge = sessionIsDaily,
                recorderLabel = recorderPlayer,
                guesserLabel = guesserPlayer
            )
            prepareAndPlayBackwardsGuide(roundIndex = nextIdx, word = nextWord)
        } else {
            _gameState.value = GameState.PassDevice(
                recordedRoundsCount = recordedClips.size,
                nextPlayerLabel = guesserPlayer,
                recorderLabel = recorderPlayer,
                isDailyChallenge = sessionIsDaily
            )
        }
    }

    fun startGuessingPhase() {
        if (recordedClips.isEmpty()) return
        stopAudioJobs()
        val firstClip = recordedClips.first()
        _gameState.value = GameState.Guessing(
            roundIndex = 0,
            totalRounds = recordedClips.size,
            replaysRemaining = 3,
            maxReplays = 3,
            amplitudes = firstClip.amplitudes,
            isDailyChallenge = sessionIsDaily,
            guesserLabel = guesserPlayer
        )
    }

    fun updateGuessText(text: String) {
        val current = _gameState.value as? GameState.Guessing ?: return
        _gameState.value = current.copy(guessText = text)
    }

    /**
     * Plays the reversed audio clip for Player B (up to 3 replays allowed per round).
     */
    fun playGuessReversedAudio() {
        val current = _gameState.value as? GameState.Guessing ?: return
        if (current.isPlayingAudio || current.replaysRemaining <= 0) return
        val clip = recordedClips.getOrNull(current.roundIndex) ?: return

        activePlaybackJob?.cancel()
        activePlaybackJob = viewModelScope.launch {
            _gameState.value = current.copy(
                replaysRemaining = current.replaysRemaining - 1,
                isPlayingAudio = true,
                playbackProgress = 0f
            )
            try {
                audioRecorder.playPcm(clip.reversedPcm) { progress ->
                    val latest = _gameState.value as? GameState.Guessing
                    if (latest != null && latest.isPlayingAudio) {
                        _gameState.value = latest.copy(playbackProgress = progress)
                    }
                }
            } finally {
                val latest = _gameState.value as? GameState.Guessing
                if (latest != null) {
                    _gameState.value = latest.copy(isPlayingAudio = false, playbackProgress = 0f)
                }
            }
        }
    }

    fun submitGuess() {
        val current = _gameState.value as? GameState.Guessing ?: return
        if (current.isChecking || current.guessText.isBlank()) return
        val clip = recordedClips.getOrNull(current.roundIndex) ?: return
        stopAudioJobs()

        viewModelScope.launch {
            _gameState.value = current.copy(isChecking = true)
            val evaluation: EvaluatedGuess = guessChecker.checkGuess(
                originalWord = clip.originalWord,
                playerGuess = current.guessText
            )
            finalizeRoundEvaluation(
                roundIndex = current.roundIndex,
                clip = clip,
                guessText = current.guessText.trim(),
                evaluation = evaluation
            )
        }
    }

    fun skipRound() {
        val current = _gameState.value as? GameState.Guessing ?: return
        if (current.isChecking) return
        val clip = recordedClips.getOrNull(current.roundIndex) ?: return
        stopAudioJobs()

        viewModelScope.launch {
            val evaluation = EvaluatedGuess(
                rating = GuessRating.WRONG,
                score = 0,
                reason = "Round skipped",
                usedGeminiNano = false
            )
            finalizeRoundEvaluation(
                roundIndex = current.roundIndex,
                clip = clip,
                guessText = "(Skipped)",
                evaluation = evaluation
            )
        }
    }

    private suspend fun finalizeRoundEvaluation(
        roundIndex: Int,
        clip: RecordedRoundClip,
        guessText: String,
        evaluation: EvaluatedGuess
    ) {
        val isPlayerBGuessing = guesserPlayer == "Player B"
        val gameRound = GameRound(
            wordPackId = clip.packId,
            originalWord = clip.originalWord,
            reversedAudioPath = clip.reversedAudioPath,
            playerAGuess = if (isPlayerBGuessing) "" else guessText,
            playerBGuess = if (isPlayerBGuessing) guessText else "",
            playerAScore = if (isPlayerBGuessing) 0 else evaluation.score,
            playerBScore = if (isPlayerBGuessing) evaluation.score else 0,
            completedAt = Instant.now().toEpochMilli()
        )
        repository.saveGameRound(gameRound)

        if (sessionIsDaily) {
            repository.recordDailyChallengeResult(evaluation.score)
        }

        completedSummaries.add(
            CompletedRoundSummary(
                roundNumber = roundIndex + 1,
                originalWord = clip.originalWord,
                guess = guessText,
                rating = evaluation.rating,
                score = evaluation.score,
                reason = evaluation.reason
            )
        )

        _gameState.value = GameState.RoundResult(
            roundIndex = roundIndex,
            totalRounds = recordedClips.size,
            originalWord = clip.originalWord,
            playerGuess = guessText,
            rating = evaluation.rating,
            score = evaluation.score,
            reason = evaluation.reason,
            usedGeminiNano = evaluation.usedGeminiNano,
            hasGuideAudio = clip.guideReversedPcm != null,
            amplitudes = clip.amplitudes,
            isDailyChallenge = sessionIsDaily,
            guesserLabel = guesserPlayer
        )

        // Automatically play flipped recording (what Player B guessed from) on reveal screen
        playResultAudio(ResultAudioMode.FLIPPED_FORWARD)
    }

    fun playResultAudio(mode: ResultAudioMode) {
        val current = _gameState.value as? GameState.RoundResult ?: return
        val clip = recordedClips.getOrNull(current.roundIndex) ?: return
        val pcm = when (mode) {
            ResultAudioMode.FLIPPED_FORWARD -> clip.reversedPcm
            ResultAudioMode.RAW_MIMIC -> clip.originalPcm
            ResultAudioMode.TARGET_BACKWARDS -> clip.guideReversedPcm ?: return
        }
        activePlaybackJob?.cancel()
        activePlaybackJob = viewModelScope.launch {
            _gameState.value = current.copy(
                isPlayingReversed = mode == ResultAudioMode.FLIPPED_FORWARD,
                isPlayingOriginal = mode == ResultAudioMode.RAW_MIMIC,
                isPlayingGuide = mode == ResultAudioMode.TARGET_BACKWARDS,
                playbackProgress = 0f
            )
            try {
                audioRecorder.playPcm(pcm) { progress ->
                    val latest = _gameState.value as? GameState.RoundResult
                    if (latest != null) {
                        _gameState.value = latest.copy(playbackProgress = progress)
                    }
                }
            } finally {
                val latest = _gameState.value as? GameState.RoundResult
                if (latest != null) {
                    _gameState.value = latest.copy(
                        isPlayingReversed = false,
                        isPlayingOriginal = false,
                        isPlayingGuide = false,
                        playbackProgress = 0f
                    )
                }
            }
        }
    }

    fun advanceFromResult() {
        val current = _gameState.value as? GameState.RoundResult ?: return
        stopAudioJobs()
        val nextIdx = current.roundIndex + 1
        if (nextIdx < recordedClips.size) {
            val nextClip = recordedClips[nextIdx]
            _gameState.value = GameState.Guessing(
                roundIndex = nextIdx,
                totalRounds = recordedClips.size,
                replaysRemaining = 3,
                maxReplays = 3,
                amplitudes = nextClip.amplitudes,
                isDailyChallenge = sessionIsDaily,
                guesserLabel = guesserPlayer
            )
        } else if (sessionIsDaily) {
            _gameState.value = GameState.Home
        } else {
            viewModelScope.launch {
                val totalScore = completedSummaries.sumOf { it.score }
                val isNewHigh = repository.recordPartyHighScore(totalScore)
                val high = repository.getHighScoreSnapshot()
                _gameState.value = GameState.GameSummary(
                    totalScore = totalScore,
                    maxPossibleScore = recordedClips.size * 100,
                    roundResults = completedSummaries.toList(),
                    highScore = high,
                    isNewHighScore = isNewHigh,
                    recorderLabel = recorderPlayer,
                    guesserLabel = guesserPlayer,
                    packId = sessionPackId,
                    packName = sessionPackName
                )
            }
        }
    }

    private fun stopAudioJobs() {
        audioRecorder.requestStopRecording()
        audioRecorder.requestStopPlayback()
        activeRecordingJob?.cancel()
        activePlaybackJob?.cancel()
        activeGuideJob?.cancel()
        activeRecordingJob = null
        activePlaybackJob = null
        activeGuideJob = null
    }

    override fun onCleared() {
        stopAudioJobs()
        wordAudioSynthesizer.shutdown()
        guessChecker.close()
        super.onCleared()
    }
}

enum class ResultAudioMode {
    FLIPPED_FORWARD,
    RAW_MIMIC,
    TARGET_BACKWARDS
}
