package com.polinalinen.madre.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StepIngredientsTest {
    private val recipes=Gson().fromJson(File(if(File("src/main/assets/recipes.json").exists())"src/main/assets/recipes.json" else "app/src/main/assets/recipes.json").readText(),RecipeDatabase::class.java).recipes
    private fun recipe(id:String)=recipes.single{it.id==id}
    @Test fun untemplatedAdditionsUseActualStageAndScaleWithoutLinkingNegativeFlour() {
        val bread=recipe("home_bread")
        val parts=StepIngredients.parts(bread,4,2)
        assertEquals(listOf("50 г","20 г","50 г"),parts.filter{it.ref!=null}.map{it.amount})
        assertEquals("Добавить сахар 50 г, месить 5 мин. Добавить соль 20 г. В конце — сливочное масло 50 г. Месить не менее 10 минут. Муку НЕ добавлять!",StepIngredients.text(parts))
        assertFalse(parts.any{it.ref?.name?.contains("муки")==true})
    }
    @Test fun explicitCrossStageTokensOverrideSpongeAndPartialWaterIsNotWholeWater() {
        val bread=StepIngredients.parts(recipe("home_bread"),2,3).filter{it.ref!=null}
        assertEquals(listOf("660 г","900 г","1545 г"),bread.map{it.amount})
        assertTrue(bread.all{it.ref!!.section=="main"})
        assertEquals("1050 мл",StepIngredients.parts(recipe("ciabatta"),0,3).single{it.ref?.name=="воды"}.amount)
        assertEquals("300 г",StepIngredients.parts(recipe("ciabatta"),2,3).single{it.ref?.name=="воды"}.amount)
    }
    @Test fun noAmbiguousOrMissingReferenceCanAcquireAnAmount() {
        val bread=recipe("home_bread");val main=bread.ingredients.getValue("main")
        val duplicate=bread.copy(ingredients=bread.ingredients+ ("main" to (main+main.single{it.name=="соли"})))
        assertFalse(StepIngredients.parts(duplicate,4,2).any{it.ref?.name=="соли"})
        val missing=bread.copy(ingredients=bread.ingredients-"main")
        assertTrue(StepIngredients.parts(missing,4,2).all{it.ref==null})
    }
    @Test fun allEditorialAddingStepsResolveAgainstExactRecipeRowsAtEveryScale() {
        val expected=mapOf("home_bread" to setOf(0,2,4),"family_bread" to setOf(0,2,4),"pirozhki" to setOf(0,2),"belyashi" to setOf(0,5),"ciabatta" to setOf(0,2,3),"focaccia" to setOf(0,2,4,5,6,9),"pizza" to setOf(0,2,4),"waffles" to setOf(0),"pancakes" to setOf(2,4),"cinnamon_buns" to setOf(0,2,4,6,11),"garlic_buns" to setOf(0))
        assertEquals(expected.keys,recipes.map{it.id}.toSet())
        recipes.forEach { recipe ->
            assertEquals(expected[recipe.id],StepIngredients.linkedSteps(recipe))
            for(index in StepIngredients.linkedSteps(recipe))for(scale in 1..5) {
                val parts=StepIngredients.parts(recipe,index,scale)
                assertEquals("${recipe.id}/$index",StepIngredients.linkCount(recipe,index),parts.count{it.ref!=null})
                assertFalse(StepIngredients.text(parts).contains("{{"))
                parts.filter{it.ref!=null}.forEach { part ->
                    assertEquals(1,recipe.ingredients[part.ref!!.section]!!.count{it.name==part.ref.name})
                    assertTrue(part.amount.isNotBlank());assertTrue(part.base.isNotBlank())
                }
            }
        }
    }
    @Test fun focacciaConsumesTheWholeLiteralQuantityPhrase() {
        val focaccia=StepIngredients.text(StepIngredients.parts(recipe("focaccia"),0,2))
        assertEquals("К закваске 30 г добавить воды 240 мл и муки 240 г. Перемешать, накрыть плёнкой.",focaccia)
        assertFalse(focaccia.contains("ст.л."))
        assertTrue(StepIngredients.text(StepIngredients.parts(recipe("focaccia"),0,5)).startsWith("К закваске 75 г"))
    }
    @Test fun fractionalEggsSpecialUnitsAndProseStayIntact() {
        val buns=recipe("cinnamon_buns")
        val starter=StepIngredients.parts(buns,0,3).first{it.ref!=null}
        assertEquals("113 г",starter.amount);assertEquals("37,5 г",starter.base)
        assertEquals("350 мл",StepIngredients.parts(recipe("ciabatta"),0,3).single{it.ref?.name=="воды"}.base)
        assertEquals("100 г",StepIngredients.parts(recipe("ciabatta"),2,3).single{it.ref?.name=="воды"}.base)
        assertEquals("220 г",StepIngredients.parts(recipe("home_bread"),2,3).first{it.ref!=null}.base)
        val bread=recipe("home_bread");val sponge=bread.ingredients.getValue("sponge").mapIndexed{i,row->if(i==0)row.copy(amount=row.amount+0.5) else row}
        val all=StepIngredients.parts(bread.copy(ingredients=bread.ingredients+("sponge" to sponge)),2,3).first{it.ref!=null}
        assertEquals("220,5 г",all.base);assertEquals("662 г",all.amount)
        assertEquals("6 шт",StepIngredients.parts(buns,4,3).first{it.ref!=null}.amount)
        assertTrue(StepIngredients.parts(buns,4,2).any{it.amount=="по вкусу"})
        assertTrue(StepIngredients.text(StepIngredients.parts(buns,6,2)).startsWith("Раскатать в пласт ~40×60 см."))
    }
}
