package com.arthatis.wallet.core.crypto

import java.nio.ByteBuffer

/**
 * Wire format of shared (Bareng) wallets, shared by the phone and the web (docs/protocol.md).
 * Pure functions: no Android, no I/O, so the whole format is testable and cross-checked against the web.
 */
object GroupCrypto {
    private fun epochBytes(epoch: Int) = ByteBuffer.allocate(4).putInt(epoch).array()

    // --- Associated data. Anything not listed here must not be reused for another purpose. ---------
    fun recordAad(groupId: String, epoch: Int, entity: String, id: String) = "$groupId|$epoch|$entity|$id"
    fun metaAad(groupId: String) = "$groupId|meta"
    fun profileAad(groupId: String) = "$groupId|profile"
    fun activityAad(groupId: String) = "$groupId|activity"
    fun approvalAad(groupId: String) = "$groupId|approval"
    const val INVITE_PREVIEW_AAD = "invite"
    const val INVITE_PROFILE_AAD = "invite-profile"

    /** Seals `epoch(4, big-endian) ‖ groupKey(32)` to a member's public keys. Returns base64. */
    fun wrapKey(groupKey: ByteArray, epoch: Int, boxPub: ByteArray, kemPub: ByteArray): String {
        require(groupKey.size == 32) { "group key" }
        return Crypto.b64(Crypto.sealTo(boxPub, kemPub, epochBytes(epoch) + groupKey))
    }

    class Learned(val epoch: Int, val key: ByteArray)

    /** Opens an envelope addressed to [keys]. Null when it is not ours or is malformed. */
    fun unwrapKey(keys: Crypto.DeviceKeys, envelopeB64: String): Learned? {
        val plain = runCatching { Crypto.openFrom(keys, Crypto.unb64(envelopeB64)) }.getOrNull() ?: return null
        if (plain.size != 36) return null
        return Learned(ByteBuffer.wrap(plain, 0, 4).int, plain.copyOfRange(4, 36))
    }

    fun sealRecord(groupKey: ByteArray, groupId: String, epoch: Int, entity: String, id: String, json: String): String =
        Crypto.sealText(groupKey, json, recordAad(groupId, epoch, entity, id))

    fun openRecord(groupKey: ByteArray, groupId: String, epoch: Int, entity: String, id: String, payload: String): String? =
        runCatching { Crypto.openText(groupKey, payload, recordAad(groupId, epoch, entity, id)) }.getOrNull()

    /**
     * Meta, profiles, activity and approvals carry no epoch in their AAD, so after a key rotation older items are still
     * readable by anyone who kept the older keys. Tries the newest key first.
     */
    fun openWithAnyKey(keysByEpoch: Map<Int, ByteArray>, payload: String, aad: String): String? {
        for (epoch in keysByEpoch.keys.sortedDescending()) {
            runCatching { Crypto.openText(keysByEpoch.getValue(epoch), payload, aad) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Pending join requests are sealed with the invite key; any key the approver issued may have been used. */
    fun openJoinProfile(inviteKeys: List<ByteArray>, payload: String): String? =
        inviteKeys.firstNotNullOfOrNull { runCatching { Crypto.openText(it, payload, INVITE_PROFILE_AAD) }.getOrNull() }
}
