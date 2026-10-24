package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.StepType

fun BakingSession.activeSnapshot() = ActiveBakeEntity(
    sessionId = id,
    recipeId = recipe.id,
    recipeName = recipe.name,
    stepTitle = currentStep.title,
    stepIndex = currentStepIndex,
    stepDurationMinutes = currentStep.durationMinutes,
    isWaitStep = currentStep.type == StepType.WAIT,
    startedAtWallClock = startedAtWallClock,
    pausedAtWallClock = pausedAtElapsed?.let { startedAtWallClock + (it - startedAtElapsed) },
    scaleFactor = trustedPortions?.toDouble(),
    bakeKey = bakeKey,
    revision = revision,
    recipeRevision = recipeRevision,
    bakeNumber = bakeNumber,
)
