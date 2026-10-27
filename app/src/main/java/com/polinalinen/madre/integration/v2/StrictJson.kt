package com.polinalinen.madre.integration.v2

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken

/** Detect duplicates before Gson's object model can discard them. Bounded depth and strict tokens. */
internal fun strictObject(text: String): JsonObject {
    val reader = JsonReader(java.io.StringReader(text))
    reader.isLenient = false
    fun read(depth: Int): JsonElement {
        require(depth <= 16)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) { val name = reader.nextName(); require(names.add(name)); add(name, read(depth + 1)) }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray(); while (reader.hasNext()) add(read(depth + 1)); reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val value = reader.nextString()
                require(value.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                JsonParser.parseString(value)
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> error("Invalid JSON")
        }
    }
    return read(0).asJsonObject.also { require(reader.peek() == JsonToken.END_DOCUMENT) }
}
