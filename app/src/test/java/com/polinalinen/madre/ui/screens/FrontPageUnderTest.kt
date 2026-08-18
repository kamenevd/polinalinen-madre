package com.polinalinen.madre.ui.screens

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.ui.theme.MadreTheme
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * Первая полоса под тестом. Общий двор для тех проверок Cycle 18, которым
 * нужна не одна кнопка, а вся страница целиком: настоящие рецепты из assets и
 * настоящий Room под [MadreApplication].
 */

/**
 * Первая полоса поднимается небыстро: рецепты едут из assets, книга — из
 * Room, и всё это на настоящем IO. Срок взят с запасом, чтобы ловить «не
 * появилось никогда», а не «появилось не сразу».
 */
internal const val FRONT_PAGE_TIMEOUT_MS = 60_000L

/**
 * Ждать появления чего-то на первой полосе — по настоящим часам.
 *
 * `waitUntil` здесь не годится: он меряет свой таймаут виртуальным временем, и
 * пятнадцать его секунд истекают за доли настоящей — а страница ждёт assets и
 * Room на Dispatchers.IO, то есть настоящий поток и настоящие миллисекунды.
 * Поэтому срок настоящий, а композиция подталкивается вручную: лупер
 * прокручивает продолжения, проснувшиеся после withContext(IO), кадр рисует
 * то, что они принесли, sleep отдаёт время самому IO.
 */
internal fun ComposeContentTestRule.awaitOnPage(what: String, present: () -> Boolean) {
    val deadline = System.currentTimeMillis() + FRONT_PAGE_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        if (present()) return
        shadowOf(Looper.getMainLooper()).idle()
        mainClock.advanceTimeByFrame()
        Thread.sleep(10)
    }
    throw AssertionError("$what не появилось на первой полосе за $FRONT_PAGE_TIMEOUT_MS мс")
}

/** Те же рецепты, что читает книга, — из тех же assets. */
internal fun frontPageRecipes(): List<Recipe> = runBlocking {
    RecipeRepository(ApplicationProvider.getApplicationContext()).getRecipes()
}

internal fun ComposeContentTestRule.showFrontPage(
    phase: GrowthPhase = GrowthPhase.HUNGRY,
    starterName: String = "Мадре",
    lastFeedingMillis: Long? = null,
    intervalHours: Int = 24,
    latestHydrationPercent: Int? = null,
    nowMillis: Long = System.currentTimeMillis(),
    onOpenRecipe: (String) -> Unit = {},
    onOpenStarter: () -> Unit = {},
    onOpenTimer: (Long) -> Unit = {},
    onOpenFeeding: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenShelf: () -> Unit = {},
) {
    setContent {
        MadreTheme {
            HomeScreen(
                madreHeadline = "я проголодалась!",
                starterName = starterName,
                phase = phase,
                lastFeedingMillis = lastFeedingMillis,
                intervalHours = intervalHours,
                latestHydrationPercent = latestHydrationPercent,
                nowMillis = nowMillis,
                onOpenRecipe = onOpenRecipe,
                onOpenStarter = onOpenStarter,
                onOpenTimer = onOpenTimer,
                onOpenFeeding = onOpenFeeding,
                onOpenSettings = onOpenSettings,
                onOpenShelf = onOpenShelf,
            )
        }
    }
}
