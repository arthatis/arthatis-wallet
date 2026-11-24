package com.arthatis.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.core.Wallet
import com.arthatis.wallet.core.formatMinor
import com.arthatis.wallet.core.walletBalance
import com.arthatis.wallet.data.Ledger

/** A wallet card that opens a list to choose from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletPicker(selected: Wallet?, ledger: Ledger, onSelect: (Wallet) -> Unit, exclude: String? = null, placeholder: String = tr("Pilih dompet", "Choose wallet")) {
    var open by remember { mutableStateOf(false) }
    if (selected == null) AppButton(placeholder, { open = true }, Modifier, style = BtnStyle.Outlined)
    else WalletCard(selected.name, walletMeta(selected), lastUsed(selected, ledger), formatMinor(walletBalance(selected, ledger.txs), selected.currency), walletIcon(selected.kind)) { open = true }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ledger.liveWallets.filter { it.id != exclude }.forEach { w ->
                WalletCard(w.name, walletMeta(w), null, formatMinor(walletBalance(w, ledger.txs), w.currency), walletIcon(w.kind)) { onSelect(w); open = false }
            }
        }
    }
}

/** Date chip that opens the M3 date picker. [millis] is UTC midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateChip(millis: Long, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val day = com.arthatis.wallet.core.dayOf(millis)
    AppChip(if (day == java.time.LocalDate.now(java.time.ZoneOffset.UTC)) tr("Hari ini", "Today") else shortDate(day), false, { open = true }, leading = Icons.Rounded.CalendarToday)
    if (open) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = millis)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { androidx.compose.material3.TextButton({ state.selectedDateMillis?.let(onPick); open = false }) { androidx.compose.material3.Text("OK") } },
            dismissButton = { androidx.compose.material3.TextButton({ open = false }) { androidx.compose.material3.Text(tr("Batal", "Cancel")) } },
        ) { androidx.compose.material3.DatePicker(state) }
    }
}
