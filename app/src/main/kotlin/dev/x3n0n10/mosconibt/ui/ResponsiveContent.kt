package dev.x3n0n10.mosconibt.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Centers [content] in a column whose width never exceeds the available height.
 *
 * This targets displays that are much wider than they are tall (a fixed car head unit,
 * a phone in a very wide split-screen pane): without a cap, Material sliders stretch
 * edge-to-edge and become awkward to use precisely. Capping width to height keeps
 * controls at a sane, thumb-friendly size and centers them instead - it also behaves
 * correctly in ordinary portrait use, multi-window, and foldable/tablet layouts, since
 * on those the height cap is rarely the binding constraint.
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val maxContentWidth = maxHeight
        Box(modifier = Modifier.widthIn(max = maxContentWidth).fillMaxSize()) {
            content()
        }
    }
}
