package com.arthatis.wallet.core

import com.arthatis.wallet.core.crypto.Crypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CryptoTest {
    @Test fun sealOpenRoundTripAndAadBinding() {
        val key = Crypto.randomBytes(32)
        val blob = Crypto.seal(key, "halo".toByteArray(), "transaction|t1".toByteArray())
        assertEquals("halo", String(Crypto.open(key, blob, "transaction|t1".toByteArray())))
        assertFailsWith<Exception> { Crypto.open(key, blob, "transaction|t2".toByteArray()) }
        assertFailsWith<Exception> { Crypto.open(Crypto.randomBytes(32), blob, "transaction|t1".toByteArray()) }
    }

    @Test fun ciphertextIsRandomized() {
        val key = Crypto.randomBytes(32)
        assertEquals(false, Crypto.seal(key, byteArrayOf(1)).contentEquals(Crypto.seal(key, byteArrayOf(1))))
    }

    /** RFC-style check: XChaCha20 subkey derivation from draft-irtf-cfrg-xchacha, Appendix A.2.1. */
    @Test fun xchachaMatchesReferenceVector() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(0, 0, 0, 9, 0, 0, 0, 0x4a, 0, 0, 0, 0, 0x31, 0x41, 0x59, 0x27)
        val sub = Crypto.javaClass.getDeclaredMethod("hChaCha20", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(Crypto, key, nonce) as ByteArray
        assertEquals("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc", sub.joinToString("") { "%02x".format(it) })
    }

    @Test fun hybridEnvelopeOpensOnlyForRecipient() {
        val alice = Crypto.newDeviceKeys()
        val bob = Crypto.newDeviceKeys()
        val vk = Crypto.randomBytes(32)
        val envelope = Crypto.sealTo(alice.boxPub, alice.kemPub, vk)
        assertContentEquals(vk, Crypto.openFrom(alice, envelope))
        assertFailsWith<Exception> { Crypto.openFrom(bob, envelope) }
        assertEquals(32, alice.boxPub.size)
        assertEquals(1184, alice.kemPub.size)
    }

    @Test fun deviceKeysSerialize() {
        val keys = Crypto.newDeviceKeys()
        val again = Crypto.DeviceKeys.decode(keys.encode())
        assertContentEquals(keys.kemPriv, again.kemPriv)
        val vk = Crypto.randomBytes(32)
        assertContentEquals(vk, Crypto.openFrom(again, Crypto.sealTo(keys.boxPub, keys.kemPub, vk)))
    }

    @Test fun recoveryKeyWrapsVaultKey() {
        val rk = Crypto.newRecoveryKey()
        val text = Crypto.formatRecoveryKey(rk)
        assertEquals(true, Regex("^([A-Z2-7]{4}-){7}[A-Z2-7]{4}$").matches(text))
        val parsed = assertNotNull(Crypto.parseRecoveryKey(text.lowercase()))
        assertContentEquals(rk, parsed)
        val vk = Crypto.randomBytes(32)
        val blob = Crypto.wrapWithRecoveryKey(rk, vk, memoryKiB = 1024)
        assertContentEquals(vk, Crypto.unwrapWithRecoveryKey(parsed, blob, memoryKiB = 1024))
        assertFailsWith<Exception> { Crypto.unwrapWithRecoveryKey(Crypto.newRecoveryKey(), blob, memoryKiB = 1024) }
    }
}
