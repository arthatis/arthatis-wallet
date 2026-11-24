package com.arthatis.wallet.core

import com.arthatis.wallet.core.crypto.Crypto
import com.arthatis.wallet.core.crypto.GroupCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GroupCryptoTest {
    private val gk = Crypto.randomBytes(32)

    @Test fun keyEnvelopeCarriesEpochAndKeyToTheRightMemberOnly() {
        val bayu = Crypto.newDeviceKeys(); val nadia = Crypto.newDeviceKeys()
        val env = GroupCrypto.wrapKey(gk, 3, bayu.boxPub, bayu.kemPub)
        val learned = assertNotNull(GroupCrypto.unwrapKey(bayu, env))
        assertEquals(3, learned.epoch)
        assertContentEquals(gk, learned.key)
        assertNull(GroupCrypto.unwrapKey(nadia, env))
        assertNull(GroupCrypto.unwrapKey(bayu, "bm90LWFuLWVudmVsb3Bl"))
    }

    @Test fun recordsAreBoundToGroupEpochEntityAndId() {
        val blob = GroupCrypto.sealRecord(gk, "g1", 2, "contribution", "c1", """{"a":1}""")
        assertEquals("""{"a":1}""", GroupCrypto.openRecord(gk, "g1", 2, "contribution", "c1", blob))
        assertNull(GroupCrypto.openRecord(gk, "g2", 2, "contribution", "c1", blob))
        assertNull(GroupCrypto.openRecord(gk, "g1", 3, "contribution", "c1", blob))
        assertNull(GroupCrypto.openRecord(gk, "g1", 2, "contribution", "c2", blob))
        assertNull(GroupCrypto.openRecord(Crypto.randomBytes(32), "g1", 2, "contribution", "c1", blob))
    }

    @Test fun olderItemsStayReadableAfterRotationWhenOldKeysAreKept() {
        val k1 = Crypto.randomBytes(32); val k2 = Crypto.randomBytes(32)
        val old = Crypto.sealText(k1, "profil lama", GroupCrypto.profileAad("g1"))
        assertEquals("profil lama", GroupCrypto.openWithAnyKey(mapOf(1 to k1, 2 to k2), old, GroupCrypto.profileAad("g1")))
        // a member who joined after the rotation only has epoch 2 and cannot read it
        assertNull(GroupCrypto.openWithAnyKey(mapOf(2 to k2), old, GroupCrypto.profileAad("g1")))
        // the purpose is part of the AAD: a profile cannot be replayed as an activity
        assertNull(GroupCrypto.openWithAnyKey(mapOf(1 to k1), old, GroupCrypto.activityAad("g1")))
    }

    @Test fun joinProfileOpensWithAnyIssuedInviteKey() {
        val a = Crypto.randomBytes(32); val b = Crypto.randomBytes(32)
        val sealed = Crypto.sealText(b, """{"name":"Bayu"}""", GroupCrypto.INVITE_PROFILE_AAD)
        assertEquals("""{"name":"Bayu"}""", GroupCrypto.openJoinProfile(listOf(a, b), sealed))
        assertNull(GroupCrypto.openJoinProfile(listOf(a), sealed))
    }
}
