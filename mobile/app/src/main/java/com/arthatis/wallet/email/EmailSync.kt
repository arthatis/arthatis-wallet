package com.arthatis.wallet.email

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arthatis.wallet.MainActivity
import com.arthatis.wallet.R
import com.arthatis.wallet.WalletApplication
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.htmlToText
import com.arthatis.wallet.core.isWatchedSender
import com.arthatis.wallet.crypto.KeyStoreBox
import com.arthatis.wallet.data.Prefs
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

/** A transaction read from an email, waiting for the user. Local to this phone (encrypted file), never synced. */
@Serializable
data class EmailDraft(
    val id: String,
    val messageId: String,
    val from: String,
    val subject: String,
    val receivedAt: Long,
    val kind: EntryKind,
    val amountMinor: Long,
    val currency: String,
    val walletId: String? = null,
    val categoryId: String? = null,
    val note: String = "",
    /** Which engine read it: Nano / Server / Cloud / Local. */
    val engine: String = "Local",
)

/** Pending email drafts in one Keystore-encrypted file. */
class EmailDraftStore(context: Context) {
    private val file = File(context.filesDir, "email-drafts.bin")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(EmailDraft.serializer())
    private val lock = Mutex()

    private fun read(): List<EmailDraft> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString(ser, String(KeyStoreBox.decrypt(file.readBytes())))
    }.getOrDefault(emptyList())

    private fun write(list: List<EmailDraft>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(KeyStoreBox.encrypt(json.encodeToString(ser, list).toByteArray()))
        tmp.renameTo(file)
    }

    suspend fun all(): List<EmailDraft> = withContext(Dispatchers.IO) { lock.withLock { read() } }
    suspend fun add(items: List<EmailDraft>) = withContext(Dispatchers.IO) {
        lock.withLock { val cur = read(); write(cur + items.filter { n -> cur.none { it.messageId == n.messageId } }) }
    }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { lock.withLock { write(read().filterNot { it.id == id }) } }
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { file.delete() } }
}

/** One new email from a watched sender, as plain text. */
data class MailItem(val messageId: String, val from: String, val subject: String, val text: String, val receivedAt: Long)

/** IMAP over TLS, read-only, INBOX only. Only messages newer than the last seen UID and from watched senders are read. */
object Imap {
    private fun session(host: String, port: Int): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host); put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true"); put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "15000"); put("mail.imaps.timeout", "30000"); put("mail.imaps.writetimeout", "15000")
        }
        return Session.getInstance(props)
    }

    /** Logs in and out; null on success, else the error message. */
    suspend fun test(host: String, port: Int, user: String, password: String): String? = withContext(Dispatchers.IO) {
        runCatching { session(host, port).getStore("imaps").use { it.connect(host, port, user, password); it.getFolder("INBOX").use { f -> f.open(Folder.READ_ONLY) } } }
            .exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }

    private inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R = try { block(this) } finally { runCatching { this?.close() } }
    private inline fun <R> Folder.use(block: (Folder) -> R): R = try { block(this) } finally { runCatching { if (isOpen) close(false) } }

    /**
     * Returns new watched mails. On the first run (no UID stored) only the last [firstRunDays] days are looked at, so an
     * old inbox is not imported wholesale. Updates the stored UID cursor.
     */
    suspend fun fetchNew(prefs: Prefs, firstRunDays: Int = 3, max: Int = 50): List<MailItem> = withContext(Dispatchers.IO) {
        val password = prefs.emailPassword ?: return@withContext emptyList()
        // No watched sender = nothing to read (privacy: the inbox is never scanned wholesale).
        if (prefs.emailSenders.isEmpty()) return@withContext emptyList()
        val store = session(prefs.emailHost, prefs.emailPort).getStore("imaps")
        store.connect(prefs.emailHost, prefs.emailPort, prefs.emailUser, password)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val uids = folder as UIDFolder
                val validity = uids.uidValidity
                val fresh = validity != prefs.emailUidValidity || prefs.emailLastUid == 0L
                val messages: Array<Message> = if (fresh) {
                    val since = System.currentTimeMillis() - firstRunDays * 86_400_000L
                    val count = folder.messageCount
                    if (count == 0) emptyArray() else folder.getMessages(maxOf(1, count - 200), count).filter { (it.receivedDate?.time ?: 0) >= since }.toTypedArray()
                } else uids.getMessagesByUID(prefs.emailLastUid + 1, UIDFolder.LASTUID)
                var lastUid = if (fresh) 0L else prefs.emailLastUid
                val out = ArrayList<MailItem>()
                for (m in messages.takeLast(max)) {
                    val uid = uids.getUID(m)
                    if (uid <= lastUid && !fresh) continue
                    lastUid = maxOf(lastUid, uid)
                    val from = m.from?.firstOrNull()?.toString().orEmpty()
                    if (!isWatchedSender(from, prefs.emailSenders)) continue
                    val text = runCatching { textOf(m) }.getOrDefault("").take(6_000)
                    val id = (m.getHeader("Message-ID")?.firstOrNull() ?: "uid-$validity-$uid")
                    out += MailItem(id, from, m.subject.orEmpty(), text, m.receivedDate?.time ?: System.currentTimeMillis())
                }
                // Empty mailbox on first run: remember the newest UID so the next run only reads new mail.
                if (fresh && messages.isEmpty() && folder.messageCount > 0) lastUid = uids.getUID(folder.getMessage(folder.messageCount))
                prefs.emailUidValidity = validity
                prefs.emailLastUid = maxOf(lastUid, if (fresh) 0 else prefs.emailLastUid)
                out
            } finally { runCatching { folder.close(false) } }
        } finally { runCatching { store.close() } }
    }

    /** Plain text of a message: text/plain preferred, else HTML converted. */
    private fun textOf(p: Part): String {
        if (p.isMimeType("text/plain")) return p.content.toString()
        if (p.isMimeType("text/html")) return htmlToText(p.content.toString())
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as Multipart
            val parts = (0 until mp.count).map { mp.getBodyPart(it) }
            parts.firstOrNull { it.isMimeType("text/plain") }?.let { return it.content.toString() }
            return parts.joinToString("\n") { runCatching { textOf(it) }.getOrDefault("") }.trim()
        }
        return ""
    }
}

/** Every ~15 minutes with a network: read new bank/e-wallet emails, turn them into drafts, notify. Nothing is saved without the user. */
class EmailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as WalletApplication).graph
        val prefs = graph.prefs
        if (!prefs.emailEnabled || prefs.emailPassword == null || prefs.emailUser.isBlank() || prefs.emailSenders.isEmpty()) return Result.success()
        val count = runCatching { EmailSyncer.run(applicationContext) }.getOrElse {
            prefs.emailLastError = it.message ?: it.javaClass.simpleName
            return Result.retry()
        }
        if (count > 0) notify(count)
        return Result.success()
    }

    private fun notify(count: Int) {
        val en = Prefs(applicationContext).language == "en"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val intent = PendingIntent.getActivity(applicationContext, 7701, Intent(applicationContext, MainActivity::class.java).putExtra("open", "email-drafts"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(applicationContext, "reminders")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (en) "$count new transaction(s) from email" else "$count transaksi baru dari email")
            .setContentText(if (en) "Tap to review and confirm." else "Ketuk untuk periksa dan konfirmasi.")
            .setContentIntent(intent).setAutoCancel(true).build()
        runCatching { manager.notify(7701, n) }
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork("email"); return }
            val request = PeriodicWorkRequestBuilder<EmailWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            wm.enqueueUniquePeriodicWork("email", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/** Shared by the worker and the "Check now" button. Returns how many new drafts were added. */
object EmailSyncer {
    private val running = Mutex()

    suspend fun run(context: Context): Int = running.withLock {
        val graph = (context.applicationContext as WalletApplication).graph
        val prefs = graph.prefs
        val mails = Imap.fetchNew(prefs)
        prefs.emailLastCheck = System.currentTimeMillis(); prefs.emailLastError = null
        if (mails.isEmpty()) return 0
        val ledger = graph.repo.snapshot()
        val currency = ledger.profile?.primaryCurrency ?: "IDR"
        val drafts = mails.mapNotNull { mail -> graph.chat.readEmail(mail, ledger, currency) }
        graph.emailDrafts.add(drafts)
        drafts.size
    }
}
