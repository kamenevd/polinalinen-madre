package com.polinalinen.madre.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 11, «Спокойный режим»: книга по умолчанию не крутит непрерывных и
 * интерактивных декораций — прокрутка важнее пылинок. Настройка живёт в
 * madre_prefs; здесь проверяется само правило, без Android — хранилище
 * подменяется картой в памяти (см. FlagStore).
 */
class CalmModeSettingTest {

    private class FakeStore(val values: MutableMap<String, Boolean> = mutableMapOf()) : FlagStore {
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key] ?: defaultValue

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
    }

    @Test
    fun `a fresh book opens in calm mode`() {
        assertThat(CalmModeSetting(FakeStore()).isCalm()).isTrue()
        assertThat(CalmModeSetting.DEFAULT).isTrue()
    }

    @Test
    fun `toggling switches between calm and full decoration`() {
        val setting = CalmModeSetting(FakeStore())
        assertThat(setting.toggle()).isFalse()
        assertThat(setting.isCalm()).isFalse()
        assertThat(setting.toggle()).isTrue()
        assertThat(setting.isCalm()).isTrue()
    }

    @Test
    fun `the choice survives a restart of the app`() {
        val store = FakeStore()
        CalmModeSetting(store).setCalm(false)
        // Новый объект над тем же хранилищем — как после перезапуска процесса.
        assertThat(CalmModeSetting(store).isCalm()).isFalse()

        CalmModeSetting(store).setCalm(true)
        assertThat(CalmModeSetting(store).isCalm()).isTrue()
    }

    @Test
    fun `the setting is written under a stable madre_prefs key`() {
        val store = FakeStore()
        CalmModeSetting(store).setCalm(false)
        assertThat(store.values).containsEntry(CalmModeSetting.KEY, false)
        assertThat(CalmModeSetting.KEY).isEqualTo("calm_mode")
    }

    @Test
    fun `the settings row reads as plain Russian`() {
        assertThat(CalmModeSetting.label(true)).isEqualTo("спокойное")
        assertThat(CalmModeSetting.label(false)).isEqualTo("полное")
    }
}
