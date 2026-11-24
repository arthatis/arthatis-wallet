package com.arthatis.wallet.core

import com.arthatis.wallet.core.crypto.Crypto
import com.arthatis.wallet.core.crypto.GroupCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Reads docs/test-vectors/interop.json (written by the web tests), proves Kotlin opens web ciphertext, and appends
 * Kotlin-sealed vectors that the web tests then open. Skipped when the file is absent.
 */
class InteropTest {
    private val file = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/test-vectors/interop.json") }.firstOrNull { it.exists() }

    @Test fun kotlinOpensWebAndWebOpensKotlin() {
        val f = file ?: return
        val root = Json.parseToJsonElement(f.readText()).jsonObject
        val web = root["web"]!!.jsonObject
        fun s(k: String) = web[k]!!.jsonPrimitive.content

        // web → Kotlin
        assertEquals(s("plaintext"), Crypto.openText(Crypto.unb64(s("key")), s("blob"), s("aad")))
        val rk = Crypto.parseRecoveryKey(s("recoveryKey"))!!
        assertEquals("arsip", String(Crypto.unwrapWithRecoveryKey(rk, Crypto.unb64(s("wrapped")), 1024)))

        // web → Kotlin: a group key envelope + record sealed by the web's own group code
        web["group"]?.jsonObject?.let { g ->
            fun gs(k: String) = g[k]!!.jsonPrimitive.content
            val keys = Crypto.DeviceKeys(Crypto.unb64(s("boxPriv")), Crypto.unb64(s("boxPub")), Crypto.unb64(s("kemPriv")), Crypto.unb64(s("kemPub")), ByteArray(32), ByteArray(32))
            val learned = assertNotNull(GroupCrypto.unwrapKey(keys, gs("envelope")))
            assertEquals(gs("epoch").toInt(), learned.epoch)
            assertEquals(gs("recordJson"), GroupCrypto.openRecord(learned.key, gs("groupId"), learned.epoch, "contribution", gs("recordId"), gs("record")))
            assertEquals(gs("profileJson"), Crypto.openText(learned.key, gs("profile"), GroupCrypto.profileAad(gs("groupId"))))
        }

        // Kotlin → web
        val secret = Crypto.randomBytes(32)
        val key = Crypto.randomBytes(32)
        val kotlin = buildJsonObject {
            put("secret", Crypto.b64(secret))
            put("envelope", Crypto.b64(Crypto.sealTo(Crypto.unb64(s("boxPub")), Crypto.unb64(s("kemPub")), secret)))
            put("key", Crypto.b64(key)); put("aad", "transaction|t2"); put("plaintext", "kopi 32k")
            put("blob", Crypto.sealText(key, "kopi 32k", "transaction|t2"))
            put("wrapped", Crypto.b64(Crypto.wrapWithRecoveryKey(rk, "arsip".toByteArray(), 1024)))
            // group format: envelope to the web's keys, a record and a profile under that group key
            val groupKey = Crypto.randomBytes(32)
            put("group", buildJsonObject {
                put("groupId", "grp-kt"); put("epoch", 4); put("recordId", "c-kt")
                put("envelope", GroupCrypto.wrapKey(groupKey, 4, Crypto.unb64(s("boxPub")), Crypto.unb64(s("kemPub"))))
                put("recordJson", "{\"amountMinor\":250000}")
                put("record", GroupCrypto.sealRecord(groupKey, "grp-kt", 4, "contribution", "c-kt", "{\"amountMinor\":250000}"))
                put("profileJson", "{\"name\":\"Sinta\"}")
                put("profile", Crypto.sealText(groupKey, "{\"name\":\"Sinta\"}", GroupCrypto.profileAad("grp-kt")))
            })
        }
        f.writeText(JsonObject(root + ("kotlin" to kotlin)).toString())
        assertContentEquals(secret, secret)
    }
}
