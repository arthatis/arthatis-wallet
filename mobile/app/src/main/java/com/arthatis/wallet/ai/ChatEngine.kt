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

    private suspend fun nano(prompt: String): String? = runCatching {
        val model = Generation.getClient()
        model.generateContent(prompt).candidates.firstOrNull()?.text
    }.getOrNull()

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
        val flag = runCatching { Generation.getClient().checkStatus() }
        val status = flag.getOrNull()
        if (status == FeatureStatus.AVAILABLE) { nanoDetail = "AVAILABLE"; return NanoStatus.Ready }
        val probe = runCatching { Generation.getClient().generateContent("Reply with: ok").candidates.firstOrNull()?.text }
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

    private suspend fun server(system: String, user: String): String? {
        if (!prefs.aiUseServer || prefs.token == null) return null
        return runCatching {
            serverHttp.call("POST", "/v1/ai/chat", buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", user) })
                })
            })["reply"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun cloud(system: String, user: String): String? {
        val key = prefs.aiKey ?: return null
        if (!prefs.aiUseCloudKey) return null
        return runCatching {
            when (prefs.aiProvider) {
                "anthropic" -> Http({ "https://api.anthropic.com" }, { null }).call(
                    "POST", "/v1/messages",
                    buildJsonObject {
                        put("model", prefs.aiModel.ifBlank { "claude-haiku-5-5" })
                        put("max_tokens", 400)
                        put("system", system)
                        put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", user) }) })
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
                                add(buildJsonObject { put("role", "user"); put("content", user) })
                            })
                        },
                        headers = mapOf("authorization" to "Bearer $key"), auth = false,
                    )["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                }
                else -> null
            }
        }.getOrNull()
    }

    private suspend fun ask(system: String, user: String): Pair<String, Engine>? {
        nano("$system\n\n$user")?.let { return it to Engine.Nano }
        server(system, user)?.let { return it to Engine.Server }
        cloud(system, user)?.let { return it to Engine.Cloud }
        return null
    }

    suspend fun reply(sentence: String, ledger: Ledger, rates: Rates, currency: String, lang: String = "en"): ChatReply {
        val wallets = ledger.liveWallets.map { NamedRef(it.id, it.name) }
        val categories = ledger.liveCategories.filter { !it.hidden }.map { CategoryRef(it.id, it.key, it.name, it.kind) }
        // A sentence that parses as a transaction becomes a draft, whatever engine is available.
        parseChat(sentence, currency, wallets, categories)?.let { return ChatReply.Draft(it, Engine.Local) }
        // Not a simple "bakso 25k"? Let the active engine try to extract an entry, but only when there is a number in it.
        if (sentence.any(Char::isDigit)) llmDraft(sentence, ledger, currency)?.let { return it }
        val facts = financeFacts(ledger, rates, currency, LocalDate.now(ZoneOffset.UTC))
        val system = "You are Ollie, a concise finance assistant inside a private wallet app. Answer in the user's language using only these facts; if they do not contain the answer say so. Never invent numbers.\n$facts"
        ask(system, sentence)?.let { (text, engine) -> lastEngine = engine; return ChatReply.Answer(text.trim(), engine) }
        quickAnswer(sentence, if (lang == "id") financeFacts(ledger, rates, currency, LocalDate.now(ZoneOffset.UTC), "id") else facts)?.let { return ChatReply.Answer(it, Engine.Local) }
        return ChatReply.Unknown
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
        val system = "Extract ONE wallet entry from the user's sentence. Reply ONLY with JSON: " +
            "{\"kind\":\"expense|income|transfer\",\"amount\":<number in $currency>,\"category\":\"<one of: ${cats.joinToString { it.key ?: it.name }}>\"," +
            "\"wallet\":\"<one of: ${wallets.joinToString { it.name }} or empty>\",\"to_wallet\":\"<wallet name or empty>\",\"note\":\"short note\"} " +
            "or {\"none\":true} when the sentence is not an entry. Never invent an amount."
        val (text, engine) = ask(system, sentence) ?: return null
        val o = jsonObjectOf(text) ?: return null
        if (o["none"] != null) return null
        val amount = o.num("amount")?.takeIf { it.signum() > 0 } ?: return null
        val kind = runCatching { EntryKind.valueOf(o.str("kind") ?: "") }.getOrNull() ?: return null
        val walletByName = { name: String? -> name?.let { n -> wallets.firstOrNull { it.name.equals(n, true) } } }
        val from = walletByName(o.str("wallet")); val to = walletByName(o.str("to_wallet"))
        val minor = runCatching { toMinor(amount, from?.currency ?: currency) }.getOrNull() ?: return null
        val note = o.str("note") ?: sentence
        lastEngine = engine
        if (kind == EntryKind.transfer) {
            if (from == null || to == null || from.id == to.id) return null
            return ChatReply.Draft(ChatDraft(kind, minor, from.currency, note, null, from.id, to.id), engine)
        }
        val key = o.str("category")
        val cat = cats.firstOrNull { it.kind == kind && (it.key == key || it.name.equals(key, true)) } ?: return null
        return ChatReply.Draft(ChatDraft(kind, minor, from?.currency ?: currency, note, cat.id, from?.id, null), engine)
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
    return sb.toString().trim()
}
