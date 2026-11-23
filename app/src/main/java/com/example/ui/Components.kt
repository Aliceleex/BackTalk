package com.example.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.game.PackDifficulty
import com.example.gemini.NanoModelStatus

/**
 * Clean, modern off-white studio backdrop with a subtle top-to-bottom depth tint.
 */
@Composable
fun BackTalkBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colorScheme.background,
                        colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        colorScheme.background
                    )
                )
            ),
        content = content
    )
}

/**
 * Crisp, tactile game button with subtle spring press feedback and clean 16.dp modern corners.
 */
@Composable
fun PlayfulGradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradientColors: List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
    ),
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 15.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "button_press_scale"
    )

    val shape = RoundedCornerShape(16.dp)
    val effectiveColors = if (enabled) {
        gradientColors
    } else {
        listOf(
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.outline
        )
    }

    Surface(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 54.dp),
        shape = shape,
        shadowElevation = if (enabled) 3.dp else 0.dp,
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(Brush.horizontalGradient(effectiveColors))
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = MaterialTheme.colorScheme.onPrimary),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(contentPadding),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

@Composable
fun DifficultyStars(
    difficulty: PackDifficulty,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val starTint = when (difficulty) {
            PackDifficulty.EASY -> MaterialTheme.colorScheme.secondary
            PackDifficulty.MEDIUM -> MaterialTheme.colorScheme.tertiary
            PackDifficulty.HARD -> MaterialTheme.colorScheme.error
        }
        repeat(3) { index ->
            val filled = index < difficulty.stars
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (filled) "${difficulty.label} star" else null,
                tint = if (filled) starTint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
fun NanoStatusPill(
    status: NanoModelStatus,
    downloadMb: Long?,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when (status) {
        NanoModelStatus.CHECKING -> "Nano..."
        NanoModelStatus.AVAILABLE -> "Gemini Nano"
        NanoModelStatus.DOWNLOADABLE -> "Get Nano"
        NanoModelStatus.DOWNLOADING -> "Nano ${downloadMb ?: 0}MB"
        NanoModelStatus.UNAVAILABLE -> "On-Device"
    }
    val dotColor = when (status) {
        NanoModelStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary
        NanoModelStatus.DOWNLOADABLE, NanoModelStatus.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier.then(
            if (status == NanoModelStatus.DOWNLOADABLE) {
                Modifier.clickable(onClick = onDownloadClick)
            } else {
                Modifier
            }
        ),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
