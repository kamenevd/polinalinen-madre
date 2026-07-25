package com.polinalinen.madre.ui.components

import kotlin.random.Random

/**
 * DESIGN-V4.md Cycle 9, фича «Ветхое ляссе» (AgedRibbon). Шёлковая ленточка-
 * закладка стареет вместе с книгой: с каждой выпечкой краска выцветает
 * (цвет уходит к пергаментному), а нижний край обтрёпывается — сначала
 * мелкие зазубрины, у совсем зачитанной книги с кончика свисают нити.
 *
 * Возраст — общее число выпечек всех глав (bake_records целиком, тот же
 * источник, что формуляр). Числа и раскладка — чистые функции (юнит-тест
 * AgedRibbonTest), рисование — в RibbonBookmark (BookComponents.kt).
 * Детерминированный seed — тот же приём, что Crumbs/InkBlot: бахрома не
 * мигает при рекомпозиции.
 */
object AgedRibbon {
    /** Первые выпечки лента переживает без потерь — трепаться начинает позже. */
    const val FRAY_AFTER_BAKES = 5
    const val MAX_NOTCHES = 9

    /** Даже у древней книги лента остаётся узнаваемо цветной. */
    const val MAX_FADE = 0.45f

    /** Доля выцветания 0..[MAX_FADE]: сколько цвета ленты отдать пергаменту. */
    fun fadeFraction(totalBakes: Int): Float =
        if (totalBakes <= 0) 0f else (totalBakes * 0.012f).coerceAtMost(MAX_FADE)

    /** Зазубрины нижнего края: появляются после [FRAY_AFTER_BAKES], растут с потолком. */
    fun notchCount(totalBakes: Int): Int =
        if (totalBakes < FRAY_AFTER_BAKES) 0
        else (1 + (totalBakes - FRAY_AFTER_BAKES) / 4).coerceAtMost(MAX_NOTCHES)

    /** Свисающие нити — только у по-настоящему зачитанной книги, не больше трёх. */
    fun threadCount(totalBakes: Int): Int = ((totalBakes - 20) / 15).coerceIn(0, 3)

    /**
     * Одна зазубрина: [position] — доля пути вдоль нижнего края (0..1),
     * [depth] — насколько глубоко выгрызена, в долях ширины ленты,
     * [halfWidth] — полуширина выгрыза, в тех же долях пути.
     */
    data class Notch(val position: Float, val depth: Float, val halfWidth: Float)

    /**
     * Детерминированная бахрома: зазубрины раскиданы по краю равномерно
     * с дрожанием, чтобы край рвался живо, а не гребёнкой.
     */
    fun notches(seed: Long, count: Int): List<Notch> {
        if (count <= 0) return emptyList()
        val rng = Random(seed)
        return List(count) { i ->
            Notch(
                position = ((i + 0.2f + rng.nextFloat() * 0.6f) / count).coerceIn(0f, 1f),
                depth = 0.15f + rng.nextFloat() * 0.35f,
                halfWidth = 0.02f + rng.nextFloat() * 0.03f,
            )
        }
    }

    /**
     * Нити: [position] — доля пути вдоль края, откуда свисает, [length] —
     * длина в долях ширины ленты, [sway] — изгиб вбок (-1..1).
     */
    data class Thread(val position: Float, val length: Float, val sway: Float)

    fun threads(seed: Long, count: Int): List<Thread> {
        if (count <= 0) return emptyList()
        val rng = Random(seed * 31 + 7)
        return List(count) {
            Thread(
                position = rng.nextFloat(),
                length = 0.5f + rng.nextFloat() * 0.9f,
                sway = rng.nextFloat() * 2f - 1f,
            )
        }
    }
}
