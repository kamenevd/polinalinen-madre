package com.polinalinen.madre.sourdough


/**
 * Cycle 26: гидратация после кормления — это арифметика, а не мнение.
 *
 * Человек называет три массы: сколько закваски оставил в банке, сколько дал
 * муки и сколько воды. Всё остальное считается, и считать это глазами не надо:
 *
 *   priorFlour = starter * 100 / (100 + priorHydration)
 *   priorWater = starter - priorFlour
 *   final      = round((priorWater + water) / (priorFlour + flour) * 100)
 *
 * Прошлая гидратация берётся из последней сохранённой записи, а не из
 * [SourdoughProfile] — профиль описывает режим кормления, а не состав банки,
 * и подставлять его сюда значит выдать настройку за измерение.
 *
 * Всё внутри — Double: при целочисленном делении 50 г закваски на 50%
 * гидратации дают «33 г муки и 17 г воды» вместо 33.33/16.67, и ошибка
 * накапливается от кормления к кормлению. Округляется только итоговый процент.
 */
object HydrationMath {

    /**
     * Стартовая гидратация Левито Мадре из буклета. Используется ровно один
     * раз — когда сохранённой гидратации ещё нет вовсе; экран обязан сказать
     * об этом вслух («стартовая гидратация 50%»), а не молча подставить число.
     */
    const val INITIAL_LEVITO_HYDRATION_PERCENT = 50

    /**
     * Гидратация, от которой считается новое кормление: самая свежая
     * сохранённая. [savedFinalsNewestFirst] — история от новой записи к старой;
     * legacy-записи без гидратации пропускаются, а не заполняются задним числом.
     */
    fun priorHydrationPercent(savedFinalsNewestFirst: List<Int?>): Int =
        savedFinalsNewestFirst.firstNotNullOfOrNull { it } ?: INITIAL_LEVITO_HYDRATION_PERCENT

    fun finalHydrationPercent(
        retainedStarterGrams: Int,
        priorHydrationPercent: Int,
        addedFlourGrams: Int,
        addedWaterGrams: Int,
    ): Int {
        val hydrationScale = 100L + priorHydrationPercent.toLong()
        val retainedStarter = retainedStarterGrams.toLong()
        val flour = retainedStarter * 100L + addedFlourGrams.toLong() * hydrationScale
        // Муки без муки не бывает: делить не на что — честнее вернуть прежнее
        // значение, чем выдать бесконечность за измерение.
        if (hydrationScale <= 0L || flour <= 0L) return priorHydrationPercent

        val numerator =
            retainedStarter * priorHydrationPercent.toLong() +
                addedWaterGrams.toLong() * hydrationScale
        val denominator = flour
        val percentageNumerator = numerator * 100L
        return ((2L * percentageNumerator + denominator) / (2L * denominator)).toInt()
    }
}
