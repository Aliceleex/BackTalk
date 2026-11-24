package com.example

import com.example.audio.WavPcmParser
import com.example.audio.extractWaveformAmplitudes
import com.example.audio.reverseAudio
import com.example.game.PackDifficulty
import com.example.game.WordJsonConverter
import com.example.game.WordRepository
import com.example.gemini.GuessRating
import com.example.gemini.GuessScoringRules
import com.example.gemini.ScoringResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

class BackTalkUnitTests {

    @Test
    fun `audio reversal correctness reverses PCM samples and preserves energy`() {
        val original = shortArrayOf(100, -250, 1200, -16000, 32000)
        val reversed = reverseAudio(original)

        assertArrayEquals(shortArrayOf(32000, -16000, 1200, -250, 100), reversed)
        // Reversing twice restores the exact original PCM buffer
        assertArrayEquals(original, reverseAudio(reversed))
        // Empty PCM buffer stays empty without throwing
        assertEquals(0, reverseAudio(ShortArray(0)).size)

        val bars = extractWaveformAmplitudes(reversed, barCount = 5)
        assertEquals(5, bars.size)
        assertTrue(bars.all { it in 0.08f..1f })
    }

    @Test
    fun `scoring logic awards 100 for EXACT 60 for CLOSE and 0 for WRONG`() {
        val exact = GuessScoringRules.normalizeScoringResult(
            ScoringResult(rating = "EXACT", score = 95, reason = "xmas matches Christmas"),
            usedGeminiNano = true
        )
        assertEquals(GuessRating.EXACT, exact.rating)
        assertEquals(100, exact.score)
        assertTrue(exact.usedGeminiNano)

        val close = GuessScoringRules.normalizeScoringResult(
            ScoringResult(rating = "CLOSE", score = 50, reason = "Same meaning synonym"),
            usedGeminiNano = true
        )
        assertEquals(GuessRating.CLOSE, close.rating)
        assertEquals(60, close.score)

        val wrong = GuessScoringRules.normalizeScoringResult(
            ScoringResult(rating = "WRONG", score = 10, reason = "Unrelated word"),
            usedGeminiNano = true
        )
        assertEquals(GuessRating.WRONG, wrong.rating)
        assertEquals(0, wrong.score)

        // Fallback semantic checks for variants & typos
        val xmasLocal = GuessScoringRules.evaluateLocally("Christmas", "xmas")
        assertEquals(GuessRating.EXACT, xmasLocal.rating)
        assertEquals(100, xmasLocal.score)

        val typoLocal = GuessScoringRules.evaluateLocally("Worcestershire", "Worcestersire")
        assertEquals(GuessRating.CLOSE, typoLocal.rating)
        assertEquals(60, typoLocal.score)
    }

    @Test
    fun `word pack loading seeds EASY MEDIUM HARD packs and parses JSON arrays`() {
        val packs = WordRepository.defaultSeedPacks(nowEpochMillis = 1_700_000_000_000L)
        assertTrue(packs.size >= 5)

        val allWords = packs.flatMap { WordJsonConverter.fromJsonArray(it.words) }
        assertTrue(allWords.contains("cat"))
        assertTrue(allWords.contains("dog"))
        assertTrue(allWords.contains("pizza"))
        assertTrue(allWords.contains("helicopter"))
        assertTrue(allWords.contains("hamburger"))
        assertTrue(allWords.contains("onomatopoeia"))
        assertTrue(allWords.contains("Worcestershire"))

        assertTrue(packs.any { it.difficulty == PackDifficulty.EASY })
        assertTrue(packs.any { it.difficulty == PackDifficulty.MEDIUM })
        assertTrue(packs.any { it.difficulty == PackDifficulty.HARD })

        // Daily challenge word is deterministic for a given LocalDate
        val day = LocalDate.of(2026, 11, 22)
        assertEquals(
            WordRepository.dailyWordForDate(day),
            WordRepository.dailyWordForDate(day)
        )
    }

    @Test
    fun `wav parser decodes mono 16bit PCM resamples to 44100Hz and reverses for backwards guide`() {
        val sampleRate = 22050
        val samples = shortArrayOf(1000, 2000, 4000, 8000)
        val dataBytes = samples.size * 2
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // PCM format
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
            samples.forEach { putShort(it) }
        }.array()

        val forward44k = WavPcmParser.parseWavToMono44kPcm(wav, targetSampleRate = 44100)
        // 4 samples at 22050Hz -> 8 samples at 44100Hz
        assertEquals(8, forward44k.size)
        val reversed44k = reverseAudio(forward44k)
        assertEquals(8, reversed44k.size)
        // Reversing puts the high-amplitude tail first
        assertTrue(reversed44k.first() > reversed44k.last())
    }
}
