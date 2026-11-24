package com.arthatis.wallet.bareng

import com.arthatis.wallet.core.crypto.Crypto
import com.arthatis.wallet.data.GroupRecordRow
import com.arthatis.wallet.data.KvRow
import com.arthatis.wallet.data.RecordRow
import com.arthatis.wallet.data.SecretStore
import com.arthatis.wallet.data.WalletDao
import com.arthatis.wallet.net.ApiException
import com.arthatis.wallet.net.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** In-memory stand-ins for the Android storage the real [Bareng] class talks to. */
private class FakeSecrets : SecretStore {
    val map = HashMap<String, ByteArray>()
    override fun secret(name: String) = map[name]
    override fun putSecret(name: String, value: ByteArray?) { if (value == null) map.remove(name) else map[name] = value }
    override var deviceKeys: Crypto.DeviceKeys? = null
    override var vaultId: String? = null
    override var serverUrl: String? = null
}

private class FakeDao : WalletDao {
    val group = HashMap<Triple<String, String, String>, GroupRecordRow>()
    val records = HashMap<Pair<String, String>, RecordRow>()
    val kv = HashMap<String, String>()
    override fun observeRecords(): Flow<List<RecordRow>> = MutableStateFlow(records.values.toList())
    override suspend fun records() = records.values.toList()
    override suspend fun dirtyRecords(limit: Int) = records.values.filter { it.dirty }.take(limit)
    override suspend fun record(entity: String, id: String) = records[entity to id]
    override suspend fun upsert(rows: List<RecordRow>) { rows.forEach { records[it.entity to it.id] = it } }
    override suspend fun markClean(entity: String, id: String, updatedAt: Long) {}
    override suspend fun markAllDirty() {}
    override suspend fun clearRecords() { records.clear() }
    override fun observeGroup(groupId: String): Flow<List<GroupRecordRow>> = MutableStateFlow(group.values.filter { it.groupId == groupId })
    override suspend fun groupRows(groupId: String) = group.values.filter { it.groupId == groupId }
    override suspend fun dirtyGroup(groupId: String) = group.values.filter { it.groupId == groupId && it.dirty }
    override suspend fun groupRecord(groupId: String, entity: String, id: String) = group[Triple(groupId, entity, id)]
    override suspend fun upsertGroup(rows: List<GroupRecordRow>) { rows.forEach { group[Triple(it.groupId, it.entity, it.id)] = it } }
    override suspend fun markGroupClean(groupId: String, entity: String, id: String, updatedAt: Long) {
        group[Triple(groupId, entity, id)]?.let { if (it.updatedAt == updatedAt) group[Triple(groupId, entity, id)] = it.copy(dirty = false) }
    }
    override suspend fun clearGroups() { group.clear() }
    override suspend fun kv(k: String) = kv[k]
    override suspend fun setKv(row: KvRow) { kv[row.k] = row.v }
    override suspend fun clearKv() { kv.clear() }
}

/** One simulated phone: its own keys, storage and token, talking to the real backend. */
private class Phone(val base: String, val name: String) {
    val secrets = FakeSecrets()
    val dao = FakeDao()
    var token: String? = null
    val http = Http({ base }, { token })
    val bareng = Bareng(secrets, dao, http) { MemberProfile(name) }
    val vaultId get() = secrets.vaultId!!

    fun register(): Phone = runBlocking {
        val keys = Crypto.newDeviceKeys()
        secrets.deviceKeys = keys
        val res = http.call("POST", "/v1/devices", buildJsonObject {
            put("label", name); put("signPub", Crypto.b64(keys.signPub)); put("boxPub", Crypto.b64(keys.boxPub)); put("kemPub", Crypto.b64(keys.kemPub))
        }, auth = false)
        token = res["token"]!!.jsonPrimitive.content
        secrets.vaultId = res["vaultId"]!!.jsonPrimitive.content
        secrets.serverUrl = base
        this@Phone
    }

    suspend fun summary(gid: String) = assertNotNull(bareng.detail(gid)).first
    suspend fun sync(gid: String) = bareng.syncGroup(summary(gid))
    suspend fun net(gid: String) = bareng.contributions(gid).first().sumOf { if (it.kind == "withdrawal") -it.amountMinor else it.amountMinor }
}

/**
 * The shared-wallet flow end to end with the real [Bareng] class and the real backend (a Node process).
 * Skipped automatically when Node or the backend dependencies are not available.
 */
/** Rupiah in minor units (IDR keeps 4 decimals). */
private fun rp(n: Long) = n * 10_000L

class BarengFlowTest {
    companion object {
        private var server: Process? = null
        private var base: String? = null

        @JvmStatic @BeforeClass fun start() {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "server/package.json").exists() }
            val ok = root != null && File(root, "server/node_modules").exists() && runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
            assumeTrue("needs node + server/node_modules", ok)
            val port = ServerSocket(0).use { it.localPort }
            val data = Files.createTempDirectory("arthatis-test").toFile()
            val pb = ProcessBuilder("node", "--experimental-strip-types", "--disable-warning=ExperimentalWarning", "src/index.ts")
                .directory(File(root, "server")).redirectErrorStream(true)
            pb.environment().apply { put("PORT", port.toString()); put("DATA_DIR", data.path) }
            server = pb.start()
            base = "http://localhost:$port"
            repeat(100) {
                if (runCatching { URL("$base/health").readText().contains("ok") }.getOrDefault(false)) return
                Thread.sleep(100)
            }
            error("backend did not start")
        }

        @JvmStatic @AfterClass fun stop() { server?.destroy() }
    }

    @Test fun sharedWalletFromCreationToRotation() = runBlocking {
        val url = base!!
        val sinta = Phone(url, "Sinta").register()
        val bayu = Phone(url, "Bayu").register()
        val nadia = Phone(url, "Nadia").register()
        val dewi = Phone(url, "Dewi").register()

        // 1. Create. The server only ever sees ciphertext; the owner reads the name back.
        val gid = sinta.bareng.create(GroupMeta("Liburan Bali", "tabungan", "IDR", rp(12_000_000)), "Sinta", "Jakarta")
        val listed = sinta.bareng.list().single()
        assertEquals("Liburan Bali", listed.meta?.name)
        assertEquals(1, listed.epoch)

        // 2-3. Invite and join. The joiner sees the wallet's name from the link, nothing else.
        val link = sinta.bareng.invite(gid, 1, "Liburan Bali", "member", url).link
        assertTrue(link.startsWith("$url/invite/"))
        assertEquals("Liburan Bali", (bayu.bareng.join(link, "Bayu", null) as JoinResult.Pending).groupName)
        assertEquals(JoinResult.Invalid, bayu.bareng.join(link.substringBefore('#') + "#AAAA", "Bayu", null))

        // 4. The approver can read who is asking (profile sealed with the invite key the approver issued).
        val pending = sinta.bareng.detail(gid)!!.second.single { it.status == "pending" }
        assertEquals("Bayu", pending.name)

        // 5. Approve. The member learns the group key on first load and rewrites their profile under it.
        sinta.bareng.approveMember(gid, 1, pending, null)
        assertEquals("active", bayu.bareng.list().single().status)
        val names = sinta.bareng.detail(gid)!!.second.filter { it.status == "active" }.associate { it.vaultId to it.name }
        assertEquals("Sinta", names[sinta.vaultId]); assertEquals("Bayu", names[bayu.vaultId])

        // A second manager (admin) for the 2-of-3 approval rule.
        val adminLink = sinta.bareng.invite(gid, 1, "Liburan Bali", "admin", url).link
        nadia.bareng.join(adminLink, "Nadia", null)
        sinta.bareng.approveMember(gid, 1, sinta.bareng.detail(gid)!!.second.single { it.name == "Nadia" && it.status == "pending" }, null)
        nadia.bareng.list()

        // 6. Contributions sync between members.
        bayu.bareng.addContribution(gid, Contribution(bayu.bareng.newContributionId(), bayu.vaultId, "contribution", rp(3_000_000), "Tabungan awal", 1, 0))
        bayu.sync(gid); sinta.sync(gid)
        assertEquals(rp(3_000_000), sinta.net(gid))

        // 7. A withdrawal above the threshold needs 2 managers; a plain member cannot vote.
        val approvalId = bayu.bareng.requestWithdrawal(gid, 1, rp(2_000_000), "Tiket pesawat")
        val seen = sinta.bareng.approvals(gid, 1).single()
        assertEquals(rp(2_000_000), seen.amountMinor); assertEquals("pending", seen.status); assertEquals(2, seen.required)
        assertEquals(403, assertFailsWith<ApiException> { bayu.bareng.vote(gid, approvalId, true) }.status)
        assertEquals("pending", sinta.bareng.vote(gid, approvalId, true))
        assertEquals("approved", nadia.bareng.vote(gid, approvalId, true))
        bayu.bareng.settleApproved(gid, 1, bayu.vaultId, bayu.bareng.approvals(gid, 1))
        bayu.bareng.settleApproved(gid, 1, bayu.vaultId, bayu.bareng.approvals(gid, 1)) // reopening must not duplicate
        assertEquals(1, bayu.dao.groupRows(gid).count { it.id.startsWith("w_") })
        bayu.sync(gid); sinta.sync(gid)
        assertEquals(rp(1_000_000), sinta.net(gid))

        // 8. Removing a member rotates the key; old records are re-encrypted; the removed member is locked out.
        val oldKey = sinta.secrets.secret("gk.$gid.1")!!
        sinta.bareng.removeMember(gid, nadia.vaultId)
        assertEquals(2, sinta.summary(gid).epoch)
        assertTrue(!sinta.summary(gid).rotateRequired)
        assertTrue(!oldKey.contentEquals(sinta.secrets.secret("gk.$gid.2")!!))
        assertEquals(404, assertFailsWith<ApiException> { nadia.bareng.detail(gid) }.status)
        sinta.sync(gid)

        // Bayu still holds the epoch-1 summary: the server answers 409 and the client refreshes once and succeeds.
        val stale = GroupSummary(gid, "member", "active", 1, false, 2, null)
        bayu.bareng.list() // learns the epoch-2 key from its own envelope
        bayu.bareng.syncGroup(stale)
        assertEquals(rp(1_000_000), bayu.net(gid))
        assertContentEquals(sinta.secrets.secret("gk.$gid.2"), bayu.secrets.secret("gk.$gid.2"))

        // Names and activity written before the rotation are still readable by members who kept the old key.
        sinta.bareng.logActivity(gid, 2, "Sinta menambah Rp1.000.000")
        assertEquals("Sinta menambah Rp1.000.000", bayu.bareng.activity(gid, 2).first().text)
        val after = bayu.bareng.detail(gid)!!.second.filter { it.status == "active" }.associate { it.vaultId to it.name }
        assertEquals("Sinta", after[sinta.vaultId]); assertEquals("Bayu", after[bayu.vaultId])

        // 9. Someone who joins after the rotation reads the re-encrypted history and the current names.
        dewi.bareng.join(sinta.bareng.invite(gid, 2, "Liburan Bali", "viewer", url).link, "Dewi", null)
        sinta.bareng.approveMember(gid, 2, sinta.bareng.detail(gid)!!.second.single { it.status == "pending" }, null)
        val dewiSummary = dewi.bareng.list().single()
        assertEquals("active", dewiSummary.status); assertEquals("Liburan Bali", dewiSummary.meta?.name)
        dewi.bareng.syncGroup(dewiSummary)
        assertEquals(rp(1_000_000), dewi.net(gid))
        assertEquals("Sinta", dewi.bareng.detail(gid)!!.second.single { it.vaultId == sinta.vaultId }.name)
    }

    @Test fun parsesOnlyWellFormedInviteLinks() {
        val b = Bareng(FakeSecrets(), FakeDao(), Http({ null }, { null }))
        val ik = Crypto.b64url(Crypto.randomBytes(32))
        assertEquals(Triple("https://wallet.example.com", "tok123", ik), b.parseInvite("https://wallet.example.com/invite/tok123#$ik")!!.let { Triple(it.first, it.second, Crypto.b64url(it.third)) })
        assertEquals(null, b.parseInvite("https://wallet.example.com/invite/tok123"))            // no key
        assertEquals(null, b.parseInvite("https://wallet.example.com/invite/tok123#AAAA"))       // key too short
        assertEquals(null, b.parseInvite("https://wallet.example.com/join/tok123#$ik"))          // wrong path
        assertEquals(null, b.parseInvite("not a url"))
    }
}
