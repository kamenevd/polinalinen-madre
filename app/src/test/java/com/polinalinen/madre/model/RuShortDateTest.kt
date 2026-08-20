package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import org.junit.Test

class RuShortDateTest {
    private fun date(year: Int): Long = Calendar.getInstance().apply {
        set(year, Calendar.AUGUST, 18, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun currentYearOmitsYear() {
        assertThat(RuShortDate.visible(date(2026), 2026)).isEqualTo("18 авг.")
    }

    @Test fun otherYearIncludesYear() {
        assertThat(RuShortDate.visible(date(2025), 2026)).isEqualTo("18 авг. 2025")
    }

    @Test fun accessibilityDateAlwaysIncludesFullYearAndTime() {
        assertThat(RuShortDate.accessible(date(2025))).isEqualTo("18 августа 2025, 12:00")
    }
}
