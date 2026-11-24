package com.arthatis.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Space a page must leave at its bottom for what is drawn over it (the app nav bar on tabs, the system bar elsewhere). */
val LocalPageBottom = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/**
 * Scrollable page body with the design's 16 dp gutter and 16 dp rhythm.
 * With [center] the content is centred both ways in the space below the app bar (empty states); it still scrolls if it grows.
 */
@Composable
fun Page(modifier: Modifier = Modifier, bottomSpace: Boolean = true, center: Boolean = false, content: @Composable () -> Unit) {
    val bottom = LocalPageBottom.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight - bottom
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .then(if (center) Modifier.heightIn(min = viewport) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = if (center) Arrangement.spacedBy(16.dp, Alignment.CenterVertically) else Arrangement.spacedBy(16.dp),
            horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            content()
            Spacer(Modifier.height(bottom + if (bottomSpace && !center) 88.dp else 0.dp))
        }
    }
}

@Composable
fun EmptyState(pose: Pose, title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null, ollieSize: androidx.compose.ui.unit.Dp = 120.dp) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Ollie(pose, ollieSize)
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) AppButton(action, onAction, Modifier.fillMaxWidth())
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun H1(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Chip row that scrolls sideways instead of overflowing. Inside a padded page it bleeds to the screen edges (so no chip is
 * clipped at the gutter); empty 8 dp spacers at both ends plus the 8 dp gap restore the 16 dp gutter at rest.
 */
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    val bleed = 16.dp
    Row(
        Modifier
            .layout { measurable, constraints ->
                val extra = bleed.roundToPx() * 2
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth + extra))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
            }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.width(8.dp))
        content()
        Spacer(Modifier.width(8.dp))
    }
}
