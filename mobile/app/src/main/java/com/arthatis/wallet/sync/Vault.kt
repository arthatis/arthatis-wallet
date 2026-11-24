package com.arthatis.wallet.sync

import com.arthatis.wallet.core.crypto.Crypto
import com.arthatis.wallet.data.WalletDao
import com.arthatis.wallet.data.Prefs
import com.arthatis.wallet.data.RecordRow
import com.arthatis.wallet.net.ApiException
import com.arthatis.wallet.net.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

sealed interface SyncResult {
    data class Ok(val pushed: Int, val pulled: Int) : SyncResult
    data object NoServer : SyncResult
    data class Offline(val reason: String) : SyncResult
    /** The server refuses this vault: `payment_required` (hosted: not activated / expired) or `suspended`. Local data is untouched. */
    data class Blocked(val reason: String) : SyncResult
}

/** What a server asks of a new vault (public `GET /v1/server`). */
data class ServerInfo(val registration: String, val mail: Boolean, val activation: Boolean, val payUrl: String?)

data class VaultStatus(val vaultId: String, val active: Boolean, val activationRequired: Boolean, val activeUntil: Long?, val payUrl: String?, val isAdmin: Boolean)

/** Device identity, vault key, recovery key, and the encrypted sync loop (docs/protocol.md). */
class Vault(private val prefs: Prefs, private val dao: WalletDao, val http: Http) {
    private val b64 = Crypto::b64

    /** Keys exist from first launch so a server can be connected later without re-encrypting anything. */
    fun ensureKeys() {
        if (prefs.vaultKey == null) prefs.vaultKey = Crypto.randomBytes(32)
        if (prefs.deviceKeys == null) prefs.deviceKeys = Crypto.newDeviceKeys()
    }

    val connected get() = prefs.serverUrl != null && prefs.token != null

    /** Registers this phone as the first device of a new vault. No email, no account. */
    suspend fun connect(label: String, email: String? = null, emailCode: String? = null) {
        ensureKeys()
        val keys = prefs.deviceKeys!!
        val res = http.call(
            "POST", "/v1/devices",
            buildJsonObject {
                put("label", label)
                put("signPub", b64(keys.signPub)); put("boxPub", b64(keys.boxPub)); put("kemPub", b64(keys.kemPub))
                if (!email.isNullOrBlank() && !emailCode.isNullOrBlank()) { put("email", email); put("emailCode", emailCode) }
            },
            auth = false,
        )
        prefs.vaultId = res["vaultId"]!!.jsonPrimitive.content
        prefs.token = res["token"]!!.jsonPrimitive.content
        prefs.syncCursor = 0
        dao.markAllDirty()
    }

    /** Pushes dirty records, then pulls everything after the cursor. Safe to call repeatedly. */
    suspend fun sync(): SyncResult {
        if (prefs.serverUrl == null) return SyncResult.NoServer
        if (prefs.token == null) return SyncResult.NoServer
        val key = prefs.vaultKey ?: return SyncResult.NoServer
        var pushed = 0; var pulled = 0
        try {
            while (true) {
                val dirty = dao.dirtyRecords(200)
                val body = buildJsonObject {
                    put("cursor", prefs.syncCursor)
                    putJsonArray("records") {
                        dirty.forEach { r ->
                            add(buildJsonObject {
                                put("entity", r.entity); put("id", r.id)
                                put("updatedAt", r.updatedAt)
                                if (r.deletedAt == null) put("deletedAt", JsonNull) else put("deletedAt", r.deletedAt)
                                put("payload", Crypto.sealText(key, r.json, "${r.entity}|${r.id}"))
                            })
                        }
                    }
                }
                val res = http.call("POST", "/v1/sync", body)
                dirty.forEach { dao.markClean(it.entity, it.id, it.updatedAt) }
                pushed += dirty.size
                val incoming = res["records"]!!.jsonArray
                pulled += apply(incoming, key)
                prefs.syncCursor = res["cursor"]!!.jsonPrimitive.long
                val more = res["more"]?.jsonPrimitive?.boolean == true
                if (!more && dao.dirtyRecords(1).isEmpty()) break
            }
            return SyncResult.Ok(pushed, pulled)
        } catch (e: ApiException) {
            if (e.status == 401) { prefs.token = null }
            if (e.status == 402) return SyncResult.Blocked("payment_required")
            if (e.status == 403 && e.code == "suspended") return SyncResult.Blocked("suspended")
            return SyncResult.Offline("api_${e.status}")
        } catch (e: java.io.IOException) {
            return SyncResult.Offline("network")
        }
    }

    /** Last write wins by `updatedAt`; a local unsynced newer edit is kept. */
    private suspend fun apply(records: JsonArray, key: ByteArray): Int {
        val rows = mutableListOf<RecordRow>()
        for (item in records) {
            val o = item.jsonObject
            val entity = o["entity"]!!.jsonPrimitive.content
            val id = o["id"]!!.jsonPrimitive.content
            val updatedAt = o["updatedAt"]!!.jsonPrimitive.long
            val deletedAt = (o["deletedAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long
            val local = dao.record(entity, id)
            if (local != null && local.updatedAt >= updatedAt) continue
            val json = runCatching { Crypto.openText(key, o["payload"]!!.jsonPrimitive.content, "$entity|$id") }.getOrNull() ?: continue
            rows += RecordRow(entity, id, json, updatedAt, deletedAt, dirty = false)
        }
        if (rows.isNotEmpty()) dao.upsert(rows)
        return rows.size
    }

    /** Public policy of a server, read before registering. Null if it cannot be reached. */
    suspend fun serverInfo(url: String): ServerInfo? = runCatching {
        val o = Http({ url }, { null }).call("GET", "/v1/server", auth = false)
        ServerInfo(
            o["registration"]!!.jsonPrimitive.content, o["mail"]?.jsonPrimitive?.boolean == true,
            o["activation"]?.jsonPrimitive?.boolean == true, (o["payUrl"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        )
    }.getOrNull()

    /** Email-gated server: asks it to mail a registration code. Null on success, else an error code. */
    suspend fun requestRegisterCode(url: String, email: String): String? = try {
        Http({ url }, { null }).call("POST", "/v1/register/email", buildJsonObject { put("email", email) }, auth = false); null
    } catch (e: ApiException) { e.code ?: "http_${e.status}" } catch (e: java.io.IOException) { "network" }

    suspend fun status(): VaultStatus? = runCatching {
        val o = http.call("GET", "/v1/vault")
        VaultStatus(
            o["vaultId"]!!.jsonPrimitive.content, o["active"]?.jsonPrimitive?.boolean != false, o["activationRequired"]?.jsonPrimitive?.boolean == true,
            (o["activeUntil"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long, (o["payUrl"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
            o["isAdmin"]?.jsonPrimitive?.boolean == true,
        )
    }.getOrNull()

    /** Creates the 160-bit recovery key. Returns it for the user to write down; only the wrapped vault key leaves the phone. */
    suspend fun createRecoveryKey(): String {
        ensureKeys()
        val rk = Crypto.newRecoveryKey()
        val blob = Crypto.wrapWithRecoveryKey(rk, prefs.vaultKey!!)
        prefs.recoveryCreated = true
        if (connected) {
            http.call("PUT", "/v1/vault/recovery-blob", buildJsonObject { put("blob", b64(blob)) })
        }
        return Crypto.formatRecoveryKey(rk)
    }

    /** Step 1: the server mails a 6-digit code. Returns null on success or the server's error code. */
    suspend fun requestEmailCode(email: String): String? = try {
        http.call("PUT", "/v1/vault/email", buildJsonObject { put("email", email) }); null
    } catch (e: ApiException) { e.code ?: "http_${e.status}" } catch (e: java.io.IOException) { "network" }

    /** Step 2: typing the code back proves the inbox is yours. Returns null on success or an error code. */
    suspend fun verifyEmailCode(code: String): String? = try {
        http.call("POST", "/v1/vault/email/verify", buildJsonObject { put("code", code) }); null
    } catch (e: ApiException) { e.code ?: "http_${e.status}" } catch (e: java.io.IOException) { "network" }

    suspend fun removeEmail() { http.call("DELETE", "/v1/vault/email") }

    /** (verified, pending) for the recovery email. */
    suspend fun emailState(): Pair<Boolean, Boolean> {
        val o = http.call("GET", "/v1/vault")
        return (o["hasEmail"]?.jsonPrimitive?.boolean == true) to (o["emailPending"]?.jsonPrimitive?.boolean == true)
    }

    // --- Restore on a new phone ------------------------------------------------------------

    suspend fun requestRecoveryEmail(email: String) {
        http.call("POST", "/v1/recovery/request", buildJsonObject { put("email", email) }, auth = false)
    }

    /** Email token + recovery key → new device on the old vault, vault key unwrapped locally. */
    suspend fun restore(emailToken: String, recoveryKeyText: String, label: String): Boolean {
        val rk = Crypto.parseRecoveryKey(recoveryKeyText) ?: return false
        val res = http.call(
            "POST", "/v1/recovery/verify",
            buildJsonObject { put("token", emailToken.trim()); put("label", label) },
            auth = false,
        )
        val blob = (res["recoveryBlob"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return false
        val vk = runCatching { Crypto.unwrapWithRecoveryKey(rk, Crypto.unb64(blob)) }.getOrNull() ?: return false
        ensureKeys()
        prefs.vaultKey = vk
        prefs.vaultId = res["vaultId"]!!.jsonPrimitive.content
        prefs.token = res["token"]!!.jsonPrimitive.content
        prefs.syncCursor = 0
        prefs.recoveryCreated = true
        return true
    }

    // --- Web link (QR) ---------------------------------------------------------------------

    class LinkRequest(val id: String, val nonce: String)

    /** `arthatis://link?s=<server>&id=<id>&n=<nonce>`. */
    fun parseLink(text: String): LinkRequest? {
        val uri = android.net.Uri.parse(text)
        if (uri.scheme != "arthatis" || uri.host != "link") return null
        val id = uri.getQueryParameter("id") ?: return null
        val n = uri.getQueryParameter("n") ?: return null
        return LinkRequest(id, n)
    }

    class LinkPreview(val code: String, val label: String, val webPub: ByteArray)

    suspend fun scanLink(req: LinkRequest): LinkPreview {
        val res = http.call("POST", "/v1/link/scan", buildJsonObject { put("id", req.id); put("nonce", req.nonce) })
        return LinkPreview(
            res["code"]!!.jsonPrimitive.content, res["label"]!!.jsonPrimitive.content,
            Crypto.unb64(res["webPub"]!!.jsonPrimitive.content),
        )
    }

    /** Seals the vault key to the browser's public keys and hands it to the server as an opaque envelope. */
    suspend fun approveLink(req: LinkRequest, preview: LinkPreview) {
        val (x25519, kem) = Crypto.splitWebPub(preview.webPub)
        val envelope = Crypto.sealTo(x25519, kem, prefs.vaultKey!!)
        http.call("POST", "/v1/link/approve", buildJsonObject { put("id", req.id); put("nonce", req.nonce); put("envelope", b64(envelope)) })
        // The web copy needs everything on the server.
        sync()
    }

    data class WebSession(val id: String, val label: String, val lastSeenAt: Long)

    suspend fun webSessions(): List<WebSession> =
        http.call("GET", "/v1/sessions")["sessions"]!!.jsonArray.map {
            val o = it.jsonObject
            WebSession(o["id"]!!.jsonPrimitive.content, o["label"]!!.jsonPrimitive.content, o["lastSeenAt"]!!.jsonPrimitive.long)
        }

    suspend fun revokeSession(id: String) { http.call("DELETE", "/v1/sessions/$id") }
    suspend fun revokeAllSessions() { http.call("POST", "/v1/sessions/revoke-all") }

    /** Reachability + version check used by the "VPS pribadi" step. */
    suspend fun ping(url: String): Boolean = runCatching {
        Http({ url }, { null }).call("GET", "/health", auth = false)["ok"]?.jsonPrimitive?.boolean == true
    }.getOrDefault(false)
}
