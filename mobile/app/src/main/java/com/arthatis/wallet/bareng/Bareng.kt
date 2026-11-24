package com.arthatis.wallet.bareng

import com.arthatis.wallet.core.Syncable
import com.arthatis.wallet.core.crypto.Crypto
import com.arthatis.wallet.core.crypto.GroupCrypto
import com.arthatis.wallet.core.ledgerJson
import com.arthatis.wallet.data.GroupRecordRow
import com.arthatis.wallet.data.SecretStore
import com.arthatis.wallet.data.WalletDao
import com.arthatis.wallet.net.ApiException
import com.arthatis.wallet.net.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** Withdrawals above this need [APPROVALS_REQUIRED] manager votes (design M09). Amounts are encrypted, so the client decides. */
/** Rp1.000.000 for IDR; other currencies keep 1.000.000 minor units. */
fun withdrawThresholdMinor(currency: String): Long = com.arthatis.wallet.core.toMinor(java.math.BigDecimal(if (currency == "IDR") 1_000_000 else 10_000), currency)
const val APPROVALS_REQUIRED = 2

@Serializable
data class GroupMeta(
    val name: String,
    /** tabungan | investasi */
    val kind: String,
    val currency: String,
    val targetMinor: Long? = null,
    val targetDay: Long? = null,
    val note: String? = null,
)

@Serializable data class MemberProfile(val name: String, val city: String? = null)

@Serializable
data class Contribution(
    override val id: String,
    val vaultId: String,
    /** contribution | withdrawal */
    val kind: String,
    val amountMinor: Long,
    val note: String = "",
    val at: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
) : Syncable

data class Member(
    val vaultId: String, val role: String, val status: String,
    val name: String, val city: String?, val joinedAt: Long, val boxPub: ByteArray, val kemPub: ByteArray,
)

data class GroupSummary(
    val id: String, val role: String, val status: String, val epoch: Int,
    val rotateRequired: Boolean, val memberCount: Int, val meta: GroupMeta?,
)

data class Approval(
    val id: String, val status: String, val required: Int, val approvals: Int, val createdBy: String,
    val amountMinor: Long, val note: String,
)

data class Activity(val text: String, val at: Long)

data class Invite(val link: String, val expiresAt: Long, val maxUses: Int)

sealed interface JoinResult {
    data class Pending(val groupName: String?) : JoinResult
    data object WrongServer : JoinResult
    data object Invalid : JoinResult
}

/**
 * Shared wallets over the sync server. All content is encrypted with a per-group key (docs/protocol.md; wire format in
 * [GroupCrypto]). Depends on small interfaces only, so the whole flow runs in JVM tests against a real backend.
 */
class Bareng(
    private val secrets: SecretStore,
    private val dao: WalletDao,
    private val http: Http,
    /** This member's own display profile, written under each new group key. */
    private val ownProfile: suspend () -> MemberProfile = { MemberProfile("—") },
) {
    private fun keyName(groupId: String, epoch: Int) = "gk.$groupId.$epoch"

    private fun groupKey(groupId: String, epoch: Int): ByteArray? = secrets.secret(keyName(groupId, epoch))

    /** Every key this phone holds for the group up to [epoch]: older items stay readable after a rotation. */
    private fun knownKeys(groupId: String, epoch: Int): Map<Int, ByteArray> =
        (1..epoch).mapNotNull { e -> groupKey(groupId, e)?.let { e to it } }.toMap()

    private fun decodeJson(groupId: String, epoch: Int, payload: String, aad: String): kotlinx.serialization.json.JsonObject? =
        GroupCrypto.openWithAnyKey(knownKeys(groupId, epoch), payload, aad)?.let {
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }.getOrNull()
        }

    /** Stores a group key under the vault key (record `groupkey`) so a linked browser can open shared wallets. */
    private suspend fun mirrorKey(groupId: String, epoch: Int, key: ByteArray) {
        val now = System.currentTimeMillis()
        val id = "gk_${groupId}_$epoch"
        if (dao.record("groupkey", id) != null) return
        val json = """{"id":"$id","groupId":"$groupId","epoch":$epoch,"key":"${Crypto.b64(key)}","updatedAt":$now,"deletedAt":null}"""
        dao.upsert(listOf(com.arthatis.wallet.data.RecordRow("groupkey", id, json, now, null, dirty = true)))
    }

    /**
     * Opens this phone's envelope and stores the key the first time an epoch is seen. A newly activated member (or anyone
     * after a rotation) also rewrites their own profile under the key everyone now shares; until then it is sealed with the
     * invite key (or an old group key) and shows up as "—".
     */
    private suspend fun ensureKey(groupId: String, epoch: Int, envelope: String?) {
        if (groupKey(groupId, epoch) != null) return
        val keys = secrets.deviceKeys ?: return
        val learned = envelope?.let { GroupCrypto.unwrapKey(keys, it) } ?: return
        secrets.putSecret(keyName(groupId, learned.epoch), learned.key)
        mirrorKey(groupId, learned.epoch, learned.key)
        runCatching { setProfile(groupId, learned.epoch, ownProfile()) }
    }

    private fun decodeMeta(groupId: String, epoch: Int, meta: String): GroupMeta? =
        GroupCrypto.openWithAnyKey(knownKeys(groupId, epoch), meta, GroupCrypto.metaAad(groupId))
            ?.let { runCatching { ledgerJson.decodeFromString(GroupMeta.serializer(), it) }.getOrNull() }

    private fun sealMeta(key: ByteArray, aadGroupId: String, meta: GroupMeta) =
        Crypto.sealText(key, ledgerJson.encodeToString(GroupMeta.serializer(), meta), GroupCrypto.metaAad(aadGroupId))

    suspend fun list(): List<GroupSummary> {
        val res = http.call("GET", "/v1/groups")["groups"]!!.jsonArray
        return res.map { item ->
            val o = item.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val status = o["status"]!!.jsonPrimitive.content
            val epoch = o["epoch"]!!.jsonPrimitive.int
            val envelope = (o["envelope"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (status == "active") ensureKey(id, epoch, envelope)
            val metaText = (o["meta"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            GroupSummary(
                id, o["role"]!!.jsonPrimitive.content, status, epoch,
                o["rotateRequired"]?.jsonPrimitive?.let { it.int == 1 } == true,
                o["memberCount"]!!.jsonPrimitive.int,
                metaText?.let { decodeMeta(id, epoch, it) },
            )
        }
    }

    suspend fun create(meta: GroupMeta, ownerName: String, ownerCity: String?): String {
        val keys = secrets.deviceKeys!!
        val gk = Crypto.randomBytes(32)
        // The id is assigned by the server, so the first copy is sealed under a placeholder id and re-saved with the real one.
        val tmp = "pending"
        val profile = MemberProfile(ownerName, ownerCity)
        val created = http.call(
            "POST", "/v1/groups",
            buildJsonObject {
                put("meta", sealMeta(gk, tmp, meta))
                put("envelope", GroupCrypto.wrapKey(gk, 1, keys.boxPub, keys.kemPub))
                put("profile", Crypto.sealText(gk, ledgerJson.encodeToString(MemberProfile.serializer(), profile), GroupCrypto.profileAad(tmp)))
            },
        )
        val id = created["id"]!!.jsonPrimitive.content
        secrets.putSecret(keyName(id, 1), gk)
        mirrorKey(id, 1, gk)
        http.call("PUT", "/v1/groups/$id/meta", buildJsonObject { put("meta", sealMeta(gk, id, meta)) })
        setProfile(id, 1, profile)
        return id
    }

    suspend fun setProfile(groupId: String, epoch: Int, profile: MemberProfile) {
        val gk = groupKey(groupId, epoch) ?: return
        http.call("PUT", "/v1/groups/$groupId/profile", buildJsonObject {
            put("profile", Crypto.sealText(gk, ledgerJson.encodeToString(MemberProfile.serializer(), profile), GroupCrypto.profileAad(groupId)))
        })
    }

    suspend fun updateMeta(groupId: String, epoch: Int, meta: GroupMeta) {
        val gk = groupKey(groupId, epoch) ?: return
        http.call("PUT", "/v1/groups/$groupId/meta", buildJsonObject { put("meta", sealMeta(gk, groupId, meta)) })
    }

    suspend fun detail(groupId: String): Pair<GroupSummary, List<Member>>? {
        val o = http.call("GET", "/v1/groups/$groupId")
        val epoch = o["epoch"]!!.jsonPrimitive.int
        ensureKey(groupId, epoch, (o["envelope"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content)
        val inviteKeys = inviteKeys(groupId)
        val members = o["members"]!!.jsonArray.map { m ->
            val mo = m.jsonObject
            val status = mo["status"]!!.jsonPrimitive.content
            val profileText = mo["profile"]!!.jsonPrimitive.content
            // Active members wrote their profile under a group key; pending ones sealed it with the invite key we issued.
            val json = if (status == "pending") GroupCrypto.openJoinProfile(inviteKeys, profileText)
            else GroupCrypto.openWithAnyKey(knownKeys(groupId, epoch), profileText, GroupCrypto.profileAad(groupId))
            val profile = json?.let { runCatching { ledgerJson.decodeFromString(MemberProfile.serializer(), it) }.getOrNull() }
            Member(
                mo["vaultId"]!!.jsonPrimitive.content, mo["role"]!!.jsonPrimitive.content, status,
                profile?.name ?: "—", profile?.city, mo["joinedAt"]!!.jsonPrimitive.long,
                Crypto.unb64(mo["boxPub"]!!.jsonPrimitive.content), Crypto.unb64(mo["kemPub"]!!.jsonPrimitive.content),
            )
        }
        val summary = GroupSummary(
            groupId, o["role"]!!.jsonPrimitive.content, "active", epoch, o["rotateRequired"]!!.jsonPrimitive.boolean,
            members.count { it.status == "active" }, decodeMeta(groupId, epoch, o["meta"]!!.jsonPrimitive.content),
        )
        return summary to members
    }

    // --- Invites ------------------------------------------------------------------------------

    /** Invite keys this phone issued for a group, so it can read the name a joiner put in their request. */
    private fun inviteKeys(groupId: String): List<ByteArray> =
        (secrets.secret("iks.$groupId") ?: ByteArray(0)).toList().chunked(32).filter { it.size == 32 }.map { it.toByteArray() }

    private fun rememberInviteKey(groupId: String, ik: ByteArray) {
        secrets.putSecret("iks.$groupId", (secrets.secret("iks.$groupId") ?: ByteArray(0)) + ik)
    }

    suspend fun invite(groupId: String, epoch: Int, groupName: String, role: String, serverUrl: String): Invite {
        // 32 bytes: it keys XChaCha20-Poly1305 directly.
        val ik = Crypto.randomBytes(32)
        val res = http.call("POST", "/v1/groups/$groupId/invites", buildJsonObject {
            put("role", role)
            put("maxUses", 10)
            put("preview", Crypto.sealText(ik, groupName, GroupCrypto.INVITE_PREVIEW_AAD))
        })
        rememberInviteKey(groupId, ik)
        val token = res["token"]!!.jsonPrimitive.content
        return Invite("$serverUrl/invite/$token#${Crypto.b64url(ik)}", res["expiresAt"]!!.jsonPrimitive.long, 10)
    }

    /** `https://server/invite/<token>#<ik>` → (origin, token, ik). */
    fun parseInvite(link: String): Triple<String, String, ByteArray>? {
        val uri = runCatching { java.net.URI(link.trim()) }.getOrNull() ?: return null
        val parts = uri.path?.split('/')?.filter { it.isNotEmpty() } ?: return null
        val token = parts.takeIf { it.size == 2 && it[0] == "invite" }?.get(1) ?: return null
        val ik = uri.rawFragment?.let { runCatching { Crypto.unb64url(it) }.getOrNull() }?.takeIf { it.size == 32 } ?: return null
        if (uri.scheme == null || uri.authority == null) return null
        return Triple("${uri.scheme}://${uri.authority}", token, ik)
    }

    suspend fun join(link: String, myName: String, myCity: String?): JoinResult {
        val (origin, token, ik) = parseInvite(link) ?: return JoinResult.Invalid
        if (origin.trimEnd('/') != secrets.serverUrl?.trimEnd('/')) return JoinResult.WrongServer
        val profile = Crypto.sealText(ik, ledgerJson.encodeToString(MemberProfile.serializer(), MemberProfile(myName, myCity)), GroupCrypto.INVITE_PROFILE_AAD)
        return try {
            val res = http.call("POST", "/v1/invites/join", buildJsonObject { put("token", token); put("profile", profile) })
            val preview = (res["preview"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            JoinResult.Pending(preview?.let { runCatching { Crypto.openText(ik, it, GroupCrypto.INVITE_PREVIEW_AAD) }.getOrNull() })
        } catch (e: ApiException) {
            if (e.status == 410) JoinResult.Invalid else throw e
        }
    }

    // --- Members --------------------------------------------------------------------------------

    suspend fun approveMember(groupId: String, epoch: Int, member: Member, role: String?) {
        val gk = groupKey(groupId, epoch) ?: error("no key")
        http.call("POST", "/v1/groups/$groupId/members/${member.vaultId}/approve", buildJsonObject {
            put("envelope", GroupCrypto.wrapKey(gk, epoch, member.boxPub, member.kemPub))
            if (role != null) put("role", role)
        })
    }

    suspend fun setRole(groupId: String, vaultId: String, role: String) {
        http.call("PUT", "/v1/groups/$groupId/members/$vaultId/role", buildJsonObject { put("role", role) })
    }

    /** Removing a member forces a rotation: a new key is sealed to everyone who stays and old records are re-encrypted. */
    suspend fun removeMember(groupId: String, vaultId: String) {
        http.call("DELETE", "/v1/groups/$groupId/members/$vaultId")
        rotateIfNeeded(groupId)
    }

    suspend fun leave(groupId: String) {
        http.call("DELETE", "/v1/groups/$groupId/members/${secrets.vaultId}")
    }

    /** Any manager's phone can finish a pending rotation; others get 403 and leave it to a manager. */
    suspend fun rotateIfNeeded(groupId: String) {
        val (summary, members) = detail(groupId) ?: return
        if (!summary.rotateRequired) return
        if (groupKey(groupId, summary.epoch) == null) return
        val newEpoch = summary.epoch + 1
        val gk = Crypto.randomBytes(32)
        val active = members.filter { it.status == "active" }
        val meta = summary.meta
        http.call("POST", "/v1/groups/$groupId/rotate", buildJsonObject {
            put("epoch", newEpoch)
            if (meta != null) put("meta", sealMeta(gk, groupId, meta))
            putJsonObject("envelopes") { active.forEach { put(it.vaultId, GroupCrypto.wrapKey(gk, newEpoch, it.boxPub, it.kemPub)) } }
        })
        // Stored only after the server accepted it. If we crash before this line, the next list() recovers the key from our own envelope.
        secrets.putSecret(keyName(groupId, newEpoch), gk)
        mirrorKey(groupId, newEpoch, gk)
        runCatching { setProfile(groupId, newEpoch, ownProfile()) }
        // Re-encrypt every record under the new key with a fresh timestamp so the server accepts them.
        val now = System.currentTimeMillis()
        dao.upsertGroup(dao.groupRows(groupId).map { it.copy(updatedAt = now, dirty = true) })
    }

    // --- Ledger --------------------------------------------------------------------------------

    fun contributions(groupId: String): Flow<List<Contribution>> = dao.observeGroup(groupId).map { rows ->
        rows.filter { it.entity == "contribution" }.mapNotNull {
            runCatching { ledgerJson.decodeFromString(Contribution.serializer(), it.json) }.getOrNull()
        }.filter { it.deletedAt == null }.sortedByDescending { it.at }
    }

    suspend fun addContribution(groupId: String, c: Contribution) {
        val now = System.currentTimeMillis()
        val r = c.copy(updatedAt = now)
        dao.upsertGroup(listOf(GroupRecordRow(groupId, "contribution", r.id, ledgerJson.encodeToString(Contribution.serializer(), r), now, null, true)))
    }

    /**
     * Pushes local changes and pulls the rest. If the server says the key was rotated meanwhile (409), refresh the
     * group once (finishing a pending rotation if we are a manager) and try again with the new epoch.
     */
    suspend fun syncGroup(summary: GroupSummary) {
        try {
            syncOnce(summary)
        } catch (e: ApiException) {
            if (e.status != 409 || (e.code != "epoch_mismatch" && e.code != "rotation_required")) throw e
            var fresh = detail(summary.id)?.first ?: return
            if (fresh.rotateRequired) {
                runCatching { rotateIfNeeded(summary.id) }
                fresh = detail(summary.id)?.first ?: return
            }
            if (!fresh.rotateRequired) syncOnce(fresh)
        }
    }

    private suspend fun syncOnce(summary: GroupSummary) {
        val gk = groupKey(summary.id, summary.epoch) ?: return
        val cursorKey = "gcursor.${summary.id}"
        var cursor = dao.kv(cursorKey)?.toLongOrNull() ?: 0L
        while (true) {
            val dirty = dao.dirtyGroup(summary.id)
            val res = http.call("POST", "/v1/groups/${summary.id}/sync", buildJsonObject {
                put("epoch", summary.epoch); put("cursor", cursor)
                putJsonArray("records") {
                    dirty.forEach { r ->
                        add(buildJsonObject {
                            put("entity", r.entity); put("id", r.id); put("updatedAt", r.updatedAt)
                            if (r.deletedAt == null) put("deletedAt", JsonNull) else put("deletedAt", r.deletedAt)
                            put("payload", GroupCrypto.sealRecord(gk, summary.id, summary.epoch, r.entity, r.id, r.json))
                        })
                    }
                }
            })
            dirty.forEach { dao.markGroupClean(summary.id, it.entity, it.id, it.updatedAt) }
            val rows = mutableListOf<GroupRecordRow>()
            for (item in res["records"]!!.jsonArray) {
                val o = item.jsonObject
                val entity = o["entity"]!!.jsonPrimitive.content; val id = o["id"]!!.jsonPrimitive.content
                val updatedAt = o["updatedAt"]!!.jsonPrimitive.long
                val local = dao.groupRecord(summary.id, entity, id)
                if (local != null && local.updatedAt >= updatedAt) continue
                // Records still sealed under an older epoch are skipped; their re-encrypted copy arrives with a newer sequence number.
                val json = GroupCrypto.openRecord(gk, summary.id, summary.epoch, entity, id, o["payload"]!!.jsonPrimitive.content) ?: continue
                rows += GroupRecordRow(summary.id, entity, id, json, updatedAt, (o["deletedAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long, false)
            }
            if (rows.isNotEmpty()) dao.upsertGroup(rows)
            cursor = res["cursor"]!!.jsonPrimitive.long
            dao.setKv(com.arthatis.wallet.data.KvRow(cursorKey, cursor.toString()))
            if (res["more"]?.jsonPrimitive?.boolean != true && dao.dirtyGroup(summary.id).isEmpty()) break
        }
    }

    // --- Activity + approvals -----------------------------------------------------------------

    suspend fun logActivity(groupId: String, epoch: Int, text: String) {
        val gk = groupKey(groupId, epoch) ?: return
        val body = """{"text":${JsonPrimitive(text)},"at":${System.currentTimeMillis()}}"""
        http.call("POST", "/v1/groups/$groupId/activity", buildJsonObject { put("payload", Crypto.sealText(gk, body, GroupCrypto.activityAad(groupId))) })
    }

    suspend fun activity(groupId: String, epoch: Int): List<Activity> =
        http.call("GET", "/v1/groups/$groupId/activity")["activity"]!!.jsonArray.mapNotNull {
            val o = decodeJson(groupId, epoch, it.jsonObject["payload"]!!.jsonPrimitive.content, GroupCrypto.activityAad(groupId)) ?: return@mapNotNull null
            Activity(o["text"]!!.jsonPrimitive.content, o["at"]!!.jsonPrimitive.long)
        }.reversed()

    suspend fun requestWithdrawal(groupId: String, epoch: Int, amountMinor: Long, note: String): String {
        val gk = groupKey(groupId, epoch) ?: error("no key")
        val payload = Crypto.sealText(gk, """{"amountMinor":$amountMinor,"note":${JsonPrimitive(note)}}""", GroupCrypto.approvalAad(groupId))
        return http.call("POST", "/v1/groups/$groupId/approvals", buildJsonObject {
            put("kind", "withdrawal"); put("payload", payload); put("required", APPROVALS_REQUIRED)
        })["id"]!!.jsonPrimitive.content
    }

    suspend fun approvals(groupId: String, epoch: Int): List<Approval> =
        http.call("GET", "/v1/groups/$groupId/approvals")["approvals"]!!.jsonArray.mapNotNull {
            val o = it.jsonObject
            val p = decodeJson(groupId, epoch, o["payload"]!!.jsonPrimitive.content, GroupCrypto.approvalAad(groupId)) ?: return@mapNotNull null
            Approval(
                o["id"]!!.jsonPrimitive.content, o["status"]!!.jsonPrimitive.content, o["required"]!!.jsonPrimitive.int,
                o["approvals"]!!.jsonPrimitive.int, o["createdBy"]!!.jsonPrimitive.content,
                p["amountMinor"]!!.jsonPrimitive.long, p["note"]!!.jsonPrimitive.content,
            )
        }

    suspend fun vote(groupId: String, approvalId: String, approve: Boolean): String =
        http.call("POST", "/v1/groups/$groupId/approvals/$approvalId/vote", buildJsonObject { put("vote", if (approve) "approve" else "reject") })["status"]!!.jsonPrimitive.content

    /**
     * Turns approved withdrawal requests of mine into ledger entries. The id is derived from the approval, and an entry
     * that already exists is left alone, so reopening the screen never rewrites (or re-syncs) it.
     */
    suspend fun settleApproved(groupId: String, epoch: Int, mine: String, items: List<Approval>) {
        items.filter { it.status == "approved" && it.createdBy == mine }.forEach {
            val id = "w_${it.id}"
            if (dao.groupRecord(groupId, "contribution", id) != null) return@forEach
            addContribution(groupId, Contribution(id, mine, "withdrawal", it.amountMinor, it.note, System.currentTimeMillis(), 0))
        }
    }

    fun newContributionId() = UUID.randomUUID().toString().replace("-", "").take(20)
}
