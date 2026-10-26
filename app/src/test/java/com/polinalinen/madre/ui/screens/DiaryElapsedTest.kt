package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiaryElapsedTest {
    @Test fun `minutes are elapsed not rounded or decimal hours`() {
        assertThat(diaryElapsedLabel(1_000, 25_921_999)).isEqualTo("7 ч 12 мин")
        assertThat(diaryElapsedLabel(1_000, 60_999)).isEqualTo("0 ч 0 мин")
        assertThat(diaryElapsedLabel(1_000, 61_000)).isEqualTo("0 ч 1 мин")
    }

    @Test fun `no timestamp or a future timestamp cannot mean zero elapsed`() {
        assertThat(diaryElapsedLabel(null, 100)).isNull()
        assertThat(diaryElapsedLabel(101, 100)).isNull()
        assertThat(diaryElapsedLabel(100, 100)).isEqualTo("0 ч 0 мин")
    }

    @Test fun `hours do not wrap at midnight or at a day`() {
        assertThat(diaryElapsedLabel(0, 445_500_000)).isEqualTo("123 ч 45 мин")
    }
}