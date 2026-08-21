package com.polinalinen.madre.viewmodel

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ShelfViewModelDoesNotWriteAccountTest {

    private fun source(): String {
        return File("src/main/java/com/polinalinen/madre/viewmodel/ShelfViewModel.kt")
            .readText()
    }

    @Test
    fun `shelf refresh receives account and does not call repository restore or refresh`() {
        val source = source()
        assertThat(source).contains("fun refresh(account: FamilyAccount?")
        assertThat(source).doesNotContain("familyAccountRepository.restore()")
        assertThat(source).doesNotContain("familyAccountRepository.refresh()")
    }
}
