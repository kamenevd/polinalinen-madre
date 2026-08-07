package com.polinalinen.madre.data.remote

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DTO записей PocketBase-коллекций: bake_stats и feeding_stats. Поля —
 * snake_case как в схеме коллекций, id/created заполняет сервер (при POST
 * отправляются null и Gson их опускает).
 *
 * Cycle 17: поля family здесь НЕТ намеренно. Семью проставляет сервер из
 * токена (backend/pb_hooks/madre_stats.pb.js) — клиент id своей семьи не
 * хранит, а очередь отправки переживает и перезапуск, и обновление
 * приложения.
 *
 * Здесь только форма данных и работа со строковыми датами PocketBase —
 * никакой сети (сеть — MadreApi, очередь — sync/SyncRepository).
 */
/**
 * Cycle 15: [clientEventId] — ключ идемпотентности. Считается из устройства и
 * номера события (sync/SyncEventId), поэтому одна и та же выпечка даёт ровно ту
 * же строку, сколько бы раз её ни отправили: повтор из очереди WorkManager,
 * второе нажатие «Поделиться», доотправка после переустановки. Уникальность
 * ключа проверяет сервер — клиент только обязуется его не выдумывать заново.
 *
 * У записей, поставленных в очередь ДО Cycle 15, этого поля в JSON нет: Gson
 * разберёт их с null, и сервер примет такую запись, как принимал раньше — без
 * дедупликации, но и без падения.
 */
data class BakeStatRecord(
    @SerializedName("id") val id: String? = null,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("client_event_id") val clientEventId: String,
    @SerializedName("recipe_id") val recipeId: String,
    @SerializedName("recipe_name") val recipeName: String,
    @SerializedName("portions") val portions: Int,
    @SerializedName("baked_at") val bakedAt: String,
)

data class FeedingStatRecord(
    @SerializedName("id") val id: String? = null,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("client_event_id") val clientEventId: String,
    @SerializedName("flour_grams") val flourGrams: Int,
    @SerializedName("water_grams") val waterGrams: Int,
    @SerializedName("fed_at") val fedAt: String,
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
    /** Записи ДРУГИХ устройств семьи: свои книга знает и без сервера. */
    fun excludeDevice(deviceId: String): String =
        "(device_id!=\"${escape(deviceId)}\")"

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
