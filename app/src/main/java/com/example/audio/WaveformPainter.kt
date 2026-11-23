package com.example.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pure helper for mapping normalized amplitudes (0f..1f) into centered bar geometry.
 */
object WaveformPainterMath {
    fun computeBarHeight(amplitude: Float, maxHeightPx: Float, minBarPx: Float = 8f): Float {
        val clamped = amplitude.coerceIn(0.06f, 1f)
        return (clamped * maxHeightPx).coerceAtLeast(minBarPx)
    }
}

/**
 * Canvas-based amplitude bar visualizer supporting both live recording pulses
 * and reversed-playback playhead progress highlighting.
 */
@Composable
fun WaveformVisualizer(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
    playheadProgress: Float = 0f,
    isActive: Boolean = false,
    activeStartColor: Color = MaterialTheme.colorScheme.primary,
    activeEndColor: Color = MaterialTheme.colorScheme.secondary,
    inactiveColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
) {
    val bars = if (amplitudes.isEmpty()) List(32) { 0.14f } else amplitudes

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val count = bars.size
        if (count == 0) return@Canvas

        val totalWidth = size.width
        val slotWidth = totalWidth / count
        val barWidth = (slotWidth * 0.58f).coerceAtLeast(4f)
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.88f

        val activeBrush = Brush.verticalGradient(
            colors = listOf(activeStartColor, activeEndColor)
        )

        bars.forEachIndexed { index, rawAmp ->
            val barHeight = WaveformPainterMath.computeBarHeight(rawAmp, maxBarHeight)
            val x = index * slotWidth + (slotWidth - barWidth) / 2f
            val y = centerY - barHeight / 2f

            val progressThreshold = (index + 1).toFloat() / count.toFloat()
            val isHighlighted = isActive && (playheadProgress == 0f || progressThreshold <= playheadProgress + 0.04f)

            if (isHighlighted) {
                drawRoundRect(
                    brush = activeBrush,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = cornerRadius
                )
            } else {
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = cornerRadius
                )
            }
        }
    }
}
