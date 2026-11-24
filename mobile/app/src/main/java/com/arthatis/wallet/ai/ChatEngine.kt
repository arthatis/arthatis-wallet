package com.arthatis.wallet.ai

import com.arthatis.wallet.core.CategoryRef
import com.arthatis.wallet.core.ChatDraft
import com.arthatis.wallet.core.EntryKind
import com.arthatis.wallet.core.NamedRef
import com.arthatis.wallet.core.Rates
import com.arthatis.wallet.core.Sign
import com.arthatis.wallet.core.dayOf
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.core.ReceiptLine
import com.arthatis.wallet.core.parseChat
import com.arthatis.wallet.core.parseReceipt
import com.arthatis.wallet.core.toMinor
import com.arthatis.wallet.core.matchWallet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.arthatis.wallet.data.Ledger
import com.arthatis.wallet.data.Prefs
import com.arthatis.wallet.net.Http
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneOffset

enum class Engine { Nano, Server, Cloud, Local }

sealed interface ChatReply {
    /** A draft the user must confirm. Nothing is saved without that. */
    data class Draft(val draft: ChatDraft, val engine: Engine) : ChatReply
    data class Answer(val text: String, val engine: Engine) : ChatReply
    data object Unknown : ChatReply
}

/**
 * Chain from the design: Gemini Nano on the phone → AI on the user's server (opt-in) → the user's own cloud key (opt-in)
 * → local parser. The last two engines that leave the phone only run after the user switched them on.
 */
class ChatEngine(private val prefs: Prefs, private val serverHttp: Http) {
    var lastEngine: Engine = Engine.Local
        private set

    /** One client for the app's lifetime; AICore keeps the model warm between calls. */
    private val nanoClient by lazy { Generation.getClient() }

    /**
     * Gemini Nano with a hard time limit. On phones where the model is still downloadable/downloading, generateContent can
     * wait indefinitely; without a limit the whole chain (and receipt scan) hung there.
     */
    private suspend fun nano(prompt: String): String? = withTimeoutOrNull(NANO_TIMEOUT_MS) {
        runCatching { nanoClient.generateContent(prompt).candidates.firstOrNull()?.text }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    enum class NanoStatus { Ready, NotInstalled, Unsupported }

    /** Result of the last real try, so Settings can say what actually happened instead of guessing from the status flag. */
    @Volatile var nanoDetail: String = ""
        private set

    /**
     * checkStatus() alone can report DOWNLOADABLE/DOWNLOADING on phones where the model is already present and usable
     * (the status describes this app's feature download, not whether AICore can answer). So a status other than
     * AVAILABLE is confirmed with one tiny generation; if that answers, Nano is ready. We still never download anything.
     */
    suspend fun nanoStatus(): NanoStatus {
        val flag = runCatching { nanoClient.checkStatus() }
        val status = flag.getOrNull()
        if (status == FeatureStatus.AVAILABLE) { nanoDetail = "AVAILABLE"; return NanoStatus.Ready }
        val probe = runCatching { withTimeoutOrNull(PROBE_TIMEOUT_MS) { nanoClient.generateContent("Reply with: ok").candidates.firstOrNull()?.text } }
        if (!probe.getOrNull().isNullOrBlank()) { nanoDetail = "probe ok (status=$status)"; return NanoStatus.Ready }
        nanoDetail = "status=${status ?: flag.exceptionOrNull()?.javaClass?.simpleName}; probe=${probe.exceptionOrNull()?.message?.take(80)}"
        return when (status) {
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> NanoStatus.NotInstalled
            else -> NanoStatus.Unsupported
        }
    }

    suspend fun nanoAvailable(): Boolean = nanoStatus() == NanoStatus.Ready

    suspend fun serverAvailable(): Boolean = prefs.serverUrl != null && runCatching {
        serverHttp.call("GET", "/v1/ai/status")["available"]?.jsonPrimitive?.content == "true"
    }.getOrDefault(false)

    /** Earlier turns of the conversation, oldest first: role is `user` or `assistant`. */
    data class Turn(val role: String, val text: String)

    private fun turns(history: List<Turn>, user: String) = buildJsonArray {
        history.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.text) }) }
        add(buildJsonObject { put("role", "user"); put("content", user) })
    }

    private suspend fun server(system: String, history: List<Turn>, user: String): String? {
        if (!prefs.aiUseServer || prefs.token == null) return null
        return runCatching {
            serverHttp.call("POST", "/v1/ai/chat", buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    turns(history, user).forEach { add(it) }
                })
            })["reply"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun cloud(system: String, history: List<Turn>, user: String): String? {
        val key = prefs.aiKey ?: return null
        if (!prefs.aiUseCloudKey) return null
        return runCatching {
            when (prefs.aiProvider) {
                "anthropic" -> Http({ "https://api.anthropic.com" }, { null }).call(
                    "POST", "/v1/messages",
                    buildJsonObject {
                        put("model", prefs.aiModel.ifBlank { "claude-haiku-5-5" })
                        put("max_tokens", 800)
                        put("system", system)
                        put("messages", turns(history, user))
                    },
                    headers = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), auth = false,
                )["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                "openai", "deepseek" -> {
                    val base = if (prefs.aiProvider == "openai") "https://api.openai.com" else "https://api.deepseek.com"
                    Http({ base }, { null }).call(
                        "POST", "/v1/chat/completions",
                        buildJsonObject {
                            put("model", prefs.aiModel.ifBlank { if (prefs.aiProvider == "openai") "gpt-5-mini" else "deepseek-chat" })
                            put("messages", buildJsonArray {
                                add(buildJsonObject { put("role", "system"); put("content", system) })
                                turns(history, user).forEach { add(it) }
                            })
                        },
                        headers = mapOf("authorization" to "Bearer $key"), auth = false,
                    )["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                }
                else -> null
            }
        }.getOrNull()
    }

    /** Engines in order; each has its own time limit (Nano above, HTTP 10 s connect + 30 s read). */
    private suspend fun ask(system: String, user: String, history: List<Turn> = emptyList()): Pair<String, Engine>? {
        val transcript = history.joinToString("\n") { "${if (it.role == "user") "User" else "Ollie"}: ${it.text}" }
        nano(if (transcript.isEmpty()) "$system\n\n$user" else "$system\n\n$transcript\nUser: $user\nOllie:")?.let { return it to Engine.Nano }
        withTimeoutOrNull(HTTP_TIMEOUT_MS) { server(system, history, user) }?.let { return it to Engine.Server }
        withTimeoutOrNull(HTTP_TIMEOUT_MS) { cloud(system, history, user) }?.let { return it to Engine.Cloud }
        return null
    }

    suspend fun reply(sentence: String, ledger: Ledger, rates: Rates, currency: String, lang: String = "en", history: List<Turn> = emptyList()): ChatReply {
        val wallets = ledger.liveWallets.map { NamedRef(it.id, it.name) }
        val categories = ledger.liveCategories.filter { !it.hidden }.map { CategoryRef(it.id, it.key, it.name, it.kind) }
        // A sentence that parses as a transaction becomes a draft, whatever engine is available.
        parseChat(sentence, currency, wallets, categories)?.let { return ChatReply.Draft(it, Engine.Local) }
        // Not a simple "bakso 25k"? Let the active engine try to extract an entry, but only when there is a number in it.
        if (sentence.any(Char::isDigit)) llmDraft(sentence, ledger, currency)?.let { return it }
        val today = LocalDate.now(ZoneOffset.UTC)
        val facts = financeFacts(ledger, rates, currency, today)
        ask(chatSystem(facts, today), sentence, history.takeLast(HISTORY_TURNS))?.let { (text, engine) -> lastEngine = engine; return ChatReply.Answer(text.trim(), engine) }
        quickAnswer(sentence, if (lang == "id") financeFacts(ledger, rates, currency, today, "id") else facts)?.let { return ChatReply.Answer(it, Engine.Local) }
        smallTalk(sentence, lang == "id")?.let { return ChatReply.Answer(it, Engine.Local) }
        return ChatReply.Unknown
    }

    /**
     * Ollie may chat and give general money advice, but stays near three topics: recording entries, personal-finance
     * consultation, and analysing the user's history. Numbers about the user come only from [facts].
     */
    private fun chatSystem(facts: String, today: LocalDate) =
        "You are Ollie, the friendly assistant inside Arthatis Wallet, a private money-tracking app. Today is $today. " +
            "Reply in the user's language, warm and concise (a few sentences unless asked for detail). " +
            "You can make small talk, give general personal-finance advice (budgeting, saving, debt, investing basics, " +
            "spending habits) and analyse the user's history using the facts below. If the user drifts far from money " +
            "(e.g. coding, politics, homework), answer briefly if harmless, then gently steer back to their finances. " +
            "Never invent the user's own numbers: use only the facts; if they lack what is asked, say so and suggest " +
            "what to record. Recording happens in the app: tell the user to write e.g. 'bakso 25k' or " +
            "'transfer 100k dari BCA ke GoPay' to get a draft. You are not a licensed advisor; say so for specific " +
            "investment picks.\n\nFacts about the user's money:\n$facts"

    private fun smallTalk(sentence: String, id: Boolean): String? {
        val q = sentence.lowercase().trim()
        val greet = listOf("halo", "hai", "hi", "hello", "hey", "pagi", "siang", "sore", "malam", "assalamualaikum")
        val thanks = listOf("makasih", "terima kasih", "thanks", "thank you", "thx", "mantap", "oke", "ok")
        return when {
            greet.any { q == it || q.startsWith("$it ") || q.startsWith("$it,") } -> if (id) "Halo! Mau catat transaksi, lihat ringkasan, atau ngobrol soal keuangan?" else "Hi! Want to record something, see a summary, or talk money?"
            thanks.any { q == it || q.startsWith(it) } -> if (id) "Sama-sama! Kabari kalau ada yang mau dicatat." else "You're welcome! Tell me when there's something to record."
            else -> null
        }
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Models like to wrap JSON in prose or code fences; take the outermost object. */
    private fun jsonObjectOf(text: String): JsonObject? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { lenient.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" }
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.content?.toBigDecimalOrNull()

    /** Turns a free sentence into a draft through the same engine chain as chat. Null = not an entry / no engine. */
    private suspend fun llmDraft(sentence: String, ledger: Ledger, currency: String): ChatReply.Draft? {
        val wallets = ledger.liveWallets
        val cats = ledger.liveCategories.filter { !it.hidden && it.kind != EntryKind.transfer }
        val system = "Extract ONE wallet entry from the user's sentence. A move between the user's own wallets " +
            "(transfer, tf, kirim, pindah, top up, tarik tunai) is kind transfer: wallet = source, to_wallet = destination. Reply ONLY with JSON: " +
            "{\"kind\":\"expense|income|transfer\",\"amount\":<number in $currency>,\"category\":\"<one of: ${cats.joinToString { it.key ?: it.name }}>\"," +
            "\"wallet\":\"<one of: ${wallets.joinToString { it.name }} or empty>\",\"to_wallet\":\"<wallet name or empty>\",\"note\":\"short note\"} " +
            "or {\"none\":true} when the sentence is not an entry. Never invent an amount."
        val (text, engine) = ask(system, sentence) ?: return null
        val o = jsonObjectOf(text) ?: return null
        if (o["none"] != null) return null
        val amount = o.num("amount")?.takeIf { it.signum() > 0 } ?: return null
        val kind = runCatching { EntryKind.valueOf(o.str("kind") ?: "") }.getOrNull() ?: return null
        val refs = wallets.map { NamedRef(it.id, it.name) }
        val walletByName = { name: String? -> name?.takeIf { it.isNotBlank() }?.let { n -> matchWallet(n, refs)?.let { r -> wallets.first { it.id == r.id } } } }
        val from = walletByName(o.str("wallet")); val to = walletByName(o.str("to_wallet"))
        val minor = runCatching { toMinor(amount, from?.currency ?: currency) }.getOrNull() ?: return null
        val note = o.str("note") ?: sentence
        lastEngine = engine
        if (kind == EntryKind.transfer) {
            // A missing side is picked on the draft card.
            if (from == null && to == null) return null
            if (from != null && from.id == to?.id) return null
            return ChatReply.Draft(ChatDraft(kind, minor, from?.currency ?: currency, note, null, from?.id, to?.id), engine)
        }
        val key = o.str("category")
        val cat = cats.firstOrNull { it.kind == kind && (it.key == key || it.name.equals(key, true)) } ?: return null
        return ChatReply.Draft(ChatDraft(kind, minor, from?.currency ?: currency, note, cat.id, from?.id, null), engine)
    }

    companion object {
        const val NANO_TIMEOUT_MS = 20_000L
        const val PROBE_TIMEOUT_MS = 8_000L
        const val HTTP_TIMEOUT_MS = 35_000L
        const val HISTORY_TURNS = 10
    }

    data class ReceiptRead(val lines: List<ReceiptLine>, val categoryId: String?, val engine: Engine)

    /**
     * Structures OCR text (read on the phone by ML Kit; the photo never leaves it) with the same engine chain as chat.
     * If no engine answers or the answer is unusable, falls back to the plain on-device parser.
     */
    suspend fun readReceipt(ocrText: String, ledger: Ledger, currency: String): ReceiptRead {
        val cats = ledger.liveCategories.filter { !it.hidden && it.kind == EntryKind.expense }
        val system = "You read shop receipts. Reply ONLY with JSON: {\"items\":[{\"name\":\"...\",\"amount\":<number in $currency>}],\"total\":<number or null>," +
            "\"category\":\"<best of: ${cats.joinToString { it.key ?: it.name }}>\"}. Use only numbers present in the text; skip lines you cannot read."
        val answered = ask(system, ocrText)?.let { (text, engine) -> jsonObjectOf(text)?.let { it to engine } }
        if (answered != null) {
            val (o, engine) = answered
            val items = (o["items"] as? JsonArray)?.mapNotNull { el ->
                val item = el as? JsonObject ?: return@mapNotNull null
                val minor = item.num("amount")?.let { runCatching { toMinor(it, currency) }.getOrNull() }?.takeIf { it > 0 } ?: return@mapNotNull null
                ReceiptLine(item.str("name") ?: "—", minor, isTotal = false, uncertain = false)
            }.orEmpty()
            val total = o.num("total")?.let { runCatching { toMinor(it, currency) }.getOrNull() }?.takeIf { it > 0 }
            if (items.isNotEmpty() || total != null) {
                lastEngine = engine
                val key = o.str("category")
                return ReceiptRead(
                    items + listOfNotNull(total?.let { ReceiptLine("Total", it, isTotal = true, uncertain = false) }),
                    cats.firstOrNull { it.key == key || it.name.equals(key, true) }?.id, engine,
                )
            }
        }
        return ReceiptRead(parseReceipt(ocrText, currency), null, Engine.Local)
    }

    /**
     * A bank / e-wallet email → a draft for the user to confirm, through the same engine chain (the text only leaves the
     * phone when the user enabled server AI or their own key). Falls back to the on-device parser. Null = not a transaction.
     */
    suspend fun readEmail(mail: com.arthatis.wallet.email.MailItem, ledger: Ledger, currency: String): com.arthatis.wallet.email.EmailDraft? {
        val wallets = ledger.liveWallets
        val refs = wallets.map { NamedRef(it.id, it.name) }
        val cats = ledger.liveCategories.filter { !it.hidden && it.kind != EntryKind.transfer }
        val system = "You read bank and e-wallet notification emails for a personal money app. If the email reports ONE completed " +
            "transaction of the account owner, reply ONLY with JSON: {\"kind\":\"expense|income\",\"amount\":<number>,\"currency\":\"IDR|USD|SGD|MYR|EUR\"," +
            "\"wallet\":\"<one of: ${wallets.joinToString { it.name }} or empty>\",\"category\":\"<one of: ${cats.joinToString { it.key ?: it.name }}>\"," +
            "\"note\":\"merchant or counterpart, short\"}. Reply {\"none\":true} for promotions, OTPs, statements, failed or pending transactions. Never invent an amount."
        val text = "From: ${mail.from}\nSubject: ${mail.subject}\n\n${mail.text.take(3_000)}"
        val guessedWallet = matchWallet("${mail.from} ${mail.subject}", refs)?.id
        val draftId = com.arthatis.wallet.data.newId()
        ask(system, text)?.let { (answer, engine) ->
            val o = jsonObjectOf(answer) ?: return@let
            if (o["none"] != null) return null
            val kind = runCatching { EntryKind.valueOf(o.str("kind") ?: "") }.getOrNull()?.takeIf { it != EntryKind.transfer } ?: return@let
            val cur = o.str("currency")?.uppercase()?.takeIf { c -> com.arthatis.wallet.core.supportedCurrencies.any { it.code == c } } ?: currency
            val minor = o.num("amount")?.takeIf { it.signum() > 0 }?.let { runCatching { toMinor(it, cur) }.getOrNull() } ?: return@let
            val wallet = o.str("wallet")?.takeIf { it.isNotBlank() }?.let { matchWallet(it, refs)?.id } ?: guessedWallet
            val key = o.str("category")
            val cat = cats.firstOrNull { it.kind == kind && (it.key == key || it.name.equals(key, true)) }
            return com.arthatis.wallet.email.EmailDraft(draftId, mail.messageId, mail.from, mail.subject, mail.receivedAt, kind, minor, cur, wallet, cat?.id, o.str("note") ?: mail.subject, engine.name)
        }
        val guess = com.arthatis.wallet.core.parseBankEmail(mail.subject, mail.text, currency) ?: return null
        val categories = ledger.liveCategories.filter { !it.hidden }.map { CategoryRef(it.id, it.key, it.name, it.kind) }
        val cat = parseChat("${guess.counterpart ?: mail.subject} 1", currency, emptyList(), categories)?.categoryId?.takeIf { id -> ledger.category(id)?.kind == guess.kind }
        return com.arthatis.wallet.email.EmailDraft(draftId, mail.messageId, mail.from, mail.subject, mail.receivedAt, guess.kind, guess.amountMinor, guess.currency, guessedWallet, cat, guess.counterpart ?: mail.subject, Engine.Local.name)
    }

    private fun quickAnswer(sentence: String, facts: String): String? {
        val q = sentence.lowercase()
        return if (listOf("ringkasan", "summary", "minggu ini", "this week", "bandingkan", "compare", "bulan lalu", "last month", "budget", "anggaran").any { q.contains(it) }) facts else null
    }
}

/** Plain-text facts about the last 7 days and this month; the only data an engine ever sees, built on the phone. */
fun financeFacts(ledger: Ledger, rates: Rates, currency: String, today: LocalDate, lang: String = "en"): String {
    val id = lang == "id"
    fun l(idText: String, enText: String) = if (id) idText else enText
    val weekStart = today.minusDays(6)
    val weekTxs = ledger.liveTxs.filter { val d = dayOf(it.occurredAt); !d.isBefore(weekStart) && !d.isAfter(today) }
    fun value(tx: com.arthatis.wallet.core.Tx): Long {
        val w = ledger.wallet(tx.walletId) ?: return 0
        return rates.convert(tx.amountMinor, w.currency, currency) ?: 0
    }
    val spent = weekTxs.filter { it.kind == EntryKind.expense }
    val income = weekTxs.filter { it.kind == EntryKind.income }
    val byCategory = spent.groupBy { ledger.category(it.categoryId)?.name ?: "Lainnya" }
        .mapValues { (_, v) -> v.sumOf(::value) }.entries.sortedByDescending { it.value }.take(5)
    val wallets = ledger.liveWallets.associateBy { it.id }
    val (ms, me) = com.arthatis.wallet.core.monthBounds(today)
    val (ps, pe) = com.arthatis.wallet.core.monthBounds(today.minusMonths(1))
    val thisMonth = com.arthatis.wallet.core.periodFlow(ledger.liveTxs, wallets, currency, rates, ms, me)
    val lastMonth = com.arthatis.wallet.core.periodFlow(ledger.liveTxs, wallets, currency, rates, ps, pe)
    val sb = StringBuilder(l("Mata uang", "Currency") + ": $currency\n")
    sb.append(l("7 hari terakhir: keluar ${formatMinor(spent.sumOf(::value), currency)} dalam ${spent.size} transaksi, masuk ${formatMinor(income.sumOf(::value), currency, Sign.None)}.\n", "Last 7 days: spent ${formatMinor(spent.sumOf(::value), currency)} in ${spent.size} transactions, income ${formatMinor(income.sumOf(::value), currency, Sign.None)}.\n"))
    if (byCategory.isNotEmpty()) sb.append(l("Kategori teratas: ", "Top categories: ") + byCategory.joinToString { "${it.key} ${formatMinor(it.value, currency)}" } + ".\n")
    spent.maxByOrNull(::value)?.let { sb.append(l("Pengeluaran terbesar: ", "Largest expense: ") + "${it.note.ifBlank { "—" }} ${formatMinor(value(it), currency)}" + l(" pada ", " on ") + "${dayOf(it.occurredAt)}.\n") }
    com.arthatis.wallet.core.budgetLines(ledger.budgets, ledger.liveTxs, wallets, currency, rates, today)?.takeIf { it.isNotEmpty() }?.let { lines ->
        sb.append(l("Anggaran bulan ini: ", "Budgets this month: ") + lines.joinToString { l("${ledger.category(it.categoryId)?.name ?: "?"} terpakai ${formatMinor(it.spentMinor, currency)} dari ${formatMinor(it.budgetMinor, currency)} (sisa ${formatMinor(it.remainingMinor, currency)})", "${ledger.category(it.categoryId)?.name ?: "?"} spent ${formatMinor(it.spentMinor, currency)} of ${formatMinor(it.budgetMinor, currency)} (remaining ${formatMinor(it.remainingMinor, currency)})") } + ".\n")
    }
    if (thisMonth != null && lastMonth != null) {
        sb.append(l("Bulan ini: keluar ${formatMinor(thisMonth.expenseMinor, currency)}, masuk ${formatMinor(thisMonth.incomeMinor, currency)}. ", "This month so far: spent ${formatMinor(thisMonth.expenseMinor, currency)}, income ${formatMinor(thisMonth.incomeMinor, currency)}. "))
        sb.append(l("Bulan lalu: keluar ${formatMinor(lastMonth.expenseMinor, currency)}, masuk ${formatMinor(lastMonth.incomeMinor, currency)}.\n", "Last month: spent ${formatMinor(lastMonth.expenseMinor, currency)}, income ${formatMinor(lastMonth.incomeMinor, currency)}.\n"))
    }
    // Net worth and each wallet, so questions like "saldo BCA berapa?" have an answer.
    com.arthatis.wallet.core.totalBalance(ledger.liveWallets, ledger.txs, currency, rates)?.let {
        sb.append(l("Kekayaan bersih (kas + investasi − utang kartu): ", "Net worth (cash + investments − card debt): ") + formatMinor(it, currency) + ".\n")
    }
    if (ledger.liveWallets.isNotEmpty()) sb.append(l("Saldo dompet: ", "Wallet balances: ") + ledger.liveWallets.joinToString { w ->
        "${w.name} (${w.kind.name}) ${formatMinor(com.arthatis.wallet.core.walletBalance(w, ledger.txs), w.currency)}"
    } + ".\n")
    // Six full months before this one: totals and the top spending categories, for trend questions.
    val history = (1..6).mapNotNull { back ->
        val month = today.minusMonths(back.toLong())
        val (s, e) = com.arthatis.wallet.core.monthBounds(month)
        val f = com.arthatis.wallet.core.periodFlow(ledger.liveTxs, wallets, currency, rates, s, e) ?: return@mapNotNull null
        if (f.incomeMinor == 0L && f.expenseMinor == 0L) return@mapNotNull null
        val top = ledger.liveTxs.filter { it.kind == EntryKind.expense && it.occurredAt >= s && it.occurredAt < e }
            .groupBy { ledger.category(it.categoryId)?.name ?: "Lainnya" }.mapValues { (_, v) -> v.sumOf(::value) }
            .entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} ${formatMinor(it.value, currency)}" }
        "${month.year}-${"%02d".format(month.monthValue)}: " + l("keluar ", "spent ") + formatMinor(f.expenseMinor, currency) +
            l(", masuk ", ", income ") + formatMinor(f.incomeMinor, currency) + (if (top.isEmpty()) "" else l(" (teratas: $top)", " (top: $top)"))
    }
    if (history.isNotEmpty()) sb.append(l("Riwayat bulanan:\n", "Monthly history:\n") + history.joinToString("\n") + "\n")
    sb.append(l("Jumlah transaksi tercatat: ", "Recorded transactions: ") + ledger.liveTxs.size + ".\n")
    return sb.toString().trim()
}
