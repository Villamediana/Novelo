package com.villamediana.novelo.mind

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.ai.Api
import com.villamediana.novelo.ai.ApiAsks
import com.villamediana.novelo.ai.ApiException
import com.villamediana.novelo.audio.Pieces
import com.villamediana.novelo.audio.Wav
import com.villamediana.novelo.local.LocalEngine
import com.villamediana.novelo.local.LocalModel
import com.villamediana.novelo.settings.NoveloSettings
import com.villamediana.novelo.settings.Settings
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.settings.Job
import com.villamediana.novelo.settings.Lang
import com.villamediana.novelo.server.NoveloServer
import com.villamediana.novelo.server.ServerException
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Something is missing before Novelo can hear or think (the button still records: nothing is lost). */
class NotReady(val what: Need, message: String) : Exception(message)

enum class Need { HEAR, THINK }

/** What was heard so far of a recording: the text of its first [pieces] pieces, cut the [by] way. */
/** What was heard so far: the text, the pieces done, how they were cut, and how many pieces there are in all (0: not known yet). */
data class Heard(val text: String = "", val pieces: Int = 0, val by: String? = null, val total: Int = 0)

/** Speech → text. */
interface Ears {
    /**
     * [from]: pieces already heard (a restart goes on from there, when they were cut the same way);
     * [onPiece]: each piece done, to be kept; [onPartial]: the text so far, as it comes.
     */
    suspend fun transcribe(wav: File, from: Heard = Heard(), onPiece: suspend (Heard) -> Unit = {}, onPartial: (String) -> Unit): String

    /** Gets ready while she is still talking (the model on the phone loads in the meantime). */
    suspend fun warm() {}

    /**
     * While she is still talking: the next piece of [wav] that is already whole (from sample [at]),
     * heard now — [heard] with it, and where the next piece starts. Null when none is whole yet.
     * When she stops, [transcribe] goes on from there: only what was left is heard then.
     */
    suspend fun ahead(wav: File, heard: Heard, at: Int, onPartial: (String) -> Unit = {}): Pair<Heard, Int>? = null

    /** Lets go of what is going on now (the model on the phone or the request), so a stop is quick. */
    fun stop() {}
}

/** Text (and maybe a photo) → the model's raw answer. */
interface Thinker {
    /** True when a photo can be looked at (Omni, on the phone, says what it shows); otherwise only its text read counts. */
    suspend fun seesImages(): Boolean

    /** The long side of the photo it gets, in px (a small model on the phone wants a smaller one). */
    suspend fun imageSide(): Int = 1600

    suspend fun answer(system: String, user: String, image: ByteArray? = null): String

    /** Gets ready while she is still talking (the model on the phone loads in the meantime). */
    suspend fun warm() {}

    /** Lets go of what is going on now (the model on the phone or the request), so a stop is quick. */
    fun stop() {}
}

/**
 * The Novelo's own intelligence can't be used now: this build has no server, or the server said
 * it is over for this phone (the trial ended) — she goes on with the model on the phone or her API.
 */
internal fun noveloNeed(server: NoveloServer): String? = when {
    !server.available -> t(R.string.need_novelo_not_in_version)
    server.ended -> t(R.string.need_novelo_ended)
    else -> null
}

/** A chat request as the Novelo's server takes it (it puts the model). */
internal fun chatBody(system: String, user: String, image: ByteArray? = null, audio: ByteArray? = null, json: Boolean = false, effort: String? = null): JsonObject = buildJsonObject {
    putJsonArray("messages") {
        addJsonObject { put("role", "system"); put("content", system) }
        addJsonObject {
            put("role", "user")
            if (image == null && audio == null) put("content", user) else putJsonArray("content") {
                if (user.isNotEmpty()) addJsonObject { put("type", "text"); put("text", user) }
                if (image != null) addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.encodeToString(image, Base64.NO_WRAP)); put("detail", "low") }
                }
                if (audio != null) addJsonObject {
                    put("type", "input_audio")
                    putJsonObject("input_audio") { put("data", Base64.encodeToString(audio, Base64.NO_WRAP)); put("format", "wav") }
                }
            }
        }
    }
    if (json) putJsonObject("response_format") { put("type", "json_object") }
    if (effort != null) put("reasoning_effort", effort)
}

/**
 * Speech → text: the Novelo (its server hears each piece, while she talks too), the model on the
 * phone (LFM3-Omni, while she talks too), or her API's transcription model (the whole recording
 * when she stops; pieces of [API_PIECE] s when it is too big to go in one request).
 */
class Hearing(
    private val settings: Settings,
    private val api: Api,
    private val engine: LocalEngine,
    private val model: LocalModel,
    private val tmp: File,
    private val server: NoveloServer,
) : Ears {
    fun readiness(s: NoveloSettings): String? = when (s.hear) {
        Source.NOVELO -> noveloNeed(server)
        Source.LOCAL -> when {
            !LocalEngine.available -> t(R.string.need_phone_not_in_version)
            !model.ready -> t(R.string.need_download_ears)
            else -> null
        }
        Source.API -> {
            val a = s.api(Job.HEAR)
            when {
                !a.ready -> t(R.string.need_hear_api)
                !a.provider.hears -> t(R.string.need_hear_provider, a.provider.name)
                a.model.isBlank() -> t(R.string.need_hear_model)
                else -> null
            }
        }
    }

    override suspend fun transcribe(wav: File, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        readiness(s)?.let { throw NotReady(Need.HEAR, it) }
        val samples = Wav.readFloats(wav)
        if (Pieces.isSilent(samples)) return@withContext ""
        when (s.hear) {
            Source.NOVELO -> inPieces(samples, from, SERVER_BY, onPiece, onPartial)
            Source.LOCAL -> inPieces(samples, from, BY, onPiece, onPartial)
            Source.API -> byApi(s, wav, samples, from, onPiece, onPartial)
        }
    }

    /** A piece at a time (each kept as it is heard): by the model on the phone, or by the Novelo's server. */
    private suspend fun inPieces(samples: FloatArray, from: Heard, by: String, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String {
        val start = if (from.by == by) from else Heard(by = by)
        var done = start.text
        if (done.isNotEmpty()) onPartial(done)
        val parts = Pieces.cut(samples, maxSeconds = PIECE)
        // How many pieces there are, so the screen can say which one it is on.
        if (parts.size > 1 && start.pieces < parts.size) onPiece(Heard(done, start.pieces, by, parts.size))
        for ((i, p) in parts.withIndex()) {
            if (i < start.pieces) continue
            val before = done
            val text = if (by == SERVER_BY) byServer(p) else engine.transcribe(model.files, p, null) { partial -> onPartial(join(before, partial)) }
            done = join(before, text)
            onPartial(done)
            onPiece(Heard(done, i + 1, by, parts.size))
        }
        return done
    }

    /** One piece heard by the Novelo's server (a model that takes sound, asked only to write what was said). */
    private suspend fun byServer(piece: FloatArray): String {
        if (Pieces.isSilent(piece)) return ""
        val file = File(tmp, "piece-${System.nanoTime()}.wav")
        val wav = try {
            Wav.write(file, piece)
            file.readBytes()
        } finally {
            file.delete()
        }
        val text = server.ask("hear", chatBody(HEAR_ASK, "", audio = wav)).text.trim().trim('"', '“', '”').trim()
        return if (text == NOTHING_SAID) "" else text
    }

    /** Her API: one request when the recording fits in it, otherwise a piece at a time (each kept, like on the phone). */
    private suspend fun byApi(s: NoveloSettings, wav: File, samples: FloatArray, from: Heard, onPiece: suspend (Heard) -> Unit, onPartial: (String) -> Unit): String {
        val a = s.api(Job.HEAR)
        val ep = a.endpoint(settings.apiKey(Job.HEAR).orEmpty())
        val by = API_BY
        val start = if (from.by == by) from else Heard(by = by)
        var done = start.text
        if (done.isNotEmpty()) onPartial(done)
        val parts: List<FloatArray?> = if (wav.length() <= API_WHOLE_BYTES) listOf(null) else Pieces.cut(samples, maxSeconds = API_PIECE)
        if (parts.size > 1 && start.pieces < parts.size) onPiece(Heard(done, start.pieces, by, parts.size))
        for ((i, p) in parts.withIndex()) {
            if (i < start.pieces) continue
            val file = if (p == null) wav else File(tmp, "piece-${System.nanoTime()}.wav").also { Wav.write(it, p) }
            try {
                done = join(done, api.transcribe(ep, a.model, file, null))
            } finally {
                if (p != null) file.delete()
            }
            onPartial(done)
            onPiece(Heard(done, i + 1, by, parts.size))
        }
        return done
    }

    override suspend fun warm() {
        val s = settings.current()
        if (s.hear == Source.LOCAL && readiness(s) == null) engine.warm(model.files)
    }

    /** The Novelo and the model on the phone hear while she talks; her API hears it all when she stops. */
    override suspend fun ahead(wav: File, heard: Heard, at: Int, onPartial: (String) -> Unit): Pair<Heard, Int>? = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (s.hear == Source.API || readiness(s) != null) return@withContext null
        val recorded = Wav.samplesSoFar(wav)
        if (recorded - at <= PIECE * Wav.RATE) return@withContext null
        val piece = Pieces.nextWhole(Wav.readRange(wav, at, recorded), PIECE) ?: return@withContext null
        val by = if (s.hear == Source.NOVELO) SERVER_BY else BY
        val before = if (heard.by == by) heard else Heard(by = by)
        val text = if (s.hear == Source.NOVELO) byServer(piece) else engine.transcribe(model.files, piece, null) { partial -> onPartial(join(before.text, partial)) }
        Heard(join(before.text, text), before.pieces + 1, by) to (at + piece.size)
    }

    override fun stop() {
        engine.cancel()
        api.cancelAll()
        server.cancel()
    }

    private fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        /** Seconds per piece: what the model on the phone hears at once. */
        const val PIECE = 28

        /**
         * How the pieces were cut (kept with what was heard): a capture half heard with other cuts
         * starts over instead of going on from pieces that don't match. "p": cut at the pauses (1.21).
         */
        const val BY = "local:${PIECE}p"

        /** The same cut, heard by the Novelo's server. */
        const val SERVER_BY = "novelo:${PIECE}p"

        /** What the server's model is asked: the words said, nothing else. */
        const val HEAR_ASK = "Transcribe exactly what is said in this recording, in the language it is spoken " +
            "(Portuguese, Spanish or English; they may come mixed). Write only the transcription: no comments, " +
            "no translation, no quotes. If nothing is said, answer only with a dash (-)."
        const val NOTHING_SAID = "-"

        /** Her API: a recording up to this size goes whole (the APIs take 25 MB; a WAV of ~10 min). */
        const val API_WHOLE_BYTES = 20L * 1024 * 1024

        /** Bigger than that: pieces of 5 minutes, cut at the pauses. */
        const val API_PIECE = 300
        const val API_BY = "api:${API_PIECE}p"
    }
}

/**
 * Text (and maybe a photo) → the model's raw answer, always online: the Novelo's own intelligence
 * (its server picks the model) or her API. The same prompt either way. Nothing on the phone thinks.
 */
class Thinking(
    private val settings: Settings,
    private val api: Api,
    private val engine: LocalEngine,
    private val model: LocalModel,
    private val server: NoveloServer,
) : Thinker {
    fun readiness(s: NoveloSettings): String? = when (s.think) {
        Source.NOVELO -> noveloNeed(server)
        Source.LOCAL -> t(R.string.need_api)
        Source.API -> when {
            !s.apiReady -> t(R.string.need_api)
            s.thinkModel.isBlank() -> t(R.string.need_think_model)
            else -> null
        }
    }

    /** Omni looks at the photo on the phone and says what it sees; the model that thinks, whichever, gets those words (never the photo). */
    override suspend fun seesImages(): Boolean = true

    /** The photo is looked at lightly (its text comes read by the phone): no need to send more than this. */
    override suspend fun imageSide(): Int = 768

    /** What each API server took and refused, so a refusal is paid once. */
    private val asks = ApiAsks()

    override fun stop() {
        engine.cancel()
        api.cancelAll()
        server.cancel()
    }

    /** Whoever thinks can be asked now (set up, and — the Novelo's — still answering this phone). */
    suspend fun ready(): Boolean = readiness(settings.current()) == null

    /**
     * A short answer that isn't a plan (the words for a search), asked plainly of whoever thinks:
     * no JSON mode, no second model. Null when nobody can be asked now.
     */
    suspend fun plain(system: String, user: String): String? = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (readiness(s) != null) return@withContext null
        when (s.think) {
            Source.NOVELO -> server.ask("think", chatBody(system, user, effort = "low")).text
            Source.API -> api.chat(s.endpoint(settings.apiKey().orEmpty()), s.thinkModel, system, user, jsonOut = false)
            Source.LOCAL -> null
        }
    }

    override suspend fun answer(system: String, user: String, image: ByteArray?): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        readiness(s)?.let { throw NotReady(Need.THINK, it) }
        when (s.think) {
            // The photo comes as what was seen in it, already in the words. An answer that isn't a
            // plan is asked again, of another model (the server skips the one that gave it).
            Source.NOVELO -> {
                val not = ArrayList<String>()
                var last = ""
                for (i in 0 until NOVELO_TRIES) {
                    val a = server.ask("think", chatBody(system, user, json = true, effort = "low"), not)
                    last = a.text
                    if (Brain.parse(last) != null || a.route.isEmpty()) break
                    not += a.route
                }
                last
            }
            Source.LOCAL -> throw NotReady(Need.THINK, t(R.string.need_api))
            Source.API -> {
                val ep = s.endpoint(settings.apiKey().orEmpty())
                // Asked with little reasoning (organizing doesn't need more, and it answers much
                // sooner) and a light look at the photo. A server that refuses something of the
                // request gets it again without it — and that is remembered, so the next answers
                // don't pay for the same refusal ([ApiAsks]).
                val key = s.apiAddress + "|" + s.thinkModel
                var last: ApiException? = null
                var refusedAsk: ApiAsks.Ask? = null
                for (a in asks.plan(key, withImage = image != null)) {
                    try {
                        val out = api.chat(
                            ep, s.thinkModel, system, user,
                            image = if (a.image) image else null,
                            jsonOut = a.json,
                            effort = if (a.effort) "low" else null,
                            lowDetail = a.lowDetail,
                        )
                        refusedAsk?.let { asks.learn(key, it, a) }
                        return@withContext out
                    } catch (e: ApiException) {
                        if (!e.refused) throw e
                        last = e
                        refusedAsk = a
                    }
                }
                throw last!!
            }
        }
    }

    private companion object {
        /** How many models are asked for one answer when what comes isn't a plan. */
        const val NOVELO_TRIES = 3
    }
}
