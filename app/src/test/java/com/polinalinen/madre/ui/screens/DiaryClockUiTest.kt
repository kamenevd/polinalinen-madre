package com.polinalinen.madre.ui.screens

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.ui.kit.Atmosphere
import com.polinalinen.madre.ui.kit.RazvorotTheme
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class DiaryClockUiTest {
    @get:Rule val rule = createComposeRule()
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var time = 3_541_000L
    private var reads = 0
    private val provider: () -> Long = { reads++; time }
    private val visible = mutableStateOf(true)
    private val owner = mutableStateOf(Owner())
    private val feeding = FeedingEntity(id = 1, sourdoughConfigId = 1, timestampMillis = 1_000,
        flourGrams = 100, waterGrams = 50,
        storageLocation = com.polinalinen.madre.data.db.entities.StorageLocation.KITCHEN)

    private fun show(fixed: Long? = null, restoration: StateRestorationTester? = null) {
        rule.runOnUiThread { owner.value.registry.currentState = Lifecycle.State.RESUMED }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            CompositionLocalProvider(LocalLifecycleOwner provides owner.value) {
                RazvorotTheme(force = Atmosphere.DAY) {
                    if (visible.value) StarterDiaryScreen(2, GrowthPhase.PEAK, profileForInterval(1),
                        emptyList(), listOf(feeding), {}, {}, intervalHours = 1,
                        nowMillis = fixed, nowProvider = provider)
                }
            }
        }
        if (restoration == null) rule.setContent(content) else restoration.setContent(content)
    }
    private fun move(state: Lifecycle.State) = rule.runOnUiThread { owner.value.registry.currentState = state }
    private fun minute() {
        rule.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60)) }
        rule.waitForIdle()
    }
    private fun elapsed(text: String) = rule.onNodeWithTag("diary-elapsed").assertTextEquals(text)

    @Test fun `foreground minute refreshes elapsed and existing schedule without recalculating phase`() {
        show()
        elapsed("0 ч 59 мин")
        rule.onNodeWithText("Следующее кормление", substring = true).assertExists()
        rule.runOnIdle { time += 60_000 }
        minute()
        elapsed("1 ч 0 мин")
        rule.onNodeWithText("Кормление уже по вашему расписанию").assertExists()
        rule.onNodeWithTag("diary-phase-PEAK").assertExists()
    }

    @Test fun `start and resume refresh immediately while stopped clock does not read or tick`() {
        show()
        move(Lifecycle.State.CREATED)
        val before = reads
        rule.runOnIdle { time = 7_201_000 }
        minute()
        assertThat(reads).isEqualTo(before)
        elapsed("0 ч 59 мин")
        move(Lifecycle.State.STARTED)
        elapsed("2 ч 0 мин")
        rule.runOnIdle { time = 10_801_000 }
        move(Lifecycle.State.RESUMED)
        elapsed("3 ч 0 мин")
    }

    @Test fun `leaving composition removes lifecycle observer and queued minute callback`() {
        show()
        val old = owner.value
        assertThat(old.registry.observerCount).isGreaterThan(0)
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        val before = reads
        assertThat(old.registry.observerCount).isEqualTo(0)
        minute()
        move(Lifecycle.State.CREATED)
        move(Lifecycle.State.RESUMED)
        assertThat(reads).isEqualTo(before)
    }

    @Test fun `owner replacement and saved state restore detach old clock and refresh new instance`() {
        val restoration = StateRestorationTester(rule)
        show(restoration = restoration)
        val old = owner.value
        rule.runOnUiThread {
            time = 7_201_000
            owner.value = Owner().also { it.registry.currentState = Lifecycle.State.RESUMED }
        }
        elapsed("2 ч 0 мин")
        assertThat(old.registry.observerCount).isEqualTo(0)
        assertThat(owner.value.registry.observerCount).isEqualTo(1)
        val before = reads
        rule.runOnUiThread {
            old.registry.currentState = Lifecycle.State.CREATED
            old.registry.currentState = Lifecycle.State.RESUMED
        }
        assertThat(reads).isEqualTo(before)
        rule.runOnIdle { time = 10_801_000 }
        restoration.emulateSavedInstanceStateRestore()
        elapsed("3 ч 0 мин")
        assertThat(owner.value.registry.observerCount).isEqualTo(1)
        rule.runOnIdle { time += 60_000 }
        minute()
        elapsed("3 ч 1 мин")
    }

    @Test fun `explicit now override remains deterministic through ticking lifecycle and restore`() {
        val restoration = StateRestorationTester(rule)
        show(fixed = 3_541_000, restoration = restoration)
        rule.runOnIdle { time = 99_001_000 }
        minute()
        move(Lifecycle.State.CREATED)
        move(Lifecycle.State.RESUMED)
        restoration.emulateSavedInstanceStateRestore()
        elapsed("0 ч 59 мин")
        assertThat(reads).isEqualTo(0)
        assertThat(owner.value.registry.observerCount).isEqualTo(0)
    }
}
