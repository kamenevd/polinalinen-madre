package com.polinalinen.madre.integration.v2

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Test driver only. Compile the unchanged production DTO, serializer, HTTP and strict parser. */
fun main() = runBlocking {
    val input = System.`in`.bufferedReader()
    val init = JsonParser.parseString(input.readLine()).asJsonObject
    val port = init.get("port").asInt
    val credential = init.get("credential").asString
    val http = ContractHttp.client().newBuilder().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.scheme == "https" && request.url.host == "kdnfx.space")
        check(request.url.encodedPath.startsWith("/alice/madre/voice/phone/"))
        chain.proceed(request.newBuilder().url(request.url.newBuilder()
            .scheme("http").host("127.0.0.1").port(port).build()).build())
    }.build()
    val api = HttpsRosterV2Api(http) { true }
    val keys = (1..4).map { UUID.randomUUID().toString() }
    var connection = ""
    var revision = 0L
    var command: RosterCommand? = null
    fun bakes() = listOf(
        RosterBake(keys[0], 1, 7, "synthetic-recipe-rev", "Хлеб", RosterBakeState.RUNNING,
            1, 3, "Замес", 120, stepText="Смешайте", nextStepTitle="Расстойка",
            nextStepText="Оставьте", quantities="100 г муки", portions=1, controlReady=true),
        RosterBake(keys[1], 2, 7, "synthetic-recipe-rev", "Чиабатта", RosterBakeState.PAUSED,
            1, 3, "Замес", 110, portions=2, controlReady=true),
        RosterBake(keys[2], 3, 7, "synthetic-recipe-rev", "Пицца", RosterBakeState.SCHEDULED,
            0, 3, "План", 0, startAt=System.currentTimeMillis()+600_000),
        RosterBake(keys[3], 4, 7, "synthetic-recipe-rev", "Булочка", RosterBakeState.FINALE,
            2, 3, "Испечено", 0)
    )
    // Stable snapshots: do not change a scheduled timestamp without a new bake revision.
    val current = bakes()
    println("{\"ready\":true}")
    try {
        while (true) {
            val line = input.readLine() ?: break
            val request = JsonParser.parseString(line).asJsonObject
            try {
                val result: Any = when (request.get("op").asString) {
                    "pair" -> api.pairConsent(credential, InstallationPairConsent(UUID.randomUUID().toString()))
                    "attach" -> { connection = api.attach(credential); mapOf("attached" to true) }
                    "poll", "empty" -> {
                        val roster = BakeRoster(connection, ++revision,
                            if (request.get("op").asString == "empty") emptyList() else current)
                        val reply = api.poll(credential, roster)
                        reply.commands.firstOrNull()?.let { command = it }
                        mapOf("commands" to reply.commands.size,
                            "operation" to reply.commands.firstOrNull()?.operation?.name,
                            "bake_number" to reply.commands.firstOrNull()?.bakeNumber)
                    }
                    "ack" -> {
                        val c = checkNotNull(command)
                        api.acknowledge(credential, c, VoiceCommandReceiptEntity(c.id, c.fingerprint(),
                            c.bakeKey, c.expectedRevision, "APPLIED", c.expectedRevision+1,
                            c.expiresAt, c.operation.name, "synthetic-recipe"))
                        mapOf("acknowledged" to true)
                    }
                    "disconnect" -> { api.disconnect(credential, connection); mapOf("disconnected" to true) }
                    "stop" -> break
                    else -> error("unknown probe operation")
                }
                println(Gson().toJson(result))
            } catch (failure: ContractFailure) {
                println(Gson().toJson(mapOf("failure" to failure.kind.name)))
            }
        }
    } finally {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}