package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure PCM audio reversal utility.
 * Reverses 16-bit PCM samples in reverse chronological order so playback sounds backwards.
 */
fun reverseAudio(pcm: ShortArray): ShortArray {
    return pcm.reversedArray()
}

/**
 * Computes normalized bar amplitudes (0f..1f) from a PCM 16-bit sample array
 * for rendering on the Canvas waveform visualizer.
 */
fun extractWaveformAmplitudes(pcm: ShortArray, barCount: Int = 36): List<Float> {
    if (pcm.isEmpty() || barCount <= 0) return List(barCount.coerceAtLeast(1)) { 0.08f }
    val chunkSize = (pcm.size / barCount).coerceAtLeast(1)
    return List(barCount) { idx ->
        val start = idx * chunkSize
        if (start >= pcm.size) {
            0.08f
        } else {
            val end = min(start + chunkSize, pcm.size)
            var peak = 0
            for (i in start until end) {
                val sample = abs(pcm[i].toInt())
                if (sample > peak) peak = sample
            }
            (peak / 32767f).coerceIn(0.08f, 1f)
        }
    }
}

/**
 * On-device PCM 16-bit audio capture, reversal, local file persistence, and AudioTrack playback.
 * Audio never leaves the device.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val MAX_DURATION_MS = 3000L
        const val MAX_SAMPLES = (SAMPLE_RATE * 3) // 132,300 samples = 3.0 seconds mono
    }

    @Volatile
    private var stopRecordingRequested = false

    @Volatile
    private var stopPlaybackRequested = false

    /**
     * Captures up to 3 seconds of 16-bit PCM mono audio at 44,100 Hz using AudioRecord.
     * Emits live elapsed time and amplitude level for the UI waveform & countdown arc.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordPcm(
        onProgress: (elapsedMs: Long, amplitude: Float) -> Unit
    ): ShortArray = withContext(Dispatchers.IO) {
        stopRecordingRequested = false
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferBytes * 2
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("Microphone AudioRecord failed to initialize")
        }

        val captured = ShortArray(MAX_SAMPLES)
        val chunk = ShortArray(minBufferBytes / 2)
        var totalSamples = 0
        val startTime = System.currentTimeMillis()

        try {
            audioRecord.startRecording()
            while (currentCoroutineContext().isActive && !stopRecordingRequested && totalSamples < MAX_SAMPLES) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= MAX_DURATION_MS) break

                val readCount = audioRecord.read(chunk, 0, chunk.size)
                if (readCount > 0) {
                    val copyCount = min(readCount, MAX_SAMPLES - totalSamples)
                    System.arraycopy(chunk, 0, captured, totalSamples, copyCount)
                    totalSamples += copyCount

                    // Compute peak amplitude in this frame for smooth live waveform bars
                    var peak = 0
                    for (i in 0 until copyCount) {
                        val v = abs(chunk[i].toInt())
                        if (v > peak) peak = v
                    }
                    val normalizedAmp = (peak / 20000f).coerceIn(0.06f, 1f)
                    onProgress(elapsed.coerceAtMost(MAX_DURATION_MS), normalizedAmp)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            try {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            } catch (_: IllegalStateException) {
            }
            audioRecord.release()
        }

        // Trim trailing silence/empty buffer to exact captured sample count
        val trimmed = captured.copyOf(totalSamples)
        trimLowEnergyTail(trimmed)
    }

    fun requestStopRecording() {
        stopRecordingRequested = true
    }

    fun requestStopPlayback() {
        stopPlaybackRequested = true
    }

    /**
     * Plays a PCM 16-bit mono buffer using AudioTrack and streams playback progress (0f..1f).
     */
    suspend fun playPcm(
        pcm: ShortArray,
        onProgress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext
        stopPlaybackRequested = false

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            audioTrack.play()
            val chunkSize = 2048
            var offset = 0
            while (currentCoroutineContext().isActive && !stopPlaybackRequested && offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                val toWrite = min(chunkSize, pcm.size - offset)
                val written = audioTrack.write(pcm, offset, toWrite)
                if (written <= 0) break
                offset += written
                onProgress((offset.toFloat() / pcm.size.toFloat()).coerceIn(0f, 1f))
            }
            onProgress(1f)
        } finally {
            try {
                audioTrack.pause()
                audioTrack.flush()
            } catch (_: IllegalStateException) {
            }
            audioTrack.release()
        }
    }

    /**
     * Saves reversed PCM audio locally inside app-private filesDir (never uploaded).
     */
    suspend fun saveReversedAudio(pcm: ShortArray, roundTag: String): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "rev_${roundTag}_${System.currentTimeMillis()}.pcm")
        val byteBuffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) {
            byteBuffer.putShort(sample)
        }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteBuffer.array())
        }
        file.absolutePath
    }

    /**
     * Loads a previously saved local PCM file back into a ShortArray.
     */
    suspend fun loadPcmFromFile(path: String): ShortArray = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext ShortArray(0)
        val bytes = DataInputStream(file.inputStream().buffered()).use { it.readBytes() }
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        shorts
    }

    /**
     * Trims long dead-air silence from the end so reversed playback starts punchily.
     */
    private fun trimLowEnergyTail(pcm: ShortArray): ShortArray {
        if (pcm.size < SAMPLE_RATE / 4) return pcm
        val threshold = 450
        var lastSoundIdx = pcm.size - 1
        while (lastSoundIdx > SAMPLE_RATE / 4 && abs(pcm[lastSoundIdx].toInt()) < threshold) {
            lastSoundIdx--
        }
        // Keep a tiny 120ms tail buffer so speech never clips abruptly
        val paddedEnd = min(pcm.size, lastSoundIdx + (SAMPLE_RATE / 8))
        return pcm.copyOf(paddedEnd)
    }
}
