package me.saffer.sill.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import me.saffer.sill.android.client.Keys
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.protocol.KeyMods
import me.saffer.sill.android.protocol.StreamSource
import me.saffer.sill.android.protocol.WindowInfo
import me.saffer.sill.android.ui.input.KeyboardSink
import me.saffer.sill.android.ui.input.TrackpadView

/** What is open over the stream: at most one panel at a time, as on iOS. */
enum class Overlay { NONE, APPS, SETTINGS, MENUS, SCALE }

/** The key row's latching modifiers (⌃ ⌥ ⌘ ⇧), spent by one key, click or drag. */
class Latch {
    var mods by mutableStateOf(0L)
    fun toggle(bit: Long) { mods = mods xor bit }
    fun spend() { mods = 0L }
}

/**
 * The stream screen (StreamScreen.swift, PortraitStreamScreen.swift) in three arrangements:
 * sideways, the bar over the picture; a phone upright, the picture in a fixed 16:10 pane at the top,
 * then the buttons, the thumbnails, six keys and the trackpad; a tablet or an unfolded Fold upright,
 * the picture in the top half and the bar, a key row and the trackpad below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamScreen(client: SillClient) {
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var confirmLeave by remember { mutableStateOf(false) }
    val latch = remember { Latch() }
    var sink by remember { mutableStateOf<KeyboardSink?>(null) }
    var keyboardWanted by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (!imeVisible) keyboardWanted = false }
    // A panel's search field takes focus; once it closes, hardware keys go to the Mac again.
    LaunchedEffect(overlay, sink) { if (overlay == Overlay.NONE) sink?.requestFocus() }

    fun toggleKeyboard() {
        val s = sink ?: return
        if (keyboardWanted || imeVisible) { keyboardWanted = false; s.hide() } else { overlay = Overlay.NONE; keyboardWanted = true; s.show() }
    }
    fun open(o: Overlay) {
        if (o != Overlay.NONE && keyboardWanted) { sink?.hide(); keyboardWanted = false }
        overlay = if (overlay == o) Overlay.NONE else o
    }

    // A touch on the picture while a panel is open closes the panel and clicks nothing.
    val closePanel: () -> Boolean = { if (overlay != Overlay.NONE) { overlay = Overlay.NONE; true } else false }

    BackHandler(enabled = true) {
        when {
            overlay != Overlay.NONE -> overlay = Overlay.NONE
            else -> confirmLeave = true
        }
    }

    val bar = BarActions(
        client = client, overlay = overlay, keyboardUp = keyboardWanted || imeVisible,
        onApps = { open(Overlay.APPS) }, onSettings = { open(Overlay.SETTINGS) }, onMenus = { open(Overlay.MENUS) },
        onScale = { open(Overlay.SCALE) }, onKeyboard = { toggleKeyboard() },
        onDesktop = { client.select(StreamSource.Desktop) },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        val wide = maxWidth >= 600.dp
        // The keyboard sink: invisible, it holds the soft keyboard and hardware keys.
        AndroidView(
            factory = { ctx -> KeyboardSink(ctx, client).also { s -> sink = s; s.post { s.requestFocus() } } },
            update = { s -> s.latched = { latch.mods }; s.spendLatch = { latch.spend() } },
            modifier = Modifier.size(1.dp),
        )
        when {
            !portrait -> Column(Modifier.fillMaxSize()) {
                LandscapeBar(bar)
                VideoPanel(
                    client, Modifier.weight(1f).fillMaxWidth().then(if (imeVisible) Modifier.imePadding() else Modifier),
                    reportSize = !imeVisible, onTouched = closePanel,
                )
            }
            wide -> Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                VideoPanel(client, Modifier.weight(1f).fillMaxWidth().padding(8.dp), onTouched = closePanel)
                LandscapeBar(bar)
                KeyRow(client, latch, full = true, onKeyboard = { toggleKeyboard() }, keyboardUp = keyboardWanted || imeVisible)
                Trackpad(client, latch, Modifier.weight(1f).fillMaxWidth().padding(8.dp), span = null)
            }
            else -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).navigationBarsPadding()) {
                // The picture in a fixed 16:10 pane: nothing below it moves when windows change.
                VideoPanel(client, Modifier.fillMaxWidth().padding(8.dp).aspectRatio(1.6f), onTouched = closePanel)
                PhoneButtons(bar)
                Strip(client, Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 6.dp))
                KeyRow(client, latch, full = false, onKeyboard = { toggleKeyboard() }, keyboardUp = keyboardWanted || imeVisible)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
                    // A phone's pad measures vertical motion against its width ÷ 1.6, as the picture is.
                    Trackpad(client, latch, Modifier.fillMaxSize(), span = maxWidth / 1.6f)
                }
            }
        }

        // Panels over the stream: sideways under the bar; a phone upright under the picture, so a
        // change shows in it; a tablet upright under the picture's half. A tap outside closes.
        val panelTop = when {
            !portrait -> 56.dp
            !wide -> maxWidth / 1.6f + 8.dp
            else -> 0.dp
        }
        if (overlay != Overlay.NONE) Box(Modifier.fillMaxSize().padding(top = panelTop).background(Color(0x66000000))
            .clickable(interactionSource = null, indication = null) { overlay = Overlay.NONE })
        Box(Modifier.fillMaxSize().padding(top = panelTop)) {
            when (overlay) {
                Overlay.APPS -> AppsPanel(client, Modifier.align(Alignment.TopStart), onDone = { overlay = Overlay.NONE })
                Overlay.SETTINGS -> SettingsPanel(client, Modifier.align(Alignment.TopEnd), onDone = { overlay = Overlay.NONE }, onDisconnect = { overlay = Overlay.NONE; client.disconnect() })
                Overlay.MENUS -> MenusPanel(client, Modifier.align(Alignment.TopEnd), onDone = { overlay = Overlay.NONE })
                Overlay.SCALE -> ScalePanel(client, Modifier.align(Alignment.TopEnd), onDone = { overlay = Overlay.NONE })
                Overlay.NONE -> Unit
            }
        }

        // The Mac's notices, the reconnect, the HUD.
        client.notice?.let { n ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp, start = 24.dp, end = 24.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xE6202226)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(n, fontSize = 14.sp)
            }
        }
        val reconnecting = client.phase as? SillClient.Phase.Connecting
        AnimatedVisibility(reconnecting != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator()
                    Text(client.status ?: "Reconnecting…", fontSize = 16.sp)
                    TextButton(onClick = { client.disconnect() }) { Text("Disconnect") }
                }
            }
        }
        if (client.showHud) client.stats?.let { s ->
            Text(
                "${s.fps} fps · age ${s.frameAgeMs} ms · dec ${client.decoder.decodeLatencyMs} ms · rtt ${s.rttMs}/${s.rttMaxMs} ms · ${client.videoSize?.let { "${it.first}×${it.second}" } ?: "–"}",
                fontSize = 11.sp, color = Color(0xFFB6FFB0),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color(0x99000000)).padding(4.dp),
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Disconnect from ${client.macName}?") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; client.disconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

class BarActions(
    val client: SillClient, val overlay: Overlay, val keyboardUp: Boolean,
    val onApps: () -> Unit, val onSettings: () -> Unit, val onMenus: () -> Unit, val onScale: () -> Unit,
    val onKeyboard: () -> Unit, val onDesktop: () -> Unit,
)

/** The bar sideways and on a tablet upright: Apps, the thumbnails, Menus, Aa, Keyboard, Desktop, Settings. */
@Composable
private fun LandscapeBar(a: BarActions) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(SillColors.bar)
            .windowInsetsPadding(WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.Filled.Apps, "Apps", a.overlay == Overlay.APPS, a.onApps)
        Strip(a.client, Modifier.weight(1f).fillMaxHeight().padding(vertical = 5.dp))
        if (a.client.hasMenus) BarButton(Icons.Filled.Menu, "Menus", a.overlay == Overlay.MENUS, a.onMenus)
        ScaleButton(a.client, a.overlay == Overlay.SCALE, a.onScale)
        BarButton(Icons.Filled.Keyboard, if (a.keyboardUp) "Hide the keyboard" else "Show the keyboard", a.keyboardUp, a.onKeyboard)
        BarButton(Icons.Filled.DesktopMac, "Desktop", a.client.active == StreamSource.Desktop, a.onDesktop)
        BarButton(Icons.Filled.Tune, "Settings", a.overlay == Overlay.SETTINGS, a.onSettings)
    }
}

/** Row 1 of a phone upright: the bar's buttons, widened to the row. */
@Composable
private fun PhoneButtons(a: BarActions) {
    Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WideButton(Icons.Filled.Apps, "Apps", a.overlay == Overlay.APPS, a.onApps)
        WideButton(null, "Aa", a.overlay == Overlay.SCALE, a.onScale, label = a.client.textScale?.let { scaleLabel(it) } ?: "Aa")
        WideButton(Icons.Filled.Keyboard, "Keyboard", a.keyboardUp, a.onKeyboard)
        WideButton(Icons.Filled.DesktopMac, "Desktop", a.client.active == StreamSource.Desktop, a.onDesktop)
        if (a.client.hasMenus) WideButton(Icons.Filled.Menu, "Menus", a.overlay == Overlay.MENUS, a.onMenus)
        WideButton(Icons.Filled.Tune, "Settings", a.overlay == Overlay.SETTINGS, a.onSettings)
    }
}

@Composable
private fun RowScope.WideButton(icon: ImageVector?, description: String, lit: Boolean, onClick: () -> Unit, label: String? = null) {
    Box(
        Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(12.dp))
            .background(if (lit) SillColors.accent.copy(alpha = 0.25f) else SillColors.surfaceHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Icon(icon, description, tint = if (lit) SillColors.accent else Color.White, modifier = Modifier.size(22.dp))
            else Text(label ?: description, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = if (lit) SillColors.accent else Color.White)
            Text(description, fontSize = 10.sp, color = SillColors.secondaryText, maxLines = 1)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, lit: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).size(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (lit) SillColors.accent.copy(alpha = 0.25f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (lit) SillColors.accent else Color.White) }
}

@Composable
private fun ScaleButton(client: SillClient, lit: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).height(44.dp).width(52.dp).clip(RoundedCornerShape(10.dp))
            .background(if (lit) SillColors.accent.copy(alpha = 0.25f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(client.textScale?.let { scaleLabel(it) } ?: "Aa", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = if (lit) SillColors.accent else Color.White)
    }
}

fun scaleLabel(s: Double) = when (s) { 0.5 -> "½×"; 0.75 -> "¾×"; 1.0 -> "1×"; 1.25 -> "1¼×"; 1.5 -> "1½×"; else -> "${s}×" }

/** The windows' thumbnails, in the Mac's order; a tap streams one, a long press shows its window's buttons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Strip(client: SillClient, modifier: Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        items(client.windows, key = { it.id }) { w -> Thumb(client, w) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumb(client: SillClient, w: WindowInfo) {
    val haptics = LocalHapticFeedback.current
    var lights by remember { mutableStateOf(false) }
    val active = (client.active as? StreamSource.Window)?.id == w.id
    Box {
        Box(
            Modifier.fillMaxHeight().aspectRatio(1.45f).clip(RoundedCornerShape(8.dp))
                .border(if (active) 2.dp else 1.dp, if (active) SillColors.accent else SillColors.outline, RoundedCornerShape(8.dp))
                .background(SillColors.surfaceHigh)
                .combinedClickable(
                    onClick = { client.select(StreamSource.Window(w.id)) },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); lights = true },
                    onClickLabel = "Stream ${w.title}",
                ),
        ) {
            val thumb = client.thumbnails[w.id]
            if (thumb != null) Image(thumb, w.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(w.appName, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(4.dp))
            client.icons[w.bundleID]?.let { icon ->
                Image(icon, null, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(18.dp))
            }
        }
        DropdownMenu(expanded = lights, onDismissRequest = { lights = false }) {
            Text(w.title.ifEmpty { w.appName }, fontSize = 13.sp, color = SillColors.secondaryText, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).width(220.dp))
            DropdownMenuItem(leadingIcon = { Dot(SillColors.close) }, text = { Text("Close Window") }, onClick = { lights = false; client.windowCommand(w.id, "close") })
            DropdownMenuItem(leadingIcon = { Dot(SillColors.minimize) }, text = { Text("Minimize") }, onClick = { lights = false; client.windowCommand(w.id, "minimize") })
            DropdownMenuItem(leadingIcon = { Dot(SillColors.zoom) }, text = { Text("Full Screen") }, onClick = { lights = false; client.windowCommand(w.id, "fullScreen") })
        }
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(12.dp).clip(CircleShape).background(c))

/**
 * The key row (PortraitStreamScreen's KeyRow): esc, tab and the latching modifiers; with room, the
 * arrows, the keyboard key and, while the Desktop streams, Spotlight (⌘Space).
 */
@Composable
private fun KeyRow(client: SillClient, latch: Latch, full: Boolean, onKeyboard: () -> Unit, keyboardUp: Boolean) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Cap("esc") { client.press(Keys.ESCAPE, latch.mods); latch.spend() }
        Cap("tab") { client.press(Keys.TAB, latch.mods); latch.spend() }
        Cap("ctrl", lit = latch.mods and KeyMods.CONTROL != 0L) { latch.toggle(KeyMods.CONTROL) }
        Cap("opt", lit = latch.mods and KeyMods.OPTION != 0L) { latch.toggle(KeyMods.OPTION) }
        Cap("cmd", lit = latch.mods and KeyMods.COMMAND != 0L) { latch.toggle(KeyMods.COMMAND) }
        Cap("shift", lit = latch.mods and KeyMods.SHIFT != 0L) { latch.toggle(KeyMods.SHIFT) }
        if (full) {
            CapIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { client.press(Keys.LEFT, latch.mods); latch.spend() }
            CapIcon(Icons.Filled.KeyboardArrowDown, "Down") { client.press(Keys.DOWN, latch.mods); latch.spend() }
            CapIcon(Icons.Filled.KeyboardArrowUp, "Up") { client.press(Keys.UP, latch.mods); latch.spend() }
            CapIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { client.press(Keys.RIGHT, latch.mods); latch.spend() }
            CapIcon(Icons.Filled.Keyboard, if (keyboardUp) "Hide the keyboard" else "Show the keyboard", lit = keyboardUp, onClick = onKeyboard)
            if (client.active == StreamSource.Desktop) CapIcon(Icons.Filled.Search, "Spotlight") { client.press(Keys.SPACE, KeyMods.COMMAND); latch.spend() }
        }
    }
}

@Composable
private fun RowScope.Cap(label: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
            .background(if (lit) SillColors.accent else SillColors.surfaceHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp, color = if (lit) Color(0xFF0B1A3D) else Color.White, fontWeight = FontWeight.Medium) }
}

@Composable
private fun RowScope.CapIcon(icon: ImageVector, description: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
            .background(if (lit) SillColors.accent else SillColors.surfaceHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (lit) Color(0xFF0B1A3D) else Color.White) }
}

@Composable
private fun Trackpad(client: SillClient, latch: Latch, modifier: Modifier, span: Dp?) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    AndroidView(
        factory = { ctx -> TrackpadView(ctx, client) },
        update = { v ->
            v.verticalSpan = span?.let { with(density) { it.toPx() } }
            v.latched = { latch.mods }
            v.spendLatch = { latch.spend() }
        },
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF16171A)),
    )
}
