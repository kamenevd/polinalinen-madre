package com.polinalinen.madre.ui.visual

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.ui.screens.StarterDiaryScreen
import com.polinalinen.madre.ui.theme.MadreTheme
import java.util.Calendar
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class StarterDiaryGoldenTest {

    private fun momentOf(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
    }

    @Test
    fun `starter formulary keeps full header and widest row`() {
        val profile = profileForInterval(24)
        val history = listOf(
            FeedingEntity(
                id = 10,
                sourdoughConfigId = 1,
                timestampMillis = momentOf(2025, Calendar.AUGUST, 18, 8, 0),
                flourGrams = 150,
                waterGrams = 75,
                storageLocation = StorageLocation.KITCHEN,
                retainedStarterGrams = 45,
                finalHydrationPercent = 86,
                generatedComment = "Перед самой широкой строкой, чтобы проверить формат года.",
            ),
            FeedingEntity(
                id = 9,
                sourdoughConfigId = 1,
                timestampMillis = momentOf(2026, Calendar.AUGUST, 18, 12, 0),
                flourGrams = 500,
                waterGrams = 300,
                storageLocation = StorageLocation.KITCHEN,
                retainedStarterGrams = 250,
                finalHydrationPercent = 99,
                generatedComment = "Готов к проверке ширины.",
                notes = "длинная строка заметки, которая не должна ломать таблицу",
            ),
            FeedingEntity(
                id = 8,
                sourdoughConfigId = 1,
                timestampMillis = momentOf(2026, Calendar.AUGUST, 17, 9, 30),
                flourGrams = 60,
                waterGrams = 30,
                storageLocation = StorageLocation.FRIDGE,
                // Legacy row without finalHydrationPercent intentionally remains "не указана".
                generatedComment = "В прошлый раз была старая запись.",
            ),
        )

        captureRoboImage {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StarterDiaryScreen(
                        dayNumber = 22,
                        phase = GrowthPhase.GROWING,
                        profile = profile,
                        entries = emptyList(),
                        history = history,
                        onBack = {},
                        onFeed = {},
                        onOpenGallery = {},
                        cancelledBakeCount = 0,
                        starterName = "Мадре",
                        intervalHours = 24,
                        currentYear = 2026,
                    )
                }
            }
        }
    }
}
