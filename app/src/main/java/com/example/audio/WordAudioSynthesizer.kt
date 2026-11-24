package com.example.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

data class SynthesizedWordAudio(
    val forwardPcm: ShortArray,
    val reversedPcm: ShortArray,
    val reversedAmplitudes: List<Float>
)

/**
 * Pure Kotlin WAV-to-PCM parser & linear resampler (44,100 Hz mono 16-bit PCM).
 * Handles arbitrary RIFF chunk ordering and 1 or 2+ channels cleanly.
 */
object WavPcmParser {

    fun parseWavToMono44kPcm(
        wavBytes: ByteArray,
        targetSampleRate: Int = AudioRecorder.SAMPLE_RATE
    ): ShortArray {
        if (wavBytes.size < 44) return ShortArray(0)
        val riff = String(wavBytes, 0, 4, Charsets.US_ASCII)
        val wave = String(wavBytes, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return ShortArray(0)

        var audioFormat = 1
        var channels = 1
        var sourceSampleRate = targetSampleRate
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buf.getInt(offset + 4).coerceAtLeast(0)
            val payloadStart = offset + 8
            if (payloadStart > wavBytes.size) break

            when (chunkId) {
                "fmt " -> {
                    if (payloadStart + 16 <= wavBytes.size) {
                        audioFormat = buf.getShort(payloadStart).toInt() and 0xFFFF
                        channels = (buf.getShort(payloadStart + 2).toInt() and 0xFFFF).coerceAtLeast(1)
                        sourceSampleRate = buf.getInt(payloadStart + 4).coerceAtLeast(8000)
                        bitsPerSample = buf.getShort(payloadStart + 14).toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataOffset = payloadStart
                    dataSize = min(chunkSize, wavBytes.size - payloadStart)
                    break
                }
            }
            // RIFF chunks are word-aligned (2 bytes)
            val paddedSize = chunkSize + (chunkSize and 1)
            offset = payloadStart + paddedSize
        }

        if (dataOffset < 0 || dataSize <= 0 || audioFormat != 1) return ShortArray(0)

        val monoPcm: ShortArray = when (bitsPerSample) {
            16 -> {
                val bytesPerFrame = channels * 2
                val frameCount = dataSize / bytesPerFrame
                ShortArray(frameCount) { frameIdx ->
                    var sum = 0
                    val frameBase = dataOffset + frameIdx * bytesPerFrame
                    for (ch in 0 until channels) {
                        sum += buf.getShort(frameBase + ch * 2).toInt()
                    }
                    (sum / channels).toShort()
                }
            }
            8 -> {
                val frameCount = dataSize / channels
                ShortArray(frameCount) { frameIdx ->
                    var sum = 0
                    val frameBase = dataOffset + frameIdx * channels
                    for (ch in 0 until channels) {
                        val unsigned = wavBytes[frameBase + ch].toInt() and 0xFF
                        sum += (unsigned - 128) shl 8
                    }
                    (sum / channels).toShort()
                }
            }
            else -> return ShortArray(0)
        }

        val trimmed = trimSilenceEdges(monoPcm, sourceSampleRate)
        if (trimmed.isEmpty()) return ShortArray(0)
        return resampleLinear(trimmed, sourceSampleRate, targetSampleRate)
    }

    internal fun resampleLinear(
        input: ShortArray,
        fromRate: Int,
        toRate: Int
    ): ShortArray {
        if (input.isEmpty() || fromRate <= 0 || toRate <= 0) return ShortArray(0)
        if (fromRate == toRate) return input
        val outLength = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        return ShortArray(outLength) { outIdx ->
            val srcPos = outIdx * ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val idx1 = (idx0 + 1).coerceIn(0, input.lastIndex)
            val frac = srcPos - idx0
            val sample = input[idx0] + (input[idx1] - input[idx0]) * frac
            sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun trimSilenceEdges(pcm: ShortArray, sampleRate: Int): ShortArray {
        if (pcm.isEmpty()) return pcm
        val threshold = 320
        var start = 0
        while (start < pcm.size && abs(pcm[start].toInt()) < threshold) {
            start++
        }
        if (start >= pcm.size) return pcm
        var end = pcm.size - 1
        while (end > start && abs(pcm[end].toInt()) < threshold) {
            end--
        }
        val pad = (sampleRate * 0.06f).toInt()
        val paddedStart = (start - pad).coerceAtLeast(0)
        val paddedEnd = (end + pad + 1).coerceAtMost(pcm.size)
        return pcm.copyOfRange(paddedStart, paddedEnd)
    }
}

/**
 * Uses on-device Android TextToSpeech to speak a target word into a temporary WAV file,
 * parses it to 44.1 kHz mono PCM, and reverses it so Player A hears the word backwards
 * (e.g., "Christmas" / "xmas" -> "samx") before recording their mimic!
 */
class WordAudioSynthesizer(private val context: Context) {

    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false

    private suspend fun ensureTts(): TextToSpeech? {
        if (ttsReady && tts != null) return tts

        return withContext(Dispatchers.Main) {
            tts?.shutdown()
            tts = null
            ttsReady = false

            val initDeferred = CompletableDeferred<Int>()
            val instance = TextToSpeech(context.applicationContext) { status ->
                if (!initDeferred.isCompleted) {
                    initDeferred.complete(status)
                }
            }

            val status = withTimeoutOrNull(6000L) { initDeferred.await() }
            if (status != TextToSpeech.SUCCESS) {
                runCatching { instance.shutdown() }
                return@withContext null
            }

            val langResult = instance.setLanguage(Locale.US)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                instance.setLanguage(Locale.getDefault())
            }
            instance.setSpeechRate(0.92f)
            tts = instance
            ttsReady = true
            instance
        }
    }

    suspend fun synthesizeReversedWord(word: String): SynthesizedWordAudio? = mutex.withLock {
        val cleanWord = word.trim()
        if (cleanWord.isEmpty()) return@withLock null

        val engine = ensureTts() ?: return@withLock null
        val utteranceId = "bt_${UUID.randomUUID()}"
        val wavFile = File(context.cacheDir, "$utteranceId.wav")

        try {
            val doneDeferred = CompletableDeferred<Boolean>()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId && !doneDeferred.isCompleted) {
                        doneDeferred.complete(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && !doneDeferred.isCompleted) {
                        doneDeferred.complete(false)
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && !doneDeferred.isCompleted) {
                        doneDeferred.complete(false)
                    }
                }
            })

            val params = Bundle()
            val queued = withContext(Dispatchers.Main) {
                engine.synthesizeToFile(cleanWord, params, wavFile, utteranceId)
            }
            if (queued != TextToSpeech.SUCCESS) {
                return@withLock null
            }

            val succeeded = withTimeoutOrNull(8000L) { doneDeferred.await() } == true
            if (!succeeded || !wavFile.exists()) {
                return@withLock null
            }

            val wavBytes = withContext(Dispatchers.IO) { wavFile.readBytes() }
            val forwardPcm = WavPcmParser.parseWavToMono44kPcm(wavBytes)
            if (forwardPcm.isEmpty()) return@withLock null

            val reversedPcm = reverseAudio(forwardPcm)
            val amplitudes = extractWaveformAmplitudes(reversedPcm, barCount = 36)
            SynthesizedWordAudio(
                forwardPcm = forwardPcm,
                reversedPcm = reversedPcm,
                reversedAmplitudes = amplitudes
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { if (wavFile.exists()) wavFile.delete() }
        }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ttsReady = false
    }
}
