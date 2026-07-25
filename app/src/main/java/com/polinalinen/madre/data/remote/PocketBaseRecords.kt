package com.polinalinen.madre.data.remote

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DTO записей PocketBase-коллекций (Cycle 5): bake_stats, feeding_stats,
 * margin_notes_sync. Поля — snake_case как в схеме коллекций, id/created
 * заполняет сервер (при POST отправляются null и Gson их опускает).
 *
 * Здесь только форма данных и работа со строковыми датами PocketBase —
 * никакой сети (сеть — MadreApi, очередь — sync/SyncRepository).
 */
data class BakeStatRecord(
    @SerializedName("id") val id: String? = null,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("recipe_id") val recipeId: String,
    @SerializedName("recipe_name") val recipeName: String,
    @SerializedName("portions") val portions: Int,
    @SerializedName("baked_at") val bakedAt: String,
)

data class FeedingStatRecord(
    @SerializedName("id") val id: String? = null,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("flour_grams") val flourGrams: Int,
    @SerializedName("water_grams") val waterGrams: Int,
    @SerializedName("fed_at") val fedAt: String,
)

data class MarginNoteSyncRecord(
    @SerializedName("id") val id: String? = null,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("recipe_id") val recipeId: String,
    @SerializedName("text") val text: String,
    @SerializedName("written_at") val writtenAt: String,
)

/**
 * «Гостевая страница» (Cycle 7): отзыв гостя. Пишется НЕ из приложения, а с
 * телефона гостя через публичную форму server/pb_public/guest.html —
 * приложение эти записи только читает.
 */
data class GuestNoteRecord(
    @SerializedName("id") val id: String? = null,
    @SerializedName("recipe_id") val recipeId: String,
    @SerializedName("author") val author: String,
    @SerializedName("text") val text: String,
    @SerializedName("created_at") val createdAt: String,
)

/** Ответ листинга PocketBase: GET /api/collections/{name}/records. */
data class RecordsPage<T>(
    @SerializedName("page") val page: Int,
    @SerializedName("perPage") val perPage: Int,
    @SerializedName("totalItems") val totalItems: Int,
    @SerializedName("items") val items: List<T>,
)

/**
 * Фильтры listing-запросов PocketBase. Значение экранируется, потому что
 * фильтр — это выражение в строке запроса: кавычка в device_id иначе
 * сломала бы синтаксис (а device_id у нас UUID, но полагаться на это нельзя).
 */
object PocketBaseFilter {
    fun excludeDevice(deviceId: String): String =
        "(device_id!=\"${escape(deviceId)}\")"

    /** «Библиотечная книга» (Cycle 6): чужие заметки на полях одного рецепта. */
    fun recipeExcludingDevice(recipeId: String, deviceId: String): String =
        "(recipe_id=\"${escape(recipeId)}\" && device_id!=\"${escape(deviceId)}\")"

    /** «Гостевая страница» (Cycle 7): все отзывы гостей одного рецепта. */
    fun forRecipe(recipeId: String): String =
        "(recipe_id=\"${escape(recipeId)}\")"

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Даты PocketBase — строки "yyyy-MM-dd HH:mm:ss.SSS'Z'" в UTC.
 * Миллисекунды формата PocketBase не обязательны при записи, поэтому пишем
 * без них, а при разборе принимаем оба варианта.
 */
object PocketBaseDates {
    private const val WRITE_PATTERN = "yyyy-MM-dd HH:mm:ss'Z'"
    private const val READ_PATTERN_MILLIS = "yyyy-MM-dd HH:mm:ss.SSS'Z'"

    private fun format(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun toIso(millis: Long): String = format(WRITE_PATTERN).format(Date(millis))

    fun parseOrNull(value: String): Long? {
        for (pattern in listOf(READ_PATTERN_MILLIS, WRITE_PATTERN)) {
            runCatching { return format(pattern).parse(value)?.time }
        }
        return null
    }
}
