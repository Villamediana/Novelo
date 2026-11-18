package com.villamediana.novelo.sync

import com.villamediana.novelo.server.NoveloServer
import com.villamediana.novelo.server.ServerException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the phone asks of the server for the Novelo Web (docs/WEB.md, "O protocolo"). */
interface SyncTransport {
    data class Opened(val space: String, val seq: Long, val fresh: Boolean)
    data class Change(val k: String, val s: Long, val d: String?)
    data class Changes(val changes: List<Change>, val seq: Long, val more: Boolean, val head: Long)
    data class Push(val k: String, val base: Long, val d: String?)
    data class Pushed(val k: String, val ok: Boolean, val s: Long, val d: String?)
    data class Computer(val id: String, val name: String, val created: Long, val lastSeen: Long)

    /** The space opened with this key ([keyCheck]); another key there throws [KeyMismatch] unless [reset]. */
    suspend fun open(keyCheck: String, reset: Boolean): Opened
    suspend fun erase()
    suspend fun changes(since: Long, waitSeconds: Int = 0): Changes
    suspend fun push(items: List<Push>): List<Pushed>
    suspend fun putBlob(id: String, bytes: ByteArray)
    suspend fun getBlob(id: String): ByteArray?
    suspend fun have(ids: List<String>): Set<String>
    suspend fun keep(ids: List<String>)
    suspend fun approve(linkId: String, wrapped: SyncCrypto.Wrapped, owner: String): String
    suspend fun computers(): List<Computer>
    suspend fun unlink(id: String)

    class KeyMismatch : Exception("key_mismatch")
}

/** The real one: signed requests to the Novelo's server. */
class ServerTransport(private val server: NoveloServer) : SyncTransport {
    private fun body(o: JsonObject) = o.toString().toByteArray()

    private suspend fun json(method: String, path: String, o: JsonObject? = null, query: String = ""): JsonObject =
        server.parse(server.call(method, path, query, o?.let(::body)))

    override suspend fun open(keyCheck: String, reset: Boolean): SyncTransport.Opened {
        val r = try {
            json("POST", "/v1/sync/space", buildJsonObject { put("key_check", keyCheck); put("reset", reset) })
        } catch (e: ServerException) {
            if (e.code == "key_mismatch") throw SyncTransport.KeyMismatch()
            throw e
        }
        return SyncTransport.Opened(r["space"]!!.jsonPrimitive.content, r["seq"]?.jsonPrimitive?.longOrNull ?: 0, r["fresh"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    override suspend fun erase() {
        json("DELETE", "/v1/sync/space")
    }

    override suspend fun changes(since: Long, waitSeconds: Int): SyncTransport.Changes {
        val r = json("GET", "/v1/sync/changes", query = "?since=$since&limit=500" + if (waitSeconds > 0) "&wait=$waitSeconds" else "")
        return SyncTransport.Changes(
            changes = r["changes"]!!.jsonArray.map { c ->
                val o = c.jsonObject
                SyncTransport.Change(o["k"]!!.jsonPrimitive.content, o["s"]!!.jsonPrimitive.longOrNull ?: 0, (o["d"] as? JsonPrimitive)?.contentOrNull)
            },
            seq = r["seq"]?.jsonPrimitive?.longOrNull ?: since,
            more = r["more"]?.jsonPrimitive?.booleanOrNull ?: false,
            head = r["head"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    override suspend fun push(items: List<SyncTransport.Push>): List<SyncTransport.Pushed> {
        val r = json("POST", "/v1/sync/push", buildJsonObject {
            putJsonArray("items") {
                items.forEach { i ->
                    add(buildJsonObject { put("k", i.k); put("base", i.base); put("d", i.d) })
                }
            }
        })
        return r["results"]!!.jsonArray.map { x ->
            val o = x.jsonObject
            SyncTransport.Pushed(o["k"]!!.jsonPrimitive.content, o["ok"]!!.jsonPrimitive.booleanOrNull == true, o["s"]?.jsonPrimitive?.longOrNull ?: 0, (o["d"] as? JsonPrimitive)?.contentOrNull)
        }
    }

    override suspend fun putBlob(id: String, bytes: ByteArray) {
        server.parse(server.call("PUT", "/v1/sync/blob/$id", body = bytes, type = "application/octet-stream"))
    }

    override suspend fun getBlob(id: String): ByteArray? {
        val r = server.call("GET", "/v1/sync/blob/$id")
        if (r.status == 404) return null
        if (r.status !in 200..299) server.parse(r)
        return r.bytes
    }

    override suspend fun have(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val r = json("POST", "/v1/sync/blobs/have", buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } })
        return (r["have"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    }

    override suspend fun keep(ids: List<String>) {
        json("POST", "/v1/sync/blobs/keep", buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } })
    }

    override suspend fun approve(linkId: String, wrapped: SyncCrypto.Wrapped, owner: String): String {
        val r = json("POST", "/v1/link/$linkId/approve", buildJsonObject {
            putJsonObject("wrapped") { put("pub", wrapped.pub); put("iv", wrapped.iv); put("ct", wrapped.ct) }
            put("owner", owner)
        })
        return r["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    override suspend fun computers(): List<SyncTransport.Computer> =
        json("GET", "/v1/links")["links"]!!.jsonArray.map { x ->
            val o = x.jsonObject
            SyncTransport.Computer(o["id"]!!.jsonPrimitive.content, o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["created"]?.jsonPrimitive?.longOrNull ?: 0, o["last_seen"]?.jsonPrimitive?.longOrNull ?: 0)
        }

    override suspend fun unlink(id: String) {
        json("DELETE", "/v1/links/$id")
    }
}
