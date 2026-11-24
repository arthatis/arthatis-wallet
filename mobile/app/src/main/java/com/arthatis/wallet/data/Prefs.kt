package com.arthatis.wallet.data

import android.content.Context
import android.util.Base64
import com.arthatis.wallet.core.crypto.Crypto
import com.arthatis.wallet.crypto.KeyStoreBox

enum class ServerMode { None, Hosted, Private }

const val HOSTED_URL = "https://wallet.arthatis.com"

/** The few things the sync/Bareng code needs from local storage. Lets that code run in JVM tests without Android. */
interface SecretStore {
    fun secret(name: String): ByteArray?
    fun putSecret(name: String, value: ByteArray?)
    val deviceKeys: Crypto.DeviceKeys?
    val vaultId: String?
    val serverUrl: String?
}

/** Local, non-synced device settings plus Keystore-wrapped secrets. */
class Prefs(context: Context) : SecretStore {
    private val sp = context.getSharedPreferences("arthatis", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** system | light | dark */
    var theme: String
        get() = sp.getString("theme", "system")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** id | en */
    var language: String
        get() = sp.getString("lang", "id")!!
        set(v) = sp.edit().putString("lang", v).apply()

    var serverMode: ServerMode
        get() = ServerMode.valueOf(sp.getString("serverMode", "None")!!)
        set(v) = sp.edit().putString("serverMode", v.name).apply()

    var privateUrl: String
        get() = sp.getString("privateUrl", "")!!
        set(v) = sp.edit().putString("privateUrl", v.trim().trimEnd('/')).apply()

    override val serverUrl: String?
        get() = when (serverMode) {
            ServerMode.None -> null
            ServerMode.Hosted -> HOSTED_URL
            ServerMode.Private -> privateUrl.takeIf { it.isNotBlank() }
        }

    override var vaultId: String?
        get() = sp.getString("vaultId", null)
        set(v) = sp.edit().putString("vaultId", v).apply()

    var syncCursor: Long
        get() = sp.getLong("cursor", 0)
        set(v) = sp.edit().putLong("cursor", v).apply()

    var recoveryCreated: Boolean
        get() = sp.getBoolean("recoveryCreated", false)
        set(v) = sp.edit().putBoolean("recoveryCreated", v).apply()

    var lastExportAt: Long
        get() = sp.getLong("lastExportAt", 0)
        set(v) = sp.edit().putLong("lastExportAt", v).apply()

    /** Last good `USD/IDR`-style rates from the API, stored as `USD=16450;SGD=12180`. */
    var cachedRates: String
        get() = sp.getString("rates", "")!!
        set(v) = sp.edit().putString("rates", v).apply()

    var ratesFetchedAt: Long
        get() = sp.getLong("ratesAt", 0)
        set(v) = sp.edit().putLong("ratesAt", v).apply()

    /** none | anthropic | openai | deepseek — user's own API key fallback. */
    var aiProvider: String
        get() = sp.getString("aiProvider", "none")!!
        set(v) = sp.edit().putString("aiProvider", v).apply()

    /** Model name for the user's own cloud key; blank = provider default. */
    var aiModel: String
        get() = sp.getString("aiModel", "")!!
        set(v) = sp.edit().putString("aiModel", v.trim()).apply()

    var aiUseServer: Boolean
        get() = sp.getBoolean("aiUseServer", false)
        set(v) = sp.edit().putBoolean("aiUseServer", v).apply()

    var aiUseCloudKey: Boolean
        get() = sp.getBoolean("aiUseCloudKey", false)
        set(v) = sp.edit().putBoolean("aiUseCloudKey", v).apply()

    // --- Secrets: wrapped by Android Keystore ------------------------------------------------

    override fun putSecret(name: String, value: ByteArray?) {
        val edit = sp.edit()
        if (value == null) edit.remove("sec.$name")
        else edit.putString("sec.$name", Base64.encodeToString(KeyStoreBox.encrypt(value), Base64.NO_WRAP))
        edit.apply()
    }

    override fun secret(name: String): ByteArray? =
        sp.getString("sec.$name", null)?.let { runCatching { KeyStoreBox.decrypt(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }

    var token: String?
        get() = secret("token")?.let { String(it) }
        set(v) = putSecret("token", v?.toByteArray())

    var aiKey: String?
        get() = secret("aiKey")?.let { String(it) }
        set(v) = putSecret("aiKey", v?.toByteArray())

    var vaultKey: ByteArray?
        get() = secret("vaultKey")
        set(v) = putSecret("vaultKey", v)

    override var deviceKeys: Crypto.DeviceKeys?
        get() = secret("deviceKeys")?.let { runCatching { Crypto.DeviceKeys.decode(it) }.getOrNull() }
        set(v) = putSecret("deviceKeys", v?.encode())

    // --- Email (IMAP) auto-tracking: settings stay on this phone, never synced ------------------

    var emailEnabled: Boolean
        get() = sp.getBoolean("email.enabled", false)
        set(v) = sp.edit().putBoolean("email.enabled", v).apply()
    var emailHost: String
        get() = sp.getString("email.host", "imap.gmail.com")!!
        set(v) = sp.edit().putString("email.host", v.trim()).apply()
    var emailPort: Int
        get() = sp.getInt("email.port", 993)
        set(v) = sp.edit().putInt("email.port", v).apply()
    var emailUser: String
        get() = sp.getString("email.user", "")!!
        set(v) = sp.edit().putString("email.user", v.trim()).apply()
    /** App password, wrapped by the Keystore like every other secret. */
    var emailPassword: String?
        get() = secret("emailPassword")?.let { String(it) }
        set(v) = putSecret("emailPassword", v?.toByteArray())
    /** Watched sender fragments, one per line. */
    var emailSenders: List<String>
        get() = sp.getString("email.senders", null)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: com.arthatis.wallet.core.defaultEmailSenders
        set(v) = sp.edit().putString("email.senders", v.joinToString("\n")).apply()
    /** IMAP UIDVALIDITY + last UID seen, so each email is read once. */
    var emailUidValidity: Long
        get() = sp.getLong("email.uidValidity", 0)
        set(v) = sp.edit().putLong("email.uidValidity", v).apply()
    var emailLastUid: Long
        get() = sp.getLong("email.lastUid", 0)
        set(v) = sp.edit().putLong("email.lastUid", v).apply()
    var emailLastCheck: Long
        get() = sp.getLong("email.lastCheck", 0)
        set(v) = sp.edit().putLong("email.lastCheck", v).apply()
    var emailLastError: String?
        get() = sp.getString("email.lastError", null)
        set(v) = sp.edit().putString("email.lastError", v).apply()

    /** Remove everything tied to a server connection (keeps theme/language). */
    fun disconnect() {
        token = null
        vaultId = null
        syncCursor = 0
    }

    fun wipeAll() {
        sp.edit().clear().apply()
    }
}
