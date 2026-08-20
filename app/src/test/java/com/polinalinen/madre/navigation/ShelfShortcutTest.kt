package com.polinalinen.madre.navigation

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.shelf.FamilyShelf
import com.polinalinen.madre.ui.theme.MadreTheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 27: с первой полосы «Полка» открывает корешки, а не сразу свою книгу.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class ShelfShortcutTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun startWorkManager() {
        runCatching {
            WorkManager.initialize(
                ApplicationProvider.getApplicationContext(),
                Configuration.Builder().build(),
            )
        }
    }

    @Test
    fun `the front page opens the family shelf`() {
        lateinit var navController: NavHostController
        rule.setContent {
            MadreTheme {
                navController = rememberNavController()
                MadreNavHost(navController = navController)
            }
        }

        rule.onNodeWithText("Полка").performClick()
        rule.waitForIdle()

        assertThat(navController.currentBackStackEntry?.destination?.route)
            .isEqualTo(MadreDestinations.SHELF)
    }

    @Test
    fun `coming back from the shelf lands on the front page`() {
        lateinit var navController: NavHostController
        rule.setContent {
            MadreTheme {
                navController = rememberNavController()
                MadreNavHost(navController = navController)
            }
        }

        rule.onNodeWithText("Полка").performClick()
        rule.waitForIdle()
        navController.popBackStack()
        rule.waitForIdle()

        assertThat(navController.currentBackStackEntry?.destination?.route)
            .isEqualTo(MadreDestinations.HOME)
    }

    @Test
    fun `the empty uncut spine is not a button`() {
        rule.setContent {
            MadreTheme {
                MadreNavHost()
            }
        }
        rule.onNodeWithText("Полка").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(FamilyShelf.UNCUT_SPINE_TAG).assertExists()
        rule.onNodeWithTag(FamilyShelf.UNCUT_SPINE_TAG).assertHasNoClickAction()
        rule.onNodeWithText(FamilyShelf.CAPTION).assertExists()
    }
}
