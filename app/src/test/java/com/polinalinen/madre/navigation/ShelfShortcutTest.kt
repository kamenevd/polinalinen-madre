package com.polinalinen.madre.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.theme.MadreTheme
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 18: летопись — в один тап с первой полосы.
 *
 * «Полка» вела на промежуточный экран, где стояла ровно одна корешок-книга —
 * своя, — и по ней надо было нажать ещё раз, чтобы дойти до формуляра. Полка
 * имеет смысл, когда книг несколько; пока книга одна, это лишний разворот на
 * каждодневной дороге.
 *
 * Проверяется маршрут, а не заголовок: заголовок можно поставить где угодно, а
 * дорога — это то, куда действительно уехал NavController.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class ShelfShortcutTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Книга целиком — это и напоминание о кормлении: SourdoughViewModel
     * перепланирует его на каждое изменение конфига, а WorkManager сам себя в
     * тестах не поднимает (инициализатор из манифеста здесь не работает).
     * Без этого NavHost падает, не дойдя до первой полосы.
     */
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
    fun `the front page opens the chronicle of my own book`() {
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
            .isEqualTo(MadreDestinations.BOOK_STATS)
        assertThat(navController.currentBackStackEntry?.arguments?.getString("ownerId"))
            .isEqualTo("me")
    }

    /**
     * И обратно — на первую полосу, а не на промежуточный разворот, которого
     * человек не видел.
     */
    @Test
    fun `coming back from the chronicle lands on the front page`() {
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
}
