package com.polinalinen.madre.ui.razvorot

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/** Chapter entry only; restoration stays still and PaperTurn geometry is not involved. */
@Composable fun ChapterEntry(recipeId: String, reducedMotion: Boolean, content: @Composable () -> Unit) {
    var shown by rememberSaveable(recipeId){mutableStateOf(false)}
    val progress by animateFloatAsState(if(shown)1f else 0f,
        tween(if(reducedMotion)200 else 520,easing=CubicBezierEasing(.2f,0f,0f,1f)),label="chapter-entry")
    LaunchedEffect(recipeId){shown=true}
    Box(Modifier.fillMaxSize().graphicsLayer {
        transformOrigin=TransformOrigin(0f,.5f)
        rotationY=if(reducedMotion)0f else -62f*(1f-progress)
        alpha=if(reducedMotion)progress else 1f
        cameraDistance=18f*density
    }) { content() }
}
