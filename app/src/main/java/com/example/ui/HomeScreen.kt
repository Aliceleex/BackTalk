package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.game.DailyChallengeState
import com.example.game.GameRound
import com.example.game.WordJsonConverter
import com.example.game.WordPack
import com.example.gemini.NanoModelStatus
import com.example.ui.theme.DarkStudioBg
import com.example.ui.theme.DarkStudioSurface
import com.example.ui.theme.PureWhite
import com.example.ui.theme.VictoryGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    wordPacks: List<WordPack>,
    selectedPackId: Long?,
    recentRounds: List<GameRound>,
    dailyChallenge: DailyChallengeState,
    nanoStatus: NanoModelStatus,
    nanoDownloadMb: Long?,
    onSelectPack: (Long) -> Unit,
    onStartNewGame: () -> Unit,
    onStartDailyChallenge: () -> Unit,
    onOpenWordPacks: () -> Unit,
    onDownloadNano: () -> Unit
) {
    val selectedPack = wordPacks.firstOrNull { it.id == selectedPackId } ?: wordPacks.firstOrNull()

    BackTalkBackdrop {
        Scaffold(containerColor = Color.Transparent) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 580.dp)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Clean Studio Header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "BackTalk",
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Hear it backwards. Mimic it. Guess it!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            NanoStatusPill(
                                status = nanoStatus,
                                downloadMb = nanoDownloadMb,
                                onDownloadClick = onDownloadNano
                            )
                        }
                    }

                    // Sleek Compose-Drawn Hero Play Card (zero clip-art)
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                DarkStudioBg,
                                                DarkStudioSurface,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                            )
                                        )
                                    )
                                    .padding(22.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = PureWhite.copy(alpha = 0.14f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Replay,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "5-Round Pass & Play",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = PureWhite
                                            )
                                        }
                                    }
                                    if (dailyChallenge.highScore > 0) {
                                        Text(
                                            text = "Best ${dailyChallenge.highScore}/500",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }

                                // Crisp geometric waveform motif rendered purely in Compose Canvas
                                StudioWaveformBanner(
                                    primaryBarColor = MaterialTheme.colorScheme.secondary,
                                    accentBarColor = MaterialTheme.colorScheme.tertiary,
                                    mutedBarColor = PureWhite.copy(alpha = 0.25f)
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = selectedPack?.let { "${it.name} Pack" } ?: "Party Mode",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = PureWhite
                                    )
                                    Text(
                                        text = "Hear a word backwards, mimic the reverse sound, and see if a friend can guess the real word!",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PureWhite.copy(alpha = 0.78f)
                                    )
                                }

                                PlayfulGradientButton(
                                    onClick = onStartNewGame,
                                    gradientColors = listOf(
                                        MaterialTheme.colorScheme.secondary,
                                        MaterialTheme.colorScheme.secondary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("new_game_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Start new game",
                                        tint = DarkStudioBg,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = "New Game",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = DarkStudioBg
                                    )
                                }
                            }
                        }
                    }

                    // Side-by-Side Quick Cards: Daily Word & Word Packs
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("daily_challenge_card")
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable(onClick = onStartDailyChallenge),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = dailyChallenge.date.format(DateTimeFormatter.ofPattern("MMM d")),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.LocalFireDepartment,
                                                contentDescription = "Streak",
                                                tint = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "${dailyChallenge.streakCount}",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Daily Word",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (dailyChallenge.completedToday) {
                                            "Done • ${dailyChallenge.todayScore ?: 0}/100"
                                        } else {
                                            "1-round challenge"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("word_packs_button")
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable(onClick = onOpenWordPacks),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.LibraryMusic,
                                            contentDescription = "Word Packs",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = "Word Packs",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${wordPacks.size} packs + custom",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Select Pack Strip
                    item {
                        Text(
                            text = "Choose a Pack",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(wordPacks, key = { it.id }) { pack ->
                                val isSelected = pack.id == selectedPack?.id
                                val sample = WordJsonConverter.fromJsonArray(pack.words).take(2).joinToString(", ")
                                Card(
                                    modifier = Modifier
                                        .width(164.dp)
                                        .testTag("home_pack_card_${pack.id}")
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { onSelectPack(pack.id) },
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = pack.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        DifficultyStars(difficulty = pack.difficulty)
                                        Text(
                                            text = sample,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Past Scores
                    item {
                        Text(
                            text = "Recent Rounds",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    if (recentRounds.isEmpty()) {
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.GraphicEq,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "No rounds yet",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Tap New Game to hear a backwards word and mimic it.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        items(recentRounds.take(8), key = { it.id }) { round ->
                            val score = maxOf(round.playerAScore, round.playerBScore)
                            val guess = round.playerBGuess.ifBlank { round.playerAGuess }.ifBlank { "Skipped" }
                            val timeLabel = Instant.ofEpochMilli(round.completedAt)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MMM d"))
                            val scoreColor = when {
                                score >= 100 -> VictoryGreen
                                score >= 60 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }

                            Card(
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${round.originalWord} → \"$guess\"",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = timeLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = scoreColor.copy(alpha = 0.14f)
                                    ) {
                                        Text(
                                            text = "+$score",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = scoreColor,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioWaveformBanner(
    primaryBarColor: Color,
    accentBarColor: Color,
    mutedBarColor: Color
) {
    val heights = listOf(
        0.25f, 0.45f, 0.35f, 0.70f, 0.95f, 0.60f, 0.85f, 0.40f,
        0.55f, 0.90f, 1.00f, 0.68f, 0.48f, 0.78f, 0.92f, 0.50f,
        0.38f, 0.66f, 0.84f, 0.44f, 0.30f, 0.52f, 0.36f, 0.22f
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val slot = size.width / heights.size
        val barWidth = (slot * 0.52f).coerceAtLeast(4f)
        val centerY = size.height / 2f
        heights.forEachIndexed { i, h ->
            val barHeight = size.height * h
            val x = i * slot + (slot - barWidth) / 2f
            val y = centerY - barHeight / 2f
            val color = when {
                i in 9..14 -> primaryBarColor
                i in 4..6 || i in 17..19 -> accentBarColor
                else -> mutedBarColor
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
