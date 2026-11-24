package com.arthatis.wallet.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.AppViewModel
import com.arthatis.wallet.Route
import com.arthatis.wallet.bareng.GroupMeta
import com.arthatis.wallet.bareng.GroupSummary
import com.arthatis.wallet.bareng.Invite
import com.arthatis.wallet.bareng.JoinResult
import com.arthatis.wallet.bareng.Member
import com.arthatis.wallet.core.dayOf
import com.arthatis.wallet.core.epochMillis
import com.arthatis.wallet.core.evaluateExpression
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.core.supportedCurrencies
import com.arthatis.wallet.core.toMinor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
private fun roleLabel(role: String) = when (role) {
    "owner" -> "Owner"; "admin" -> "Admin"
    "member" -> tr("Anggota", "Member"); else -> tr("Hanya lihat", "View only")
}

@Composable
private fun kindLabel(kind: String) = if (kind == "investasi") tr("Investasi", "Investment") else tr("Tabungan", "Savings")

@Composable
private fun NeedsServer(vm: AppViewModel) {
    EmptyState(Pose.Thinking, tr("Bareng butuh server", "Shared wallets need a server"), tr("Wallet bareng disinkronkan lewat servermu (hosted atau VPS pribadi), terenkripsi E2EE. Hubungkan server dulu.", "Shared wallets sync through your server (hosted or your own VPS), E2EE. Connect a server first."),
        action = tr("Hubungkan server", "Connect a server"), onAction = { vm.go(Route.ServerSettings) })
}

/** M08 */
@Composable
fun BarengScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val groups by vm.groups.collectAsState()
    val totals by vm.groupTotals.collectAsState()
    LaunchedEffect(settings.connected) { vm.loadGroups() }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Wallet bareng", "Shared wallets"))
        // Nothing to list yet (no server, or no wallets): centre the empty state in the space under the app bar.
        if (!settings.connected || groups.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!settings.connected) NeedsServer(vm)
                else {
                    EmptyState(Pose.HappyCoin, tr("Belum ada wallet bareng", "No shared wallets yet"), tr("Buat satu, lalu undang teman atau keluarga.", "Create one, then invite friends or family."))
                    AppButton(tr("Buat wallet bareng", "Create shared wallet"), { vm.go(Route.BarengCreate) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
                    AppButton(tr("Gabung dengan tautan", "Join with a link"), { vm.go(Route.BarengJoin) }, Modifier.fillMaxWidth(), style = BtnStyle.Tonal, icon = Icons.Rounded.PersonAdd)
                }
            }
            return@Column
        }
        Page {
            Body(tr("Nabung dan investasi bersama. Setiap rupiah tercatat dan bisa diaudit semua anggota.", "Save and invest together. Every rupiah is recorded and auditable by every member."))
            groups.forEach { g ->
                val meta = g.meta
                if (g.status != "active" || meta == null) {
                    AppCard { Hint(tr("Menunggu persetujuan pemilik wallet…", "Waiting for the wallet owner to approve…")) }
                } else {
                    val collected = totals[g.id] ?: 0L
                    val fraction = meta.targetMinor?.takeIf { it > 0 }?.let { collected.toFloat() / it } ?: 0f
                    SharedWalletCard(
                        meta.name, kindLabel(meta.kind), formatMinor(collected, meta.currency),
                        meta.targetMinor?.let { tr("dari ${formatMinor(it, meta.currency)}", "of ${formatMinor(it, meta.currency)}") } ?: tr("tanpa target", "no target"),
                        fraction, emptyList(),
                        listOfNotNull(tr("${g.memberCount} anggota", "${g.memberCount} members"), meta.targetDay?.let { tr("target ", "target ") + shortDateLong(dayOf(it)) }).joinToString(" · "),
                    ) { vm.go(Route.BarengDetail(g.id)) }
                }
            }
            AppButton(tr("Buat wallet bareng", "Create shared wallet"), { vm.go(Route.BarengCreate) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
            AppButton(tr("Gabung dengan tautan", "Join with a link"), { vm.go(Route.BarengJoin) }, Modifier.fillMaxWidth(), style = BtnStyle.Tonal, icon = Icons.Rounded.PersonAdd)
        }
    }
}

@Composable
private fun shortDateLong(day: LocalDate): String = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", locale()).format(day)

@Composable
fun BarengCreateScreen(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("tabungan") }
    var currency by remember { mutableStateOf("IDR") }
    var target by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Buat wallet bareng", "Create shared wallet"), onBack = { vm.back() })
        Page {
            AppTextField(name, { name = it }, tr("Nama wallet", "Wallet name"), supporting = tr("Contoh: Liburan Bali.", "Example: Bali trip."))
            ChipRow { AppChip(tr("Tabungan", "Savings"), kind == "tabungan", { kind = "tabungan" }); AppChip(tr("Investasi", "Investment"), kind == "investasi", { kind = "investasi" }) }
            FieldLabel(tr("Mata uang", "Currency"))
            ChipRow { supportedCurrencies.forEach { c -> AppChip(c.code, currency == c.code, { currency = c.code }) } }
            AmountField(target, { target = it }, currency, tr("Target (opsional)", "Target (optional)"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Tanggal target", "Target date"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                DateChip(date ?: epochMillis(LocalDate.now(ZoneOffset.UTC)).also { }) { date = it }
            }
            Hint(tr("Nama, anggota, dan semua catatan dienkripsi dengan kunci grup. Server tidak bisa membacanya.", "The name, members and every entry are encrypted with the group key. The server can't read them."))
            if (failed) Text(tr("Gagal membuat. Periksa koneksi server.", "Couldn't create it. Check the server connection."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            AppButton(tr("Buat wallet", "Create wallet"), {
                busy = true; failed = false
                val targetMinor = evaluateExpression(target)?.let { runCatching { toMinor(it, currency) }.getOrNull() }
                vm.createGroup(GroupMeta(name.trim(), kind, currency, targetMinor, if (targetMinor != null) date else null)) { id ->
                    busy = false
                    if (id == null) failed = true else { vm.back(); vm.go(Route.BarengDetail(id)) }
                }
            }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), loading = busy)
        }
    }
}

@Composable
fun BarengJoinScreen(vm: AppViewModel) {
    var link by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JoinResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Gabung wallet bareng", "Join a shared wallet"), onBack = { vm.back() })
        Page {
            Ollie(Pose.Mail, 96.dp, Modifier.align(Alignment.CenterHorizontally))
            Body(tr("Tempel tautan undangan. Pemilik wallet akan menyetujui permintaanmu.", "Paste the invite link. The wallet owner will approve your request."))
            AppTextField(link, { link = it }, tr("Tautan undangan", "Invite link"), keyboard = KeyboardType.Uri)
            AppButton(tr("Tempel dari clipboard", "Paste from clipboard"), { clipboard.getText()?.text?.let { link = it } }, style = BtnStyle.Text, size = BtnSize.S)
            when (val r = result) {
                is JoinResult.Pending -> Text(tr("Permintaan terkirim${r.groupName?.let { " ke “$it”" } ?: ""}. Menunggu persetujuan.", "Request sent${r.groupName?.let { " to “$it”" } ?: ""}. Waiting for approval."), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                JoinResult.WrongServer -> Text(tr("Undangan ini dari server lain. Ganti server di Pengaturan → Server, lalu coba lagi.", "This invite is from a different server. Switch server in Settings → Server and try again."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                JoinResult.Invalid -> Text(tr("Tautan tidak valid atau sudah kedaluwarsa.", "The link is invalid or has expired."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                null -> {}
            }
            AppButton(tr("Minta bergabung", "Request to join"), { scope.launch { busy = true; result = vm.joinGroup(link); busy = false; vm.loadGroups() } }, Modifier.fillMaxWidth(), enabled = link.isNotBlank(), loading = busy)
        }
    }
}

/** M09 */
@Composable
fun BarengDetailScreen(vm: AppViewModel, id: String) {
    val view by vm.groupView.collectAsState()
    val me = vm.prefs.vaultId
    LaunchedEffect(id) { vm.loadGroup(id) }
    val v = view?.takeIf { it.summary.id == id }
    var sheet by remember { mutableStateOf<String?>(null) }
    var manage by remember { mutableStateOf<Member?>(null) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar(v?.summary?.meta?.name ?: tr("Wallet bareng", "Shared wallet"), onBack = { vm.back() })
        Page {
            if (v == null) { Body(tr("Memuat…", "Loading…")); return@Page }
            val meta = v.summary.meta
            val cur = meta?.currency ?: "IDR"
            val collected = v.contributions.sumOf { if (it.kind == "withdrawal") -it.amountMinor else it.amountMinor }
            val target = meta?.targetMinor
            val myRole = v.summary.role
            val manager = myRole == "owner" || myRole == "admin"
            val writer = myRole != "viewer"
            AppCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatMinor(collected, cur), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (target != null) {
                        Hint(tr("dari ${formatMinor(target, cur)}", "of ${formatMinor(target, cur)}"))
                        ProgressBar(collected.toFloat() / target)
                        Hint(tr("${(collected * 100 / target).coerceIn(0, 999)}% terkumpul · ${v.summary.memberCount} anggota", "${(collected * 100 / target).coerceIn(0, 999)}% saved · ${v.summary.memberCount} members") + (meta.targetDay?.let { " · target " + shortDateLong(dayOf(it)) } ?: ""))
                    } else Hint(tr("${v.summary.memberCount} anggota · tanpa target", "${v.summary.memberCount} members · no target"))
                }
            }
            if (writer) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(tr("Tambah kontribusi", "Add contribution"), { sheet = "in" }, Modifier.weight(1f), size = BtnSize.S, icon = Icons.Rounded.Add)
                AppButton(tr("Tarik dana", "Withdraw"), { sheet = "out" }, Modifier.weight(1f), style = BtnStyle.Tonal, size = BtnSize.S)
            }
            SectionTitle(tr("Anggota", "Members"), if (manager) tr("Undang", "Invite") else null, { vm.go(Route.Invite(id)) })
            v.members.filter { it.status == "active" }.forEach { m ->
                MemberRow(m.name + if (m.vaultId == me) tr(" (kamu)", " (you)") else "", listOfNotNull(roleLabel(m.role), m.city).joinToString(" · "), roleLabel(m.role),
                    onClick = if (manager && m.role != "owner" && m.vaultId != me) ({ manage = m }) else null)
            }
            v.members.filter { it.status == "pending" }.forEach { m ->
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { Text(tr("Permintaan bergabung", "Join request"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface); Hint(tr("Menunggu persetujuan", "Awaiting approval")) }
                        if (manager) { AppButton(tr("Setujui", "Approve"), { vm.approveMember(v, m) }, size = BtnSize.XS); AppButton(tr("Tolak", "Reject"), { vm.removeMember(v, m) }, style = BtnStyle.Text, size = BtnSize.XS) }
                    }
                }
            }
            val pending = v.approvals.filter { it.status == "pending" }
            if (pending.isNotEmpty()) {
                SectionTitle(tr("Persetujuan", "Approvals"))
                pending.forEach { a ->
                    AppCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("Penarikan ${formatMinor(a.amountMinor, cur)}", "Withdrawal ${formatMinor(a.amountMinor, cur)}"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Hint(tr("Penarikan di atas ${formatMinor(com.arthatis.wallet.bareng.WITHDRAW_THRESHOLD_MINOR, cur)} butuh persetujuan ${a.required} anggota. Saat ini: ${a.approvals} menyetujui.", "Withdrawals above ${formatMinor(com.arthatis.wallet.bareng.WITHDRAW_THRESHOLD_MINOR, cur)} need ${a.required} approvals. So far: ${a.approvals}."))
                            if (a.note.isNotBlank()) Hint(a.note)
                            if (manager) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppButton(tr("Setujui", "Approve"), { vm.vote(v, a, true) }, size = BtnSize.S)
                                AppButton(tr("Tolak", "Reject"), { vm.vote(v, a, false) }, style = BtnStyle.Outlined, size = BtnSize.S)
                            }
                        }
                    }
                }
            }
            SectionTitle(tr("Aktivitas", "Activity"))
            if (v.activity.isEmpty()) EmptyState(Pose.Sleepy, tr("Belum ada aktivitas", "No activity yet"), tr("Perubahan di wallet ini akan tercatat di sini.", "Changes to this wallet will be logged here."), ollieSize = 96.dp) else v.activity.take(10).forEach { a ->
                Text("•  ${a.text} · ${shortDateLong(dayOf(a.at))}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            if (myRole != "owner") AppButton(tr("Keluar dari wallet", "Leave wallet"), { confirmLeave = true }, Modifier.fillMaxWidth(), style = BtnStyle.Outlined, size = BtnSize.S)
            if (sheet != null) androidx.compose.material3.AlertDialog(
                onDismissRequest = { sheet = null },
                title = { Text(if (sheet == "in") tr("Tambah kontribusi", "Add contribution") else tr("Tarik dana", "Withdraw")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AmountField(amount, { amount = it }, cur, tr("Nominal ($cur)", "Amount ($cur)"))
                        AppTextField(note, { note = it }, tr("Catatan (opsional)", "Note (optional)"))
                        if (sheet == "out") Hint(tr("Di atas ${formatMinor(com.arthatis.wallet.bareng.WITHDRAW_THRESHOLD_MINOR, cur)} butuh persetujuan 2 anggota.", "Above ${formatMinor(com.arthatis.wallet.bareng.WITHDRAW_THRESHOLD_MINOR, cur)} needs 2 approvals."))
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton({
                        val minor = evaluateExpression(amount)?.let { runCatching { toMinor(it, cur) }.getOrNull() }
                        if (minor != null && minor > 0) { vm.contribute(v, minor, note.trim(), sheet == "out"); amount = ""; note = ""; sheet = null }
                    }) { Text(tr("Simpan", "Save")) }
                },
                dismissButton = { androidx.compose.material3.TextButton({ sheet = null }) { Text(tr("Batal", "Cancel")) } },
            )
            manage?.let { m ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { manage = null }, title = { Text(m.name) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Hint(tr("Ubah peran", "Change role"))
                            ChipRow {
                                if (myRole == "owner") AppChip("Admin", m.role == "admin", { vm.setRole(v, m, "admin"); manage = null })
                                AppChip(tr("Anggota", "Member"), m.role == "member", { vm.setRole(v, m, "member"); manage = null })
                                AppChip(tr("Hanya lihat", "View only"), m.role == "viewer", { vm.setRole(v, m, "viewer"); manage = null })
                            }
                            Hint(tr("Mengeluarkan anggota memutar kunci wallet.", "Removing a member rotates the wallet key."))
                        }
                    },
                    confirmButton = { androidx.compose.material3.TextButton({ vm.removeMember(v, m); manage = null }) { Text(tr("Keluarkan", "Remove"), color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { androidx.compose.material3.TextButton({ manage = null }) { Text(tr("Tutup", "Close")) } },
                )
            }
            if (confirmLeave) ConfirmDialog(tr("Keluar dari wallet?", "Leave this wallet?"), tr("Kamu tidak akan lagi melihat wallet ini.", "You will no longer see this wallet."), tr("Keluar", "Leave"), { confirmLeave = false }) { confirmLeave = false; vm.leaveGroup(id) }
        }
    }
}

@Composable
internal fun QrCode(text: String, size: androidx.compose.ui.unit.Dp = 180.dp) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }
    Canvas(Modifier.size(size).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp)) {
        val cell = this.size.width / matrix.width
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
    }
}

/** M10 */
@Composable
fun InviteScreen(vm: AppViewModel, id: String) {
    val view by vm.groupView.collectAsState()
    val v = view?.takeIf { it.summary.id == id }
    var role by remember { mutableStateOf("member") }
    var invite by remember { mutableStateOf<Invite?>(null) }
    var failed by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) { if (v == null) vm.loadGroup(id) }
    LaunchedEffect(v?.summary?.id, role) { if (v != null) { invite = vm.createInvite(v, role); failed = invite == null } }
    val shareText = tr("Gabung ke wallet bareng “${v?.summary?.meta?.name ?: ""}” di Arthatis: ${invite?.link ?: ""}", "Join the shared wallet “${v?.summary?.meta?.name ?: ""}” on Arthatis: ${invite?.link ?: ""}")
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("Undang ke ${v?.summary?.meta?.name ?: ""}", "Invite to ${v?.summary?.meta?.name ?: ""}"), onBack = { vm.back() })
        Page {
            Ollie(Pose.Mail, 96.dp, Modifier.align(Alignment.CenterHorizontally))
            Body(tr("Siapa pun dengan tautan ini bisa minta bergabung. Kamu tetap yang menyetujui.", "Anyone with this link can ask to join. You still approve each request."))
            invite?.let { inv ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { QrCode(inv.link) }
                AppCard { Text(inv.link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                Hint(tr("Berlaku 7 hari · maks ${inv.maxUses} anggota", "Valid 7 days · max ${inv.maxUses} members"))
            }
            if (failed) Text(tr("Tidak bisa membuat undangan. Periksa koneksi server.", "Couldn't create the invite. Check the server connection."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            FieldLabel(tr("Peran anggota baru", "New member role"))
            ChipRow { AppChip(tr("Anggota", "Member"), role == "member", { role = "member" }); AppChip(tr("Hanya lihat", "View only"), role == "viewer", { role = "viewer" }) }
            AppButton(tr("Salin tautan", "Copy link"), { invite?.let { clipboard.setText(AnnotatedString(it.link)); vm.say(vm.t("Tautan disalin.", "Link copied.")) } }, Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCopy, enabled = invite != null)
            AppButton(tr("Bagikan via WhatsApp", "Share via WhatsApp"), {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText); setPackage("com.whatsapp") }
                runCatching { context.startActivity(send) }.onFailure {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }, null))
                }
            }, Modifier.fillMaxWidth(), style = BtnStyle.Tonal, icon = Icons.Rounded.Share, enabled = invite != null)
        }
    }
}
