package com.arthatis.wallet.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom
import java.util.Base64

/** Implements docs/protocol.md. All blobs: `0x01 ‖ fields…`. */
object Crypto {
    private const val VERSION: Byte = 1
    private const val KEM_CT = 1088
    private val rng = SecureRandom()
    private val envelopeInfo = "arthatis-envelope-v1".toByteArray()

    fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    // --- XChaCha20-Poly1305 ---------------------------------------------------------------

    private fun rotl(v: Int, n: Int) = (v shl n) or (v ushr (32 - n))

    private fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) s[12 + i] = le32(nonce16, i * 4)
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
            s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
            s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
            s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        val out = ByteArray(32)
        for (i in 0 until 4) putLe32(out, i * 4, s[i])
        for (i in 0 until 4) putLe32(out, 16 + i * 4, s[12 + i])
        return out
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce24: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val subkey = hChaCha20(key, nonce24.copyOfRange(0, 16))
        val nonce12 = ByteArray(12).also { System.arraycopy(nonce24, 16, it, 4, 8) }
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(subkey), 128, nonce12, aad))
        val out = ByteArray(cipher.getOutputSize(data.size))
        var n = cipher.processBytes(data, 0, data.size, out, 0)
        n += cipher.doFinal(out, n)
        return out.copyOf(n)
    }

    /** `0x01 ‖ nonce(24) ‖ ciphertext+tag`. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32) { "key" }
        val nonce = randomBytes(24)
        return byteArrayOf(VERSION) + nonce + aead(true, key, nonce, plaintext, aad)
    }

    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(blob.size >= 1 + 24 + 16 && blob[0] == VERSION) { "bad blob" }
        val nonce = blob.copyOfRange(1, 25)
        return aead(false, key, nonce, blob.copyOfRange(25, blob.size), aad)
    }

    fun sealText(key: ByteArray, text: String, aad: String): String =
        b64(seal(key, text.toByteArray(), aad.toByteArray()))

    fun openText(key: ByteArray, blob: String, aad: String): String =
        String(open(key, unb64(blob), aad.toByteArray()))

    // --- Device identity -------------------------------------------------------------------

    class DeviceKeys(
        val boxPriv: ByteArray, val boxPub: ByteArray,
        val kemPriv: ByteArray, val kemPub: ByteArray,
        val signPriv: ByteArray, val signPub: ByteArray,
    ) {
        /** Serialised for storage under a Keystore-wrapped key. */
        fun encode(): ByteArray = listOf(boxPriv, boxPub, kemPriv, kemPub, signPriv, signPub).fold(ByteArray(0)) { acc, part ->
            acc + java.nio.ByteBuffer.allocate(4).putInt(part.size).array() + part
        }

        companion object {
            fun decode(bytes: ByteArray): DeviceKeys {
                val parts = mutableListOf<ByteArray>()
                var i = 0
                while (i < bytes.size) {
                    val len = java.nio.ByteBuffer.wrap(bytes, i, 4).int
                    parts += bytes.copyOfRange(i + 4, i + 4 + len)
                    i += 4 + len
                }
                return DeviceKeys(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
            }
        }
    }

    fun newDeviceKeys(): DeviceKeys {
        val x = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(rng)) }.generateKeyPair()
        val kem = MLKEMKeyPairGenerator().apply { init(MLKEMKeyGenerationParameters(rng, MLKEMParameters.ml_kem_768)) }.generateKeyPair()
        val ed = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(rng)) }.generateKeyPair()
        return DeviceKeys(
            (x.private as X25519PrivateKeyParameters).encoded, (x.public as X25519PublicKeyParameters).encoded,
            (kem.private as MLKEMPrivateKeyParameters).encoded, (kem.public as MLKEMPublicKeyParameters).encoded,
            (ed.private as Ed25519PrivateKeyParameters).encoded, (ed.public as Ed25519PublicKeyParameters).encoded,
        )
    }

    // --- Hybrid envelope: X25519 + ML-KEM-768 ----------------------------------------------

    private fun deriveEnvelopeKey(x25519Secret: ByteArray, kemSecret: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(x25519Secret + kemSecret, null, envelopeInfo))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
    }

    /** Seals [secret] to a recipient's public keys. */
    fun sealTo(boxPub: ByteArray, kemPub: ByteArray, secret: ByteArray): ByteArray {
        val eph = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(rng)) }.generateKeyPair()
        val agreement = X25519Agreement().apply { init(eph.private) }
        val shared = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(boxPub, 0), shared, 0)
        val encapsulated = MLKEMGenerator(rng).generateEncapsulated(MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, kemPub))
        val key = deriveEnvelopeKey(shared, encapsulated.secret)
        val nonce = randomBytes(24)
        val ephPub = (eph.public as X25519PublicKeyParameters).encoded
        return byteArrayOf(VERSION) + ephPub + encapsulated.encapsulation + nonce + aead(true, key, nonce, secret, ByteArray(0))
    }

    fun openFrom(keys: DeviceKeys, blob: ByteArray): ByteArray {
        require(blob.size > 1 + 32 + KEM_CT + 24 + 16 && blob[0] == VERSION) { "bad envelope" }
        val ephPub = blob.copyOfRange(1, 33)
        val kemCt = blob.copyOfRange(33, 33 + KEM_CT)
        val nonce = blob.copyOfRange(33 + KEM_CT, 33 + KEM_CT + 24)
        val body = blob.copyOfRange(33 + KEM_CT + 24, blob.size)
        val agreement = X25519Agreement().apply { init(X25519PrivateKeyParameters(keys.boxPriv, 0)) }
        val shared = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(ephPub, 0), shared, 0)
        val kemSecret = MLKEMExtractor(MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, keys.kemPriv)).extractSecret(kemCt)
        return aead(false, deriveEnvelopeKey(shared, kemSecret), nonce, body, ByteArray(0))
    }

    /** `webPub` = `x25519Pub(32) ‖ mlKemPub(1184)`. */
    fun splitWebPub(webPub: ByteArray): Pair<ByteArray, ByteArray> =
        webPub.copyOfRange(0, 32) to webPub.copyOfRange(32, webPub.size)

    // --- Recovery key ----------------------------------------------------------------------

    private const val RK_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 20 random bytes (160 bits) shown as 8 base32 groups: `XXXX-XXXX-…`. */
    fun newRecoveryKey(): ByteArray = randomBytes(20)

    fun formatRecoveryKey(bytes: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0; var value = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(RK_ALPHABET[(value shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(RK_ALPHABET[(value shl (5 - bits)) and 31])
        return sb.toString().chunked(4).joinToString("-")
    }

    fun parseRecoveryKey(text: String): ByteArray? {
        val clean = text.uppercase().filter { it != '-' && !it.isWhitespace() }
        if (clean.any { it !in RK_ALPHABET }) return null
        val out = java.io.ByteArrayOutputStream()
        var bits = 0; var value = 0
        for (c in clean) {
            value = (value shl 5) or RK_ALPHABET.indexOf(c); bits += 5
            if (bits >= 8) { out.write((value shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray().takeIf { it.size == 20 }
    }

    private fun argon(secret: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt).withMemoryAsKB(memoryKiB).withIterations(iterations).withParallelism(1).build()
        return ByteArray(32).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(secret, it) }
    }

    /** `0x01 ‖ salt(16) ‖ seal(Argon2id(recoveryKey, salt), data)`. [memoryKiB] is 64 MiB in production. */
    fun wrapWithRecoveryKey(recoveryKey: ByteArray, data: ByteArray, memoryKiB: Int = 65536): ByteArray {
        val salt = randomBytes(16)
        return byteArrayOf(VERSION) + salt + seal(argon(recoveryKey, salt, memoryKiB, 3), data)
    }

    fun unwrapWithRecoveryKey(recoveryKey: ByteArray, blob: ByteArray, memoryKiB: Int = 65536): ByteArray {
        require(blob.isNotEmpty() && blob[0] == VERSION) { "bad blob" }
        val salt = blob.copyOfRange(1, 17)
        return open(argon(recoveryKey, salt, memoryKiB, 3), blob.copyOfRange(17, blob.size))
    }
}
