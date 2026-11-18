package com.villamediana.novelo.server

import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The server answered, and it wasn't the answer: [code] says what ("busy", "quota", "plan"…).
 * Not an [IOException] — the network was fine.
 */
class ServerException(val status: Int, val code: String, message: String, val retryAfter: Int = 0) : Exception(message) {
    /** It may well go through in a moment by itself (the models were all taken). */
    val later: Boolean get() = code == "busy"
}

/**
 * What this phone has, as the server says (the app never decides it): the trial, Novelo+, or
 * neither any more. [active]: the Novelo's intelligence answers.
 */
@Serializable
data class Plan(
    val kind: String = "",
    val active: Boolean = false,
    @SerialName("trial_ends") val trialEnds: Long = 0,
)

/** An answer of a model, and which one gave it (to skip it if the answer can't be used). */
data class Answer(val text: String, val route: String)

/** What came back from the server, whatever the status. */
class Reply(val status: Int, val bytes: ByteArray, val header: (String) -> String?)

/**
 * The Novelo's server: its own intelligence (the app sends the request whole — the instructions,
 * what she said, the photo or the sound — and gets the model's answer).
 * Every request is signed with the phone's key ([KeyBox]); the phone registers itself the first
 * time, and again by itself if the server no longer knows it.
 */
class NoveloServer(
    http: OkHttpClient,
    private val base: String,
    private val box: KeyBox,
    private val prefs: SharedPreferences,
    private val install: () -> String?,
    private val version: String,
    private val pack: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A space every 10 s comes while a request waits in the server's line: silence longer than this is a dead connection. */
    private val client = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val registering = Mutex()

    /** This build knows where the server is. */
    val available: Boolean get() = base.isNotBlank()

    private val _plan = MutableStateFlow(prefs.getString(PLAN, null)?.let { runCatching { json.decodeFromString<Plan>(it) }.getOrNull() })

    /** What the server last said this phone has; null while it hasn't said yet. */
    val plan: StateFlow<Plan?> = _plan

    /** The server said the Novelo's intelligence is over for this phone (the trial ended, no Novelo+). */
    val ended: Boolean get() = _plan.value?.let { !it.active } ?: false

    private fun keep(plan: Plan) {
        _plan.value = plan
        prefs.edit().putString(PLAN, json.encodeToString(Plan.serializer(), plan)).apply()
    }

    private var device: String?
        get() = prefs.getString(DEVICE, null)?.takeIf { box.exists }
        set(value) = prefs.edit().putString(DEVICE, value).apply()

    /**
     * This phone as the server knows it, short enough to read out: what she tells when she asks for
     * what the server keeps of her phone to be erased. Null while the phone never registered.
     */
    val code: String? get() = runCatching { device?.take(10) }.getOrNull()

    /** The server's clock minus the phone's (a phone with the wrong time still signs right). */
    private var skew: Long
        get() = prefs.getLong(SKEW, 0)
        set(value) = prefs.edit().putLong(SKEW, value).apply()

    /** Lets go of the requests going on now (a stop is quick). */
    fun cancel() {
        client.dispatcher.runningCalls().filter { it.request().tag(String::class.java) == TAG }.forEach { it.cancel() }
    }

    /**
     * A model's answer for [job] ("hear", "see", "think"): [body] is a chat request without a
     * model. [not]: the routes of answers that couldn't be used (the server skips them).
     */
    suspend fun ask(job: String, body: JsonObject, not: List<String> = emptyList()): Answer {
        val reply = send("POST", "/v1/ask/$job", body.toString().toByteArray(), JSON, if (not.isEmpty()) emptyMap() else mapOf("X-Novelo-Not" to not.joinToString(",")))
        val root = parse(reply)
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }
            else -> ""
        }
        if (text.isBlank()) throw ServerException(reply.status, "empty", "empty answer")
        return Answer(text, root["novelo"]?.jsonObject?.get("route")?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    /** What this phone has now, asked of the server. */
    suspend fun me(): Plan {
        val root = parse(send("GET", "/v1/me", null, null))
        return json.decodeFromJsonElement<Plan>(root["plan"] ?: throw IOException("no plan")).also(::keep)
    }

    /** The JSON of [reply]; an error in it (even under a 200: the answer began before the outcome was known) is thrown. */
    fun parse(reply: Reply): JsonObject {
        val root = runCatching { json.parseToJsonElement(reply.bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: throw if (reply.status in 200..299) IOException("unreadable answer") else ServerException(reply.status, "http", "HTTP ${reply.status}")
        val err = root["error"] as? JsonObject
        if (err == null) {
            if (reply.status !in 200..299) throw ServerException(reply.status, "http", "HTTP ${reply.status}")
            return root
        }
        val code = err["code"]?.jsonPrimitive?.contentOrNull ?: "error"
        (err["plan"] as? JsonObject)?.let { p -> runCatching { keep(json.decodeFromJsonElement<Plan>(p)) } }
        throw ServerException(reply.status, code, err["message"]?.jsonPrimitive?.contentOrNull ?: code, err["retry_after"]?.jsonPrimitive?.intOrNull ?: 0)
    }

    /**
     * Any signed request (the Novelo Web's sync and links, docs/WEB.md): [path] is what is signed,
     * [query] ("?since=…") goes along unsigned. Its own tag: a stop of the work doesn't cut the sync.
     */
    suspend fun call(method: String, path: String, query: String = "", body: ByteArray? = null, type: String? = JSON): Reply =
        send(method, path, body, type, query = query, tag = SYNC_TAG)

    private suspend fun send(method: String, path: String, body: ByteArray?, type: String?, headers: Map<String, String> = emptyMap(), query: String = "", tag: String = TAG): Reply = withContext(Dispatchers.IO) {
        if (!available) throw ServerException(0, "not_configured", "no server")
        // One id for the request, whatever happens on the way: the server asks a model once.
        val request = UUID.randomUUID().toString()
        var registered = false
        var clocked = false
        var dropped = 0
        while (true) {
            val id = device ?: register(null).also { registered = true }
            val reply = try {
                once(method, path, body, type, headers, id, request, query, tag)
            } catch (e: IOException) {
                // The connection fell on the way: the same request again (the server answers it once).
                if (++dropped > DROPS) throw e
                ensureActive()
                delay(1_000L * dropped)
                continue
            }
            if (reply.status == 401) {
                val err = runCatching { json.parseToJsonElement(reply.bytes.toString(Charsets.UTF_8)).jsonObject["error"]?.jsonObject }.getOrNull()
                when (err?.get("code")?.jsonPrimitive?.contentOrNull) {
                    // The server doesn't know this phone (any more), or the key it has isn't the one
                    // here (a registration that didn't finish): it registers again, once.
                    "unknown_device", "bad_signature" -> if (!registered) {
                        register(id)
                        registered = true
                        continue
                    }
                    // The phone's clock is off: the server's is used from here on.
                    "stale_time" -> err["now"]?.jsonPrimitive?.longOrNull?.let { now ->
                        if (!clocked) {
                            skew = now - clock()
                            clocked = true
                            continue
                        }
                    }
                }
            }
            return@withContext reply
        }
        error("unreachable")
    }

    private fun once(method: String, path: String, body: ByteArray?, type: String?, headers: Map<String, String>, id: String, request: String, query: String = "", tag: String = TAG): Reply {
        val bytes = body ?: ByteArray(0)
        val time = clock() + skew
        val text = "v1\n$method\n$path\n$time\n$request\n${hex(MessageDigest.getInstance("SHA-256").digest(bytes))}"
        val builder = Request.Builder().url(base + path + query)
            .method(method, if (method == "GET" || (method == "DELETE" && body == null)) null else bytes.toRequestBody((type ?: BYTES).toMediaType()))
            .header("X-Novelo-Device", id)
            .header("X-Novelo-Time", time.toString())
            .header("X-Novelo-Request", request)
            .header("X-Novelo-Signature", Base64.encodeToString(box.sign(text.toByteArray()), Base64.NO_WRAP))
            .header("X-Novelo-App", version)
            .tag(String::class.java, tag)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { r ->
            return Reply(r.code, r.body.bytes()) { name -> r.headers[name] }
        }
    }

    /**
     * The phone makes its key (a new one, attesting the server's challenge) and tells the server
     * its public half. One at a time, and once: a request that waited for another's registration
     * uses it ([stale]: the id that stopped working, when this is a registration over again).
     */
    private suspend fun register(stale: String?): String = registering.withLock {
        device?.takeIf { it != stale }?.let { return@withLock it }
        fun post(path: String, body: String): JsonObject {
            val req = Request.Builder().url(base + path).post(body.toRequestBody(JSON.toMediaType())).tag(String::class.java, TAG).build()
            client.newCall(req).execute().use { r -> return parse(Reply(r.code, r.body.bytes()) { null }) }
        }
        val challenge = post("/v1/devices/challenge", "{}")["challenge"]?.jsonPrimitive?.contentOrNull ?: throw IOException("no challenge")
        // The old key goes when the new one is made: from here until the server knows the new one,
        // the phone has no id (a registration that fails halfway is simply done again).
        prefs.edit().remove(DEVICE).apply()
        val spki = box.create(Base64.decode(challenge, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val out = post("/v1/devices", buildJsonObject {
            put("challenge", challenge)
            put("public_key", Base64.encodeToString(spki, Base64.NO_WRAP))
            put("proof", Base64.encodeToString(box.sign("novelo-device-v1\n$challenge".toByteArray()), Base64.NO_WRAP))
            putJsonArray("attestation") { box.chain().forEach { add(JsonPrimitive(Base64.encodeToString(it, Base64.NO_WRAP))) } }
            putJsonObject("app") { put("version", version); put("package", pack) }
            install()?.let { put("install", it) }
        }.toString())
        (out["plan"] as? JsonObject)?.let { p -> runCatching { keep(json.decodeFromJsonElement<Plan>(p)) } }
        (out["device"]?.jsonPrimitive?.contentOrNull ?: throw IOException("no device")).also { device = it }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "novelo-server"
        const val SYNC_TAG = "novelo-sync"
        const val JSON = "application/json"
        const val BYTES_TYPE = "application/octet-stream"
        const val BYTES = "application/octet-stream"
        const val DEVICE = "device"
        const val PLAN = "plan"
        const val SKEW = "skew"

        /** How many times a request whose connection fell is sent again. */
        const val DROPS = 2
    }
}
