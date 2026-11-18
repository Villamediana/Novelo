package com.villamediana.novelo.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The phone's locks are the page's: the same key gives the same names and opens the same bytes (vectors from web/js/crypto.js). */
class SyncCryptoTest {
    private val master = ByteArray(32) { it.toByte() }
    private val crypto = SyncCrypto(master)

    @Test fun theSameKeyGivesTheSameNamesAsThePage() {
        assertEquals("SB8bi6061rT5kY5SeVB6Bw", crypto.check)
        assertEquals("GJCT4l9iHgV6FmDnCGLRZoylkBi57goMdVIWIOEtAe0", crypto.recordKey("entries", "42"))
        assertEquals("0TV_cfiCyaOA3EdCx63mscJ4tAoHfhqmAh_RFi3mH5M", crypto.recordKey("entry_topics", "7:3"))
    }

    @Test fun whatThePageSealedOpensHereAndTheOtherWayAround() {
        val k = crypto.recordKey("entries", "42")
        val fromPage = "UsDCOlDKPYTPvunU/Jby9LREl+7I+PN9YqzP58V/dFfrYG2r9Dpa+HpyAGAfEkejHlmr/kAnXLkk2Dy4vrde4eEuAqyeu64uMU17QWERPyo="
        val rec = Json.parseToJsonElement(crypto.open(k, fromPage)).jsonObject
        assertEquals("Olá, Novelo", rec["r"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("foto", String(crypto.openBlob("blob-id-teste-123456", java.util.Base64.getDecoder().decode("1hGtQRTjTbFZJvS+X8G88AHvuNT1HBkm1Ssyrj5xvMw="))))
        // Sealed here, opened here; under another name it doesn't open.
        val sealed = crypto.seal(k, """{"x":1}""")
        assertEquals("""{"x":1}""", crypto.open(k, sealed))
        assertThrows(Exception::class.java) { crypto.open(crypto.recordKey("entries", "43"), sealed) }
        // Another key doesn't open it either.
        assertThrows(Exception::class.java) { SyncCrypto(ByteArray(32) { 9 }).open(k, sealed) }
        assertNotEquals(crypto.seal(k, "a"), crypto.seal(k, "a")) // a new IV each time
    }

    @Test fun theMergeKeepsWhatEachSideChanged() {
        fun o(s: String) = Json.parseToJsonElement(s).jsonObject
        val base = o("""{"title":"A","status":"todo","body":"x","updatedAt":1}""")
        val local = o("""{"title":"A","status":"done","body":"x","updatedAt":5}""")
        val remote = o("""{"title":"B","status":"todo","body":"x","updatedAt":3}""")
        assertEquals(o("""{"body":"x","status":"done","title":"B","updatedAt":5}"""), SyncEngine.merge(base, local, remote))
        // Both changed the same field: the one changed last wins.
        val l2 = o("""{"title":"Mine","updatedAt":9}""")
        val r2 = o("""{"title":"Theirs","updatedAt":7}""")
        assertEquals("Mine", SyncEngine.merge(o("""{"title":"Old","updatedAt":1}"""), l2, r2)["title"]!!.jsonPrimitive.content)
        assertEquals("Theirs", SyncEngine.merge(o("""{"title":"Old","updatedAt":1}"""), r2.let { o("""{"title":"Mine","updatedAt":2}""") }, r2)["title"]!!.jsonPrimitive.content)
    }

    @Test fun aComputersCodeIsReadOnlyWhenItIsOne() {
        val key = SyncCrypto.b64u(byteArrayOf(4) + ByteArray(64) { 1 })
        val code = NoveloWeb.code("https://novelo.simplyonegoal.com/web/link#abcdefghijklmnopqrstuv.$key")
        assertEquals("abcdefghijklmnopqrstuv", code!!.linkId)
        assertEquals(key, code.webKey)
        assertEquals(null, NoveloWeb.code("https://novelo.simplyonegoal.com/web/link#abc.$key"))
        assertEquals(null, NoveloWeb.code("https://novelo.simplyonegoal.com/web/link#abcdefghijklmnopqrstuv.AAAA"))
        assertEquals(null, NoveloWeb.code("http://evil/web/link#abcdefghijklmnopqrstuv.$key"))
        assertEquals(null, NoveloWeb.code(null))
    }
}
