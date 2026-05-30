package com.polinalinen.madre.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.model.*
import com.polinalinen.madre.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BakingTimelineScreen(
    session: BakingSession,
    remainingSeconds: Long,
    onAdvance: () -> Unit,
    onTogglePause: () -> Unit,
    onBack: () -> Unit,
    devMode: Boolean = false,
    onToggleDevMode: () -> Unit = {},
    onSkipStep: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val step = session.currentStep
    val totalStepSeconds = step.durationMinutes * 60L
    val elapsedSeconds = totalStepSeconds - remainingSeconds
    val progress = if (totalStepSeconds > 0) elapsedSeconds.toFloat() / totalStepSeconds else 0f

    Surface(
        modifier = modifier.fillMaxSize(),
        color = BackgroundDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = TextSecondary
                    )
                }
                Text(
                    text = session.recipe.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = AccentGold,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = {},
                            onDoubleClick = {},
                            onLongClick = onToggleDevMode
                        ),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.width(48.dp)) // balance back button
            }

            // Overall progress
            LinearProgressIndicator(
                progress = session.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = AccentGold,
                trackColor = DividerColor
            )

            Text(
                text = "Шаг ${session.currentStepIndex + 1} из ${session.recipe.timeline.size}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Current step card — HERO
            CurrentStepCard(
                step = step,
                remainingSeconds = remainingSeconds,
                totalStepSeconds = totalStepSeconds,
                progress = progress,
                isPaused = session.isPaused,
                onAdvance = onAdvance,
                onTogglePause = onTogglePause,
                isLastStep = session.isLastStep
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Dev mode indicator + skip button
            if (devMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ DEV ×1000",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentRose
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = onSkipStep,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentRose,
                            contentColor = BackgroundDark
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Пропустить ⏭", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Timeline overview
            Text(
                text = "Все шаги",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(session.recipe.timeline) { index, timelineStep ->
                    TimelineItem(
                        step = timelineStep,
                        index = index,
                        isCurrent = index == session.currentStepIndex,
                        isCompleted = index < session.currentStepIndex,
                        isFuture = index > session.currentStepIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun CurrentStepCard(
    step: TimelineStep,
    remainingSeconds: Long,
    totalStepSeconds: Long,
    progress: Float,
    isPaused: Boolean,
    onAdvance: () -> Unit,
    onTogglePause: () -> Unit,
    isLastStep: Boolean,
    modifier: Modifier = Modifier
) {
    val isWait = step.type == StepType.WAIT
    val accentColor = if (isWait) StatusWait else StatusAction

    // Pulse animation for active wait
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = BackgroundCard
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Step type badge
            val (icon, label) = when (step.type) {
                StepType.ACTION -> Icons.Default.Restaurant to "ДЕЛАЕМ"
                StepType.WAIT -> Icons.Default.HourglassTop to "ЖДЁМ"
            }

            Surface(
                color = accentColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = accentColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step title
            Text(
                text = step.title,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Description
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Timer circle (for wait steps)
            if (isWait && remainingSeconds > 0) {
                TimerCircle(
                    remainingSeconds = remainingSeconds,
                    totalSeconds = totalStepSeconds,
                    progress = progress,
                    isPaused = isPaused,
                    accentColor = accentColor
                )
                Spacer(modifier = Modifier.height(20.dp))
            } else if (isWait && remainingSeconds <= 0) {
                // Timer finished — show "Time's up!"
                Text(
                    text = "⏰ Время вышло!",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TimerUrgent
                )
                Spacer(modifier = Modifier.height(20.dp))
            } else if (!isWait) {
                // Show duration for action steps
                Text(
                    text = "~${step.durationMinutes} мин",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isWait) {
                    // Pause/Resume button
                    IconButton(
                        onClick = onTogglePause,
                        modifier = Modifier
                            .size(48.dp)
                            .background(DividerColor, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Продолжить" else "Пауза",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }

                // Advance button (main CTA)
                Button(
                    onClick = onAdvance,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = BackgroundDark
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .height(52.dp)
                        .weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLastStep) "Готово! 🎉" else "Далее →",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerCircle(
    remainingSeconds: Long,
    totalSeconds: Long,
    progress: Float,
    isPaused: Boolean,
    accentColor: androidx.compose.ui.graphics.Color
) {
    val hours = remainingSeconds / 3600
    val minutes = (remainingSeconds % 3600) / 60
    val seconds = remainingSeconds % 60

    val timeText = when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        else -> String.format("%02d:%02d", minutes, seconds)
    }

    val isUrgent = remainingSeconds < 300 // < 5 min
    val color = if (isUrgent) TimerUrgent else accentColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(180.dp)
    ) {
        // Background circle
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(CircleShape)
                .background(TimerBackground)
        )

        // Progress arc
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.size(168.dp),
            color = color,
            strokeWidth = 5.dp,
            trackColor = DividerColor
        )

        // Time text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = timeText,
                style = MaterialTheme.typography.displayMedium,
                color = if (isPaused) TextSecondary else TextPrimary
            )
            if (isPaused) {
                Text(
                    text = "ПАУЗА",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentRose
                )
            }
        }
    }
}

@Composable
private fun TimelineItem(
    step: TimelineStep,
    index: Int,
    isCurrent: Boolean,
    isCompleted: Boolean,
    isFuture: Boolean,
    modifier: Modifier = Modifier
) {
    val isWait = step.type == StepType.WAIT
    val bgColor = when {
        isCurrent -> BackgroundCardHover
        isCompleted -> BackgroundCard.copy(alpha = 0.5f)
        else -> BackgroundCard.copy(alpha = 0.3f)
    }

    val dotColor = when {
        isCompleted -> StatusCompleted
        isCurrent -> if (isWait) StatusWait else StatusAction
        else -> DividerColor
    }

    val textAlpha = when {
        isFuture -> 0.4f
        isCompleted -> 0.6f
        else -> 1f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Icon
        val icon = when (step.type) {
            StepType.ACTION -> Icons.Default.Restaurant
            StepType.WAIT -> Icons.Default.HourglassTop
        }
        Icon(
            imageVector = if (isCompleted) Icons.Default.CheckCircle else icon,
            contentDescription = null,
            tint = dotColor.copy(alpha = textAlpha),
            modifier = Modifier.size(18.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        // Text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary.copy(alpha = textAlpha)
            )
        }

        // Duration
        val durationText = formatDuration(step.durationMinutes)
        Text(
            text = durationText,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary.copy(alpha = textAlpha)
        )
    }
}

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}ч ${m}мин"
        h > 0 -> "${h}ч"
        else -> "${m}мин"
    }
}
