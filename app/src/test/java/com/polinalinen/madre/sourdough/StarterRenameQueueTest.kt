package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 14: имя, набранное раньше, чем книга открылась.
 *
 * Конфиг закваски приезжает из Room асинхронно. До этой правки переименование,
 * набранное в эти доли секунды, молча пропадало: поле показывало «Соня»,
 * человек уходил из колофона, а в базе оставалась «Мадре» — экран говорил
 * «сохранено» там, где сохранять было нечем.
 */
class StarterRenameQueueTest {

    private val queue = StarterRenameQueue()

    @Test
    fun `a rename after the config loaded goes straight into that config`() {
        assertThat(queue.rename(configId = 7L, name = "Соня")).isEqualTo(7L)
        // Очередь при этом пуста — писать второй раз нечего.
        assertThat(queue.flush()).isNull()
    }

    /** Писать некуда — но и терять нечего: имя ждёт конфига. */
    @Test
    fun `a rename typed before the config loaded waits instead of vanishing`() {
        assertThat(queue.rename(configId = null, name = "Соня")).isNull()
        assertThat(queue.flush()).isEqualTo("Соня")
    }

    @Test
    fun `only the last name typed while waiting reaches the book`() {
        queue.rename(configId = null, name = "Со")
        queue.rename(configId = null, name = "Сон")
        queue.rename(configId = null, name = "Соня")
        assertThat(queue.flush()).isEqualTo("Соня")
    }

    @Test
    fun `nothing typed means nothing to write when the config arrives`() {
        assertThat(queue.flush()).isNull()
    }

    @Test
    fun `a queued name is written once and then forgotten`() {
        queue.rename(configId = null, name = "Соня")
        assertThat(queue.flush()).isEqualTo("Соня")
        assertThat(queue.flush()).isNull()
    }

    /**
     * Тонкое место: конфиг успел приехать между набором и записью. То, что
     * набрано ПОСЛЕ загрузки, новее — и очередь не имеет права затереть его
     * тем, что ждало в ней с прошлой секунды.
     */
    @Test
    fun `a name typed after the config loaded is not overwritten by the queued one`() {
        queue.rename(configId = null, name = "Соня")
        assertThat(queue.rename(configId = 7L, name = "Борис")).isEqualTo(7L)
        assertThat(queue.flush()).isNull()
    }
}
