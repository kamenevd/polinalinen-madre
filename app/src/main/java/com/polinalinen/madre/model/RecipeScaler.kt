package com.polinalinen.madre.model

import kotlin.math.abs
import kotlin.math.round

/**
 * Поварской калькулятор: пересчёт рецепта по целевому весу муки или теста.
 * Портировано БЕЗ ИЗМЕНЕНИЙ из v3 (master / feat/living-culture-v2 — идентичные хеши,
 * математика подтверждена рабочей — см. madre-v4-plan.md §1).
 *
 * Baker percentage: каждый ингредиент выражается в % от суммарного веса муки.
 * Ссылочные ингредиенты ([Ingredient.refType]) исключаются из суммарного веса теста,
 * чтобы не считать секцию дважды (она уже учтена отдельной секцией).
 */
object RecipeScaler {

    fun totalFlourGrams(recipe: Recipe): Double =
        recipe.ingredients.values
            .flatten()
            .filter { it.isFlour }
            .sumOf { it.gramsValue() }

    fun totalDoughGrams(recipe: Recipe): Double =
        recipe.ingredients.values
            .flatten()
            .filter { it.scalable && it.refType == null }
            .sumOf { it.gramsValue() }

    fun bakerPercentage(ingredient: Ingredient, recipe: Recipe): Double {
        val total = totalFlourGrams(recipe)
        if (total == 0.0) return 0.0
        return ingredient.gramsValue() / total * 100.0
    }

    fun scaleByFlour(recipe: Recipe, targetFlourGrams: Double): Double {
        val total = totalFlourGrams(recipe)
        if (total == 0.0) return 1.0
        return targetFlourGrams / total
    }

    fun scaleByTotalWeight(recipe: Recipe, targetTotalGrams: Double): Double {
        val total = totalDoughGrams(recipe)
        if (total == 0.0) return 1.0
        return targetTotalGrams / total
    }

    fun scaledDisplayText(ingredient: Ingredient, scaleFactor: Double): String {
        if (!ingredient.scalable) return ingredient.name

        // Ссылка на всю секцию веса не имеет вовсе: он складывается из той
        // секции, а она уже пересчитана — своего числа здесь нет и быть не может.
        if (ingredient.refType == "all_of_section") {
            return "опара (вес рассчитается автоматически)"
        }

        if (ingredient.eggGrams != null) {
            val scaled = roundToHalf(ingredient.amount * scaleFactor)
            return "${formatCount(scaled)} ${ingredient.unit} ${ingredient.name}"
        }

        // Cycle 14: ссылка «часть секции» — обычный вес и масштабируется как
        // обычный вес. До этого цикла она возвращала исходные граммы, и при ×3
        // в списке стояло «150 г опары» рядом с втрое выросшим тестом.
        val scaledAmount = roundWeight(ingredient.amount * scaleFactor)
        return ingredient.displayText(scaledAmount)
    }

    /**
     * Строки рецепта, к которым текст шага вправе привязать свой вес: те, что
     * заданы граммами или миллилитрами и растут с порциями.
     *
     * Яйца сюда не идут: они считаются штуками и половинками, и «2 г» из текста
     * шага не то же самое, что «2 шт». Ссылка «вся опара» тоже: своего веса у
     * неё нет вовсе, его приносит [RecipeScale].
     */
    fun scalableBindings(recipe: Recipe): Map<IngredientRef, Ingredient> =
        recipe.ingredients.flatMap { (section, items) ->
            items
                .filter { it.scalable && it.eggGrams == null && it.amount > 0.0 }
                .filter { it.unit == GRAMS || it.unit == MILLILITRES }
                .map { IngredientRef(section, it.name) to it }
        }.toMap()

    /**
     * Cycle 14: текст шага — такая же часть рецепта, как список ингредиентов.
     * «Смешать всю опару с 300 г воды» при ×3 обязано стать 900 г, иначе на
     * одной странице стоят два разных рецепта — и книжный, и таймерный текст
     * рассказывают человеку с весами разное.
     *
     * ЧТО ИМЕННО ПЕРЕСЧИТЫВАЕТСЯ И ПОЧЕМУ ТОЛЬКО ЭТО. Свободный текст книга не
     * разбирает вообще: пересчитывается ровно то, что в самом рецепте помечено
     * как вес и названо по имени — `{{300 г|main:тёплой воды}}`. Число внутри
     * пометки принадлежит вот этой строке вот этой секции, и порции двигают
     * именно её.
     *
     * До этой правки здесь стоял поиск по числу: любое «100 г» в прозе считалось
     * весом, если хоть один ингредиент рецепта весил 100 — хоть мука, хоть сахар
     * в креме, хоть миллилитры против граммов. «Оставьте 100 г опары на
     * следующий раз» при ×3 превращалось в «300 г»: рецепт, которого Полина не
     * писала. Совпадение числа — не доказательство, и книга больше не выдаёт
     * его за доказательство.
     *
     * Всё непомеченное остаётся как написано — «1 ст. ложка», «0,5 л», «2 см»,
     * «180°C», «40 минут» и любые граммы, которые рецепт не привязал.
     */
    fun scaledStepText(
        text: String,
        scaleFactor: Double,
        bindings: Map<IngredientRef, Ingredient>,
    ): String =
        STEP_QUANTITY.replace(text) { match ->
            val written = match.groupValues[1].trim()
            val quantity = parseQuantity(
                written = written,
                ref = IngredientRef(match.groupValues[2].trim(), match.groupValues[3].trim()),
            ) ?: return@replace written
            // Единственные ворота — [scalableBindings]: строки, которых там нет
            // (яйца, «по вкусу», «вся опара», чужой рецепт), веса не имеют.
            bindings[quantity.ref] ?: return@replace written
            "${formatCount(roundWeight(quantity.amount * scaleFactor))} ${quantity.unit}"
        }

    /**
     * Разобранная пометка веса, либо null — если она написана не так, как книга
     * умеет читать. Тогда текст остаётся ровно тем, что стоит в рецепте: не
     * понял — не трогай.
     */
    private fun parseQuantity(written: String, ref: IngredientRef): StepQuantity? {
        val match = WRITTEN_WEIGHT.matchEntire(written.trim()) ?: return null
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        if (ref.section.isBlank() || ref.name.isBlank()) return null
        return StepQuantity(amount = amount, unit = match.groupValues[2], ref = ref)
    }

    /**
     * Сколько пометок веса стоит в тексте — считая написанные с ошибкой.
     * Вместе со [stepQuantities] это и есть проверка книги: пометка, которую не
     * удалось разобрать, ведёт себя тихо (текст остаётся как есть), и заметить
     * её можно только по расхождению этих двух чисел.
     */
    fun stepQuantityCount(text: String): Int = STEP_QUANTITY.findAll(text).count()

    /**
     * Разобранные пометки веса одного шага. Каждая обязана указывать на строку,
     * которая в рецепте правда есть, — за этим следит тест на всю книгу.
     */
    fun stepQuantities(text: String): List<StepQuantity> =
        STEP_QUANTITY.findAll(text).mapNotNull { match ->
            parseQuantity(
                written = match.groupValues[1],
                ref = IngredientRef(match.groupValues[2].trim(), match.groupValues[3].trim()),
            )
        }.toList()

    /**
     * Вес округляется до грамма, но никогда не до нуля: «0 г соли» — это не
     * рецепт, а стёртая со страницы строка. Ноль остаётся нулём только если
     * ингредиент и правда ничего не весит.
     */
    private fun roundWeight(value: Double): Double {
        // Math.round, а не kotlin.math.round: последний округляет половинки к
        // чётному (112.5 → 112), и «37.5 г закваски ×3» разошлось бы между
        // списком ингредиентов и текстом шага. На кухне половинка идёт вверх.
        val rounded = Math.round(value).toDouble()
        return if (rounded == 0.0 && value > 0.0) 1.0 else rounded
    }

    /** Половинки яиц тоже не пропадают: четверть яйца — это половина, а не ноль. */
    private fun roundToHalf(value: Double): Double {
        val rounded = round(value * 2.0) / 2.0
        return if (rounded == 0.0 && value > 0.0) 0.5 else rounded
    }

    /**
     * Пометка веса в тексте шага: `{{300 г|main:тёплой воды}}` — вес, вертикальная
     * черта, секция, двоеточие, имя строки. Имя пишется точно так же, как в
     * списке ингредиентов: это и есть привязка, а не подсказка.
     */
    private val STEP_QUANTITY = Regex("\\{\\{([^|{}]+)\\|([^:{}]+):([^{}]+)\\}\\}")

    /** Внутренность пометки: число (точка или запятая) и знакомая единица. */
    private val WRITTEN_WEIGHT = Regex("(\\d+(?:[.,]\\d+)?)\\s*(мл|г)")

    private const val GRAMS = "г"

    /**
     * Воду рецепты этой книги задают в граммах, а в тексте шага та же вода
     * иногда написана миллилитрами («120 мл» при «120 г воды» в списке). Один
     * грамм воды — один миллилитр, поэтому пометке разрешено назвать свою
     * единицу самой: перевод здесь тождественный, а не выдуманный.
     */
    private const val MILLILITRES = "мл"

    private fun formatCount(value: Double): String =
        if (abs(value - value.toLong().toDouble()) < 0.001) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}
