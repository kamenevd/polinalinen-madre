package com.polinalinen.madre.ui.screens

import java.util.Locale

/** Stable row identity and user-facing aliases; filtering never changes a setting. */
internal enum class SettingsEntry(val words: String) {
    ACCOUNT("Яндекс ID аккаунт имя портрет вход"),
    NAME("Ваше имя подпись экслибрис книга"),
    BOOKPLATE("Как подписана книга название экслибрис"),
    STARTER_NAME("Имя закваски Соня Мадре"),
    RHYTHM("Ритм кормления как часто кормить интервал сутки неделя"),
    REMINDERS("Напоминания уведомления кормление"),
    SHELF("Полка семья подключить книги"),
    THEME("Тема чтение вид атмосфера утро день вечер ночь по часам"),
    READING_MODE("Как читать рецепт листать страницы прокрутка чтение"),
    TEXT_SIZE("Размер текста шрифт чтение Аа"),
    CALM("Спокойный режим оформление движение анимация"),
    KEEP_SCREEN("Не гасить экран рецепт таймер чтение"),
    LOCK_PROGRESS("Ход выпечки на экране блокировки уведомления минуты шаг приватность"),
    TIMER_SOUND("Звук таймера уведомления канал колокольчик"),
    ALICE("Алиса на кухне голос закваски"),
    JOURNAL("В журнале выпечки кормления данные"),
    VERSION("Версия о книге"),
}

internal fun settingsMatches(entry: SettingsEntry, query: String): Boolean {
    val words = entry.words.lowercase(Locale.ROOT).replace('ё', 'е')
    return query.trim().lowercase(Locale.ROOT).replace('ё', 'е').split(Regex("\\s+"))
        .all { words.contains(it) }
}
