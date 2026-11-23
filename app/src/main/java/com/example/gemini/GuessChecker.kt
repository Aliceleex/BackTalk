package com.example.gemini

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.min

enum class GuessRating(val points: Int, val label: String) {
    EXACT(100, "EXACT"),
    CLOSE(60, "CLOSE"),
    WRONG(0, "WRONG")
}

@Generable("Guess scoring")
data class ScoringResult(
    @Guide(description = "EXACT/CLOSE/WRONG", enumValues = ["EXACT", "CLOSE", "WRONG"])
    val rating: String = "WRONG",
    @Guide(description = "Score 0-100", minimum = 0.0, maximum = 100.0)
    val score: Int = 0,
    @Guide(description = "Short reason under 10 words")
    val reason: String = ""
)

data class EvaluatedGuess(
    val rating: GuessRating,
    val score: Int,
    val reason: String,
    val usedGeminiNano: Boolean
)

enum class NanoModelStatus {
    CHECKING,
    AVAILABLE,
    DOWNLOADABLE,
    DOWNLOADING,
    UNAVAILABLE
}

/**
 * Pure scoring normalization and local semantic matcher utilities.
 * Enforces BackTalk's canonical point rules: EXACT = 100, CLOSE = 60, WRONG = 0.
 */
object GuessScoringRules {

    private val COMMON_SYNONYMS: Map<String, Set<String>> = mapOf(
        "christmas" to setOf("xmas", "noel", "yuletide", "holiday"),
        "pizza" to setOf("pie", "pepperoni", "slice"),
        "cat" to setOf("kitty", "kitten", "feline"),
        "dog" to setOf("puppy", "pup", "hound", "doggy"),
        "hamburger" to setOf("burger", "cheeseburger", "patty"),
        "helicopter" to setOf("chopper", "heli"),
        "worcestershire" to setOf("worcester", "sauce"),
        "bicycle" to setOf("bike", "cycle"),
        "microphone" to setOf("mic"),
        "astronaut" to setOf("spaceman", "cosmonaut"),
        "television" to setOf("tv", "telly")
    )

    private val EXACT_VARIANTS: Map<String, Set<String>> = mapOf(
        "christmas" to setOf("xmas", "x-mas"),
        "hamburger" to setOf("burger"),
        "microphone" to setOf("mic"),
        "television" to setOf("tv"),
        "bicycle" to setOf("bike")
    )

    fun normalizeRating(rawRating: String): GuessRating {
        val cleaned = rawRating.trim().uppercase()
        return when {
            cleaned.contains("EXACT") -> GuessRating.EXACT
            cleaned.contains("CLOSE") -> GuessRating.CLOSE
            else -> GuessRating.WRONG
        }
    }

    /**
     * Maps raw AI or parsed rating strings into BackTalk's canonical score (100 / 60 / 0).
     */
    fun normalizeScoringResult(raw: ScoringResult, usedGeminiNano: Boolean): EvaluatedGuess {
        val rating = normalizeRating(raw.rating)
        val canonicalScore = rating.points
        val reason = raw.reason.trim().ifEmpty {
            when (rating) {
                GuessRating.EXACT -> "Spot-on match!"
                GuessRating.CLOSE -> "Close meaning or spelling"
                GuessRating.WRONG -> "Not quite the word"
            }
        }
        return EvaluatedGuess(
            rating = rating,
            score = canonicalScore,
            reason = reason,
            usedGeminiNano = usedGeminiNano
        )
    }

    /**
     * Parses free-form Gemini Nano text output when Structured Output isn't supported on-device.
     */
    fun parseRawTextResponse(text: String): ScoringResult {
        val upper = text.uppercase()
        val rating = when {
            "EXACT" in upper -> "EXACT"
            "CLOSE" in upper -> "CLOSE"
            else -> "WRONG"
        }
        val score = normalizeRating(rating).points
        val reasonLine = text.lines()
            .firstOrNull { it.contains("reason", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.take(60)
            ?.ifEmpty { null }
            ?: text.trim().take(48).ifEmpty { "Scored by Gemini Nano" }
        return ScoringResult(rating = rating, score = score, reason = reasonLine)
    }

    /**
     * Deterministic on-device fallback judge used only when Gemini Nano AICore is UNAVAILABLE.
     */
    fun evaluateLocally(originalWord: String, playerGuess: String): EvaluatedGuess {
        val target = normalizeText(originalWord)
        val guess = normalizeText(playerGuess)

        if (guess.isEmpty()) {
            return EvaluatedGuess(GuessRating.WRONG, 0, "No guess entered", usedGeminiNano = false)
        }
        if (target == guess) {
            return EvaluatedGuess(GuessRating.EXACT, 100, "Exact spelling match!", usedGeminiNano = false)
        }
        if (EXACT_VARIANTS[target]?.contains(guess) == true ||
            EXACT_VARIANTS[guess]?.contains(target) == true
        ) {
            return EvaluatedGuess(GuessRating.EXACT, 100, "Recognized common variant!", usedGeminiNano = false)
        }

        // Check plural / singular suffix match
        if (target + "s" == guess || guess + "s" == target ||
            target + "es" == guess || guess + "es" == target
        ) {
            return EvaluatedGuess(GuessRating.EXACT, 100, "Plural/singular match!", usedGeminiNano = false)
        }

        // Check known close synonyms
        val isSynonym = COMMON_SYNONYMS[target]?.contains(guess) == true ||
            COMMON_SYNONYMS.entries.any { (k, v) -> k == guess && v.contains(target) }
        if (isSynonym) {
            return EvaluatedGuess(GuessRating.CLOSE, 60, "Close synonym match!", usedGeminiNano = false)
        }

        // Check near-miss Levenshtein edit distance for tricky spelling words like Worcestershire
        val distance = levenshtein(target, guess)
        val allowedTypo = if (target.length >= 9) 3 else if (target.length >= 5) 2 else 1
        if (distance <= allowedTypo) {
            return EvaluatedGuess(GuessRating.CLOSE, 60, "Very close spelling!", usedGeminiNano = false)
        }

        // Substring overlap for multi-syllable words
        if (guess.length >= 4 && (target.contains(guess) || guess.contains(target))) {
            return EvaluatedGuess(GuessRating.CLOSE, 60, "Partial word match!", usedGeminiNano = false)
        }

        return EvaluatedGuess(GuessRating.WRONG, 0, "Different word from '$originalWord'", usedGeminiNano = false)
    }

    private fun normalizeText(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = i - 1
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }
}

/**
 * On-device guess judge powered by ML Kit GenAI Prompt API (Gemini Nano via AICore).
 * Falls back gracefully to local matching if the device does not support AICore / Gemini Nano.
 */
class GuessChecker {

    companion object {
        private const val TAG = "GuessChecker"
    }

    private val generativeModel: GenerativeModel by lazy { Generation.getClient() }

    private val _modelStatus = MutableStateFlow(NanoModelStatus.CHECKING)
    val modelStatus: StateFlow<NanoModelStatus> = _modelStatus.asStateFlow()

    private val _downloadProgressMb = MutableStateFlow<Long?>(null)
    val downloadProgressMb: StateFlow<Long?> = _downloadProgressMb.asStateFlow()

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        try {
            val status = generativeModel.checkStatus()
            _modelStatus.value = when (status) {
                FeatureStatus.AVAILABLE -> NanoModelStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> NanoModelStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> NanoModelStatus.DOWNLOADING
                else -> NanoModelStatus.UNAVAILABLE
            }
            if (status == FeatureStatus.AVAILABLE) {
                runCatching { generativeModel.warmup() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Gemini Nano status check unavailable on this device: ${t.message}")
            _modelStatus.value = NanoModelStatus.UNAVAILABLE
        }
    }

    suspend fun downloadNanoModel() = withContext(Dispatchers.IO) {
        try {
            _modelStatus.value = NanoModelStatus.DOWNLOADING
            generativeModel.download().collect { dlStatus ->
                when (dlStatus) {
                    is DownloadStatus.DownloadStarted -> _downloadProgressMb.value = 0L
                    is DownloadStatus.DownloadProgress ->
                        _downloadProgressMb.value = dlStatus.totalBytesDownloaded / (1024L * 1024L)
                    DownloadStatus.DownloadCompleted -> {
                        _downloadProgressMb.value = null
                        _modelStatus.value = NanoModelStatus.AVAILABLE
                        runCatching { generativeModel.warmup() }
                    }
                    is DownloadStatus.DownloadFailed -> {
                        Log.e(TAG, "Gemini Nano download failed: ${dlStatus.e.message}")
                        _downloadProgressMb.value = null
                        _modelStatus.value = NanoModelStatus.UNAVAILABLE
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Gemini Nano download error: ${t.message}")
            _downloadProgressMb.value = null
            _modelStatus.value = NanoModelStatus.UNAVAILABLE
        }
    }

    /**
     * Checks whether [playerGuess] matches [originalWord] semantically using on-device Gemini Nano.
     */
    suspend fun checkGuess(originalWord: String, playerGuess: String): EvaluatedGuess =
        withContext(Dispatchers.IO) {
            val trimmedGuess = playerGuess.trim()
            if (trimmedGuess.isEmpty()) {
                return@withContext EvaluatedGuess(
                    rating = GuessRating.WRONG,
                    score = 0,
                    reason = "Skipped round",
                    usedGeminiNano = false
                )
            }

            // Fast exact equality check avoids unnecessary model latency when spelling matches identically
            if (originalWord.trim().equals(trimmedGuess, ignoreCase = true)) {
                return@withContext EvaluatedGuess(
                    rating = GuessRating.EXACT,
                    score = 100,
                    reason = "Exact spelling match!",
                    usedGeminiNano = _modelStatus.value == NanoModelStatus.AVAILABLE
                )
            }

            val prompt = buildPrompt(originalWord, trimmedGuess)

            try {
                val status = generativeModel.checkStatus()
                if (status != FeatureStatus.AVAILABLE) {
                    _modelStatus.value = when (status) {
                        FeatureStatus.DOWNLOADABLE -> NanoModelStatus.DOWNLOADABLE
                        FeatureStatus.DOWNLOADING -> NanoModelStatus.DOWNLOADING
                        else -> NanoModelStatus.UNAVAILABLE
                    }
                    return@withContext GuessScoringRules.evaluateLocally(originalWord, trimmedGuess)
                }

                _modelStatus.value = NanoModelStatus.AVAILABLE

                // Prefer Structured Output API when supported by the device's Gemini Nano runtime
                val structuredAvailable = runCatching {
                    generativeModel.isStructuredOutputFeatureAvailable()
                }.getOrDefault(false)

                val baseRequest = GenerateContentRequest.Builder(TextPart(prompt)).apply {
                    temperature = 0.1f
                    topK = 10
                    candidateCount = 1
                }.build()

                if (structuredAvailable) {
                    val typedRequest = generateTypedContentRequest(
                        generateContentRequest = baseRequest,
                        outputClass = ScoringResult::class
                    )
                    val typedResponse = generativeModel.generateContent(typedRequest)
                    val parsed = typedResponse.candidates.firstOrNull()?.response
                    if (parsed != null) {
                        return@withContext GuessScoringRules.normalizeScoringResult(
                            raw = parsed,
                            usedGeminiNano = true
                        )
                    }
                }

                // Fallback to standard text prompt inference on Gemini Nano
                val textResponse = generativeModel.generateContent(baseRequest)
                val rawText = textResponse.candidates.firstOrNull()?.text.orEmpty()
                if (rawText.isNotBlank()) {
                    val parsed = GuessScoringRules.parseRawTextResponse(rawText)
                    return@withContext GuessScoringRules.normalizeScoringResult(
                        raw = parsed,
                        usedGeminiNano = true
                    )
                }
            } catch (e: GenAiException) {
                Log.w(TAG, "ML Kit GenAI error (${e.errorCode}): ${e.message}")
            } catch (t: Throwable) {
                Log.w(TAG, "ML Kit GenAI unavailable, falling back locally: ${t.message}")
            }

            GuessScoringRules.evaluateLocally(originalWord, trimmedGuess)
        }

    fun buildPrompt(originalWord: String, playerGuess: String): String {
        return "You are a game judge. The original word was '$originalWord'. " +
            "A player guessed '$playerGuess'. " +
            "Determine: EXACT (spelling matches or obvious variant like xmas/Christmas), " +
            "CLOSE (same meaning, related, synonym, nickname), WRONG (not related). " +
            "Return ScoringResult."
    }

    fun close() {
        runCatching { generativeModel.close() }
    }
}
