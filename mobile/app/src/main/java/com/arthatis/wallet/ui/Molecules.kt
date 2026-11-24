package com.arthatis.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.core.Wallet
import com.arthatis.wallet.core.WalletKind
import com.arthatis.wallet.core.legacyIcon

fun categoryIcon(key: String?): ImageVector = when (key) {
    "food" -> Icons.Rounded.Restaurant
    "shopping" -> Icons.Rounded.ShoppingBag
    "bills" -> Icons.Rounded.Bolt
    "transport" -> Icons.Rounded.DirectionsCar
    "fuel" -> Icons.Rounded.LocalGasStation
    "fun" -> Icons.Rounded.Movie
    "home" -> Icons.Rounded.Home
    "health" -> Icons.Rounded.MedicalServices
    "salary" -> Icons.Rounded.Work
    "other_income" -> Icons.Rounded.Payments
    "transfer" -> Icons.Rounded.SwapHoriz
    else -> Icons.Rounded.Payments
}

fun iconFor(key: String): ImageVector = when (key) {
    "bank" -> Icons.Rounded.AccountBalance
    "cash" -> Icons.Rounded.Payments
    "phone" -> Icons.Rounded.Phonelink
    "card" -> Icons.Rounded.CreditCard
    "savings" -> Icons.Rounded.Savings
    "trending" -> Icons.AutoMirrored.Rounded.TrendingUp
    "gift" -> Icons.Rounded.CardGiftcard
    "home" -> Icons.Rounded.Home
    "car" -> Icons.Rounded.DirectionsCar
    "shopping" -> Icons.Rounded.ShoppingBag
    "coin" -> Icons.Rounded.Paid
    else -> Icons.Rounded.AccountBalanceWallet
}

fun walletIcon(w: Wallet): ImageVector = iconFor(w.icon ?: legacyIcon(w.kind))

/** The wallet's own colour, or null for the theme default. */
fun walletTint(w: Wallet): Color? = w.color?.let { hexColor(it) }

fun hexColor(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

@Composable
fun walletKindLabel(kind: WalletKind): String = when (kind) {
    WalletKind.investasi -> tr("Investasi", "Investment")
    WalletKind.kartu -> tr("Kartu kredit", "Credit card")
    else -> tr("Tunai", "Cash")
}

/** Card surface: surfaceContainerHigh with a hairline outlineVariant border, radius 12 (shape/medium). Low alone was nearly invisible on surface. */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 16.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Radius.medium)
    Box(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
    ) { content() }
}

@Composable
private fun LeadingIcon(icon: ImageVector, size: Dp, container: Color = MaterialTheme.colorScheme.secondaryContainer, tint: Color? = null) {
    // Light colours (yellow) get a darker glyph so the icon stays readable on its pale tile.
    val glyph = tint?.let { if (it.luminance() > 0.5f) androidx.compose.ui.graphics.lerp(it, Color.Black, 0.45f) else it }
    Box(Modifier.size(size).clip(RoundedCornerShape(Radius.medium)).background(tint?.copy(alpha = 0.22f) ?: container), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = glyph ?: MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * Money text that never breaks a layout: shows the full amount when it fits in one line, otherwise the compact form
 * (`Rp4,1k`, `$6.7m`, `Rp7,2b`). Non-money strings are shown as they are.
 */
@Composable
fun FitAmount(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val compact = remember(text) { com.arthatis.wallet.core.compactOf(text) }
    var short by remember(text) { androidx.compose.runtime.mutableStateOf(false) }
    Text(
        if (short && compact != null) compact else text, modifier, style = style, color = color, maxLines = 1, softWrap = false,
        overflow = TextOverflow.Clip, onTextLayout = { if (!short && compact != null && it.hasVisualOverflow) short = true },
    )
}

/** M/TxRow: 40 leading icon + headline + supporting + amount (primary for income). */
@Composable
fun TxRow(title: String, supporting: String, amount: String, income: Boolean, icon: ImageVector, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    AppCard(modifier, onClick, padding = 0.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LeadingIcon(icon, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FitAmount(amount, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, color = if (income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** M/WalletCard: Personal (secondaryContainer icon) or Shared (tertiaryContainer icon). */
@Composable
fun WalletCard(name: String, meta: String, extra: String?, balance: String, icon: ImageVector, modifier: Modifier = Modifier, shared: Boolean = false, tint: Color? = null, onClick: (() -> Unit)? = null) {
    AppCard(modifier, onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LeadingIcon(icon, 48.dp, if (shared) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FitAmount(balance, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun MemberRow(name: String, supporting: String, role: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    AppCard(modifier, onClick, padding = 0.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Badge(role)
        }
    }
}

@Composable
fun StatCard(label: String, value: String, delta: String? = null, modifier: Modifier = Modifier) {
    AppCard(modifier, padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FitAmount(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (delta != null) Text(delta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** M/SearchField: pill, 56 high, surfaceContainerHighest. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, trailing: ImageVector? = null, onTrailing: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
        }
        if (trailing != null) Icon(trailing, null, Modifier.clickable { onTrailing?.invoke() }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ListItem(headline: String, modifier: Modifier = Modifier, supporting: String? = null, leading: ImageVector? = null, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Icon(leading, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** O/TopAppBar: Small (title + actions) or Small+Back. Title 22. */
@Composable
fun TopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = if (onBack != null) 4.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) AppIconButton(Icons.AutoMirrored.Rounded.ArrowBack, onBack, description = tr("Kembali", "Back"))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

enum class Tab(val labelId: String, val labelEn: String, val icon: ImageVector) {
    Home("Beranda", "Home", Icons.Rounded.Home),
    Wallets("Dompet", "Wallets", Icons.Rounded.AccountBalanceWallet),
    Transactions("Transaksi", "Transactions", Icons.Rounded.Receipt),
    Bareng("Bareng", "Together", Icons.Rounded.Groups),
    Settings("Pengaturan", "Settings", Icons.Rounded.Settings),
}

/** O/NavBar: 5 Material icons (never the mascot); the label is centred on the same axis as its icon. */
@Composable
fun NavBar(active: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().height(80.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == active
            Column(
                Modifier.weight(1f).padding(top = 12.dp, bottom = 4.dp).clickable(interactionSource = null, indication = null) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.width(64.dp).height(32.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent), contentAlignment = Alignment.Center) {
                    Icon(tab.icon, null, Modifier.size(24.dp), tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(tr(tab.labelId, tab.labelEn), style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** O/BalanceHero: primaryContainer, radius 28. */
@Composable
fun BalanceHero(total: String, subtitle: String, statusChip: String, incomeLabel: String, income: String, expenseLabel: String, expense: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.extraLarge)).background(scheme.primaryContainer).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Total saldo", "Total balance"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
            Row(Modifier.clip(CircleShape).background(scheme.surfaceContainerLowest).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
                Text(statusChip, style = MaterialTheme.typography.labelSmall, color = scheme.onSurface)
            }
        }
        FitAmount(total, style = MaterialTheme.typography.headlineLarge, color = scheme.onPrimaryContainer)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(incomeLabel, style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
                FitAmount(income, style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(expenseLabel, style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer)
                FitAmount(expense, style = MaterialTheme.typography.titleMedium, color = scheme.onPrimaryContainer)
            }
        }
    }
}

/** O/SharedWalletCard. */
@Composable
fun SharedWalletCard(
    name: String, kindLabel: String, amount: String, caption: String, fraction: Float,
    memberNames: List<String>, memberCaption: String, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    AppCard(modifier, onClick, padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Badge(kindLabel, container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            FitAmount(amount, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProgressBar(fraction)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                memberNames.take(3).forEach { Avatar(it, 28.dp) }
                Text(memberCaption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("Lihat detail", "View details"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Rounded.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
