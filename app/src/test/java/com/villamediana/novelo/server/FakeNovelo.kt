package com.villamediana.novelo.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The Novelo's server, as the app sees it: it registers a phone's key, checks every signature the
 * way the real one does (the same text, the same algorithm), and answers the asks.
 */
class FakeNovelo : Dispatcher() {
    val web = MockWebServer()
    val url: String get() = web.url("/").toString().trimEnd('/')

    class Ask(val job: String, val body: String, val request: String, val not: String?)

    val devices = HashMap<String, PublicKey>()
    val installs = ArrayList<String?>()
    val asks = ArrayList<Ask>()

    /** What the next asks answer: the text of a model, or a whole response. */
    @Volatile var reply: (Ask, Int) -> MockResponse = { _, _ -> answer("ok") }
    @Volatile var plan = """{"kind":"plus","active":true}"""

    /** The server's clock is this far from the phone's. */
    @Volatile var clockAhead = 0L

    /** Signatures that didn't check (there should be none). */
    val refused = ArrayList<String>()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        web.dispatcher = this
        web.start()
    }

    override fun close() = web.close()

    fun answer(text: String, route: String = "r1", spaces: Int = 0): MockResponse = body(
        " ".repeat(spaces) + buildJsonObject {
            putJsonArray("choices") { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", text) }) }) }
            put("novelo", buildJsonObject { put("route", route) })
        }.toString(),
    )

    fun error(status: Int, code: String, extra: String = ""): MockResponse = body("""{"error":{"code":"$code","message":"$code"$extra}}""", status)

    private fun body(text: String, status: Int = 200) = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(text).build()

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val raw = request.body?.toByteArray() ?: ByteArray(0)
        if (path == "/v1/devices/challenge") return body("""{"challenge":"${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(40) { it.toByte() })}","expires_in":300}""")
        if (path == "/v1/devices") {
            val o = json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
            val spki = Base64.getDecoder().decode(o.getValue("public_key").jsonPrimitive.content)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
            val challenge = o.getValue("challenge").jsonPrimitive.content
            val proof = Base64.getDecoder().decode(o.getValue("proof").jsonPrimitive.content)
            if (!Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update("novelo-device-v1\n$challenge".toByteArray()) }.verify(proof)) return error(422, "bad_proof")
            val id = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki)).take(24)
            devices[id] = key
            installs += o["install"]?.jsonPrimitive?.content
            return body("""{"device":"$id","verified":false,"plan":$plan}""")
        }
        // Everything else is signed.
        val id = request.headers["X-Novelo-Device"]
        val key = devices[id] ?: return error(401, "unknown_device")
        val time = request.headers["X-Novelo-Time"]?.toLongOrNull() ?: 0
        val now = System.currentTimeMillis() + clockAhead
        if (kotlin.math.abs(now - time) > 5 * 60_000) return error(401, "stale_time", ""","now":$now""")
        val rid = request.headers["X-Novelo-Request"].orEmpty()
        val hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        val text = "v1\n${request.method}\n$path\n$time\n$rid\n$hash"
        val sig = runCatching { Base64.getDecoder().decode(request.headers["X-Novelo-Signature"].orEmpty()) }.getOrDefault(ByteArray(0))
        if (!runCatching { Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(text.toByteArray()) }.verify(sig) }.getOrDefault(false)) {
            refused += path
            return error(401, "bad_signature")
        }
        if (path == "/v1/me") return body("""{"plan":$plan,"now":$now}""")
        if (path.startsWith("/v1/ask/")) {
            val ask = Ask(path.removePrefix("/v1/ask/"), raw.toString(Charsets.UTF_8), rid, request.headers["X-Novelo-Not"])
            asks += ask
            return reply(ask, asks.size)
        }
        return error(404, "not_found")
    }
}
