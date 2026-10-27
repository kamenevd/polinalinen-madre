package com.polinalinen.madre.integration.v2

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Unchanged pinned producer over real loopback sockets. No synthetic network response interceptor. */
internal class ActualAliceProducer : AutoCloseable {
    private val scratch = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"), "madre-roster-fixtures").apply { mkdirs() }
    private val root = Files.createTempDirectory(scratch.toPath(), "madre-alice-client-").toFile()
    private val files = listOf("launch.py", "source.json", "18aef85.tar.gz").associateWith { name ->
        File(root, name).also { file -> javaClass.getResourceAsStream("/alice-producer/$name")!!.use { file.writeBytes(it.readBytes()) } }
    }
    private val errors = File(root, "producer.stderr")
    private val process = ProcessBuilder("python3", "-u", files.getValue("launch.py").path,
        root.path, files.getValue("source.json").path, files.getValue("18aef85.tar.gz").path).apply {
        redirectError(errors)
        val path = environment()["PATH"]
        environment().clear(); environment()["PATH"] = path ?: "/usr/bin:/bin"
        environment()["TMPDIR"] = root.path
        environment()["PYTHONDONTWRITEBYTECODE"] = "1"
    }.start()
    private val reader = process.inputStream.bufferedReader()
    private val writer = process.outputStream.bufferedWriter()
    private fun line(): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!reader.ready() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        check(reader.ready()) { "Isolated producer startup/control failed: ${errors.readText().take(2000)}" }
        return JsonParser.parseString(reader.readLine()).asJsonObject
    }
    private val ready = line()
    val credential: String = ready.string("credential")
    val unpairedStatus: Long = ready.integer("unpaired_status")
    val client: OkHttpClient = ContractHttp.client().newBuilder().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.scheme == "https" && request.url.host == "kdnfx.space" && request.url.encodedPath.startsWith("/alice/madre/voice/phone/"))
        // Only socket destination changes. JSON/auth/status/parser all remain production code.
        chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1")
            .port(ready.integer("port").toInt()).build()).build())
    }.build()
    val api = HttpsRosterV2Api(client) { true }
    suspend fun completeExplicitPair() {
        api.pairConsent(credential, InstallationPairConsent(java.util.UUID.randomUUID().toString()))
        control("authorize"); control("redeem")
    }
    @Synchronized fun control(op: String, text: String? = null): JsonObject {
        writer.write(json { addProperty("op", op); if (text != null) addProperty("text", text) }.toString())
        writer.newLine(); writer.flush(); return line()
    }
    override fun close() {
        try {
            if (process.isAlive) { control("stop"); check(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(errors.readText(),0,process.exitValue()) }
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS) }
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            root.deleteRecursively()
        }
    }
}
