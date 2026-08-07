package com.polinalinen.madre.data.remote

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Test

/**
 * Cycle 5, MadreApi: контракт с PocketBase держится на строках — имена полей
 * snake_case, фильтр-выражение, формат дат. Всё это проверяем без сети:
 * сломанное имя поля Gson молча превратит в отсутствующее значение на сервере.
 */
class PocketBaseRecordsTest {

    private val gson = Gson()

    @Test
    fun `bake stat serializes to snake_case and omits server id`() {
        val json = gson.toJson(
            BakeStatRecord(
                deviceId = "dev-1",
                recipeId = "ciabatta",
                recipeName = "Чиабатта",
                portions = 2,
                bakedAt = "2026-07-24 10:00:00Z",
                clientEventId = "test",
            )
        )
        assertThat(json).contains("\"device_id\":\"dev-1\"")
        assertThat(json).contains("\"recipe_id\":\"ciabatta\"")
        assertThat(json).contains("\"recipe_name\":\"Чиабатта\"")
        assertThat(json).contains("\"portions\":2")
        assertThat(json).contains("\"baked_at\":\"2026-07-24 10:00:00Z\"")
        assertThat(json).doesNotContain("\"id\"")
    }

    @Test
    fun `feeding stat serializes to snake_case`() {
        val feeding = gson.toJson(
            FeedingStatRecord(deviceId = "d", flourGrams = 50, waterGrams = 50, fedAt = "2026-07-24 10:00:00Z", clientEventId = "test")
        )
        assertThat(feeding).contains("\"flour_grams\":50")
        assertThat(feeding).contains("\"water_grams\":50")
        assertThat(feeding).contains("\"fed_at\"")
        assertThat(feeding).contains("\"client_event_id\":\"test\"")
    }


    @Test
    fun `records page parses pocketbase listing`() {
        val json = """
            {"page":1,"perPage":200,"totalItems":1,"items":[
              {"id":"abc","device_id":"other","recipe_id":"rye","recipe_name":"Ржаной","portions":1,
               "baked_at":"2026-07-20 08:30:00.000Z"}
            ]}
        """.trimIndent()
        val type = object : TypeToken<RecordsPage<BakeStatRecord>>() {}.type
        val page: RecordsPage<BakeStatRecord> = gson.fromJson(json, type)
        assertThat(page.totalItems).isEqualTo(1)
        assertThat(page.items.single().deviceId).isEqualTo("other")
        assertThat(page.items.single().id).isEqualTo("abc")
    }

    @Test
    fun `exclude device filter matches pocketbase syntax and escapes quotes`() {
        assertThat(PocketBaseFilter.excludeDevice("self")).isEqualTo("(device_id!=\"self\")")
        assertThat(PocketBaseFilter.excludeDevice("a\"b")).isEqualTo("(device_id!=\"a\\\"b\")")
    }

    @Test
    fun `dates round-trip and parse both pocketbase variants`() {
        val millis = 1_784_887_200_000L // 2026-07-24 10:00:00 UTC
        val iso = PocketBaseDates.toIso(millis)
        assertThat(iso).isEqualTo("2026-07-24 10:00:00Z")
        assertThat(PocketBaseDates.parseOrNull(iso)).isEqualTo(millis)
        assertThat(PocketBaseDates.parseOrNull("2026-07-24 10:00:00.000Z")).isEqualTo(millis)
        assertThat(PocketBaseDates.parseOrNull("не дата")).isNull()
    }
}
