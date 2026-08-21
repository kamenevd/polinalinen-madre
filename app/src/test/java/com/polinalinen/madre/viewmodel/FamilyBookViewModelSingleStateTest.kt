package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.account.FamilyAccountRepository
import com.polinalinen.madre.account.InMemoryTokenStore
import com.polinalinen.madre.account.ScriptedFamilyApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FamilyBookViewModelSingleStateTest {

    @Test
    fun `viewmodel keeps only password reset mutable state flow`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = FamilyAccountRepository(ScriptedFamilyApi(), InMemoryTokenStore())
        val viewModel = FamilyBookViewModel(app, repository)

        val mutableFlows = FamilyBookViewModel::class.java.declaredFields
            .filter { MutableStateFlow::class.java.isAssignableFrom(it.type) }
            .map { it.name }

        assertThat(mutableFlows).containsExactly("_passwordReset")
        assertThat(viewModel.state).isSameInstanceAs(repository.state)
    }
}
