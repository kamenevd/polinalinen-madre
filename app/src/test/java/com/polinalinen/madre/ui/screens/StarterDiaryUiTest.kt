package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.notifications.FeedingSchedule
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.ui.theme.MadreTheme
import java.util.Calendar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 26: «Формуляр кормлений» — таблица. Шесть колонок целиком помещаются
 * в портрет, длинный человеческий текст живёт отдельным раскрытием, а старая
 * запись без гидратации говорит «не указана», а не подставляет число.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class StarterDiaryUiTest {
    @get:Rule val rule = createComposeRule()

    private fun momentOf(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private val computed = FeedingEntity(
        id = 7,
        sourdoughConfigId = 1,
        timestampMillis = momentOf(2025, Calendar.AUGUST, 18, 12, 0),
        flourGrams = 100,
        waterGrams = 50,
        storageLocation = StorageLocation.FRIDGE,
        retainedStarterGrams = 50,
        finalHydrationPercent = 50,
        generatedComment = "Записано 18 августа, 12:00. Расписание: каждые 24 ч.",
        notes = "пахнет яблоками",
    )

    /** Запись, сделанная до Cycle 26: масс закваски никто не называл. */
    private val legacy = FeedingEntity(
        id = 3,
        sourdoughConfigId = 1,
        timestampMillis = momentOf(2025, Calendar.AUGUST, 10, 9, 30),
        flourGrams = 60,
        waterGrams = 30,
        storageLocation = StorageLocation.KITCHEN,
    )

    /** Legacy row with old hydration field only, no calculated hydration. */
    private val legacyHydrationFallback = FeedingEntity(
        id = 4,
        sourdoughConfigId = 1,
        timestampMillis = momentOf(2025, Calendar.AUGUST, 11, 9, 45),
        flourGrams = 70,
        waterGrams = 40,
        storageLocation = StorageLocation.KITCHEN,
        hydrationPercent = 80,
    )

    /** Insertion-order authority with rolled-back timestamp and different final seed. */
    private val secondInsertWithEarlierTime = FeedingEntity(
        id = 6,
        sourdoughConfigId = 1,
        timestampMillis = momentOf(2025, Calendar.AUGUST, 13, 8, 0),
        finalHydrationPercent = 68,
        flourGrams = 65,
        waterGrams = 30,
        storageLocation = StorageLocation.KITCHEN,
    )
    private val laterByTimestampRow = FeedingEntity(
        id = 5,
        sourdoughConfigId = 1,
        timestampMillis = momentOf(2025, Calendar.AUGUST, 14, 9, 0),
        finalHydrationPercent = 82,
        flourGrams = 60,
        waterGrams = 35,
        storageLocation = StorageLocation.KITCHEN,
    )

    private fun show(history: List<FeedingEntity>, intervalHours: Int = 24) {
        rule.setContent {
            MadreTheme {
                StarterDiaryScreen(
                    dayNumber = 2,
                    phase = GrowthPhase.GROWING,
                    profile = profileForInterval(intervalHours),
                    entries = emptyList(),
                    history = history,
                    onBack = {},
                    onFeed = {},
                    intervalHours = intervalHours,
                    currentYear = 2026,
                )
            }
        }
    }

    @Test fun `actions precede the formulary and its sticky header`() {
        show(listOf(computed))

        val feedTop = rule.onNodeWithText("Покормить").fetchSemanticsNode().boundsInRoot.top
        val galleryTop = rule.onNodeWithText("Фото").fetchSemanticsNode().boundsInRoot.top
        val formularyTop = rule.onNodeWithText("ФОРМУЛЯР КОРМЛЕНИЙ").performScrollTo()
            .fetchSemanticsNode().boundsInRoot.top
        assertThat(feedTop).isLessThan(formularyTop)
        assertThat(galleryTop).isLessThan(formularyTop)
        // Шапка таблицы: шесть колонок, ровно те и в том порядке.
        listOf("Дата", "Закв.", "Мука", "Вода", "Гидр.", "Хранение").forEach { title ->
            rule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithContentDescription(
            "Дата, Закваска, Мука, Вода, Гидратация, Хранение"
        ).assertIsDisplayed()
        rule.onAllNodesWithText("Прошлые главы").assertCountEquals(0)
    }

    /** Строка читается одним предложением: шесть подписанных значений с единицами. */
    @Test fun `a computed row is announced as one ordered sentence`() {
        show(listOf(computed))
        rule.onNodeWithContentDescription(
            "18 августа 2025, 12:00. Закваска: 50 граммов. Мука: 100 граммов. " +
                "Вода: 50 граммов. Гидратация: 50 процентов. Хранение: холодильник."
        ).performScrollTo().assertIsDisplayed()
    }

    /** Старая запись не выдумывает ни закваску, ни гидратацию. */
    @Test fun `a legacy row says the hydration is unknown`() {
        show(listOf(legacy))
        rule.onNodeWithContentDescription(
            "10 августа 2025, 09:30. Закваска: не указана. Мука: 60 граммов. " +
                "Вода: 30 граммов. Гидратация: не указана. Хранение: кухня."
        ).performScrollTo().assertIsDisplayed()
    }

    /**
     * Заметка и комментарий — отдельное раскрытие под строкой, а не вместо
     * неё: пока не нажали, таблица остаётся таблицей.
     */
    @Test fun `the long detail is a separate optional disclosure`() {
        show(listOf(computed))
        rule.onAllNodesWithText("пахнет яблоками", substring = true).assertCountEquals(0)

        rule.onNodeWithText("ПОДРОБНЕЕ").performScrollTo().performClick()

        rule.onNodeWithText("Заметка: пахнет яблоками").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "Записано 18 августа, 12:00. Расписание: каждые 24 ч."
        ).performScrollTo().assertIsDisplayed()
        // Раскрытая запись без заметок и комментария раскрывать нечего.
        rule.onNodeWithText("СВЕРНУТЬ").assertExists()
    }

    /** Записи без заметки, комментария и фото раскрывать нечем. */
    @Test fun `a bare row offers no disclosure`() {
        show(listOf(legacy))
        rule.onAllNodesWithText("ПОДРОБНЕЕ").assertCountEquals(0)
    }

    /** Старые ручные поля показываются в списке именно как display-only seed. */
    @Test fun `legacy hydration with no final value is shown from legacy seed`() {
        show(listOf(legacyHydrationFallback))
        rule.onNodeWithText("80%", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("80 процентов", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Срок — точный местный, тот же расчёт, что на первой полосе. */
    @Test fun `the exact next feeding follows the settings interval`() {
        show(listOf(computed), intervalHours = 24)
        rule.onNodeWithText("Следующее кормление: 19 августа, 12:00")
            .performScrollTo().assertIsDisplayed()
    }

    /**
     * Точка опоры для срока — последняя вставка по id, а не максимальный
     * timestamp: это единственный способ пережить откат системных часов.
     */
    @Test fun `feeding due follows insertion-authoritative history`() {
        val authoritativeDue = FeedingSchedule.nextFeedingLabel(
            secondInsertWithEarlierTime.timestampMillis + 24 * 3_600_000L,
        )
        val nonAuthoritativeDue = FeedingSchedule.nextFeedingLabel(
            laterByTimestampRow.timestampMillis + 24 * 3_600_000L,
        )

        show(listOf(secondInsertWithEarlierTime, laterByTimestampRow), intervalHours = 24)
        rule.onNodeWithText(authoritativeDue).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(nonAuthoritativeDue).assertDoesNotExist()
    }

    /** Кормлений нет — срока нет: выдумывать его не от чего. */
    @Test fun `an empty diary shows no next feeding`() {
        show(emptyList())
        rule.onAllNodesWithText("Следующее кормление", substring = true).assertCountEquals(0)
    }
}
