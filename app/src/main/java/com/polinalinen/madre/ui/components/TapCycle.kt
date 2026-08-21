package com.polinalinen.madre.ui.components

/**
 * Круг значений по одному тапу.
 *
 * Неизвестное текущее значение (например, после миграции) возвращает первый
 * вариант круга; из одного значения круг никуда не уходит.
 */
object TapCycle {
    fun <T> next(options: List<T>, current: T): T {
        require(options.isNotEmpty()) { "options must not be empty" }
        if (options.size == 1) return options.first()
        val index = options.indexOf(current)
        if (index < 0) return options.first()
        return options[(index + 1) % options.size]
    }
}
