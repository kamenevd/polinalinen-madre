package com.polinalinen.madre.ui.razvorot

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.model.*
import com.polinalinen.madre.navigation.BookBookmark
import com.polinalinen.madre.navigation.PlannedBake
import com.polinalinen.madre.ui.kit.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.polinalinen.madre.viewmodel.BakingViewModel
import com.polinalinen.madre.ui.screens.Home

@Composable fun BookTocContent(recipes: List<Recipe>, sessions: List<BakingSession>, bookmark: BookBookmark?,
    onChapter: (String, Int) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier, onAa: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CatalogFilter.ALL) }
    val rows = remember(recipes, sessions, query, filter) { BookCatalog.search(recipes, query, filter, sessions) }
    Column(modifier.fillMaxSize().testTag(Home.LIST_TAG).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallLabel("${LocalAtmosphere.current.label} · КНИГА СЕМЬИ", Modifier.weight(1f).padding(top = 18.dp))
            PaperTextButton(onAa,label="Как читать") { BookText("Aa",21) }
            PaperIconButton(onSettings, label = "Настройки") { Icon(Icons.Outlined.Settings, null) }
        }
        SmallLabel("КНИГА РЕЦЕПТОВ · ${recipes.size} ГЛАВ")
        BookText("Оглавление", 44, weight=androidx.compose.ui.text.font.FontWeight.Normal)
        PaperNote("Мадре — книга рецептов на закваске · ${recipes.size} глав")
        bookmark?.let { saved -> recipes.firstOrNull { it.id == saved.recipeId }?.let { recipe ->
            PaperCard(Modifier.fillMaxWidth()) {
                SmallLabel("ЗАКЛАДКА")
                Text(recipe.name, style = PaperType.title, color = LocalPaper.current.ink)
                PaperTextAction("Продолжить · стр. ${saved.page + 1}", { onChapter(saved.recipeId, saved.page) })
            }
        } }
        PaperField(query, { query = it }, label = "Найти рецепт или ингредиент", placeholder = "Например, рж",
            modifier = Modifier.testTag("toc-search"))
        PaperChipRow(CatalogFilter.entries.map { if(it==CatalogFilter.ALL)"${it.label} ${recipes.size}" else it.label }, filter.ordinal, { filter = CatalogFilter.entries[it] })
        if (rows.isEmpty()) {
            BookText("Такой главы нет", 24)
            PaperNote("Попробуйте другое название или ингредиент.")
            PaperTextAction("Показать все главы", { query = ""; filter = CatalogFilter.ALL })
        } else rows.forEach { match ->
            val recipe = match.recipe
            val active = sessions.firstOrNull { it.recipe.id == recipe.id && !it.isCompleted }
            PaperTocRow(match.chapter + 1, recipe.name, BookCatalog.duration(RecipeScale.totalMinutes(recipe, 1)),
                match.ingredient?.let { "есть: $it" } ?: difficultyLabel(recipe.difficulty),
                { onChapter(recipe.id, -1) }, modifier=Modifier.testTag(Home.chapterRowTag(recipe.id)),
                activeLabel = active?.let { "В печи · шаг ${it.currentStepIndex + 1} из ${recipe.timeline.size}" }, query = query)
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable fun BookOvenContent(recipes: List<Recipe>, sessions: List<BakingSession>, plans: List<PlannedBake>,
    onOpenSession: (Long) -> Unit, onConfigure: (Recipe) -> Unit, onQuickStart: (Recipe) -> Unit,
    onOpenPlan: (PlannedBake) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier,
    onAa: () -> Unit = {}, pendingFinished: List<Pair<Long,BakingViewModel.BakeCompletion>> = emptyList()) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val p = LocalPaper.current
    val choices = listOf("Все", "К ужину", "До 3 часов", "Ночью")
    val rows = remember(recipes, filter) { recipes.filter { recipe ->
        val minutes = RecipeScale.totalMinutes(recipe, 1)
        when (filter) { 1 -> minutes <= 150; 2 -> minutes <= 180; 3 -> minutes >= 480; else -> true }
    } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallLabel("ПЕЧЬ · ${SimpleDateFormat("EEEE, d MMMM",Locale.forLanguageTag("ru")).format(Date())}",Modifier.weight(1f).padding(top=16.dp))
            PaperTextButton(onAa,label="Как читать") { BookText("Aa",21) }
        }
        PaperCountHeading("Уже печём",sessions.count{!it.isCompleted})
        if (sessions.isNotEmpty()) {
            SmallLabel("УЖЕ ПЕЧЁМ · ${sessions.size}")
            sessions.forEach { session -> key(session.id) { ActiveBookBake(session) { onOpenSession(session.id) } } }
        } else {
            SmallLabel("ПОКА НИЧЕГО НЕ ПЕЧЁМ")
            PaperNote("Выберите рецепт ниже — и поставим опару.")
        }
        pendingFinished.forEach { (id,completion) -> PaperCard(Modifier.fillMaxWidth()) {
            SmallLabel("ИСПЕЧЕНО · ВПИШИТЕ В КНИГУ",color=p.rib)
            Text(completion.recipeName,style=PaperType.title,color=p.ink)
            PaperTextAction("Вписать в книгу",{onOpenSession(id)})
        } }
        if (plans.isNotEmpty()) {
            SmallLabel("ЗАПЛАНИРОВАНО")
            plans.forEach { plan -> recipes.firstOrNull { it.id == plan.recipeId }?.let { recipe ->
                PaperCard(Modifier.fillMaxWidth()) {
                    Text(recipe.name, style = PaperType.title, color = p.ink)
                    PaperNote("×${plan.portions} · ${SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(plan.startAtMillis))}")
                    PaperTextAction("Открыть план", { onOpenPlan(plan) })
                }
            } }
        }
        SmallLabel("ЧТО ИСПЕЧЬ", Modifier.padding(top = 10.dp))
        PaperChipRow(choices, filter, { filter = it })
        if (rows.isEmpty()) PaperNote("Таких рецептов в книге пока нет. Выберите другое время.")
        rows.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { recipe -> PaperRecipeTile(recipes.indexOf(recipe) + 1, recipe.name, recipePhoto(recipe.id),
                    BookCatalog.duration(RecipeScale.totalMinutes(recipe, 1)), "${recipe.timeline.size} шагов",
                    { onConfigure(recipe) }, { onQuickStart(recipe) }, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

/** The ticker invalidates just its card, not the recipe catalogue every second. */
@Composable private fun ActiveBookBake(session: BakingSession, onClick: () -> Unit) {
    var remaining by remember(session.id, session.revision) { mutableLongStateOf(session.remainingSeconds(SystemClock.elapsedRealtime())) }
    LaunchedEffect(session.id, session.revision) {
        while (true) { remaining = session.remainingSeconds(SystemClock.elapsedRealtime()); delay(1000) }
    }
    val step = session.currentStep
    val duration = (step.durationMinutes * 60L).coerceAtLeast(1)
    val progress = (1 - remaining.toFloat() / duration).coerceIn(0f, 1f)
    PaperBakeCard(session.recipe.name,
        if (session.isCompleted) "ИСПЕЧЕНО · СОХРАНИТЬ РЕЗУЛЬТАТ" else "ШАГ ${session.currentStepIndex + 1}/${session.recipe.timeline.size} · ${step.title}",
        recipePhoto(session.recipe.id), session.progress, onClick) {
        if (session.isCompleted) PaperNote("Результат ждёт в этой выпечке")
        else {
            TimerNumber(timerDigits(remaining), progress, 30.sp, 0.sp, LocalPaper.current.ink)
            if (session.isPaused) PaperNote("Пауза")
        }
    }
}

@Composable fun BookPortionsSheet(recipe: Recipe, factor: Double, onFactor: (Double) -> Unit, onDismiss: () -> Unit) {
    var mode by rememberSaveable(recipe.id) { mutableIntStateOf(0) }
    var input by rememberSaveable(recipe.id, mode) { mutableStateOf("") }
    var percent by rememberSaveable(recipe.id) { mutableStateOf(false) }
    val p = LocalPaper.current
    val parsed = input.replace(',', '.').toDoubleOrNull()
    val invalid = input.isNotBlank() && (parsed == null || !parsed.isFinite() || parsed <= 0)
    val baseMass = if(mode==1)RecipeScaler.totalFlourGrams(recipe) else RecipeScaler.totalDoughGrams(recipe)
    fun applyMass(value: Double) {
        if(value.isFinite() && value>0) {
            val next=if(mode==1)RecipeScaler.scaleByFlour(recipe,value) else RecipeScaler.scaleByTotalWeight(recipe,value)
            if(next.isFinite() && next>0 && (RecipeScaler.totalDoughGrams(recipe)*next).isFinite())onFactor(next)
        }
    }
    PaperSheet(onDismiss) {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BookText("На сколько печём", 28)
        Text(recipe.name, color = p.mut, style = PaperType.note)
        PaperSegmented(listOf("Порции", "По муке", "По тесту"), mode, { mode = it })
        if(mode==0) {
            PaperStepper("×${BookCatalog.number(factor)}", { onFactor((factor.roundToInt()-1).coerceAtLeast(1).toDouble()) },
                { onFactor((factor.roundToInt()+1).coerceAtMost(5).toDouble()) },minusEnabled=factor>1,plusEnabled=factor<5)
            PaperSegmented((1..5).map { "×$it" },factor.roundToInt().coerceIn(1,5)-1,{onFactor((it+1).toDouble())})
        } else {
            PaperField(input,{raw->input=raw;raw.replace(',','.').toDoubleOrNull()?.let(::applyMass)},
                label=if(mode==1)"Сколько муки, г" else "Общая масса, г",placeholder=BookCatalog.number(baseMass*factor),numeric=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),error=if(invalid)"Введите массу больше нуля" else null,
                modifier=Modifier.testTag("chapter-calculator-mass"))
            val mass=parsed?.takeIf{it.isFinite()&&it>0}?:baseMass*factor
            PaperStepper("${BookCatalog.number(mass)} г",{val next=(mass-100).coerceAtLeast(1.0);input=BookCatalog.number(next);applyMass(next)},
                {val next=mass+100;input=BookCatalog.number(next);applyMass(next)},minusEnabled=mass>1)
        }
        val whole = factor.roundToInt().takeIf { kotlin.math.abs(it - factor) < .0001 && it in 1..5 }
        PaperNote("×${BookCatalog.number(factor)} · веса во всей главе пересчитаны")
        if (whole != null) {
            RecipeScale.yieldText(recipe, whole)?.let { PaperNote(it) }
            RecipeScale.capacityNote(recipe, whole)?.let { PaperNote(it) }
        }
        PaperNote(RecipeScale.TIMING_NOTE)
        PaperSegmented(listOf("Граммы","% пекаря"),if(percent)1 else 0,{percent=it==1})
        recipe.ingredients.forEach { (section,ingredients) ->
            SmallLabel(com.polinalinen.madre.utils.ingredientSectionTitle(section))
            ingredients.forEach { ingredient ->
                if(percent && ingredient.refType==null && ingredient.gramsValue()>0)
                    PaperIngredientLine(ingredient.name,"${BookCatalog.number(RecipeScaler.bakerPercentage(ingredient,recipe),1)} %")
                else PaperNote(RecipeScaler.scaledDisplayText(ingredient,factor))
            }
        }
        if(percent) {
            val flour=RecipeScaler.totalFlourGrams(recipe)
            val water=recipe.ingredients.values.flatten().filter{it.refType==null&&it.category=="water"}.sumOf{it.gramsValue()}
            if(flour>0)PaperNote("Суммарно воды · ${BookCatalog.number(water/flour*100,1)} %")
        }
        PaperButton("Готово", onDismiss, Modifier.fillMaxWidth())
      }
    }
}
