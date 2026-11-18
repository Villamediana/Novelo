package com.villamediana.novelo.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.villamediana.novelo.data.NoveloDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * The phone's side of the Novelo Web: every row of what she keeps goes to the server as a record
 * encrypted here, and what a computer wrote comes back into the rows (docs/WEB.md). Generic over
 * the tables: a row is its columns as SQLite keeps them (true/false as 1/0), so a column added to a
 * table one day goes along by itself.
 *
 * - [sync]: what came first (a change made here meanwhile is merged field by field with it), then
 *   what changed here — a write refused because the server has something newer is merged and sent again.
 * - [moveFiles]: the photos, sounds and documents of the captures, up and down (big ones only off
 *   mobile data).
 *
 * The ids are the same everywhere: the phone's are its own (1, 2, 3…), a computer makes its own far
 * above them (2^45 and up), so nothing that points at a row has to be translated.
 */
class SyncEngine(
    private val db: NoveloDatabase,
    private val transport: SyncTransport,
    private val space: SyncSpace,
    private val media: SyncMedia,
    private val applied: suspend (Applied) -> Unit = {},
) {
    /** What came from a computer: the entries whose reminders may have changed, the medicines, and things written to organize here. */
    data class Applied(val entries: Set<Long>, val meds: Boolean, val handedOff: Boolean) {
        val isEmpty: Boolean get() = entries.isEmpty() && !meds && !handedOff
    }

    data class Status(
        val on: Boolean = false,
        val busy: Boolean = false,
        val lastAt: Long = 0,
        val error: String? = null,
        /** Files here not yet there, and there not yet here. */
        val filesUp: Int = 0,
        val filesDown: Int = 0,
    )

    private val _status = MutableStateFlow(Status(on = space.on))
    val status: StateFlow<Status> = _status

    private val lock = Mutex()
    private val files = Mutex()
    private val dao get() = db.sync()
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase
    private var opened: String? = null

    // ------------------------------------------------------------------ the space

    /**
     * Starts syncing (the first computer is being linked): the key is made (or the one there is kept),
     * the space opened on the server — another key there means a space this phone can't read any more:
     * it starts over, from what is here. Returns (space id, key).
     */
    suspend fun start(): Pair<String, ByteArray> = lock.withLock {
        val master = space.master() ?: SyncCrypto.newMaster().also { space.start(it) }
        val crypto = SyncCrypto(master)
        val id = openSpace(crypto)
        _status.value = _status.value.copy(on = true)
        id to master
    }

    /** Stops syncing and erases the server's copy (computers included). */
    suspend fun stop() = lock.withLock {
        runCatching { transport.erase() }.getOrElse { if (it is java.io.IOException) throw it }
        space.stop()
        dao.clearRecords()
        dao.clearFiles()
        opened = null
        _status.value = Status(on = false)
    }

    private suspend fun openSpace(crypto: SyncCrypto): String {
        opened?.let { return it }
        val o = try {
            transport.open(crypto.check, reset = false)
        } catch (_: SyncTransport.KeyMismatch) {
            // The server's copy is in a key this phone no longer has: it is made again from here.
            transport.open(crypto.check, reset = true)
        }
        if (o.fresh || o.space != space.spaceId) {
            dao.clearRecords()
            dao.clearFiles()
            space.cursor = 0
            space.spaceId = o.space
        }
        opened = o.space
        return o.space
    }

    // ------------------------------------------------------------------ syncing

    /**
     * News first, then what changed here. [waitSeconds] > 0: waits that long on the server for news
     * when there is none (the app on the screen keeps one such wait going).
     */
    suspend fun sync(waitSeconds: Int = 0): Applied = withContext(Dispatchers.IO) {
        val master = space.master() ?: return@withContext Applied(emptySet(), false, false)
        val crypto = SyncCrypto(master)
        lock.withLock { openSpace(crypto) }
        _status.value = _status.value.copy(on = true, busy = true)
        try {
            val got = pull(crypto, waitSeconds)
            lock.withLock { push(crypto) }
            val pend = lock.withLock { retryPending(crypto) }
            val all = got + pend
            if (!all.isEmpty) applied(all)
            _status.value = _status.value.copy(busy = false, lastAt = System.currentTimeMillis(), error = null, filesUp = dao.toSend().size, filesDown = dao.toFetch().size)
            all
        } catch (e: Exception) {
            _status.value = _status.value.copy(busy = false, error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private operator fun Applied.plus(o: Applied) = Applied(entries + o.entries, meds || o.meds, handedOff || o.handedOff)

    private suspend fun pull(crypto: SyncCrypto, waitSeconds: Int): Applied {
        var total = Applied(emptySet(), false, false)
        var wait = waitSeconds
        while (true) {
            val since = space.cursor
            // The wait happens outside the lock: what she does here meanwhile goes up at once.
            val res = transport.changes(since, wait)
            wait = 0
            val got = lock.withLock {
                if (space.cursor != since) return@withLock null // another sync took these already
                val a = apply(crypto, res.changes)
                space.cursor = res.seq
                a
            } ?: return total
            total += got
            if (!res.more) return total
        }
    }

    /** Changes from the server into the rows. */
    private suspend fun apply(crypto: SyncCrypto, changes: List<SyncTransport.Change>): Applied {
        val entries = HashSet<Long>()
        var meds = false
        var handed = false
        for (c in changes) {
            val st = dao.record(c.k)
            if (c.d == null) {
                if (st != null) {
                    deleteRow(st.tbl, st.rid)
                    if (st.tbl == "captures") st.rid.toLongOrNull()?.let { dao.dropFiles(it) }
                    dao.drop(c.k)
                    note(st.tbl, st.rid, entries) { meds = true }
                }
                continue
            }
            if (st != null && st.seq >= c.s && st.pending == null) continue // ours, or already here
            val rec = runCatching { Json.parseToJsonElement(crypto.open(c.k, c.d)).jsonObject }.getOrNull() ?: continue
            val r = take(crypto, c.k, c.s, rec, st)
            if (r != null) {
                note(r.first, r.second, entries) { meds = true }
                if (r.first == "captures" && r.third) handed = true
            }
        }
        return Applied(entries, meds, handed)
    }

    /**
     * One record that came: written over the row, or merged with a change made here that didn't go up
     * yet. A row that can't go in yet (what it points at hasn't come) waits as pending.
     * Returns (table, id, a capture written on a computer to be organized here) when it went in.
     */
    private suspend fun take(crypto: SyncCrypto, k: String, s: Long, rec: JsonObject, st: SyncRecord?): Triple<String, String, Boolean>? {
        val t = rec["t"]?.jsonPrimitive?.contentOrNull ?: return null
        val rid = rec["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val table = SyncTables.byName[t] ?: return null
        val incoming = (rec["r"] as? JsonObject) ?: return null
        val remote = project(table, incoming)
        val local = readRow(table, rid)
        val target = when {
            local == null -> remote
            st != null && sha(canon(local)) == st.hash -> remote
            else -> merge(st?.base?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }, local, remote)
        }
        val wasNew = local == null
        if (!writeRow(table, rid, target)) {
            dao.put(SyncRecord(k, t, rid, st?.seq ?: 0, st?.hash ?: "", st?.base ?: "", pending = buildJsonObject { put("s", s); put("rec", rec) }.toString()))
            return null
        }
        if (table.name == "captures") takeMedia(rid.toLong(), remote["media"] as? JsonObject)
        val base = canon(remote)
        dao.put(SyncRecord(k, t, rid, s, sha(base), base))
        val handed = table.name == "captures" && wasNew && target["state"]?.jsonPrimitive?.contentOrNull == "recorded" && target["source"]?.jsonPrimitive?.contentOrNull == "text"
        return Triple(t, rid, handed)
    }

    private suspend fun retryPending(crypto: SyncCrypto): Applied {
        val entries = HashSet<Long>()
        var meds = false
        var handed = false
        // Parents first: what points at something goes in after it.
        val waiting = dao.pending().sortedBy { SyncTables.ALL.indexOfFirst { t -> t.name == it.tbl } }
        for (st in waiting) {
            val p = runCatching { Json.parseToJsonElement(st.pending!!).jsonObject }.getOrNull() ?: continue
            val s = p["s"]?.jsonPrimitive?.longOrNull ?: continue
            val rec = p["rec"] as? JsonObject ?: continue
            val r = take(crypto, st.k, s, rec, st.copy(pending = null).takeIf { it.hash.isNotEmpty() }) ?: continue
            note(r.first, r.second, entries) { meds = true }
            if (r.first == "captures" && r.third) handed = true
        }
        return Applied(entries, meds, handed)
    }

    private inline fun note(table: String, rid: String, entries: MutableSet<Long>, meds: () -> Unit) {
        when (table) {
            "entries" -> rid.toLongOrNull()?.let(entries::add)
            "meds", "med_taken" -> meds()
        }
    }

    /** What changed here goes up; a write the server refused (someone wrote first) is merged and goes again. */
    private suspend fun push(crypto: SyncCrypto) {
        repeat(4) {
            val out = scan(crypto)
            if (out.isEmpty()) return
            var refused = false
            for (chunk in out.chunked(200)) {
                val results = transport.push(chunk.map { it.item }).associateBy { it.k }
                for (o in chunk) {
                    val r = results[o.item.k] ?: continue
                    if (r.ok) {
                        if (o.row == null) dao.drop(o.item.k)
                        else dao.put(SyncRecord(o.item.k, o.table.name, o.rid, r.s, sha(o.row), o.row))
                        continue
                    }
                    refused = true
                    conflict(crypto, o, r)
                }
            }
            if (!refused) return
        }
    }

    private suspend fun conflict(crypto: SyncCrypto, o: Outgoing, r: SyncTransport.Pushed) {
        val st = dao.record(o.item.k)
        if (r.d == null) {
            // Deleted there: it goes here too (deleting wins over changing).
            if (o.row != null) deleteRow(o.table.name, o.rid)
            if (o.table.name == "captures") o.rid.toLongOrNull()?.let { dao.dropFiles(it) }
            dao.drop(o.item.k)
            return
        }
        if (o.row == null) {
            // Deleted here, changed there: the deletion goes again over what is there now.
            dao.put(SyncRecord(o.item.k, o.table.name, o.rid, r.s, st?.hash ?: "", st?.base ?: ""))
            return
        }
        val rec = runCatching { Json.parseToJsonElement(crypto.open(o.item.k, r.d)).jsonObject }.getOrNull() ?: return
        val remote = project(o.table, (rec["r"] as? JsonObject) ?: return)
        val local = Json.parseToJsonElement(o.row).jsonObject
        val merged = merge(st?.base?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }, local, remote)
        if (merged != local) writeRow(o.table, o.rid, merged)
        if (o.table.name == "captures") takeMedia(o.rid.toLong(), remote["media"] as? JsonObject)
        val base = canon(remote)
        dao.put(SyncRecord(o.item.k, o.table.name, o.rid, r.s, sha(base), base))
    }

    private class Outgoing(val table: SyncTables.Table, val rid: String, val row: String?, val item: SyncTransport.Push)

    /** The rows that changed since they last went up (and the ones deleted here), sealed. */
    private suspend fun scan(crypto: SyncCrypto): List<Outgoing> {
        val out = ArrayList<Outgoing>()
        for (table in SyncTables.ALL) {
            val rows = readRows(table)
            val states = dao.records(table.name).associateBy { it.rid }
            for ((rid, row) in rows) {
                val text = canon(row)
                val h = sha(text)
                val st = states[rid]
                if (st != null && st.hash == h && st.pending == null) continue
                if (st?.pending != null) continue // a record that came and waits to go in: not overwritten from here
                val k = st?.k ?: crypto.recordKey(table.name, rid)
                val rec = buildJsonObject {
                    put("t", table.name)
                    put("id", rid)
                    put("r", row)
                }
                out += Outgoing(table, rid, text, SyncTransport.Push(k, st?.seq ?: 0, crypto.seal(k, rec.toString())))
            }
            for ((rid, st) in states) {
                if (rid in rows || st.pending != null) continue
                out += Outgoing(table, rid, null, SyncTransport.Push(st.k, st.seq, null))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ rows

    /** A row as JSON, its columns sorted, without what only means something on this phone; a capture with its files. */
    private suspend fun readRows(table: SyncTables.Table): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        sql.query("SELECT * FROM ${table.name}").use { c ->
            while (c.moveToNext()) {
                val (rid, row) = row(table, c)
                out[rid] = row
            }
        }
        if (table.name == "captures") {
            for ((rid, row) in out) out[rid] = withMedia(rid.toLong(), row)
        }
        return out
    }

    private suspend fun readRow(table: SyncTables.Table, rid: String): JsonObject? {
        val (where, args) = table.where(rid)
        val r = sql.query("SELECT * FROM ${table.name} WHERE $where", args).use { c -> if (c.moveToNext()) row(table, c) else null } ?: return null
        return if (table.name == "captures") withMedia(rid.toLong(), r.second) else r.second
    }

    private fun row(table: SyncTables.Table, c: Cursor): Pair<String, JsonObject> {
        val cols = (0 until c.columnCount).map { c.getColumnName(it) to it }.sortedBy { it.first }
        val obj = LinkedHashMap<String, JsonElement>()
        var paths: Map<String, String?> = emptyMap()
        for ((name, i) in cols) {
            if (name in table.localOnly) {
                paths = paths + (name to c.getString(i))
                continue
            }
            obj[name] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> JsonPrimitive(c.getString(i))
                else -> continue
            }
        }
        val rid = table.pk.joinToString(":") { p -> c.getString(c.getColumnIndexOrThrow(p)) }
        if (table.name == "captures") localPaths[rid.toLong()] = paths
        return rid to JsonObject(obj)
    }

    /** The phone's own paths of each capture read last ([row] leaves them out of the record). */
    private val localPaths = HashMap<Long, Map<String, String?>>()

    /**
     * A capture's files, as the record says them: the ones there already, and the ones here that never
     * went (a name for each is made now, and a thumbnail for a photo, for the Galeria to open light).
     */
    private suspend fun withMedia(captureId: Long, row: JsonObject): JsonObject {
        val known = dao.files(captureId)
        val have = known.associateBy { it.kind }.toMutableMap()
        val paths = localPaths[captureId].orEmpty()
        fun local(kind: String, column: String, mime: String?) {
            val path = paths[column] ?: return
            val f = File(path)
            if (!f.isFile) return
            val slot = have[kind]
            if (slot != null && slot.path == path) return
            if (slot != null && slot.path == null) {
                // It came from there and is here now (the download): the same name.
                have[kind] = slot.copy(path = path)
                return
            }
            have[kind] = SyncFile("$captureId:$kind", captureId, kind, SyncCrypto.newId(), f.length(), mime, path)
        }
        local("image", "imagePath", "image/jpeg")
        // A recording only goes when its sound was kept on purpose (a melody): the one waiting to be heard stays here.
        if (row["sound"]?.jsonPrimitive?.longOrNull == 1L) local("audio", "audioPath", "audio/wav")
        local("file", "filePath", row["fileMime"]?.jsonPrimitive?.contentOrNull)
        val image = have["image"]
        if (image?.path != null && have["thumb"] == null) {
            media.thumb(captureId, File(image.path), row["turn"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0)?.let { t ->
                have["thumb"] = SyncFile("$captureId:thumb", captureId, "thumb", SyncCrypto.newId(), t.length(), "image/jpeg", t.path)
            }
        }
        // What is new is kept (the name stays the same from here on).
        have.values.forEach { f -> if (f !in known) dao.putFile(f) }
        if (have.isEmpty()) return row
        val m = buildJsonObject {
            have.values.sortedBy { it.kind }.forEach { f ->
                put(f.kind, buildJsonObject {
                    put("id", f.blob)
                    put("size", f.size)
                    f.mime?.let { put("mime", it) }
                })
            }
        }
        return JsonObject(row + ("media" to m))
    }

    /** The files a record says a capture has: the ones not here yet are fetched later. */
    private suspend fun takeMedia(captureId: Long, m: JsonObject?) {
        val mine = dao.files(captureId).associateBy { it.kind }
        for ((kind, v) in m.orEmpty()) {
            val o = v as? JsonObject ?: continue
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val old = mine[kind]
            if (old?.blob == id) continue
            dao.putFile(SyncFile("$captureId:$kind", captureId, kind, id, o["size"]?.jsonPrimitive?.longOrNull ?: 0, o["mime"]?.jsonPrimitive?.contentOrNull, path = null, up = true))
        }
    }

    /** [row] over what is there (only the columns this phone has; its own paths stay), or a new row. False when it can't go in yet. */
    private fun writeRow(table: SyncTables.Table, rid: String, row: JsonObject): Boolean {
        val cols = columns(table.name)
        val values = ContentValues()
        for (name in cols) {
            if (name in table.localOnly || !row.containsKey(name)) continue
            when (val v = row[name]) {
                null, is JsonNull -> values.putNull(name)
                is JsonPrimitive -> when {
                    v.isString -> values.put(name, v.content)
                    v.booleanOrNull != null -> values.put(name, if (v.booleanOrNull == true) 1L else 0L)
                    v.longOrNull != null -> values.put(name, v.longOrNull)
                    v.doubleOrNull != null -> values.put(name, v.doubleOrNull)
                    else -> values.put(name, v.content)
                }
                else -> values.put(name, v.toString())
            }
        }
        val (where, args) = table.where(rid)
        return try {
            val n = sql.update(table.name, SQLiteDatabase.CONFLICT_ABORT, values, where, args)
            if (n == 0) {
                // A new row: a column the other side doesn't know (an older page, a newer app) gets its empty value.
                for (c in columnInfo(table.name)) {
                    if (values.containsKey(c.name) || !c.notNull || c.hasDefault) continue
                    when {
                        c.type.contains("INT") -> values.put(c.name, 0L)
                        c.type.contains("REAL") || c.type.contains("FLOA") || c.type.contains("DOUB") -> values.put(c.name, 0.0)
                        else -> values.put(c.name, "")
                    }
                }
                sql.insert(table.name, SQLiteDatabase.CONFLICT_ABORT, values)
            }
            true
        } catch (_: SQLiteConstraintException) {
            false
        } catch (_: android.database.SQLException) {
            false
        }
    }

    private fun deleteRow(table: String, rid: String) {
        val t = SyncTables.byName[table] ?: return
        val (where, args) = t.where(rid)
        sql.delete(table, where, args)
    }

    private class Column(val name: String, val type: String, val notNull: Boolean, val hasDefault: Boolean)

    private val columnCache = HashMap<String, List<Column>>()

    private fun columnInfo(table: String): List<Column> = columnCache.getOrPut(table) {
        sql.query("PRAGMA table_info($table)").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Column(
                            c.getString(c.getColumnIndexOrThrow("name")),
                            c.getString(c.getColumnIndexOrThrow("type")).orEmpty().uppercase(),
                            c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                            !c.isNull(c.getColumnIndexOrThrow("dflt_value")),
                        ),
                    )
                }
            }
        }
    }

    private fun columns(table: String): List<String> = columnInfo(table).map { it.name }

    /** A record's row as this phone would read it: only its columns (and a capture's files), sorted. */
    private fun project(table: SyncTables.Table, r: JsonObject): JsonObject {
        val cols = columns(table.name).toSet()
        return JsonObject(r.filterKeys { (it in cols && it !in table.localOnly) || (it == "media" && table.name == "captures") }.toSortedMap())
    }

    // ------------------------------------------------------------------ files

    /**
     * Photos, sounds and documents up (the ones over [SMALL] only off mobile data) and down. The
     * server is told now and then which files are still in use ([keep]).
     */
    suspend fun moveFiles(keep: Boolean = false) = withContext(Dispatchers.IO) {
        val master = space.master() ?: return@withContext
        val crypto = SyncCrypto(master)
        files.withLock {
            val metered = media.metered()
            for (f in dao.toSend()) {
                if (metered && f.size > SMALL && f.kind != "thumb") continue
                val path = f.path ?: continue
                val bytes = runCatching { File(path).readBytes() }.getOrNull()
                if (bytes == null) {
                    dao.dropFile(f.slot)
                    continue
                }
                transport.putBlob(f.blob, crypto.sealBlob(f.blob, bytes))
                dao.putFile(f.copy(up = true, size = bytes.size.toLong()))
            }
            for (f in dao.toFetch()) {
                if (metered && f.size > SMALL * 8) continue
                val sealed = transport.getBlob(f.blob) ?: continue
                val bytes = runCatching { crypto.openBlob(f.blob, sealed) }.getOrNull() ?: continue
                val row = readRow(SyncTables.byName.getValue("captures"), f.captureId.toString()) ?: continue
                val file = media.place(f.captureId, f.kind, f.mime, row["fileName"]?.jsonPrimitive?.contentOrNull, row["createdAt"]?.jsonPrimitive?.longOrNull ?: 0)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                val column = when (f.kind) {
                    "image" -> "imagePath"
                    "audio" -> "audioPath"
                    "file" -> "filePath"
                    else -> null
                }
                if (column != null) {
                    val values = ContentValues().apply { put(column, file.path) }
                    sql.update("captures", SQLiteDatabase.CONFLICT_ABORT, values, "id = ?", arrayOf(f.captureId))
                }
                dao.putFile(f.copy(path = file.path))
            }
            if (keep) transport.keep(dao.allFiles().map { it.blob })
            _status.value = _status.value.copy(filesUp = dao.toSend().size, filesDown = dao.toFetch().size)
        }
    }

    companion object {
        /** Files up to this go over mobile data too; bigger ones wait for Wi-Fi. */
        const val SMALL = 1_500_000L

        /** The row as text, its fields sorted: what is hashed and kept as the base of a merge. */
        fun canon(row: JsonObject): String = JsonObject(row.toSortedMap()).toString()

        fun sha(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * Three ways: a field changed only here stays, changed only there comes, changed on both sides
         * goes to the side changed last ([updatedAt], when the row has it; there, otherwise). Without a
         * base (never agreed), every difference counts as changed on both sides.
         */
        fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject): JsonObject {
            val newer = (local["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0) > (remote["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0)
            val out = LinkedHashMap<String, JsonElement>()
            for (key in (local.keys + remote.keys).sorted()) {
                val l = local[key]
                val r = remote[key]
                val b = base?.get(key)
                out[key] = when {
                    l == r -> l ?: JsonNull
                    base != null && l == b -> r ?: JsonNull
                    base != null && r == b -> l ?: JsonNull
                    newer -> l ?: JsonNull
                    else -> r ?: JsonNull
                }
            }
            return JsonObject(out)
        }
    }
}

/** The tables that go to the Novelo Web, parents first (what points at a row comes after it). */
object SyncTables {
    class Table(val name: String, val pk: List<String>, val localOnly: Set<String> = emptySet()) {
        fun where(rid: String): Pair<String, Array<Any?>> {
            val parts = rid.split(':')
            require(parts.size == pk.size) { "id $rid de $name" }
            return pk.joinToString(" AND ") { "$it = ?" } to parts.map<String, Any?> { it.toLongOrNull() ?: it }.toTypedArray()
        }
    }

    val ALL = listOf(
        Table("entries", listOf("id")),
        Table("entities", listOf("id")),
        Table("meds", listOf("id")),
        // The phone's own paths of a capture's files stay here; the record says its files ("media").
        Table("captures", listOf("id"), localOnly = setOf("audioPath", "imagePath", "filePath")),
        Table("entry_topics", listOf("entryId", "topicId")),
        Table("links", listOf("a", "b")),
        Table("progress", listOf("id")),
        Table("med_taken", listOf("medId", "at")),
    )

    val byName: Map<String, Table> = ALL.associateBy { it.name }
}

/** Where the space is kept on this phone: on or off, the server's space, how far it has read, and its key. */
interface SyncSpace {
    val on: Boolean
    var cursor: Long
    var spaceId: String?
    fun master(): ByteArray?
    fun start(master: ByteArray)
    fun stop()
}

/** What the sync needs of the phone's files. */
interface SyncMedia {
    /** On mobile data (or another network that costs). */
    fun metered(): Boolean

    /** A photo's thumbnail (480 px, upright) as a file; null when it can't be made. */
    fun thumb(captureId: Long, image: File, turn: Int): File?

    /** Where a file that came for a capture goes. */
    fun place(captureId: Long, kind: String, mime: String?, name: String?, at: Long): File
}
