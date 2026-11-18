package com.villamediana.novelo.mind

import com.villamediana.novelo.R
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.audio.Recorder
import com.villamediana.novelo.data.Capture
import com.villamediana.novelo.data.Entry
import com.villamediana.novelo.data.NoveloDatabase
import com.villamediana.novelo.data.Kind
import com.villamediana.novelo.data.Outcome
import com.villamediana.novelo.data.Status
import com.villamediana.novelo.data.Vector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** What the main screen shows around the button. */
sealed interface MindState {
    data object Idle : MindState

    /** The microphone is open. [locked]: tapped (stays open until tapped again), not held. */
    /** [closesAt]: when the microphone closes by itself if she is still talking (see [Mind.LONGEST_MS]). */
    data class Listening(val startedAt: Long, val locked: Boolean, val focusId: Long?, val closesAt: Long = Long.MAX_VALUE) : MindState

    /** [step] of [of]: which piece, photo or file it is on (0: just the stage). */
    data class Working(val captureId: Long, val stage: Stage, val text: String, val photo: Boolean = false, val typed: Boolean = false, val step: Int = 0, val of: Int = 0) : MindState

    data class Answer(
        val captureId: Long,
        val said: String,
        val reply: String,
        val outcomes: List<Outcome>,
        val shown: List<Long>,
        val photo: Boolean,
        /** Written instead of said. */
        val typed: Boolean = false,
        /** Photos that answer (capture ids). */
        val photos: List<Long> = emptyList(),
        /** Medicines made or changed. */
        val meds: List<Long> = emptyList(),
        /** Medicines that answer a question. */
        val shownMeds: List<Long> = emptyList(),
    ) : MindState

    data class Problem(val captureId: Long?, val need: Need?, val message: String, val said: String? = null) : MindState
}

/**
 * Something said (or written, a photo, a file) that didn't go all the way yet: a row of the line
 * under the button. The button stays free — she says the next thing while these go, one by one.
 */
data class Waiting(
    val id: Long,
    val kind: Kind,
    /** What she said or wrote, as far as it is known (a file's name; nothing for a photo). */
    val text: String,
    val where: Where,
    /** Why it stopped ([Where.LATER], [Where.STOPPED], [Where.FAILED]). */
    val message: String? = null,
    /** Something in Ajustes is missing for it to go. */
    val need: Need? = null,
    /** How long a recording is: what tells one from another while its words aren't known yet. */
    val durationMs: Long = 0,
) {
    enum class Kind { VOICE, TEXT, PHOTO, FILE }

    enum class Where {
        /** Going through now (see [Mind.going] for what it is doing). */
        GOING,
        /** Waiting for its turn. */
        IN_LINE,
        /** No network (or the server full): it goes by itself when there is one. */
        LATER,
        /** Stopped by hand: only a hand sends it again. */
        STOPPED,
        /** It couldn't go: to send again or throw away. */
        FAILED,
    }
}

/** The phone has no network: what needs one waits, without being tried. */
class Offline : IOException("offline")

/** Hearing what she said, reading a photo's text, looking at photos, seeing what files are, organizing, looking things up. */
enum class Stage { HEARING, READING, SEEING, FILES, THINKING, LOOKING }

/** Turns the photo into what the model reads: its text, and the picture itself for a model that sees. */
interface PhotoReader {
    suspend fun text(file: File): String

    /** The photo as a JPEG at most [side] px on its long side. */
    suspend fun jpeg(file: File, side: Int = 1600): ByteArray

    /** Several photos in one image (side by side, in order), at most [side] px: one look sees them all. */
    suspend fun jpeg(files: List<File>, side: Int = 1600): ByteArray = jpeg(files.first(), side)
}

/**
 * A photo or a file waiting on the main screen: it goes alone, or with what she says or writes next.
 * [name]: a file's own name (a photo has none).
 */
data class Staged(val file: File, val takenAt: Long, val name: String? = null) {
    val isFile: Boolean get() = name != null
}

/** Keeps reminders in step with the entries (AlarmManager in the app; a fake in the tests). */
interface ReminderSync {
    suspend fun sync(entryIds: List<Long>)

    /** The medicines' reminders again (one changed, was made or went). */
    suspend fun syncMeds() {}

    /** A time of the medicines ticked in the app: its notification goes once all of that time are taken. */
    suspend fun medTaken(at: Long) {}
}

/**
 * Keeps the app alive with the screen off or in the background (a foreground service in the app):
 * the microphone while recording ([hold]/[release]), and the work while something said is being
 * transcribed and organized ([working]), so minimizing the app never stops it halfway.
 */
interface MicHolder {
    fun hold()
    fun release()
    fun working(on: Boolean) {}

    /** The microphone closes by itself soon (too long open): [ticks] light touches, more the closer it is. */
    fun closingSoon(ticks: Int) {}

    /** The microphone closed by itself (a long silence, or too long open): she is told without looking. */
    fun closedByItself() {}
}

/**
 * The path of a thought: the button records → the speech becomes text → the model decides what
 * it is (task, note, diary, news about something, a question) → it is kept, reminders are set →
 * the answer goes back to the screen. One capture at a time, in order; nothing said is lost: a
 * capture that couldn't go through (no network, Ajustes missing) stays and is tried again.
 */
class Mind(
    private val scope: CoroutineScope,
    private val db: NoveloDatabase,
    private val recorder: Recorder,
    private val hearing: Ears,
    private val thinking: Thinker,
    private val keeper: Keeper,
    private val reminders: ReminderSync,
    private val photos: PhotoReader,
    private val mic: MicHolder,
    private val dir: File,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** A capture stopped for lack of network: what was waiting goes by itself when it comes back. */
    private val offline: () -> Unit = {},
    /** False when the phone has no network at all: what needs one isn't even tried (it waits, and says so at once). */
    private val online: () -> Boolean = { true },
    /** The beginning of a file sent (never the whole of it), for Omni to say what it is. */
    private val peek: FilePeek = NoPeek,
    /** What a photo shows and what a file is, said on the phone (Omni): kept for the search, and given to the model. */
    private val about: About = NoAbout,
    /** What is near what she says by meaning (the search by meaning, on the phone). */
    private val near: Nearby = Nearby.NONE,
    /** How long an answer stays on the screen by itself (see [reading]). */
    private val answerFor: Long = ANSWER_MS,
    /** A microphone left open closes by itself after this long without her voice… */
    private val quietFor: Long = QUIET_MS,
    /** …or after this long in all. */
    private val longest: Long = LONGEST_MS,
) {
    private val _state = MutableStateFlow<MindState>(MindState.Idle)
    /**
     * What the main screen shows around the button: nothing, the microphone open, an answer, or a
     * word for her. Never the work going on: that is the line's ([line], [going]) — the button is free.
     */
    val state: StateFlow<MindState> = _state

    private val _told = MutableStateFlow<MindState>(MindState.Idle)
    /** Everything said along the way, the work and what couldn't go included (what the "done" notification reads). */
    val told: StateFlow<MindState> = _told

    private val _going = MutableStateFlow<MindState.Working?>(null)
    /** What the capture going through now is at (its stage, the words heard so far); null when nothing is. */
    val going: StateFlow<MindState.Working?> = _going

    private val _line = MutableStateFlow<List<Waiting>>(emptyList())
    /** What didn't go all the way yet, the oldest first: going, waiting its turn, waiting for a network or for a hand. */
    val line: StateFlow<List<Waiting>> = _line

    /** Kept in a folder of their own: a photo waiting is still there after the app closes. */
    private val stagedDir = File(dir, ".staged")
    private val _staged = MutableStateFlow(
        stagedDir.listFiles().orEmpty().mapNotNull { f ->
            STAGED.matchEntire(f.name)?.let { Staged(f, it.groupValues[1].toLong()) to it.groupValues[2].toLong() }
                ?: STAGED_FILE.matchEntire(f.name)?.let { Staged(f, it.groupValues[1].toLong(), it.groupValues[3]) to it.groupValues[2].toLong() }
        }.sortedBy { it.second }.map { it.first },
    )
    /** The photos and files waiting to go (see [stage], [stageFiles]). */
    val staged: StateFlow<List<Staged>> = _staged

    private val _pending = MutableStateFlow(0)
    /** Captures waiting to go through (shown as a quiet chip; a tap tries them again). */
    val pending: StateFlow<Int> = _pending

    val level: StateFlow<Float> get() = recorder.level

    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val queued = HashSet<Long>()

    /** Asked to go again while they were going through (a run ending as the network comes back): they go once more if that run didn't make it. */
    private val again = HashSet<Long>()

    /** Just said, still being saved (the piece heard when she stopped is finishing): in line, but not to be sent yet. */
    private val holding = HashSet<Long>()

    /** Waiting for a network (or for the server to have room): they go by themselves. */
    private val later = java.util.Collections.synchronizedSet(HashSet<Long>())

    /** What each one that couldn't go is missing in Ajustes. */
    private val needs = java.util.concurrent.ConcurrentHashMap<Long, Need>()

    /** An answer that came while the microphone was open: it shows when she is done talking. */
    @Volatile private var missed: MindState.Answer? = null
    private val refreshing = kotlinx.coroutines.sync.Mutex()

    /** Photos that went through in another one's run ([together]): their own turn in line has nothing left to do. */
    private val carried = HashSet<Long>()
    @Volatile private var current: Long? = null
    private val ids = Json

    /** Captures on their way (being saved, waiting in line, or going through): while > 0 the app keeps working in the background. */
    private val work = AtomicInteger(0)
    private val stagedSeq = java.util.concurrent.atomic.AtomicLong(0)

    private fun begin() { if (work.getAndIncrement() == 0) mic.working(true) }

    /** Something is on its way (being saved, in line, or going through). */
    val working: Boolean get() = work.get() > 0

    private fun end() { if (work.decrementAndGet() == 0) mic.working(false) }

    /** The capture going through now, as a job the "parar" can cancel… */
    @Volatile private var job: Job? = null

    /** …and the stop itself: the line moves on at once, without waiting for the job to notice. */
    @Volatile private var stop: CompletableDeferred<Unit>? = null

    init {
        scope.launch {
            for (id in queue) {
                current = id
                synchronized(queued) { queued.remove(id) }
                refreshPending()
                try {
                    val j = scope.launch { runCatching { process(id) } }
                    val halt = CompletableDeferred<Unit>()
                    job = j
                    stop = halt
                    select<Unit> {
                        j.onJoin {}
                        halt.onAwait {}
                    }
                    if (halt.isCompleted || j.isCancelled) stopped(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                } finally {
                    job = null
                    stop = null
                    current = null
                    _going.value = null
                    end()
                }
                // Asked again while this run was ending: it didn't make it, so it goes once more.
                if (synchronized(queued) { again.remove(id) }) {
                    val c = runCatching { db.captures().get(id) }.getOrNull()
                    if (c != null && c.state != Capture.DONE && c.state != Capture.STOPPED) enqueue(id)
                }
                refreshPending()
            }
        }
        scope.launch { refreshPending() }
    }

    // ------------------------------------------------------------------ the button

    /**
     * The recording going on now and what of it was already heard while she talks: each piece is
     * transcribed as soon as it is whole, so when she stops only the last bit is left to hear.
     */
    private class Live(val file: File) {
        lateinit var job: Job
        @Volatile var heard = Heard()
        @Volatile var at = 0
        @Volatile var talking = true
        /** The warnings before [longest] already given (how many of [CLOSING_SOON]). */
        var warned = 0
    }

    @Volatile private var live: Live? = null
    private val liveLock = Any()

    /** The recording going on, taken by whoever ends it (the button, the notification, or [watch]): only one does. */
    private fun takeLive(): Live? = synchronized(liveLock) { live.also { live = null } }

    /**
     * A microphone forgotten open costs: it closes by itself after [quietFor] without her voice, or
     * after [longest] in all. What she said goes on as if she had stopped; nothing said, nothing kept.
     * Coming close to [longest] she feels it, lightly, more the closer it is ([CLOSING_SOON]): she can
     * stop and record again.
     */
    private suspend fun watch(l: Live) {
        while (l.talking) {
            delay(WATCH_EVERY_MS)
            if (!l.talking) return
            val open = recorder.elapsedMs()
            if (recorder.quietMs() >= quietFor || open >= longest) {
                stop(byItself = true)
                return
            }
            // Only the closest one crossed (a warning missed isn't given late on top of the next).
            val crossed = CLOSING_SOON.count { (left, _) -> open >= longest - left }
            if (crossed > l.warned) {
                l.warned = crossed
                mic.closingSoon(CLOSING_SOON[crossed - 1].second)
            }
        }
    }

    private suspend fun listenAhead(l: Live) {
        while (l.talking) {
            delay(AHEAD_EVERY_MS)
            // No network and the ear is out there: nothing to try now (it is heard when it goes).
            if (!online() && runCatching { hearing.needsNet() }.getOrDefault(false)) continue
            val next = runCatching { hearing.ahead(l.file, l.heard, l.at) }.getOrNull() ?: continue
            l.heard = next.first
            l.at = next.second
        }
    }

    fun startListening(locked: Boolean, focusId: Long? = null): Boolean {
        if (_state.value is MindState.Listening) return true
        val file = File(dir, "voice-${clock()}.wav")
        if (!recorder.start(file)) {
            _state.value = MindState.Problem(null, null, t(R.string.mic_failed))
            return false
        }
        mic.hold()
        val at = clock()
        _state.value = MindState.Listening(at, locked, focusId, closesAt = at + longest)
        live = Live(file).also { l ->
            l.job = scope.launch { listenAhead(l) }
            scope.launch { watch(l) }
        }
        // The model on the phone loads while she talks: when she stops, the work starts at once.
        scope.launch {
            runCatching { hearing.warm() }
            runCatching { thinking.warm() }
        }
        return true
    }

    /** A hold that turned into a tap: the microphone stays open until the next tap. */
    fun lock() {
        val s = _state.value as? MindState.Listening ?: return
        _state.value = s.copy(locked = true)
    }

    fun stopListening() = stop(byItself = false)

    private fun stop(byItself: Boolean) {
        val s = _state.value as? MindState.Listening ?: return
        val l = takeLive() ?: return
        l.talking = false
        val voice = recorder.heardVoice
        val stopped = recorder.stop()
        if (byItself) mic.closedByItself()
        if (stopped == null || stopped.second < MIN_MS || (byItself && !voice)) {
            l.job.cancel()
            stopped?.first?.delete()
            mic.release()
            _state.value = MindState.Idle
            showMissed()
            // Closed by itself and she said nothing at all: nothing to hear; she is told why it closed.
            if (byItself && stopped != null) show(MindState.Problem(null, null, t(R.string.closed_quiet, (quietFor / 60_000).coerceAtLeast(1))))
            return
        }
        val (file, ms) = stopped
        // When she said it: what "daqui a 10 minutos" counts from (not when the last piece was heard).
        val saidAt = clock()
        // From here it is work: the app keeps running for it before the microphone lets go (so the
        // background never has a gap). And the button is free at once: what she said goes to the
        // line under it, and she can say the next thing while it goes.
        begin()
        mic.release()
        _state.value = MindState.Idle
        showMissed()
        scope.launch {
            try {
                val first = l.heard
                val id = db.captures().insert(
                    Capture(
                        createdAt = saidAt, source = "voice", audioPath = file.path, durationMs = ms, state = Capture.RECORDED, focusEntryId = s.focusId,
                        heard = first.text.takeIf { it.isNotEmpty() }, heardPieces = first.pieces, heardBy = first.by,
                    ),
                )
                synchronized(queued) { holding += id }
                try {
                    attach(id)
                    refreshPending()
                    // The piece being heard when she stopped is finished (it isn't heard twice), then
                    // the rest goes on from there. An ear out on the network isn't waited for long,
                    // and not at all without one: that piece is heard again when its turn comes.
                    val far = runCatching { hearing.needsNet() }.getOrDefault(false)
                    when {
                        !far -> l.job.join()
                        !online() -> l.job.cancel()
                        else -> withTimeoutOrNull(AHEAD_WAIT_MS) { l.job.join() } ?: l.job.cancel()
                    }
                    val h = l.heard
                    if (h != first) db.captures().get(id)?.let { c ->
                        db.captures().update(c.copy(heard = h.text.takeIf { it.isNotEmpty() }, heardPieces = h.pieces, heardBy = h.by))
                    }
                } finally {
                    synchronized(queued) { holding -= id }
                }
                enqueue(id)
            } finally {
                end()
            }
        }
    }

    fun cancelListening() {
        if (_state.value !is MindState.Listening) return
        val l = takeLive() ?: return
        l.talking = false
        l.job.cancel()
        recorder.stop()?.first?.delete()
        mic.release()
        _state.value = MindState.Idle
        showMissed()
    }

    /** She is done talking: the answer that came meanwhile, if one did. */
    private fun showMissed() {
        val a = missed ?: return
        missed = null
        show(a)
    }

    /** Something written instead of said: it is already text, so it goes straight to organizing. */
    fun write(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        begin()
        scope.launch {
            try {
                val id = db.captures().insert(Capture(createdAt = clock(), source = Capture.TEXT, transcript = said, state = Capture.RECORDED))
                attach(id)
                enqueue(id)
            } finally {
                end()
            }
        }
    }

    /** A photo taken in the app's camera: read, understood and kept like something said. */
    fun photo(file: File) = photos(listOf(file to clock()))

    /**
     * Photos (the camera's, or many from the gallery; [file] to when it was taken) waiting on the
     * main screen: [sendStaged] sends them as they are; what she says or writes next takes them along.
     */
    fun stage(files: List<Pair<File, Long>>) {
        if (files.isEmpty()) return
        stagedDir.mkdirs()
        val added = files.mapNotNull { (f, at) ->
            val to = File(stagedDir, "s-$at-${stagedSeq.incrementAndGet() + clock()}.${f.extension.ifBlank { "jpg" }}")
            if (f.renameTo(to)) Staged(to, at) else null
        }
        synchronized(_staged) { _staged.value = _staged.value + added }
    }

    /**
     * Files she attached ([file] to its own name), waiting on the main screen like the photos: sent
     * as they are, or with what she says or writes next. Each is dated when she attached it.
     */
    fun stageFiles(files: List<Pair<File, String>>) {
        if (files.isEmpty()) return
        stagedDir.mkdirs()
        val at = clock()
        val added = files.mapNotNull { (f, name) ->
            val clean = name.replace('/', '-').filter { !it.isISOControl() }.trim().take(120).ifEmpty { "arquivo" }
            val to = File(stagedDir, "d-$at-${stagedSeq.incrementAndGet() + at}-$clean")
            if (f.renameTo(to)) Staged(to, at, clean) else null.also { f.delete() }
        }
        synchronized(_staged) { _staged.value = _staged.value + added }
    }

    /** Something that didn't come (a file that couldn't be copied): said on the main screen. */
    fun problem(message: String) {
        show(MindState.Problem(null, null, message))
    }

    /** One photo or file out of the waiting ones (it is let go). */
    fun unstage(photo: Staged) {
        synchronized(_staged) { _staged.value = _staged.value - photo }
        photo.file.delete()
    }

    /** What waits goes as it is: each photo the way of a photo taken here, each file on its own. */
    fun sendStaged() {
        val all = takeStaged()
        photos(all.filterNot { it.isFile }.map { it.file to it.takenAt })
        files(all.filter { it.isFile })
    }

    /** What was waiting, out of the wait and into the app's folder (photos and files apart). */
    private fun takeStaged(): List<Staged> {
        val list = synchronized(_staged) { _staged.value.also { _staged.value = emptyList() } }
        return list.mapNotNull { s ->
            // A file keeps its own name (in a folder of its own): the app that opens it shows that name.
            val to = if (s.isFile) File(File(dir, "files/${s.takenAt}-${stagedSeq.incrementAndGet()}").apply { mkdirs() }, s.name!!)
            else File(dir, "photo-${s.takenAt}-${stagedSeq.incrementAndGet()}.${s.file.extension.ifBlank { "jpg" }}")
            if (s.file.renameTo(to)) s.copy(file = to) else null
        }
    }

    /** A file kept: its copy in the app, its own name, type and size. */
    private fun fileCapture(s: Staged, state: String, partOf: Long?) = Capture(
        createdAt = s.takenAt, source = Capture.FILE, filePath = s.file.path, fileName = s.name, fileMime = mimeOf(s.name!!),
        fileSize = s.file.length(), state = state, partOf = partOf,
    )

    /** What was waiting goes with capture [id] (what she said or wrote): seen with it, kept where it goes. */
    private suspend fun attach(id: Long) {
        for (s in takeStaged()) {
            db.captures().insert(
                if (s.isFile) fileCapture(s, Capture.DONE, id)
                else Capture(createdAt = s.takenAt, source = "photo", imagePath = s.file.path, state = Capture.DONE, partOf = id),
            )
        }
    }

    /** What she said went nowhere (nothing heard, dropped): the photos and files sent with it go on their own. */
    private suspend fun letGo(id: Long) {
        for (p in db.captures().partsOf(id)) {
            db.captures().update(p.copy(partOf = null, state = Capture.RECORDED))
            enqueue(p.id)
        }
    }

    /**
     * Photos ([file] to when it was taken): the camera's one, or many from the gallery — each kept
     * as a photo taken here, in the order given. All are saved before the first goes, so the ones
     * picked together go through together ([together]): one look and one think for all of them.
     */
    fun photos(files: List<Pair<File, Long>>) {
        if (files.isEmpty()) return
        begin()
        scope.launch {
            try {
                val made = files.map { (file, at) -> db.captures().insert(Capture(createdAt = at, source = "photo", imagePath = file.path, state = Capture.RECORDED)) }
                made.forEach(::enqueue)
            } finally {
                end()
            }
        }
    }

    /** Files sent alone: each goes on its own (the model sees its name and the start of it, and finds its place). */
    private fun files(list: List<Staged>) {
        if (list.isEmpty()) return
        begin()
        scope.launch {
            try {
                list.forEach { s ->
                    val id = db.captures().insert(fileCapture(s, Capture.RECORDED, null))
                    enqueue(id)
                }
            } finally {
                end()
            }
        }
    }

    /** Nothing going through or waiting in line, the microphone closed: an import can replace everything. */
    val idle: Boolean
        get() = current == null && synchronized(queued) { queued.isEmpty() } && _state.value !is MindState.Listening && work.get() == 0

    /** Everything was replaced by an import: the screen starts clean and what came unfinished goes on. */
    fun restored() {
        _state.value = MindState.Idle
        retryPending()
        scope.launch { refreshPending() }
    }

    fun retry(captureId: Long) {
        scope.launch {
            val c = db.captures().get(captureId) ?: return@launch
            if (c.tries > 0 && !busy(c.id)) db.captures().update(c.copy(tries = 0))
            enqueue(captureId)
        }
    }

    private fun busy(id: Long) = current == id || synchronized(queued) { id in queued }

    /**
     * Everything that didn't go through yet (app opening, Ajustes saved). What was stopped by hand
     * waits for a hand too, and so does what made the app fall several times in a row (going again
     * on its own would only make it fall again): only [byHand] (the "esperando · tentar agora"
     * chip) takes those.
     */
    fun retryPending(byHand: Boolean = false) {
        scope.launch {
            db.captures().unfinished().forEach { c ->
                // Just said and still being saved: it goes by itself in a moment.
                if (synchronized(queued) { c.id in holding }) return@forEach
                val waitsForHand = c.state == Capture.STOPPED || c.tries >= MAX_TRIES
                if (waitsForHand && !byHand) return@forEach
                if (byHand && c.tries > 0 && !busy(c.id)) db.captures().update(c.copy(tries = 0))
                enqueue(c.id)
            }
        }
    }

    /** A network again: what was waiting for one goes, in the order it was said. */
    fun networkBack() {
        val waiting = synchronized(later) { later.toList() }.sorted()
        if (waiting.isEmpty()) return
        scope.launch {
            waiting.forEach { id ->
                val c = db.captures().get(id) ?: return@forEach
                if (c.state != Capture.DONE && c.state != Capture.STOPPED) enqueue(id)
            }
        }
    }

    /**
     * The "parar" under the progress: lets go of the model or the request at once and cancels the
     * work. What was said stays (with its text, if it got that far), to try again or discard.
     */
    fun stopWork() {
        val j = job ?: return
        hearing.stop()
        thinking.stop()
        j.cancel()
        stop?.complete(Unit)
    }

    private suspend fun stopped(id: Long) {
        val c = db.captures().get(id)
        if (c != null && c.state != Capture.DONE) db.captures().update(c.copy(state = Capture.STOPPED, error = STOPPED_MESSAGE, tries = 0))
        if (c?.state == Capture.DONE) return
        // What was heard before the stop stays (and a "Tentar de novo" goes on from there).
        show(MindState.Problem(id, null, STOPPED_MESSAGE, c?.transcript ?: c?.heard))
    }

    /** Drops a capture that couldn't go through (and its audio or photo). */
    fun discard(captureId: Long) {
        // Going through right now: it is stopped first.
        if (current == captureId) stopWork()
        later -= captureId
        needs -= captureId
        scope.launch {
            db.captures().get(captureId)?.let { c ->
                c.audioPath?.let { File(it).delete() }
                c.imagePath?.let { File(it).delete() }
                c.filePath?.let { File(it).delete(); File(it).parentFile?.delete() }
                db.captures().delete(c.id)
                letGo(c.id)
            }
            if ((_state.value as? MindState.Problem)?.captureId == captureId) _state.value = MindState.Idle
            refreshPending()
        }
    }

    fun dismiss() {
        if (_state.value is MindState.Answer || _state.value is MindState.Problem) {
            _state.value = MindState.Idle
            _told.compareAndSet(_told.value.takeIf { it is MindState.Answer || (it is MindState.Problem && it.captureId == null) } ?: return, MindState.Idle)
        }
    }

    /** She is reading the answer (a touch on it): its time on the screen starts again. */
    fun reading() {
        _state.value.takeIf(::passing)?.let(::fade)
    }

    /** What is only there to be read: an answer, or a word that asks nothing of her (nothing to try again or set up). */
    private fun passing(s: MindState) = s is MindState.Answer || (s is MindState.Problem && s.captureId == null && s.need == null)

    @Volatile private var fading: Job? = null

    /** [s] leaves the screen by itself after [answerFor], unless something else took its place: an app opened later starts clean. */
    private fun fade(s: MindState) {
        fading?.cancel()
        fading = scope.launch {
            delay(answerFor)
            _state.compareAndSet(s, MindState.Idle)
        }
    }

    private fun enqueue(id: Long) {
        if (current == id) {
            // Already going through; if that run is just ending without making it, it goes again.
            synchronized(queued) { again += id }
            return
        }
        val fresh = synchronized(queued) { queued.add(id) }
        if (fresh) {
            later -= id
            needs -= id
            begin()
            queue.trySend(id)
            scope.launch { refreshPending() }
        }
    }

    /**
     * Waiting = not done and not being handled now. Its saved state doesn't tell: a capture left
     * "transcribing" by an app that was closed halfway is waiting too.
     */
    private suspend fun refreshPending() = refreshing.withLock {
        val all = withContext(Dispatchers.IO) { db.captures().unfinished() }
        val (inLine, held) = synchronized(queued) { queued.toSet() to holding.toSet() }
        val now = current
        val busy = inLine + listOfNotNull(now)
        _pending.value = all.count { it.id !in busy }
        _line.value = all.filter { it.partOf == null }.map { c ->
            val where = when {
                c.id == now -> Waiting.Where.GOING
                c.id in inLine || c.id in held -> Waiting.Where.IN_LINE
                c.state == Capture.STOPPED -> Waiting.Where.STOPPED
                c.id in later -> Waiting.Where.LATER
                c.error != null || c.tries >= MAX_TRIES -> Waiting.Where.FAILED
                // Left on its way by an app that closed: it goes again as the app opens.
                else -> Waiting.Where.IN_LINE
            }
            val kind = when (c.source) {
                "photo" -> Waiting.Kind.PHOTO
                Capture.TEXT -> Waiting.Kind.TEXT
                Capture.FILE -> Waiting.Kind.FILE
                else -> Waiting.Kind.VOICE
            }
            val still = where != Waiting.Where.GOING && where != Waiting.Where.IN_LINE
            Waiting(c.id, kind, (if (kind == Waiting.Kind.FILE) c.fileName else c.transcript ?: c.heard).orEmpty(), where, c.error.takeIf { still }, needs[c.id].takeIf { still }, c.durationMs)
        }
    }

    // ------------------------------------------------------------------ the path

    private suspend fun process(id: Long) {
        // A job stopped by hand may still be finishing something: from then on it says nothing.
        val me = currentCoroutineContext().job
        fun say(st: MindState) { if (me.isActive) show(st) }
        if (synchronized(queued) { carried.remove(id) }) return
        var c = db.captures().get(id) ?: return
        if (c.state == Capture.DONE) return
        val photo = c.source == "photo"
        val typed = c.source == Capture.TEXT
        // A file sent alone: nothing to hear or read — its name is what is "said", the model sees the rest.
        val file = c.source == Capture.FILE
        // Photos sent alone that wait together (picked together in the gallery) go as one.
        val group = if (photo && c.partOf == null) together(c) else listOf(c)
        // A run that starts counts until it ends by itself (an answer, a problem on the screen, a
        // stop); one that never ends is an app that closed or fell in the middle.
        c = c.copy(tries = c.tries + 1).also { db.captures().update(it) }
        try {
            if (group.size > 1) {
                photosTogether(c, group, ::say) { c = it }
                return
            }
            var said = c.transcript
            if (said == null && file) {
                said = c.fileName.orEmpty().ifBlank { "arquivo" }
                c = c.copy(transcript = said).also { db.captures().update(it) }
            }
            if (said == null) {
                c = c.copy(state = Capture.TRANSCRIBING, error = null).also { db.captures().update(it) }
                if (photo) {
                    say(MindState.Working(id, Stage.READING, "", photo = true))
                    said = photos.text(File(c.imagePath!!))
                } else {
                    say(MindState.Working(id, Stage.HEARING, c.heard.orEmpty()))
                    // The ear is out on the network and there is none: it waits, without trying.
                    if (hearing.needsNet() && !online()) throw Offline()
                    // Which piece of how many, on the screen while it goes.
                    var piece = 0
                    var pieces = 0
                    // Each piece heard is kept: after a fall it goes on from there, not from the start.
                    said = hearing.transcribe(
                        File(c.audioPath!!),
                        from = Heard(c.heard.orEmpty(), c.heardPieces, c.heardBy),
                        onPiece = { h ->
                            c = c.copy(heard = h.text, heardPieces = h.pieces, heardBy = h.by).also { db.captures().update(it) }
                            pieces = h.total
                            piece = (h.pieces + 1).coerceAtMost(h.total)
                            say(MindState.Working(id, Stage.HEARING, h.text, step = piece, of = pieces))
                        },
                    ) { partial -> say(MindState.Working(id, Stage.HEARING, partial, step = piece, of = pieces)) }
                }
                c = c.copy(transcript = said).also { db.captures().update(it) }
                // The line has her words from here on.
                scope.launch { refreshPending() }
            }
            if (!photo && said.isBlank()) {
                c.audioPath?.let { File(it).delete() }
                db.captures().delete(id)
                letGo(id)
                say(MindState.Problem(null, null, t(R.string.heard_nothing)))
                return
            }
            // Photos sent with it: what is written in each, read once on the phone, and what each one
            // shows, said by who sees in one look at all of them (each its own: they may be of
            // different things) — kept for the search and given to the model that thinks, whichever.
            // The photo never goes to it.
            val attached = if (photo || file) emptyList() else db.captures().attachedTo(id)
            val sent = seenAll(read(attached) { i, n -> say(MindState.Working(id, Stage.READING, said, step = i, of = n)) }) { n ->
                say(MindState.Working(id, Stage.SEEING, said, of = n))
            }
            // A photo alone: the same, once.
            if (photo && c.description == null) say(MindState.Working(id, Stage.SEEING, said, photo = true))
            val shows = if (!photo) null else c.description ?: look(File(c.imagePath!!))?.also { d -> c = c.copy(description = d).also { db.captures().update(it) } }
            if (photo && said.isBlank() && shows == null && !thinking.seesImages()) {
                // Nothing to read, nothing seen, and a model that can't look: there is nothing to keep.
                c.imagePath?.let { File(it).delete() }
                db.captures().delete(id)
                say(MindState.Problem(null, null, t(R.string.read_nothing)))
                return
            }
            c = c.copy(state = Capture.THINKING).also { db.captures().update(it) }
            say(MindState.Working(id, Stage.THINKING, said, photo, typed))
            // Files: the one sent alone, or those sent with it — Omni says what each is (from its
            // name, its start and what she said); the model gets that, never the file.
            val sentFiles = if (file) emptyList() else db.captures().filesWith(id)
            val toSee = if (file) listOf(c) else sentFiles
            val seen = toSee.mapIndexed { i, f ->
                if (f.description == null) say(MindState.Working(id, Stage.FILES, if (file) f.fileName.orEmpty() else said, step = i + 1, of = toSee.size))
                described(f, if (file) null else said)
            }
            say(MindState.Working(id, Stage.THINKING, if (file) c.fileName.orEmpty() else said, photo, typed))
            if (file) c = db.captures().get(id) ?: c
            // Who thinks is out on the network: without one it waits (already heard), and goes by itself.
            if (thinking.needsNet() && !online()) throw Offline()
            val plan = think(c, said, photo, typed, sent, seen, fileAlone = file, shows = shows)
            // The numbers the model gave each photo and file are theirs, in the order it saw them.
            val filesSent = if (file) listOf(c) else sentFiles
            // A time from now counts from when she said or wrote it (a photo's date is when it was taken, not said).
            val result = keeper.apply(plan, id, sentPhotos = sent.take(MAX_SENT_SEEN).map { it.id }, sentFiles = filesSent.map { it.id }, photo = if (photo) id else null, saidAt = c.createdAt.takeIf { !photo && !file })
            // A photo is the day's too: it shows in the day's diary (begun now when there is none).
            if (photo) diaryFor(c.createdAt)
            if (sent.isNotEmpty()) sent.map { it.createdAt }.distinctBy { DayDiary.bounds(it, zone()).first }.forEach { diaryFor(it) }
            // A file lives in one place: the item the model put it in, or else its day's diary.
            filesSent.filter { f -> result.outcomes.none { it.holdsFile(f.id) } }
                .map { it.createdAt }.distinctBy { DayDiary.bounds(it, zone()).first }.forEach { diaryFor(it) }
            runCatching { reminders.sync(result.touched) }
            if (result.medsChanged) runCatching { reminders.syncMeds() }
            val shown = plan.show.distinct().filter { db.entries().get(it) != null }
            val shownPhotos = plan.showPhotos.distinct().filter { p -> p != id && db.captures().get(p)?.let { it.source == "photo" && it.imagePath != null } == true }
            val shownMeds = plan.showMeds.distinct().filter { it !in result.meds && db.meds().get(it) != null }
            // The recording goes, unless the model saw its sound is what she wants kept.
            val keep = plan.keepAudio && c.source == "voice" && c.audioPath?.let { File(it).isFile } == true
            db.captures().update(
                c.copy(
                    state = Capture.DONE,
                    tries = 0,
                    error = null,
                    reply = plan.reply,
                    outcomes = ids.encodeToString(ListSerializer(Outcome.serializer()), result.outcomes),
                    shown = ids.encodeToString(ListSerializer(Long.serializer()), shown),
                    shownPhotos = ids.encodeToString(ListSerializer(Long.serializer()), shownPhotos),
                    audioPath = if (keep) c.audioPath else null,
                    sound = keep,
                ),
            )
            if (!keep) c.audioPath?.let { File(it).delete() }
            // A file sent alone: nothing was said (its name isn't words of hers).
            say(MindState.Answer(id, if (file) "" else said, plan.reply, result.outcomes, shown, photo, typed, shownPhotos, meds = result.meds, shownMeds = shownMeds))
        } catch (e: NotReady) {
            db.captures().update(c.copy(state = if (c.transcript == null) Capture.RECORDED else Capture.FAILED, error = e.message, tries = 0))
            say(MindState.Problem(id, e.what, e.message ?: "", c.transcript ?: c.heard))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Anything at all (an error of the model on the phone too) ends here, on the screen:
            // the progress never stays up with nothing going on behind it.
            val msg = when (e) {
                is com.villamediana.novelo.ai.ApiException -> t(R.string.api_answered, e.code, e.message.orEmpty())
                // The Novelo's server answered, and it wasn't an answer: its models all taken (it goes
                // again by itself in a moment), today's limit, or something else of its own.
                is com.villamediana.novelo.server.ServerException -> when (e.code) {
                    "busy" -> t(R.string.novelo_busy).also { later += id; runCatching { offline() } }
                    "quota", "rate_limited" -> t(R.string.novelo_quota)
                    // The server just said the Novelo's intelligence is over for this phone.
                    "plan" -> t(R.string.need_novelo_ended)
                    else -> t(R.string.novelo_later)
                }
                is IOException -> t(R.string.offline_kept).also { later += id; runCatching { offline() } }
                else -> e.message ?: e.javaClass.simpleName
            }
            db.captures().update(c.copy(state = if (c.transcript == null) Capture.RECORDED else Capture.FAILED, error = msg, tries = 0))
            say(MindState.Problem(id, null, msg, c.transcript ?: c.heard))
        }
    }

    /**
     * The other photos sent alone that wait to go (picked together in the gallery, or let go
     * together), up to [MAX_SENT_SEEN] with [c]: they go through in its run. Those still in line
     * are skipped when their turn comes. A photo stopped by hand, or that made the app fall
     * several times, waits for a hand and doesn't go along.
     */
    private suspend fun together(c: Capture): List<Capture> {
        val others = db.captures().unfinished()
            .filter { it.id != c.id && it.source == "photo" && it.partOf == null && it.imagePath != null && it.state != Capture.STOPPED && it.tries < MAX_TRIES }
            .sortedBy { it.id }
            .take(MAX_SENT_SEEN - 1)
        if (others.isEmpty()) return listOf(c)
        synchronized(queued) { others.forEach { if (it.id in queued) carried += it.id } }
        return (listOf(c) + others).sortedBy { it.id }
    }

    /** What is written in each of [list], read on the phone once ([reading]: which of how many, before each). */
    private suspend fun read(list: List<Capture>, reading: (Int, Int) -> Unit): List<Capture> = list.mapIndexed { i, p ->
        if (p.transcript != null) return@mapIndexed p
        reading(i + 1, list.size)
        p.copy(transcript = runCatching { photos.text(File(p.imagePath!!)) }.getOrDefault("")).also { db.captures().update(it) }
    }

    /**
     * What each of the first [MAX_SENT_SEEN] of [list] shows, for the ones not seen yet: one look at
     * all of them ([looking]: how many, before it), kept with each (a try again doesn't ask again).
     */
    private suspend fun seenAll(list: List<Capture>, looking: (Int) -> Unit): List<Capture> {
        val blind = list.take(MAX_SENT_SEEN).filter { it.description == null }
        if (blind.isEmpty()) return list
        looking(blind.size)
        val side = thinking.imageSide()
        val images = blind.map { p -> runCatching { photos.jpeg(File(p.imagePath!!), side) }.getOrNull() }
        val ready = blind.indices.filter { images[it] != null }
        val said = runCatching { about.photos(ready.map { images[it]!! }) }.getOrNull().orEmpty()
        val seen = ready.withIndex().mapNotNull { (k, i) -> said.getOrNull(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { blind[i].id to it } }.toMap()
        return list.map { p -> seen[p.id]?.let { d -> p.copy(description = d).also { db.captures().update(it) } } ?: p }
    }

    /**
     * Photos sent alone that went together ([group], [first] the one whose turn it is): what is
     * written in each is read on the phone, one look says what each shows, and one think finds each
     * one's place — the way of the photos sent with something said, with nothing said. Each stays a
     * photo of its own (the gallery, its day's diary, the search); an item made is the photos' whose
     * numbers the model gave it. [keep]: [first] as it is now (what the run's failure starts from).
     */
    private suspend fun photosTogether(first: Capture, group: List<Capture>, say: (MindState) -> Unit, keep: (Capture) -> Unit) {
        val id = first.id
        fun mine(p: Capture) { if (p.id == id) keep(p) }
        var pics = group.map { if (it.id == id) first.copy(state = Capture.TRANSCRIBING, error = null).also { f -> db.captures().update(f); keep(f) } else it }
        pics = read(pics) { i, n -> say(MindState.Working(id, Stage.READING, "", photo = true, step = i, of = n)) }.onEach(::mine)
        pics = seenAll(pics) { n -> say(MindState.Working(id, Stage.SEEING, "", photo = true, of = n)) }.onEach(::mine)
        // Nothing to read, nothing seen, and a model that can't look: nothing to keep of that one.
        if (!thinking.seesImages()) {
            val empty = pics.filter { it.transcript.isNullOrBlank() && it.description == null }
            empty.forEach { p -> p.imagePath?.let { File(it).delete() }; db.captures().delete(p.id) }
            pics = pics.filter { p -> empty.none { it.id == p.id } }
        }
        if (pics.isEmpty()) {
            say(MindState.Problem(null, null, t(R.string.read_nothing)))
            return
        }
        pics.firstOrNull { it.id == id }?.let { mine(it.copy(state = Capture.THINKING).also { f -> db.captures().update(f) }) }
        say(MindState.Working(id, Stage.THINKING, "", photo = true))
        // The run's answer is the newest one's: the one the next turn of the conversation looks back at.
        val owner = pics.maxBy { it.id }
        val plan = think(owner, "", photo = true, sent = pics, together = true)
        val result = keeper.apply(plan, owner.id, sentPhotos = pics.map { it.id })
        // Each photo is its day's too: it shows in that day's diary (begun now when there is none).
        pics.map { it.createdAt }.distinctBy { DayDiary.bounds(it, zone()).first }.forEach { diaryFor(it) }
        runCatching { reminders.sync(result.touched) }
        if (result.medsChanged) runCatching { reminders.syncMeds() }
        val mineIds = pics.map { it.id }.toSet()
        val shown = plan.show.distinct().filter { db.entries().get(it) != null }
        val shownPhotos = plan.showPhotos.distinct().filter { p -> p !in mineIds && db.captures().get(p)?.let { it.source == "photo" && it.imagePath != null } == true }
        val shownMeds = plan.showMeds.distinct().filter { it !in result.meds && db.meds().get(it) != null }
        for (p in pics) {
            val now = db.captures().get(p.id) ?: continue
            val whole = p.id == owner.id
            // Each photo keeps what it went to (what it made, for the gallery and the item); the newest, the whole answer.
            val outcomes = if (whole) result.outcomes else result.outcomes.filter { it.holdsPhoto(p.id) }
            mine(
                now.copy(
                    state = Capture.DONE,
                    tries = 0,
                    error = null,
                    reply = if (whole) plan.reply else now.reply,
                    outcomes = ids.encodeToString(ListSerializer(Outcome.serializer()), outcomes),
                    shown = if (whole) ids.encodeToString(ListSerializer(Long.serializer()), shown) else now.shown,
                    shownPhotos = if (whole) ids.encodeToString(ListSerializer(Long.serializer()), shownPhotos) else now.shownPhotos,
                ).also { db.captures().update(it) },
            )
        }
        say(MindState.Answer(id, "", plan.reply, result.outcomes, shown, photo = true, photos = shownPhotos, meds = result.meds, shownMeds = shownMeds))
    }

    /** What a photo shows, said by Omni on the phone; null when it can't say. */
    private suspend fun look(f: File): String? {
        val image = runCatching { photos.jpeg(f, thinking.imageSide()) }.getOrNull() ?: return null
        return runCatching { about.photo(image) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * A file as the model gets it: its name, type, size and what it is — said once by Omni (from its
     * name, the start of it, a look at its first page when it has no text, and what she [said]),
     * then kept with it (a try again doesn't ask again). The start itself only when nothing was said.
     */
    private suspend fun described(f: Capture, said: String?): FileSeen {
        val name = f.fileName.orEmpty().ifBlank { "arquivo" }
        f.description?.let { return FileSeen(name, f.fileMime, f.fileSize, about = it) }
        val path = f.filePath?.let(::File)?.takeIf { it.isFile }
        val start = path?.let { runCatching { peek.peek(it, name, f.fileMime) }.getOrDefault("") }.orEmpty()
        val look = if (path != null && start.isBlank()) runCatching { peek.look(path, name, f.fileMime) }.getOrNull() else null
        val what = runCatching { about.file(FileSeen(name, f.fileMime, f.fileSize, start), said, look) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (what != null) db.captures().get(f.id)?.let { db.captures().update(it.copy(description = what)) }
        return FileSeen(name, f.fileMime, f.fileSize, if (what == null) start else "", what)
    }

    /**
     * One look at the context; while the model asks to look something up (words, a stretch of
     * days), another look with everything found so far — [MAX_LOOKUPS] times at most.
     */
    private suspend fun think(
        c: Capture,
        said: String,
        photo: Boolean,
        typed: Boolean = false,
        sent: List<Capture> = emptyList(),
        files: List<FileSeen> = emptyList(),
        fileAlone: Boolean = false,
        /** What the photo shows, as the phone saw it (the model gets these words, never the photo). */
        shows: String? = null,
        /** Photos sent alone, together ([sent]): nothing said, each one goes where it belongs. */
        together: Boolean = false,
    ): Plan {
        val now = clock()
        val z = zone()
        val all = db.entries().all()
        val topics = db.graph().allTopics()
        val ties = db.graph().allTies()
        val links = db.graph().allLinks()
        // A task already done is over: the model doesn't get it by itself (nothing to change in it,
        // no words spent on it). Only a lookup it asks for, to answer about the past, brings it back.
        val live = all.filterNot { it.kindEnum == Kind.TASK && it.statusEnum == Status.DONE }
        // Everything (the tasks done too) only if the model asks to look something up.
        val recall by lazy { Recall(all, topics, ties, links) }
        val open = db.entries().openTasks()
        val focus = c.focusEntryId?.let { db.entries().get(it) }
        // What her words find, and what is near them by meaning (another word for the same thing, another language).
        val liveById = live.associateBy { it.id }
        val byMeaning = if (photo && said.isBlank()) emptyList() else near.near(said, Vector.KIND_ENTRY, liveById.keys, 12).mapNotNull { liveById[it.id] }
        val hits = Fuse.rrf(Recall(live, topics, ties, links).search(said, 12, now).map { it.entry }, byMeaning).take(12)
        val sentPhotos = sent.take(MAX_SENT_SEEN).mapNotNull(Photo::of)
        val heard = if (photo && said.isBlank()) "(nenhum texto legível na foto)" else said

        val recent = live.take(RECENT) // all() comes newest first
        val diary = Brain.todaysDiary(all, now, z)
        // The photos kept, found by what they show — only for what is said or written (a photo
        // being described needs no others), a few at first; more when the model asks to look.
        var photoIndex: PhotoRecall? = null
        suspend fun pictures(): PhotoRecall = photoIndex ?: PhotoRecall(db.captures().allPhotos().filter { p -> p.id != c.id && p.partOf != c.id && sent.none { it.id == p.id } }.mapNotNull(Photo::of)).also { photoIndex = it }
        suspend fun nearPhotos(text: String, limit: Int): List<Photo> {
            val all = pictures().all
            val byId = all.associateBy { it.id }
            return near.near(text, Vector.KIND_PHOTO, byId.keys, limit).mapNotNull { byId[it.id] }
        }
        val photoHits = if (photo || fileAlone) emptyList() else Fuse.rrf(pictures().search(said, FIRST_PHOTO_HITS), nearPhotos(said, FIRST_PHOTO_HITS)).take(FIRST_PHOTO_HITS)

        val taken = if (photo && !together) c.createdAt else null
        val before = before(c, live)
        val categories = com.villamediana.novelo.data.Categories.inUse(all)
        // Her medicines, with today's times taken: what "já dei o remédio do Cooper" is about.
        val meds = db.meds().all()
        val takenToday = if (meds.isEmpty()) emptyList() else db.meds().allTaken()
        val first = Brain.context(heard, now, z, open, hits, focus, recent, photo = photo, typed = typed, diary = diary, photos = photoHits, takenAt = taken, before = before, sent = sentPhotos, files = files, fileAlone = fileAlone, seen = shows, categories = categories, meds = meds, taken = takenToday, together = together)
        var plan = Brain.parse(thinking.answer(Brain.SYSTEM, first)) ?: throw IllegalStateException(t(R.string.bad_format))
        var found = emptyList<Entry>()
        var foundPhotos = emptyList<Photo>()
        val looked = ArrayList<String>()
        var lookups = 0
        while (plan.isLookupOnly && lookups < MAX_LOOKUPS) {
            lookups++
            if (currentCoroutineContext().isActive) show(MindState.Working(c.id, Stage.LOOKING, said, photo))
            val asked = plan.search.joinToString(" ")
            val allById = all.associateBy { it.id }
            val byWords = if (plan.search.isEmpty()) emptyList()
                else Fuse.rrf(recall.search(asked, 15, now).map { it.entry }, near.near(asked, Vector.KIND_ENTRY, allById.keys, 15).mapNotNull { allById[it.id] }).take(15)
            val days = Brain.range(plan.period, z)
            val byTime = if (days == null) emptyList() else all.filter { e -> e.createdAt in days || e.doneAt?.let { it in days } == true || e.dueAt?.let { it in days } == true }
            found = (found + byWords + byTime).distinctBy { it.id }
            foundPhotos = (foundPhotos + (if (plan.search.isEmpty()) emptyList() else Fuse.rrf(pictures().search(asked, MAX_PHOTO_HITS), nearPhotos(asked, MAX_PHOTO_HITS)).take(MAX_PHOTO_HITS)) +
                (if (days == null) emptyList() else pictures().during(days, MAX_PHOTO_HITS))).distinctBy { it.id }
            looked += Brain.looked(plan)
            val next = Brain.context(heard, now, z, open, found, focus, recent, looked = looked.joinToString(" · "), photo = photo, typed = typed, diary = diary, photos = foundPhotos, takenAt = taken, before = before, sent = sentPhotos, files = files, fileAlone = fileAlone, seen = shows, categories = categories, meds = meds, taken = takenToday, together = together)
            plan = Brain.parse(thinking.answer(Brain.SYSTEM, next))
                ?: plan.copy(search = emptyList(), period = null, reply = plan.reply.ifBlank { NOTHING_FOUND })
        }
        // Still asking after the last look: it answers with what it has.
        if (plan.isLookupOnly) plan = plan.copy(reply = plan.reply.ifBlank { NOTHING_FOUND })
        return plan.copy(search = emptyList(), period = null)
    }

    /** The previous turn of the conversation: what she said then, the answer, and the items it touched (as they are now, among [all]). */
    private suspend fun before(c: Capture, all: List<Entry>): Before? {
        val last = db.captures().lastDone(c.id) ?: return null
        val byId = all.associateBy { it.id }
        val items = runCatching { ids.decodeFromString(ListSerializer(Outcome.serializer()), last.outcomes) }.getOrDefault(emptyList())
            .filter { it.action != Outcome.DELETED }
            .mapNotNull { o -> byId[o.entryId]?.let { it to WHAT[o.action].orEmpty().ifEmpty { "mudou" } } }
        return Before(last.createdAt, last.transcript.orEmpty(), last.reply.orEmpty(), photo = last.source == "photo", items = items, file = last.fileName?.takeIf { last.source == Capture.FILE })
    }

    /** The diary of the day [at] falls in, made (named by the date, nothing written yet) when the day has none. */
    private suspend fun diaryFor(at: Long) {
        val day = DayDiary.bounds(at, zone())
        if (db.entries().diariesBetween(day.first, day.last).isNotEmpty()) return
        db.entries().insert(Entry(kind = Kind.DIARY.key, title = DayDiary.title(at, zone()), createdAt = at, updatedAt = clock()))
    }

    private fun show(state: MindState) {
        _told.value = state
        when {
            // The work is the line's: the screen around the button stays as it is.
            state is MindState.Working -> _going.value = state
            // What couldn't go stays in the line too (to send again or throw away), with why.
            state is MindState.Problem && state.captureId != null -> {
                state.need?.let { needs[state.captureId] = it }
                scope.launch { refreshPending() }
            }
            // A new recording on screen wins; the answer that comes meanwhile shows when she is done.
            _state.value is MindState.Listening -> if (state is MindState.Answer) missed = state
            else -> {
                _state.value = state
                if (passing(state)) fade(state)
            }
        }
    }

    companion object {
        /** Shorter than this is a slip of the finger, not a thought. */
        const val MIN_MS = 700L

        /** An answer read (or left there) goes after this long; a touch on it starts it again. */
        const val ANSWER_MS = 30_000L

        /** Without her voice this long, the microphone closes by itself… */
        const val QUIET_MS = 2 * 60_000L

        /** …and open this long in all, too (a television on doesn't stop by silence). */
        const val LONGEST_MS = 15 * 60_000L

        /** How often, while she talks, the microphone is looked at for that. */
        const val WATCH_EVERY_MS = 1_000L

        /** Before [LONGEST_MS]: this long before it, so many light touches (the closer, the more). */
        val CLOSING_SOON = listOf(2 * 60_000L to 1, 60_000L to 2, 30_000L to 3)

        /** On the screen, how long before it closes it starts saying so (quietly). */
        const val CLOSING_SHOWN_MS = 2 * 60_000L

        /** How often, while she talks, it looks for a piece that is already whole. */
        const val AHEAD_EVERY_MS = 1_500L

        /** When she stops, a piece being heard out on the network is waited for this long at most. */
        const val AHEAD_WAIT_MS = 6_000L

        /** Runs that never ended (the app fell in the middle) before it waits for a hand. */
        const val MAX_TRIES = 3

        /** How many times the model may ask to look something up for one thing said. */
        const val MAX_LOOKUPS = 2

        /** Photos found by what they show that go to the model at most: on the first look, and when it asks to look. */
        const val FIRST_PHOTO_HITS = 4
        const val MAX_PHOTO_HITS = 8

        /** Photos sent together (with what she said, or alone) seen in one look and given to the model at most. */
        const val MAX_SENT_SEEN = 9

        private val STAGED = Regex("""s-(\d+)-(\d+)\.\w+""")
        private val STAGED_FILE = Regex("""d-(\d+)-(\d+)-(.+)""")

        /** A file's type from its name ("application/pdf"…), when Android knows it. */
        fun mimeOf(name: String): String? =
            FileKind.ext(name)?.let { runCatching { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }.getOrNull() }

        /** The last things kept, shown to the model so "aquilo que acabei de falar" has a face. */
        const val RECENT = 5

        private val NOTHING_FOUND: String get() = t(R.string.nothing_found)

        private val WHAT = mapOf(Outcome.CREATED to "criado", Outcome.UPDATED to "mudou", Outcome.DONE to "feito")

        private val STOPPED_MESSAGE: String get() = t(R.string.stopped)
    }
}
