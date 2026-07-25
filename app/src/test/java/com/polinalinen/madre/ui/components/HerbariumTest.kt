package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.model.Season
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 8, фича «Гербарий» (Herbarium): у каждого сезона свой
 * набор находок, высыхание идёт месяц и клампится, отпечаток проступает
 * с высыханием, а запись в SharedPreferences переживает roundtrip.
 */
class HerbariumTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    @Test
    fun `every season offers its own pair of finds`() {
        val allIds = Season.entries.flatMap { season ->
            val specimens = Herbarium.specimensFor(season)
            assertThat(specimens).hasSize(2)
            specimens.map { it.id }
        }
        // Наборы не пересекаются: рябина не растёт летом.
        assertThat(allIds).containsNoDuplicates()
    }

    @Test
    fun `seasonal finds match the season`() {
        assertThat(Herbarium.specimensFor(Season.WINTER).map { it.id }).contains("spruce")
        assertThat(Herbarium.specimensFor(Season.SUMMER).map { it.id }).contains("chamomile")
        assertThat(Herbarium.specimensFor(Season.AUTUMN).map { it.id }).contains("maple")
        assertThat(Herbarium.specimensFor(Season.SPRING).map { it.id }).contains("cherry")
    }

    @Test
    fun `specimen lookup finds every known id and rejects strangers`() {
        Season.entries.flatMap { Herbarium.specimensFor(it) }.forEach {
            assertThat(Herbarium.specimenById(it.id)).isEqualTo(it)
        }
        assertThat(Herbarium.specimenById("tumbleweed")).isNull()
    }

    @Test
    fun `a fresh find is not dry and a month old one is`() {
        assertThat(Herbarium.dryness(now, now)).isEqualTo(0f)
        assertThat(Herbarium.dryness(now - 15 * day, now)).isWithin(0.01f).of(0.5f)
        assertThat(Herbarium.dryness(now - Herbarium.DRY_AFTER_DAYS * day, now)).isEqualTo(1f)
        assertThat(Herbarium.dryness(now - 400 * day, now)).isEqualTo(1f)
        assertThat(Herbarium.dryness(0L, now)).isEqualTo(0f)
        assertThat(Herbarium.dryness(now + day, now)).isEqualTo(0f)
    }

    @Test
    fun `the imprint appears only as the find dries`() {
        assertThat(Herbarium.imprintAlpha(0f)).isEqualTo(0f)
        assertThat(Herbarium.imprintAlpha(0.5f)).isGreaterThan(0f)
        assertThat(Herbarium.imprintAlpha(1f)).isGreaterThan(Herbarium.imprintAlpha(0.5f))
        // Отпечаток — след, а не пятно: даже у высохшей находки он бледный.
        assertThat(Herbarium.imprintAlpha(1f)).isAtMost(0.12f)
    }

    @Test
    fun `caption follows the drying`() {
        assertThat(Herbarium.caption(0f)).isEqualTo("вложено недавно")
        assertThat(Herbarium.caption(0.5f)).isEqualTo("подсыхает между страниц")
        assertThat(Herbarium.caption(1f)).isEqualTo("высох и оставил след на странице")
    }

    @Test
    fun `a stored record survives the roundtrip`() {
        val raw = Herbarium.record("chamomile", now)
        val parsed = Herbarium.parse(raw)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.first.id).isEqualTo("chamomile")
        assertThat(parsed.second).isEqualTo(now)
    }

    @Test
    fun `garbage in the prefs keeps the section silent`() {
        assertThat(Herbarium.parse(null)).isNull()
        assertThat(Herbarium.parse("")).isNull()
        assertThat(Herbarium.parse("chamomile")).isNull()
        assertThat(Herbarium.parse("tumbleweed|123")).isNull()
        assertThat(Herbarium.parse("chamomile|not-a-number")).isNull()
        assertThat(Herbarium.parse("a|b|c")).isNull()
    }
}
