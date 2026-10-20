package com.polinalinen.madre

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.services.storage.TestStorage
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.screens.Home
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI-скриншоты главных экранов для приёмки в Telegram.
 *
 * Это не golden-тест: ничего не сравнивает, только снимает экран на
 * эмуляторе и кладёт PNG в TestStorage. AGP забирает их в
 * `app/build/outputs/connected_android_test_additional_output/`, workflow
 * дополнительно делает `adb pull /sdcard/googletest/test_outputfiles` и
 * выкладывает всё артефактом `madre-screenshots`.
 */
@RunWith(AndroidJUnit4::class)
class MainScreensScreenshotTest {

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val recipes: List<Recipe> by lazy {
        runBlocking {
            RecipeRepository(InstrumentationRegistry.getInstrumentation().targetContext).getRecipes()
        }
    }

    private fun awaitTableOfContents() {
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithTag(Home.LIST_TAG).fetchSemanticsNodes().isNotEmpty() &&
                recipes.isNotEmpty() &&
                rule.onAllNodesWithTag(Home.chapterRowTag(recipes.first().id))
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        // Даём отрисоваться последнему кадру анимаций/шрифтов.
        Thread.sleep(SETTLE_MS)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: return
        TestStorage().openOutputFile("screenshots/$name.png").use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }

    @Test
    fun main_screens() {
        awaitTableOfContents()
        capture("01-home")

        val first = recipes.first()
        val tag = Home.chapterRowTag(first.id)
        rule.onNodeWithTag(Home.LIST_TAG).performScrollToNode(hasTestTag(tag))
        capture("02-table-of-contents")

        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
        capture("03-chapter-first")

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        val last = recipes.last()
        val lastTag = Home.chapterRowTag(last.id)
        rule.onNodeWithTag(Home.LIST_TAG).performScrollToNode(hasTestTag(lastTag))
        rule.onNodeWithTag(lastTag).performClick()
        rule.waitForIdle()
        capture("04-chapter-last")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val SETTLE_MS = 600L
    }
}
