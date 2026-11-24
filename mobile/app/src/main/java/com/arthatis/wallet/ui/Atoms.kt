package com.arthatis.wallet.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arthatis.wallet.R
import kotlin.math.PI
import kotlin.math.sin

enum class BtnStyle { Filled, Tonal, Outlined, Text }
enum class BtnSize(val height: Dp, val hPad: Dp, val icon: Dp) {
    XS(32.dp, 12.dp, 18.dp), S(40.dp, 16.dp, 18.dp), M(56.dp, 24.dp, 24.dp), L(72.dp, 32.dp, 24.dp)
}

/** Button: 4 styles × 4 sizes. The icon follows the label colour (white on Filled). Pressed morphs the pill to a rounder-square. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Filled,
    size: BtnSize = BtnSize.M,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val source = remember_interaction()
    val pressed by source.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (style) {
        BtnStyle.Filled -> scheme.primary to scheme.onPrimary
        BtnStyle.Tonal -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BtnStyle.Outlined -> Color.Transparent to scheme.onSurfaceVariant
        BtnStyle.Text -> Color.Transparent to scheme.primary
    }
    val shape = RoundedCornerShape(if (pressed) (if (size == BtnSize.XS) 8.dp else 12.dp) else 1000.dp)
    val alive = enabled && !loading
    val bg = if (!enabled && style != BtnStyle.Text && style != BtnStyle.Outlined) scheme.onSurface.copy(alpha = .12f) else container
    val fg = if (!enabled) scheme.onSurface.copy(alpha = .38f) else content
    Row(
        modifier
            .heightIn(min = size.height)
            .clip(shape)
            .background(bg, shape)
            .then(if (style == BtnStyle.Outlined) Modifier.borderStroke(scheme.outline, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = alive, onClick = onClick)
            .padding(horizontal = size.hPad),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = fg, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, null, Modifier.size(size.icon), tint = fg)
        Text(text, color = fg, style = if (size >= BtnSize.M) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
    }
}

@Composable private fun remember_interaction() = androidx.compose.runtime.remember { MutableInteractionSource() }

private fun Modifier.borderStroke(color: Color, shape: RoundedCornerShape): Modifier =
    this.border(BorderStroke(1.dp, color), shape)

@Composable
fun AppIconButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    IconButton(onClick, modifier.size(48.dp)) { Icon(icon, description, tint = tint) }
}

/** FAB: 56 × 56, container primaryContainer, radius 16. */
@Composable
fun Fab(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    Box(
        modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
}

@Composable
fun AppChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null, enabled: Boolean = true) {
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 32.dp),
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = when {
            selected -> ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) })
            leading != null -> ({ Icon(leading, null, Modifier.size(18.dp)) })
            else -> null
        },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

@Composable
fun AppTextField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    supporting: String? = null, keyboard: KeyboardType = KeyboardType.Text, error: Boolean = false,
    singleLine: Boolean = true, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth(),
        supportingText = supporting?.let { { Text(it) } }, isError = error, singleLine = singleLine,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(Radius.extraSmall), leadingIcon = leading, trailingIcon = trailing,
    )
}

/** Switch 52 × 32 (M3 default size). */
@Composable
fun AppSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Switch(checked, onChange, modifier, colors = SwitchDefaults.colors())
}

@Composable
fun Avatar(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(letter, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Box(modifier.clip(RoundedCornerShape(1000.dp)).background(container).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = content, style = MaterialTheme.typography.labelSmall)
    }
}

/** Straight progress bar: 8 high, radius 4. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().size(width = Dp.Unspecified, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(scheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).size(width = Dp.Unspecified, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(scheme.primary))
    }
}

/** M3 Expressive wavy progress: a travelling sine wave, not a straight bar. [fraction] null = indeterminate. */
@Composable
fun WavyProgress(modifier: Modifier = Modifier, fraction: Float? = null) {
    val scheme = MaterialTheme.colorScheme
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val track = scheme.surfaceContainerHighest
    val active = scheme.primary
    Canvas(modifier.fillMaxWidth().size(width = Dp.Unspecified, height = 16.dp)) {
        val h = size.height; val mid = h / 2; val amp = h * 0.28f; val stroke = 4.dp.toPx()
        val progress = fraction ?: 0.62f
        val end = size.width * progress
        drawLine(track, Offset(end, mid), Offset(size.width, mid), stroke, StrokeCap.Round)
        val path = Path()
        var x = 0f
        path.moveTo(0f, mid)
        while (x <= end) {
            path.lineTo(x, mid + amp * sin((x / 28.dp.toPx()) * 2f * PI.toFloat() - phase))
            x += 2f
        }
        drawPath(path, active, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

enum class Pose(@DrawableRes val res: Int) {
    Wave(R.drawable.ollie_wave), HappyCoin(R.drawable.ollie_happy_coin), Thinking(R.drawable.ollie_thinking),
    Sleepy(R.drawable.ollie_sleepy), Worried(R.drawable.ollie_worried), Mail(R.drawable.ollie_mail),
}

/** Ollie is only for brand and illustration, never navigation or function icons. */
@Composable
fun Ollie(pose: Pose, size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(pose.res), null, modifier.size(size), contentScale = ContentScale.Fit)
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (action != null) Text(
            action, Modifier.clickable { onAction?.invoke() }.padding(vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End,
        )
    }
}

class DialAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * One FAB that fans out into labelled actions (the "branching FAB"). The caller owns [open] so it can draw a scrim behind it.
 */
@Composable
fun SpeedDialFab(actions: List<DialAction>, open: Boolean, onOpenChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.animation.AnimatedVisibility(open, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 3 }, exit = androidx.compose.animation.fadeOut()) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEach { a ->
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(scheme.secondaryContainer).clickable { onOpenChange(false); a.onClick() }.padding(start = 16.dp, end = 8.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(a.label, style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
                        Icon(a.icon, null, Modifier.size(24.dp), tint = scheme.onSecondaryContainer)
                        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                    }
                }
            }
        }
        Fab(if (open) Icons.Rounded.Close else Icons.Rounded.Add, { onOpenChange(!open) }, description = if (open) "Tutup" else "Tambah")
    }
}


/**
 * Amount input that shows the currency's thousands separator while typing (`1.234.567`, `1,234.56`, `1.234,56`).
 * [value] is the plain stored text (`1234567.5`); [onChange] gets it back already cleaned.
 */
@Composable
fun AmountField(
    value: String, onChange: (String) -> Unit, currency: String, label: String, modifier: Modifier = Modifier,
    supporting: String? = null, error: Boolean = false,
) {
    val transformation = remember(currency) {
        androidx.compose.ui.text.input.VisualTransformation { text ->
            val shown = com.arthatis.wallet.core.displayAmount(text.text, currency)
            androidx.compose.ui.text.input.TransformedText(
                androidx.compose.ui.text.AnnotatedString(shown.text),
                object : androidx.compose.ui.text.input.OffsetMapping {
                    override fun originalToTransformed(offset: Int) = shown.originalToDisplay(offset)
                    override fun transformedToOriginal(offset: Int) = shown.displayToOriginal(offset)
                },
            )
        }
    }
    OutlinedTextField(
        value = value, onValueChange = { onChange(com.arthatis.wallet.core.sanitizeAmountInput(it, currency)) }, label = { Text(label) },
        modifier = modifier.fillMaxWidth(), supportingText = supporting?.let { { Text(it) } }, isError = error, singleLine = true,
        prefix = { Text(com.arthatis.wallet.core.currencyInfo(currency).symbol) },
        visualTransformation = transformation,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(Radius.extraSmall),
    )
}

/** Icon grid + colour palette for a wallet; both optional (null colour = theme default). */
@Composable
fun WalletStyleFields(icon: String, onIcon: (String) -> Unit, color: String?, onColor: (String?) -> Unit) {
    val tint = color?.let { hexColor(it) }
    FieldLabel(tr("Ikon", "Icon"))
    ChipRow {
        com.arthatis.wallet.core.walletIconKeys.forEach { key ->
            val selected = icon == key
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(Radius.medium))
                    .background(if (selected) (tint?.copy(alpha = 0.18f) ?: MaterialTheme.colorScheme.secondaryContainer) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(if (selected) 2.dp else 1.dp, if (selected) (tint ?: MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.medium))
                    .clickable { onIcon(key) },
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Icon(iconFor(key), key, tint = tint ?: MaterialTheme.colorScheme.onSecondaryContainer) }
        }
    }
    FieldLabel(tr("Warna", "Colour"))
    ChipRow {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                .border(if (color == null) 3.dp else 1.dp, if (color == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable { onColor(null) },
            contentAlignment = Alignment.Center,
        ) { Text("A", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge) }
        com.arthatis.wallet.core.walletColors.forEach { hex ->
            val c = hexColor(hex) ?: return@forEach
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c)
                    .border(if (color == hex) 3.dp else 1.dp, if (color == hex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onColor(hex) },
            )
        }
    }
}
