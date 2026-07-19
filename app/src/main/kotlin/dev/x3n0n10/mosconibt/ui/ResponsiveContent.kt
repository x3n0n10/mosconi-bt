package dev.x3n0n10.mosconibt.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A fixed cap rather than an aspect-ratio-derived one (e.g. "width capped to height")
 * deliberately: the latter can end up narrower than a phone in portrait on a screen
 * that's both short and phone-density (a real car head unit, or the Paparazzi configs
 * used to test this), which is worse than not capping at all. 520dp comfortably fits
 * every label/button this app has with room to spare, without being so wide that
 * sliders on a larger screen become as imprecise as an uncapped edge-to-edge layout.
 */
private val MAX_CONTENT_WIDTH = 520.dp

/**
 * Centers [content] in a column whose width never exceeds [MAX_CONTENT_WIDTH].
 *
 * This targets displays that are much wider than they are tall (a fixed car head unit,
 * a phone in a very wide split-screen pane): without a cap, Material sliders stretch
 * edge-to-edge and become awkward to use precisely. It's a no-op on ordinary portrait,
 * multi-window, and foldable/tablet layouts narrower than the cap.
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize()) {
            content()
        }
    }
}
