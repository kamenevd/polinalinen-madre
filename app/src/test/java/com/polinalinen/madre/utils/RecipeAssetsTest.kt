package com.polinalinen.madre.utils

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.R
import org.junit.Test

/**
 * Cycle 19: hero только через явный R.drawable — иначе shrinkResources
 * выкидывает webp из release.
 */
class RecipeAssetsTest {

    private val bookIds = listOf(
        "home_bread",
        "family_bread",
        "pirozhki",
        "belyashi",
        "ciabatta",
        "focaccia",
        "pizza",
        "waffles",
        "pancakes",
        "cinnamon_buns",
        "garlic_buns",
    )

    @Test
    fun `every chapter in the book has a hero drawable id`() {
        bookIds.forEach { id ->
            assertThat(heroResFor(id)).isNotNull()
        }
    }

    @Test
    fun `hero map points at real drawable constants not zero`() {
        assertThat(heroResFor("home_bread")).isEqualTo(R.drawable.hero_home_bread)
        assertThat(heroResFor("cinnamon_buns")).isEqualTo(R.drawable.hero_cinnamon_buns)
        assertThat(heroResFor("no_such_loaf")).isNull()
    }

    @Test
    fun `ingredient section keys speak Russian on the page`() {
        assertThat(ingredientSectionTitle("sponge")).isEqualTo("Опара")
        assertThat(ingredientSectionTitle("sponge1")).isEqualTo("Опара 1")
        assertThat(ingredientSectionTitle("sponge2")).isEqualTo("Опара 2")
        assertThat(ingredientSectionTitle("main")).isEqualTo("Тесто")
        assertThat(ingredientSectionTitle("dough")).isEqualTo("Тесто")
        assertThat(ingredientSectionTitle("filling")).isEqualTo("Начинка")
        assertThat(ingredientSectionTitle("cream")).isEqualTo("Крем")
        // Никакого сырого english key в заголовке разворота.
        assertThat(ingredientSectionTitle("filling")).doesNotContain("filling")
        assertThat(ingredientSectionTitle("dough")).doesNotContain("dough")
    }
}
