package com.arthatis.wallet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthatis.wallet.ai.ChatReply
import com.arthatis.wallet.bareng.Approval
import com.arthatis.wallet.bareng.Bareng
import com.arthatis.wallet.bareng.Activity
import com.arthatis.wallet.bareng.Contribution
import com.arthatis.wallet.bareng.GroupMeta
import com.arthatis.wallet.bareng.GroupSummary
import com.arthatis.wallet.bareng.JoinResult
import com.arthatis.wallet.bareng.Member
import com.arthatis.wallet.core.ArchiveData
import com.arthatis.wallet.core.ArchiveResult
import com.arthatis.wallet.core.ChatDraft
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.Profile
import com.arthatis.wallet.core.RateOverride
import com.arthatis.wallet.core.Rates
import com.arthatis.wallet.core.Tx
import com.arthatis.wallet.core.Wallet
import com.arthatis.wallet.core.WalletKind
import com.arthatis.wallet.core.buildTransfer
import com.arthatis.wallet.core.crypto.Crypto
import com.arthatis.wallet.core.decodeArchive
import com.arthatis.wallet.core.encodeArchive
import com.arthatis.wallet.core.pairFor
import com.arthatis.wallet.core.planMerge
import com.arthatis.wallet.data.Ledger
import com.arthatis.wallet.data.ServerMode
import com.arthatis.wallet.data.newId
import com.arthatis.wallet.sync.SyncResult
import com.arthatis.wallet.sync.Vault
import com.arthatis.wallet.ui.Tab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

sealed interface Route {
    data object Loading : Route
    data object Welcome : Route
    data object Restore : Route
    data object Onboarding : Route
    data object Main : Route
    data class WalletDetail(val id: String) : Route
    data class AddTx(val walletId: String? = null, val editId: String? = null) : Route
    data object Receipt : Route
    data object AddWallet : Route
    data class Transfer(val fromId: String?) : Route
    data object Chat : Route
    data class BarengDetail(val id: String) : Route
    data object BarengCreate : Route
    data class Invite(val id: String) : Route
    data object BarengJoin : Route
    data object Security : Route
    data object DataBackup : Route
    data object ImportPreview : Route
    data object ServerSettings : Route
    data object Recurring : Route
    data class RecurringForm(val id: String? = null) : Route
    data class EditWallet(val id: String) : Route
    data object Categories : Route
    data object AiSettings : Route
    data object Budgets : Route
    data object LinkWeb : Route
    data object Report : Route
    data object EmailSync : Route
    data object EmailDrafts : Route
}

data class Settings(
    val onboarded: Boolean, val theme: String, val lang: String, val serverMode: ServerMode, val serverUrl: String?,
    val connected: Boolean, val recoveryCreated: Boolean, val lastExportAt: Long,
)

data class SnackMsg(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

data class ChatMessage(val fromUser: Boolean, val text: String, val draft: ChatDraft? = null, val time: String = "")

data class PendingImport(val name: String, val bytes: ByteArray, val data: ArchiveData?, val error: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as WalletApplication).graph
    val repo = graph.repo
    val prefs = graph.prefs
    val vault: Vault = graph.vault
    val bareng: Bareng = graph.bareng

    val ledger: StateFlow<Ledger> = repo.ledger.stateIn(viewModelScope, SharingStarted.Eagerly, Ledger())

    private val rateTick = MutableStateFlow(0)
    val rates: StateFlow<Rates> = combine(ledger, rateTick) { l, _ -> graph.rates.build(l.overrides) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Rates())

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings
    private fun readSettings() = Settings(
        prefs.onboarded, prefs.theme, prefs.language, prefs.serverMode, prefs.serverUrl,
        vault.connected, prefs.recoveryCreated, prefs.lastExportAt,
    )
    fun reloadSettings() { _settings.value = readSettings() }

    private val _stack = MutableStateFlow<List<Route>>(listOf(Route.Loading))
    val stack: StateFlow<List<Route>> = _stack
    private val _tab = MutableStateFlow(Tab.Home)
    val tab: StateFlow<Tab> = _tab
    private val _snack = MutableStateFlow<SnackMsg?>(null)
    val snack: StateFlow<SnackMsg?> = _snack
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline
    private val _blocked = MutableStateFlow<String?>(null)
    /** `payment_required` (hosted vault not activated / expired) or `suspended`; null when the server accepts this vault. */
    val blocked: StateFlow<String?> = _blocked
    private val _vaultStatus = MutableStateFlow<com.arthatis.wallet.sync.VaultStatus?>(null)
    val vaultStatus: StateFlow<com.arthatis.wallet.sync.VaultStatus?> = _vaultStatus
    private val _ready = MutableStateFlow(false)
    /** True once local keys and default categories exist. The splash waits for this, never for the network. */
    val ready: StateFlow<Boolean> = _ready
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun viewModelScope_launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    /** Inline bilingual text for messages raised outside Compose. */
    fun t(id: String, en: String) = if (prefs.language == "en") en else id

    fun say(message: String, action: String? = null, onAction: (() -> Unit)? = null) { _snack.value = SnackMsg(message, action, onAction) }
    fun snackShown() { _snack.value = null }

    fun go(route: Route) = _stack.update { it + route }
    fun back() = _stack.update { if (it.size > 1) it.dropLast(1) else it }
    fun reset(route: Route) { _stack.value = listOf(route) }
    fun selectTab(tab: Tab) { _tab.value = tab; _stack.update { it.takeWhile { r -> r != Route.Main } + Route.Main } }

    init {
        viewModelScope.launch {
            vault.ensureKeys()
            repo.ensureDefaults()
            _ready.value = true
            graph.rates.refresh().also { rateTick.update { it + 1 } }
            runSync()
        }
    }

    /** Loading → Welcome (first run) or Main. Called when the splash is tapped or after a short delay. */
    fun leaveSplash() { if (_stack.value.singleOrNull() == Route.Loading) reset(if (prefs.onboarded) Route.Main else Route.Welcome) }

    // --- Onboarding ---------------------------------------------------------------------------

    fun finishOnboarding(name: String, currency: String, walletName: String, kind: WalletKind, icon: String, color: String?, openingMinor: Long, mode: ServerMode, url: String, email: String = "", emailCode: String = "") {
        viewModelScope.launch {
            repo.saveProfile(Profile(name = name.ifBlank { "—" }, primaryCurrency = currency, updatedAt = 0))
            repo.saveWallet(Wallet(newId(), walletName.ifBlank { "Tunai" }, kind, currency, openingMinor, 0, 0, icon = icon, color = color))
            prefs.onboarded = true
            if (mode != ServerMode.None) connect(mode, url, silent = false, email = email, emailCode = emailCode)
            reloadSettings()
            reset(Route.Main)
        }
    }

    // --- Server -------------------------------------------------------------------------------

    suspend fun testServer(url: String): Boolean = vault.ping(url.trim().trimEnd('/'))

    /** Connects (or switches) the server. Local data is pushed encrypted; nothing else changes on the phone. */
    suspend fun connect(mode: ServerMode, url: String, silent: Boolean = false, email: String = "", emailCode: String = ""): Boolean =
        connectResult(mode, url, silent, email, emailCode) == null

    /** Null on success, else an error code from the server (`wrong_code`, `email_not_allowed`, `rate_limited`, …) or `network`. */
    suspend fun connectResult(mode: ServerMode, url: String, silent: Boolean = false, email: String = "", emailCode: String = ""): String? {
        prefs.disconnect()
        prefs.serverMode = mode
        if (mode == ServerMode.Private) prefs.privateUrl = url
        if (mode == ServerMode.None) { reloadSettings(); return null }
        return try {
            vault.connect(android.os.Build.MODEL, email.ifBlank { null }, emailCode.ifBlank { null })
            reloadSettings()
            runSync()
            null
        } catch (e: com.arthatis.wallet.net.ApiException) {
            // A refused registration must not leave a half-connected state behind.
            prefs.disconnect(); prefs.serverMode = ServerMode.None; reloadSettings()
            if (!silent) say(gateMessage(e.code ?: "http_${e.status}"))
            e.code ?: "http_${e.status}"
        } catch (e: Exception) {
            _offline.value = true
            if (!silent) say(t("Tidak bisa terhubung ke server.", "Can't reach the server."))
            reloadSettings()
            "network"
        }
    }

    /** Plain-language reason for a refused registration (no composable context needed). */
    fun gateMessage(code: String): String = when (code) {
        "email_required" -> t("Server ini hanya menerima email yang terdaftar. Masukkan email dan kodenya.", "This server only accepts listed emails. Enter your email and the code.")
        "email_not_allowed" -> t("Email ini tidak ada di daftar server.", "This email isn't on the server's list.")
        "wrong_code" -> t("Kode salah.", "Wrong code.")
        "too_many_attempts" -> t("Terlalu banyak percobaan. Minta kode baru nanti.", "Too many attempts. Ask for a new code later.")
        "email_in_use" -> t("Email ini sudah dipakai vault lain.", "This email is already used by another vault.")
        "rate_limited" -> t("Terlalu banyak pendaftaran dari jaringan ini. Coba lagi nanti.", "Too many registrations from this network. Try again later.")
        "mail_not_configured" -> t("Server ini belum punya pengiriman email (SMTP).", "This server has no email delivery (SMTP).")
        "resend_cooldown" -> t("Tunggu semenit sebelum meminta kode lagi.", "Wait a minute before asking for another code.")
        else -> t("Tidak bisa mendaftar di server ini.", "Couldn't register on this server.")
    }

    suspend fun serverInfo(mode: ServerMode, url: String) = vault.serverInfo(if (mode == ServerMode.Hosted) com.arthatis.wallet.data.HOSTED_URL else url.trim().trimEnd('/'))

    suspend fun sendRegisterCode(mode: ServerMode, url: String, email: String): String? =
        vault.requestRegisterCode(if (mode == ServerMode.Hosted) com.arthatis.wallet.data.HOSTED_URL else url.trim().trimEnd('/'), email.trim())

    fun disconnectServer() { prefs.disconnect(); prefs.serverMode = ServerMode.None; reloadSettings() }

    fun runSync() {
        viewModelScope.launch {
            if (!vault.connected) { _offline.value = false; return@launch }
            when (val r = vault.sync()) {
                is SyncResult.Ok -> { _offline.value = false; _blocked.value = null }
                is SyncResult.Offline -> _offline.value = true
                SyncResult.NoServer -> _offline.value = false
                is SyncResult.Blocked -> { _offline.value = false; _blocked.value = r.reason; _vaultStatus.value = vault.status() }
            }
            reloadSettings()
        }
    }

    // --- Ledger -------------------------------------------------------------------------------

    fun saveWallet(name: String, kind: WalletKind, currency: String, openingMinor: Long, icon: String? = null, color: String? = null, creditLimitMinor: Long? = null, dueDay: Int? = null) {
        viewModelScope.launch {
            repo.saveWallet(Wallet(newId(), name, kind, currency, openingMinor, repo.nextWalletPosition(), 0, icon = icon, color = color, creditLimitMinor = creditLimitMinor, dueDay = dueDay))
            runSync()
        }
    }

    /** Name, icon, colour and opening balance can change; type and currency never do. */
    fun updateWallet(w: Wallet) { viewModelScope.launch { repo.saveWallet(w); runSync() } }

    fun saveTx(tx: Tx) { viewModelScope.launch { repo.saveTx(tx); runSync() } }
    fun deleteTx(tx: Tx) { viewModelScope.launch { repo.deleteTx(tx); runSync() } }
    fun deleteWallet(w: Wallet) { viewModelScope.launch { repo.deleteWallet(w); runSync() } }

    /** Returns false when a needed rate is missing (the UI then asks for a manual rate). */
    suspend fun transfer(from: Wallet, to: Wallet, amountMinor: Long, manualRate: BigDecimal?, note: String, receivedMinor: Long? = null, feeMinor: Long = 0, categoryId: String? = null): Boolean {
        val tx = buildTransfer(newId(), from, to, amountMinor, rates.value, manualRate, System.currentTimeMillis(), 0, note, receivedMinor, feeMinor,
            categoryId ?: ledger.value.liveCategories.firstOrNull { it.key == "transfer" }?.id) ?: return false
        repo.saveTx(tx); runSync(); return true
    }

    fun setPrimaryCurrency(code: String) {
        viewModelScope.launch {
            val current = ledger.value.profile
            repo.saveProfile((current ?: Profile(name = "—", primaryCurrency = code, updatedAt = 0)).copy(primaryCurrency = code))
            runSync()
        }
    }

    fun setName(name: String) {
        viewModelScope.launch {
            val current = ledger.value.profile ?: Profile(name = name, primaryCurrency = "IDR", updatedAt = 0)
            repo.saveProfile(current.copy(name = name)); runSync()
        }
    }

    /** Blank [value] clears the manual override and falls back to the API rate. */
    fun editRate(base: String, quote: String, value: BigDecimal?) {
        viewModelScope.launch {
            val key = pairFor(base, quote).key
            val existing = ledger.value.overrides.firstOrNull { it.id == key }
            if (value == null) existing?.let { repo.clearOverride(it) }
            else repo.saveOverride(RateOverride(key, value.toPlainString(), 0))
            rateTick.update { it + 1 }; runSync()
        }
    }

    fun refreshRates() { viewModelScope.launch { val ok = graph.rates.refresh(); rateTick.update { it + 1 }; if (!ok) say(t("Kurs tidak bisa diperbarui. Memakai kurs terakhir.", "Couldn't update rates. Using the last known rates.")) } }

    // --- Settings -----------------------------------------------------------------------------

    fun setTheme(v: String) { prefs.theme = v; reloadSettings() }
    fun setLanguage(v: String) {
        prefs.language = v; reloadSettings()
        // Material pickers and dialogs take their own strings from the app locale.
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags(if (v == "en") "en" else "id"))
    }

    // --- AI chat ------------------------------------------------------------------------------

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking

    /** Label under the chat title: which engine answers first on this phone. */
    suspend fun engineLabel(): String = when {
        graph.chat.nanoAvailable() -> "On-device · Gemini Nano"
        prefs.aiUseServer && vault.connected -> "Server · ${prefs.serverUrl?.removePrefix("https://")}"
        prefs.aiUseCloudKey && prefs.aiProvider != "none" -> "Cloud · ${prefs.aiProvider}"
        else -> "On-device · parser"
    }

    suspend fun nanoStatus() = graph.chat.nanoStatus()
    fun nanoDetail() = graph.chat.nanoDetail

    /** Quick-entry text → draft, through the same engines as chat. */
    suspend fun quickDraft(text: String): ChatDraft? =
        (graph.chat.reply(text, ledger.value, rates.value, ledger.value.profile?.primaryCurrency ?: "IDR") as? ChatReply.Draft)?.draft

    /** OCR text → receipt lines, through the same engines as chat (parser fallback). */
    suspend fun readReceipt(ocr: String): com.arthatis.wallet.ai.ChatEngine.ReceiptRead =
        graph.chat.readReceipt(ocr, ledger.value, ledger.value.profile?.primaryCurrency ?: "IDR")

    fun ask(text: String, time: String) {
        if (text.isBlank()) return
        _chat.update { it + ChatMessage(true, text, time = time) }
        viewModelScope.launch {
            _thinking.value = true
            val currency = ledger.value.profile?.primaryCurrency ?: "IDR"
            // Earlier turns (not drafts or status markers) so the engine can follow the conversation.
            val history = _chat.value.dropLast(1).filter { it.draft == null && it.text !in setOf("UNKNOWN", "SAVED", "") }
                .map { com.arthatis.wallet.ai.ChatEngine.Turn(if (it.fromUser) "user" else "assistant", it.text) }
            val reply = graph.chat.reply(text, ledger.value, rates.value, currency, prefs.language, history)
            _thinking.value = false
            _chat.update {
                it + when (reply) {
                    is ChatReply.Draft -> ChatMessage(false, "", reply.draft, time)
                    is ChatReply.Answer -> ChatMessage(false, reply.text, time = time)
                    ChatReply.Unknown -> ChatMessage(false, "UNKNOWN", time = time)
                }
            }
        }
    }

    fun confirmDraft(draft: ChatDraft, walletId: String?, toWalletId: String? = draft.toWalletId) {
        viewModelScope.launch {
            val id = walletId ?: draft.walletId ?: ledger.value.liveWallets.firstOrNull()?.id ?: return@launch
            if (draft.kind == EntryKind.transfer) {
                // Through buildTransfer, so the destination amount and the rate are right across currencies.
                val from = ledger.value.wallet(id) ?: return@launch
                val to = toWalletId?.let { ledger.value.wallet(it) } ?: return@launch
                if (!transfer(from, to, draft.amountMinor, null, draft.note)) {
                    say(t("Kurs belum tersedia. Isi kurs manual di layar Transfer.", "Rate unavailable. Enter a manual rate on the Transfer screen."))
                    go(Route.Transfer(from.id)); return@launch
                }
            } else {
                repo.saveTx(Tx(newId(), id, null, draft.categoryId, draft.kind, draft.amountMinor, note = draft.note, occurredAt = System.currentTimeMillis(), updatedAt = 0))
                runSync()
            }
            _chat.update { list -> list.map { if (it.draft == draft) it.copy(draft = null, text = "SAVED") else it } }
        }
    }

    // --- Receipt scan -------------------------------------------------------------------------

    sealed interface ScanState {
        data object Idle : ScanState
        data object Reading : ScanState
        data object Structuring : ScanState
        data class Done(val read: com.arthatis.wallet.ai.ChatEngine.ReceiptRead) : ScanState
        /** [reason]: `ocr` (text could not be read), `image` (photo could not be opened). */
        data class Failed(val reason: String) : ScanState
    }

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan
    private var scanJob: kotlinx.coroutines.Job? = null

    /** Photo file → on-device OCR → same AI chain as chat (each engine time-limited) → parser fallback. The photo is deleted. */
    fun scanReceipt(file: java.io.File) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _scan.value = ScanState.Reading
            val text = try {
                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.arthatis.wallet.scan.ReceiptImage.load(file) }
                if (bitmap == null) { _scan.value = ScanState.Failed("image"); return@launch }
                kotlinx.coroutines.withTimeoutOrNull(30_000) { com.arthatis.wallet.scan.ReceiptImage.recognize(bitmap) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null } finally { file.delete() }
            if (text == null) { _scan.value = ScanState.Failed("ocr"); return@launch }
            _scan.value = ScanState.Structuring
            _scan.value = ScanState.Done(readReceipt(text))
        }
    }

    fun cancelScan() { scanJob?.cancel(); scanJob = null; _scan.value = ScanState.Idle }

    // --- Email auto-tracking --------------------------------------------------------------------

    private val _emailDrafts = MutableStateFlow<List<com.arthatis.wallet.email.EmailDraft>>(emptyList())
    val emailDrafts: StateFlow<List<com.arthatis.wallet.email.EmailDraft>> = _emailDrafts
    private val _emailBusy = MutableStateFlow(false)
    val emailBusy: StateFlow<Boolean> = _emailBusy

    fun loadEmailDrafts() { viewModelScope.launch { _emailDrafts.value = graph.emailDrafts.all().sortedByDescending { it.receivedAt } } }

    /** From the notification: wait for the splash, then open the drafts. */
    fun openEmailDrafts() {
        viewModelScope.launch {
            ready.first { it }
            if (_stack.value.singleOrNull() == Route.Loading) leaveSplash()
            if (prefs.onboarded && _stack.value.last() != Route.EmailDrafts) go(Route.EmailDrafts)
        }
    }

    /** Null on success, else the server's error message. */
    suspend fun testEmail(host: String, port: Int, user: String, password: String): String? =
        com.arthatis.wallet.email.Imap.test(host, port, user, password)

    fun saveEmailSettings(enabled: Boolean, provider: String, host: String, port: Int, user: String, password: String?, senders: List<String>) {
        val changedAccount = host != prefs.emailHost || user != prefs.emailUser
        prefs.emailProvider = provider
        prefs.emailHost = host; prefs.emailPort = port; prefs.emailUser = user; prefs.emailSenders = senders
        if (!password.isNullOrEmpty()) prefs.emailPassword = password
        if (changedAccount) { prefs.emailLastUid = 0; prefs.emailUidValidity = 0 }
        prefs.emailEnabled = enabled
        com.arthatis.wallet.email.EmailWorker.schedule(getApplication(), enabled)
    }

    /** Forgets the account, its password and every pending email draft. */
    fun removeEmailAccount() {
        viewModelScope.launch {
            prefs.emailEnabled = false; prefs.emailPassword = null; prefs.emailUser = ""; prefs.emailLastUid = 0; prefs.emailUidValidity = 0
            com.arthatis.wallet.email.EmailWorker.schedule(getApplication(), false)
            graph.emailDrafts.clear(); loadEmailDrafts()
        }
    }

    fun checkEmailNow() {
        viewModelScope.launch {
            _emailBusy.value = true
            val result = runCatching { com.arthatis.wallet.email.EmailSyncer.run(getApplication()) }
            _emailBusy.value = false
            result.onSuccess { n -> say(if (n == 0) t("Tidak ada transaksi baru dari email.", "No new transactions from email.") else t("$n draf baru dari email.", "$n new drafts from email.")) }
                .onFailure { prefs.emailLastError = it.message; say(t("Gagal membaca email: ${it.message ?: ""}", "Couldn't read email: ${it.message ?: ""}")) }
            loadEmailDrafts()
        }
    }

    fun confirmEmailDraft(d: com.arthatis.wallet.email.EmailDraft, walletId: String, categoryId: String?) {
        viewModelScope.launch {
            repo.saveTx(Tx(newId(), walletId, null, categoryId, d.kind, d.amountMinor, note = d.note, occurredAt = d.receivedAt, updatedAt = 0))
            graph.emailDrafts.remove(d.id); loadEmailDrafts(); runSync()
        }
    }

    fun discardEmailDraft(d: com.arthatis.wallet.email.EmailDraft) { viewModelScope.launch { graph.emailDrafts.remove(d.id); loadEmailDrafts() } }

    // --- Backup -------------------------------------------------------------------------------

    suspend fun archiveData(): ArchiveData {
        val l = repo.snapshot()
        val groups = if (vault.connected) runCatching { bareng.list().mapNotNull { it.meta?.name } }.getOrDefault(emptyList()) else emptyList()
        return ArchiveData(
            createdAt = System.currentTimeMillis(), device = android.os.Build.MODEL,
            wallets = l.wallets, categories = l.categories, transactions = l.txs, recurring = l.recurring,
            rates = l.overrides, profile = l.profile, sharedWallets = groups,
        )
    }

    fun markExported() { prefs.lastExportAt = System.currentTimeMillis(); reloadSettings() }

    suspend fun createRecoveryKey(): String = vault.createRecoveryKey().also { reloadSettings() }

    fun encodeBackup(data: ArchiveData, recoveryKeyText: String): ByteArray? {
        val key = Crypto.parseRecoveryKey(recoveryKeyText) ?: return null
        return encodeArchive(data, key)
    }

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport
    fun stageImport(name: String, bytes: ByteArray) { _pendingImport.value = PendingImport(name, bytes, null); go(Route.ImportPreview) }
    fun clearImport() { _pendingImport.value = null }

    /** `.arthatis` stays encrypted until unlocked; a CSV is readable right away. */
    fun stageFile(name: String, bytes: ByteArray) {
        val csv = runCatching { com.arthatis.wallet.core.csvToArchive(String(bytes), System.currentTimeMillis()) }.getOrNull()
        _pendingImport.value = PendingImport(name, bytes, csv)
        go(Route.ImportPreview)
    }

    /** Decrypts the staged file with the recovery key. Returns null on success, or an error key. */
    fun unlockImport(recoveryKeyText: String): String? {
        val staged = _pendingImport.value ?: return "none"
        val key = Crypto.parseRecoveryKey(recoveryKeyText) ?: return "key"
        return when (val r = decodeArchive(staged.bytes, key)) {
            is ArchiveResult.Ok -> { _pendingImport.value = staged.copy(data = r.data); null }
            ArchiveResult.WrongKey -> "key"
            ArchiveResult.NotArchive -> "file"
        }
    }

    /** Merge keeps what is newer; replace erases the phone first. Returns how many records were written. */
    suspend fun applyImport(replace: Boolean): Int {
        var staged = _pendingImport.value?.data ?: return 0
        if (replace) repo.eraseAll()
        val local = if (replace) ArchiveData(createdAt = 0) else archiveData()
        if (staged.device == "CSV" && !replace) staged = com.arthatis.wallet.core.reconcileCsv(staged, local)
        val plan = planMerge(staged, local)
        plan.wallets.forEach { repo.saveWallet(it.copy(updatedAt = it.updatedAt)) }
        plan.categories.forEach { repo.saveCategory(it) }
        plan.transactions.forEach { repo.saveTx(it) }
        plan.recurring.forEach { repo.saveRecurring(it) }
        plan.rates.forEach { repo.saveOverride(it) }
        plan.profile?.let { repo.saveProfile(it) }
        repo.ensureDefaults()
        _pendingImport.value = null
        prefs.onboarded = true
        reloadSettings()
        runSync()
        return plan.wallets.size + plan.categories.size + plan.transactions.size + plan.recurring.size + plan.rates.size
    }

    fun wipeEverything() {
        viewModelScope.launch {
            repo.eraseAll(); prefs.wipeAll(); graph.emailDrafts.clear(); com.arthatis.wallet.email.EmailWorker.schedule(getApplication(), false); vault.ensureKeys(); repo.ensureDefaults()
            reloadSettings(); reset(Route.Welcome)
        }
    }

    // --- Restore on a new phone ----------------------------------------------------------------

    suspend fun restoreFromServer(mode: ServerMode, url: String, token: String, recoveryKey: String): Boolean {
        prefs.disconnect(); prefs.serverMode = mode
        if (mode == ServerMode.Private) prefs.privateUrl = url
        return try {
            val ok = vault.restore(token, recoveryKey, android.os.Build.MODEL)
            if (ok) { prefs.onboarded = true; runSync(); reloadSettings(); reset(Route.Main) }
            ok
        } catch (e: Exception) { false }
    }

    // --- Bareng -------------------------------------------------------------------------------

    private val _groups = MutableStateFlow<List<GroupSummary>>(emptyList())
    val groups: StateFlow<List<GroupSummary>> = _groups
    private val _groupTotals = MutableStateFlow<Map<String, Long>>(emptyMap())
    val groupTotals: StateFlow<Map<String, Long>> = _groupTotals
    private val _groupsLoading = MutableStateFlow(false)
    val groupsLoading: StateFlow<Boolean> = _groupsLoading

    fun loadGroups() {
        if (!vault.connected) { _groups.value = emptyList(); return }
        viewModelScope.launch {
            _groupsLoading.value = true
            runCatching { bareng.list() }.onSuccess { list ->
                _groups.value = list
                list.filter { it.status == "active" }.forEach { runCatching { bareng.syncGroup(it) } }
                _groupTotals.value = list.filter { it.status == "active" }.associate { g ->
                    g.id to bareng.contributions(g.id).first().sumOf { if (it.kind == "withdrawal") -it.amountMinor else it.amountMinor }
                }
                _offline.value = false
            }.onFailure {
                // 402/403 are not "offline": the server answered and refused this vault; AccessCard explains it.
                val refused = it is com.arthatis.wallet.net.ApiException && (it.status == 402 || it.status == 403)
                if (!refused) _offline.value = true
            }
            _groupsLoading.value = false
        }
    }

    data class GroupView(
        val summary: GroupSummary, val members: List<Member>, val activity: List<Activity>,
        val approvals: List<Approval>, val contributions: List<Contribution>,
    )

    private val _groupView = MutableStateFlow<GroupView?>(null)
    val groupView: StateFlow<GroupView?> = _groupView

    fun loadGroup(id: String) {
        viewModelScope.launch {
            val (summary, members) = runCatching { bareng.detail(id) }.getOrNull() ?: return@launch
            if (summary.rotateRequired) runCatching { bareng.rotateIfNeeded(id) }
            runCatching { bareng.syncGroup(summary) }
            val approvals = runCatching { bareng.approvals(id, summary.epoch) }.getOrDefault(emptyList())
            bareng.settleApproved(id, summary.epoch, prefs.vaultId ?: "", approvals)
            runCatching { bareng.syncGroup(summary) }
            val activity = runCatching { bareng.activity(id, summary.epoch) }.getOrDefault(emptyList())
            val contributions = bareng.contributions(id).first()
            _groupView.value = GroupView(summary, members, activity, approvals, contributions)
        }
    }

    private fun myName() = ledger.value.profile?.name ?: "—"

    fun createGroup(meta: GroupMeta, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val id = runCatching { bareng.create(meta, myName(), null) }.getOrNull()
            loadGroups(); onDone(id)
        }
    }

    fun contribute(group: GroupView, amountMinor: Long, note: String, withdraw: Boolean) {
        viewModelScope.launch {
            val me = prefs.vaultId ?: return@launch
            val epoch = group.summary.epoch
            val name = myName()
            if (withdraw && amountMinor > com.arthatis.wallet.bareng.withdrawThresholdMinor(group.summary.meta?.currency ?: "IDR")) {
                bareng.requestWithdrawal(group.summary.id, epoch, amountMinor, note)
                bareng.logActivity(group.summary.id, epoch, "$name meminta penarikan")
            } else {
                bareng.addContribution(group.summary.id, Contribution(bareng.newContributionId(), me, if (withdraw) "withdrawal" else "contribution", amountMinor, note, System.currentTimeMillis(), 0))
                bareng.logActivity(group.summary.id, epoch, if (withdraw) "$name menarik dana" else "$name menambah kontribusi")
            }
            runCatching { bareng.syncGroup(group.summary) }
            loadGroup(group.summary.id)
        }
    }

    fun vote(group: GroupView, approval: Approval, approve: Boolean) {
        viewModelScope.launch {
            runCatching { bareng.vote(group.summary.id, approval.id, approve) }
            bareng.logActivity(group.summary.id, group.summary.epoch, "${myName()} ${if (approve) "menyetujui" else "menolak"} penarikan")
            loadGroup(group.summary.id)
        }
    }

    suspend fun joinGroup(link: String): JoinResult = runCatching { bareng.join(link, myName(), null) }.getOrDefault(JoinResult.Invalid)

    fun approveMember(group: GroupView, member: Member) {
        viewModelScope.launch { runCatching { bareng.approveMember(group.summary.id, group.summary.epoch, member, null) }; loadGroup(group.summary.id) }
    }

    fun removeMember(group: GroupView, member: Member) {
        viewModelScope.launch { runCatching { bareng.removeMember(group.summary.id, member.vaultId) }; loadGroup(group.summary.id) }
    }

    fun setRole(group: GroupView, member: Member, role: String) {
        viewModelScope.launch { runCatching { bareng.setRole(group.summary.id, member.vaultId, role) }; loadGroup(group.summary.id) }
    }

    fun leaveGroup(id: String) { viewModelScope.launch { runCatching { bareng.leave(id) }; loadGroups(); back() } }

    suspend fun createInvite(group: GroupView, role: String) =
        runCatching { bareng.invite(group.summary.id, group.summary.epoch, group.summary.meta?.name ?: "", role, prefs.serverUrl ?: "") }.getOrNull()
}
