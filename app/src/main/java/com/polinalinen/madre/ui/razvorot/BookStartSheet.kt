package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeScale
import com.polinalinen.madre.ui.kit.*

/** Common chapter/oven start entry. Timer starts only after the durable VM write. */
@Composable fun BookStartSheet(recipe: Recipe, initialPortions: Int, onStart: (Int) -> Unit,
    onDismiss: () -> Unit, onChapter: (() -> Unit)? = null) {
    var portions by rememberSaveable(recipe.id) { mutableIntStateOf(initialPortions.coerceIn(1,5)) }
    PaperSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SmallLabel("ИСПЕЧЬ")
            BookText(recipe.name,30)
            PaperStepper("×$portions",{portions=(portions-1).coerceAtLeast(1)},
                {portions=(portions+1).coerceAtMost(5)},minusEnabled=portions>1,plusEnabled=portions<5)
            RecipeScale.yieldText(recipe,portions)?.let { PaperNote(it) }
            RecipeScale.capacityNote(recipe,portions)?.let { PaperNote(it) }
            PaperNote("${BookCatalog.duration(RecipeScale.totalMinutes(recipe,portions))} · ${recipe.timeline.size} шагов")
            PaperNote(RecipeScale.TIMING_NOTE)
            PaperButton("Начать · ×$portions",{onStart(portions)},Modifier.fillMaxWidth(),primary=true)
            if(onChapter!=null)PaperTextAction("Открыть главу",onChapter,Modifier.fillMaxWidth())
        }
    }
}
