package com.polinalinen.madre.ui.razvorot

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.time.Clock
import androidx.compose.ui.Alignment

@Composable fun BookTocContent(recipes: List<Recipe>, sessions: List<BakingSession>, bookmark: BookBookmark?,
    onChapter: (String, Int) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier, onAa: () -> Unit = {},
    profileOverride:com.polinalinen.madre.account.yandex.VerifiedYandexProfile?=null) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CatalogFilter.ALL) }
    val rows = remember(recipes, sessions, query, filter) { BookCatalog.search(recipes, query, filter, sessions) }
    val account=com.polinalinen.madre.account.yandex.LocalYandexAccountUi.current
    val accountState=account?.model?.state?.collectAsState()?.value
    val profile=profileOverride ?: (accountState as? com.polinalinen.madre.account.yandex.YandexAccountState.SignedIn)?.profile
    LazyColumn(modifier.fillMaxSize().testTag(Home.LIST_TAG),contentPadding=PaddingValues(horizontal=22.dp)) {
      item(key="toc-header",contentType="header") {
       Column(Modifier.padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallLabel("${LocalAtmosphere.current.label} · КНИГА СЕМЬИ", Modifier.weight(1f).padding(top = 18.dp))
            PaperTextButton(onAa,label="Как читать") { BookText("Aa",21) }
            PaperIconButton(onSettings, label = profile?.let{"Аккаунт Яндекса, ${it.displayName.ifBlank{it.login}}"} ?: "Настройки") {
                if(profile!=null)YandexAccountPortrait(profile,30.dp) else Icon(Icons.Outlined.Settings, null)
            }
        }

        BookText("Оглавление", 44, weight=androidx.compose.ui.text.font.FontWeight.Normal)
        val greeting=when(LocalAtmosphere.current) {
            Atmosphere.DAWN->"Доброе утро";Atmosphere.DAY->"Добрый день";Atmosphere.DUSK->"Добрый вечер"
            Atmosphere.NIGHT->"Доброй ночи";else->"Здравствуйте"
        }
        PaperNote(if(profile==null)"Мадре — книга рецептов на закваске · ${recipes.size} глав"
            else "$greeting, ${profile.displayName.ifBlank{profile.login}}.")
        bookmark?.let { saved -> recipes.firstOrNull { it.id == saved.recipeId }?.let { recipe ->
            val step=saved.step?.takeIf{it in recipe.timeline.indices}
            val position=if(saved.section!=null)"«${saved.section}»${step?.let{" · шаг ${it+1}"}.orEmpty()}"
                else "Страница ${saved.page+1}"
            PaperBookmarkCard(recipe.name,recipePhoto(recipe.id),saved.page+1,"$position — там, где вы остановились") {
                onChapter(saved.recipeId,saved.page)
            }
        } }
        PaperSearchField(query,{query=it},Modifier.heightIn(min=52.dp),
            label="Рецепт или ингредиент",iconSize=24.dp,inputModifier=Modifier.testTag("toc-search"))
        PaperChipRow(CatalogFilter.entries.map { if(it==CatalogFilter.ALL)"${it.label} ${recipes.size}" else it.label }, filter.ordinal, { filter = CatalogFilter.entries[it] })
       }
      }
        if (rows.isEmpty()) item(key="toc-empty",contentType="empty") { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            BookText("Такой главы нет", 24)
            PaperNote("Попробуйте другое название или ингредиент.")
            PaperTextAction("Показать все главы", { query = ""; filter = CatalogFilter.ALL })
        } } else items(rows,key={"recipe:${it.recipe.id}"},contentType={"recipe"}) { match -> Column {
            val recipe = match.recipe
            val active = sessions.firstOrNull { it.recipe.id == recipe.id && !it.isCompleted }
            PaperTocRow(match.chapter + 1, recipe.name, BookCatalog.shortTotalTime(RecipeScale.totalMinutes(recipe, 1)),
                match.ingredient?.let { "есть: $it · ${difficultyLabel(recipe.difficulty)}" }
                    ?: "${recipe.description.trimEnd('.')} · ${difficultyLabel(recipe.difficulty)}",
                { onChapter(recipe.id, -1) }, modifier=Modifier.testTag(Home.chapterRowTag(recipe.id)),
                activeLabel = active?.let { "В печи · ${BakeNumbers.label(it.bakeNumber)} · шаг ${it.currentStepIndex + 1} из ${recipe.timeline.size}" }, query = query,
                spokenTime=com.polinalinen.madre.notifications.BakingProgress.spokenRemaining(RecipeScale.totalMinutes(recipe,1)*60L))
        } }
        item(key="toc-colophon",contentType="footer") { Column(Modifier.padding(top=8.dp,bottom=30.dp)) {
            PaperNote("— тираж: одна семья · печатается с любовью —")
        } }
    }
}

@Composable fun BookOvenContent(recipes: List<Recipe>, sessions: List<BakingSession>, plans: List<PlannedBake>,
    onOpenSession: (Long) -> Unit, onConfigure: (Recipe) -> Unit, onQuickStart: (Recipe) -> Unit,
    onOpenPlan: (PlannedBake) -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier,
    onAa: () -> Unit = {}, pendingFinished: List<Pair<Long,BakingViewModel.BakeCompletion>> = emptyList(),
    clock:Clock=LocalBookClock.current,onStartPlan:(PlannedBake)->Unit={},onSnoozePlan:(PlannedBake)->Unit={},plansBusy:Boolean=false) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val p = LocalPaper.current
    val now=rememberBookNow(clock)
    val choices = listOf("Все", "Успеть к ужину", "До 3 часов", "С вечера — к утру")
    val rows = remember(recipes, filter,now,clock) { recipes.filter { recipe ->
        val minutes = RecipeScale.totalMinutes(recipe, 1)
        when (filter) { 1 -> com.polinalinen.madre.navigation.BakePlanning.options(minutes,now,clock.zone).first().available
            2 -> minutes <= 180; 3 -> minutes >= 480; else -> true }
    } }
    val visiblePlans=remember(plans,recipes){plans.mapNotNull{plan->recipes.firstOrNull{it.id==plan.recipeId}?.let{plan to it}}}
    val count=sessions.size+visiblePlans.size+pendingFinished.size
    val recipePairs=remember(rows){rows.chunked(2)}
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
      item(key="oven-header",contentType="header") { Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallLabel("ПЕЧЬ · ${java.time.Instant.ofEpochMilli(now).atZone(clock.zone).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM",Locale.forLanguageTag("ru")))}",Modifier.weight(1f).padding(top=16.dp))
            PaperTextButton(onAa,label="Как читать") { BookText("Aa",21) }
        }
        if(count>0)PaperCountHeading("Уже печём",count) else Text("Уже печём",style=PaperType.display,color=p.ink)
      } }
        if (count>0) {
            items(sessions,key={"session:${it.id}"},contentType={"active-bake"}) { session -> ActiveBookBake(session) { onOpenSession(session.id) } }
        } else {
            item(key="oven-empty",contentType="empty") { PaperNote("Сейчас ничего не печётся. Выберите главу ниже — запуск в одно касание.") }
        }
        items(pendingFinished,key={"finished:${it.first}"},contentType={"finished-bake"}) { (id,completion) -> PaperCard(Modifier.fillMaxWidth()) {
            PaperBakeNumber(completion.bakeNumber)
            SmallLabel("ИСПЕЧЕНО · ВПИШИТЕ В КНИГУ",color=p.rib)
            Text(completion.recipeName,style=PaperType.title,color=p.ink)
            PaperTextAction("Вписать в книгу",{onOpenSession(id)})
        } }
        items(visiblePlans,key={"plan:${it.first.id}"},contentType={"planned-bake"}) { (plan,recipe) ->
            val overdue=plan.startAtMillis<now
            PaperBakeCard(recipe.name,if(overdue)"ПОРА НАЧИНАТЬ · ×${plan.portions}" else "ЗАПЛАНИРОВАНО · ×${plan.portions}",
                recipePhoto(recipe.id),0f,{onOpenPlan(plan)},overlineColor=if(overdue)p.rib else p.mut,bakeNumber=plan.number,planned=true,
                actions={if(overdue)Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        PaperButton("Начать",{onStartPlan(plan)},Modifier.weight(1f),enabled=!plansBusy,variant=PaperButtonVariant.ACCENT)
                        PaperButton("Через 30 мин",{onSnoozePlan(plan)},Modifier.weight(1f),enabled=!plansBusy,variant=PaperButtonVariant.SECONDARY)
                    }}) {
                PaperNote(if(overdue)"План ждёт вас — таймер ещё не запущен" else "начнём ${relativeBookTime(plan.startAtMillis,now,clock)}")
            }
        }
      item(key="oven-choices",contentType="filters") { Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Что испечь",style=PaperType.h2,color=p.ink,modifier=Modifier.padding(top=10.dp))
        Text("▶ — сразу, тап — с настройкой",style=PaperType.note,color=p.ink2)
        PaperChipRow(choices, filter, { filter = it })
      } }
        if (rows.isEmpty()) item(key="recipes-empty",contentType="empty") { PaperNote("Таких рецептов в книге пока нет. Выберите другое время.") }
        items(recipePairs,key={pair->"recipes:"+pair.joinToString(":"){it.id}},contentType={"recipe-pair"}) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { recipe -> key(recipe.id) { PaperRecipeTile(recipes.indexOf(recipe) + 1, recipe.name, recipePhoto(recipe.id),
                    "${BookCatalog.shortTotalTime(RecipeScale.totalMinutes(recipe,1))} · руками ${BookCatalog.duration(recipe.timeline.filter{it.type==StepType.ACTION}.sumOf{it.durationMinutes})}",
                    "готово ${relativeBookTime(Math.addExact(now,RecipeScale.totalMinutes(recipe,1)*60_000L),now,clock)}",
                    { onConfigure(recipe) }, { onQuickStart(recipe) }, Modifier.weight(1f)) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item(key="oven-bottom",contentType="footer") { Spacer(Modifier.height(22.dp)) }
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
        if (session.isCompleted) "ИСПЕЧЕНО · ВПИШИТЕ В КНИГУ" else "ШАГ ${session.currentStepIndex + 1}/${session.recipe.timeline.size} · ${step.title}",
        recipePhoto(session.recipe.id), session.progress, onClick,bakeNumber=session.bakeNumber) {
        if (session.isCompleted) PaperNote("Результат ждёт в этой выпечке")
        else {
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TimerNumber(timerDigits(remaining), progress, 30.sp, 0.sp, LocalPaper.current.ink)
                Text(if(session.isPaused)"пауза" else if(step.type==StepType.WAIT)"ждём" else "руками",
                    style=PaperType.caption,color=LocalPaper.current.ink2,modifier=Modifier.padding(bottom=4.dp))
            }
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
            PaperSlider(factor.toFloat().coerceIn(1f,5f),{onFactor(it.roundToInt().coerceIn(1,5).toDouble())},
                valueRange=1f..5f,steps=3,modifier=Modifier.fillMaxWidth().testTag("quantity-scale"))
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
        }
        PaperNote(if(whole!=null && RecipeScale.batches(recipe,whole)>1)
            "≈${RecipeScale.OVEN_BATCH_GRAMS} г за заход · время прежнее" else "Время этапов прежнее")
        PaperSegmented(listOf("Граммы","% пекаря"),if(percent)1 else 0,{percent=it==1})
        recipe.ingredients.forEach { (section,ingredients) ->
            SmallLabel(com.polinalinen.madre.utils.ingredientSectionTitle(section))
            ingredients.forEach { ingredient ->
                if(percent && ingredient.refType==null && ingredient.gramsValue()>0)
                    PaperIngredientLine(ingredient.name,"${BookCatalog.number(RecipeScaler.bakerPercentage(ingredient,recipe),1)} %")
                else ingredientDisplayParts(RecipeScaler.scaledDisplayText(ingredient,factor)).let{(name,amount)->PaperIngredientLine(name,amount)}
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
