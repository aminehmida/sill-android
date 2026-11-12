package me.saffer.sill.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.saffer.sill.android.client.MenuState
import me.saffer.sill.android.client.SillClient
import androidx.compose.ui.semantics.clearAndSetSemantics
import me.saffer.sill.android.clipboard.ClipboardPasteButton
import me.saffer.sill.android.clipboard.ClipboardPolicy
import me.saffer.sill.android.clipboard.ClipboardSync
import me.saffer.sill.android.protocol.LockedMac
import me.saffer.sill.android.protocol.MacMenuItem
import me.saffer.sill.android.protocol.TextScale
import me.saffer.sill.android.protocol.QualityPreset
import me.saffer.sill.android.protocol.SettingsChange
import me.saffer.sill.android.protocol.StreamSource
import me.saffer.sill.android.video.VideoDecoder

@Composable
private fun PanelFrame(modifier: Modifier, title: String, onDone: () -> Unit, back: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(8.dp).widthIn(max = 380.dp).fillMaxWidth().heightIn(max = 640.dp)
            .clip(RoundedCornerShape(18.dp)).background(Color(0xFF1C1D20))
            .clickable(interactionSource = null, indication = null) {},   // taps inside never reach the scrim
    ) {
        Row(Modifier.fillMaxWidth().padding(start = if (back != null) 4.dp else 18.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done") }
        }
        content()
    }
}

/** The Apps drawer: the Mac's windows and the Desktop, then every installed app (launching one streams its first window). */
@Composable
fun AppsPanel(client: SillClient, modifier: Modifier, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    PanelFrame(modifier, "Apps", onDone) {
        val why = client.lockWhy
        if (why != null && !client.offConsoleView) {
            // A locked Mac's list has no windows and no apps: say why instead (LockedMac.line).
            Text(LockedMac.line(client.macName, why), fontSize = 14.sp, color = SillColors.secondaryText,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
            return@PanelFrame
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            if (client.offConsoleView) item {
                Text(LockedMac.VIEW_PANEL_LINE, fontSize = 13.sp, color = SillColors.secondaryText,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { client.select(StreamSource.Desktop); onDone() }.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DesktopMac, null, Modifier.size(28.dp), tint = SillColors.accent)
                    Spacer(Modifier.size(14.dp))
                    Text("Desktop", fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (client.active == StreamSource.Desktop) Icon(Icons.Filled.Check, "Streaming", tint = SillColors.accent)
                }
            }
            if (client.windows.isNotEmpty()) item { Header("Open now") }
            items(client.windows, key = { "w${it.id}" }) { w ->
                Row(Modifier.fillMaxWidth().clickable { client.select(StreamSource.Window(w.id)); onDone() }.padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(client, w.bundleID)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.title.ifEmpty { w.appName }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(w.appName, fontSize = 12.sp, color = SillColors.secondaryText, maxLines = 1)
                    }
                    if ((client.active as? StreamSource.Window)?.id == w.id) Icon(Icons.Filled.Check, "Streaming", tint = SillColors.accent)
                }
            }
            if (client.apps.isNotEmpty()) {
                item { Header("All apps") }
                item {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
                }
                val shown = client.apps.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                items(shown, key = { "a${it.bundleID}" }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { client.launch(a); onDone() }.padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(client, a.bundleID)
                        Spacer(Modifier.size(14.dp))
                        Text(a.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(client: SillClient, bundleID: String) {
    val icon = client.icons[bundleID]
    if (icon != null) Image(icon, null, Modifier.size(30.dp))
    else Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(SillColors.surfaceHigh))
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SillColors.secondaryText, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp))
}

/**
 * The Settings panel (HostSettingsPanel.swift): the Mac's streaming settings as its menu shows them
 * (only a control's action ever sends, kind 17), this device's own, and Disconnect.
 */
@Composable
fun SettingsPanel(client: SillClient, modifier: Modifier, onDone: () -> Unit, onDisconnect: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = client.settingsRevision
    val s = client.shownSettings
    val state = client.hostState
    PanelFrame(modifier, client.macName, onDone) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val running = state?.stream
            Text(
                listOfNotNull(
                    running?.let { "${it.width}×${it.height}" }, running?.let { "${it.fps} fps" }, running?.let { "${it.mbps} Mbps" },
                    client.route, client.stats?.rttMs?.takeIf { it >= 0 }?.let { "$it ms" },
                ).joinToString(" · ").ifEmpty { "Connected" },
                fontSize = 13.sp, color = SillColors.secondaryText,
            )
            val why = client.lockWhy
            if (client.offConsoleView) Note(LockedMac.VIEW_PANEL_LINE)
            else if (why != null) Text(LockedMac.line(client.macName, why), fontSize = 13.sp, color = SillColors.accent)
            state?.away?.let { a ->
                Text(
                    if (a.awayRunning) "Away: ${QualityPreset.shortTitle(a.awayBitrate, a.awayCaptureScale)}"
                    else if (a.thisConnectionAway) "Home quality: ${QualityPreset.shortTitle(a.homeBitrate, a.homeCaptureScale)} (a device at home is connected)"
                    else "Home quality",
                    fontSize = 13.sp, color = SillColors.accent,
                )
            }
            me.saffer.sill.android.protocol.AwayCopy.callout(state?.link, state?.away, client.macName)?.let { c ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x33FFB020)).padding(12.dp)) {
                    Text(c.text, fontSize = 14.sp)
                    if (c.button != null && c.change != null) TextButton(onClick = { client.changeSettings(c.change) }) { Text(c.button) }
                }
            }
            if (state?.softwareEncoder == true) Note("${client.macName}’s hardware encoder is busy or not responding, so for now streams run at up to 60 fps at Standard.")
            if (s == null) {
                Text(if (client.hasSettings) "" else "Update Sill on ${client.macName} to change its settings from here.", fontSize = 14.sp, color = SillColors.secondaryText)
            } else {
                Group("Quality")
                QualityPreset.entries.forEach { p ->
                    Choice(p.title + " — ${p.bitrate / 1_000_000} Mbps", s.bitrate == p.bitrate, client.ledger.isPending(me.saffer.sill.android.client.SettingsLedger.Field.BITRATE) && s.bitrate == p.bitrate) {
                        client.changeSettings(SettingsChange(bitrate = p.bitrate))
                    }
                }
                if (QualityPreset.entries.none { it.bitrate == s.bitrate }) Text("Custom — ${QualityPreset.mbps(s.bitrate)} Mbps", fontSize = 14.sp, color = SillColors.secondaryText)
                Note("Ultra and Extreme need very fast Wi‑Fi; if the picture lags, step down.")
                Group("Resolution")
                Choice("Retina", s.captureScale >= 1.5) { client.changeSettings(SettingsChange(captureScale = 2.0)) }
                Choice("Standard", s.captureScale < 1.5) { client.changeSettings(SettingsChange(captureScale = 1.0)) }
                VideoDecoder.largestHardwareSize()?.let { (w, h) -> Note("This device decodes up to ${w}×$h; a larger stream drops to Standard by itself.") }
                Group("Frame Rate")
                Choice("60 fps", s.maxFPS == 60) { client.changeSettings(SettingsChange(maxFPS = 60)) }
                Choice("120 fps", s.maxFPS == 120) { client.changeSettings(SettingsChange(maxFPS = 120)) }
                Group(" ")
                Toggle("Prioritize Encoding Speed", s.prioritizeSpeed) { client.changeSettings(SettingsChange(prioritizeSpeed = it)) }
                Toggle("Virtual Display", s.virtualDisplay, enabled = s.virtualDisplay || state?.virtualDisplayAvailable == true) {
                    client.changeSettings(SettingsChange(virtualDisplay = it))
                }
                state?.virtualDisplayNote?.let { Note(it) }
                s.directWireless?.let { dw ->
                    // Every device sees it; only a paired one at home changes it (HostSettingsState.sessionPaired).
                    val canChange = client.sessionPaired != false && state?.away?.thisConnectionAway != true
                    Toggle("Direct Wireless Connection", dw, enabled = canChange) { client.changeSettings(SettingsChange(directWireless = it)) }
                    if (!canChange) Note("Only a paired device at home can change this.")
                }
                Group("Sound")
                if (s.sendAudio == null) {
                    Toggle("Send Audio (Beta)", false, enabled = false) {}
                    Note("Update Sill on ${client.macName} to hear its sound here.")
                } else {
                    Toggle("Send Audio (Beta)", s.sendAudio, pending = client.ledger.isPending(me.saffer.sill.android.client.SettingsLedger.Field.SEND_AUDIO)) {
                        client.changeSettings(SettingsChange(sendAudio = it))
                    }
                    Note("Plays the sound of the window you stream, or of every app for the Desktop, on every connected device.")
                    state?.audioNote?.takeIf { s.sendAudio }?.let { Note(it) }
                    if (client.sound == null || client.sound?.codecs?.isEmpty() == true) Note("This device can’t play ${client.macName}’s sound.")
                }
                if (state?.persistent == false) Note("Changes last until SillHost quits.")
            }
            val word = deviceWord(androidx.compose.ui.platform.LocalContext.current)
            Group("This $word")
            if (client.macHasSound) {
                Toggle("Mute", client.soundMuted) { client.changeSoundMuted(it) }
                Note("Silences ${client.macName}’s sound on this $word only.")
            }
            // The clipboard (docs/clipboard-plan.md): only while the Mac shares it, as on iOS.
            val clipboard = client.clipboard as? ClipboardSync
            if (clipboard != null && clipboard.shared) {
                val mac = client.macName
                val on = client.shareClipboard
                Toggle(ClipboardPolicy.Copy.SWITCH_TITLE, on) { client.changeShareClipboard(it) }
                if (on) {
                    Toggle(ClipboardPolicy.Copy.rightAwayTitle(mac), clipboard.pasteRightAway) { clipboard.changePasteRightAway(it) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ClipboardPolicy.Copy.rowTitle(mac), fontSize = 15.sp, modifier = Modifier.weight(1f))
                        ClipboardPasteButton(
                            hint = ClipboardPolicy.Copy.pasteHint(mac, word, clipboard.pasteRightAway),
                            onClick = { client.pasteClipboard() }, enabled = clipboard.deviceHasText,
                        )
                    }
                    // Announced as it came (ClipboardSync), so hidden from TalkBack here.
                    clipboard.note?.let { n ->
                        Text(n.text, fontSize = 12.sp, lineHeight = 16.sp, color = if (n.warning) Color(0xFFFF9F0A) else SillColors.accent,
                            modifier = Modifier.clearAndSetSemantics { })
                    }
                }
                Note(ClipboardPolicy.Copy.footnote(on, clipboard.pasteRightAway, mac, word))
            }
            Toggle("Three-Finger Gestures", client.gesturesEnabled) { client.setGestures(it) }
            if (client.hostGestures < 1) Note("Update Sill on ${client.macName} for Mission Control, Spaces and more with three fingers.")
            else Note("Swipe up: Mission Control. Down: App Exposé. Left and right: Spaces. Pinch: Apps. Spread: Desktop.")
            Toggle("Reverse Scrolling", client.reverseScroll) { client.changeReverseScroll(it) }
            Note("A finger dragged over the picture, or two fingers on the trackpad below it, scroll the other way. A connected mouse or trackpad keeps the system’s own setting.")
            HoldStepper(client.holdToShowControls) { client.changeHoldToShowControls(it) }
            if (word == "phone") {
                Toggle("Larger Picture", client.largerPicture) { client.changeLargerPicture(it) }
                Note("Held upright, a window’s picture takes the place of the window thumbnails and reaches down to where the keyboard comes up.")
            }
            Toggle("Show Stream Stats", client.showHud) { client.setHud(it) }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF6B5E), contentColor = SillColors.danger)) {
                Text("Disconnect")
            }
        }
    }
}

@Composable private fun Group(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SillColors.secondaryText, modifier = Modifier.padding(top = 12.dp))
@Composable private fun Note(t: String) = Text(t, fontSize = 12.sp, color = SillColors.tertiaryText, lineHeight = 16.sp)

@Composable
private fun Choice(label: String, selected: Boolean, pending: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (pending) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, enabled: Boolean = true, pending: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f), color = if (enabled) Color.White else SillColors.tertiaryText)
        if (pending) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(14.dp), strokeWidth = 2.dp)
        Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Hold to Show Window Controls (ThumbnailHold): 0.2 to 2 s, a tenth at a time. */
@Composable
private fun HoldStepper(seconds: Double, onChange: (Double) -> Unit) {
    val tenths = Math.round(seconds * 10).toInt().coerceIn(2, 20)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Hold to Show Window Controls", fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(String.format(java.util.Locale.ROOT, "%.1f s", tenths / 10.0), fontSize = 15.sp, color = SillColors.secondaryText)
        TextButton(onClick = { onChange((tenths - 1).coerceAtLeast(2) / 10.0) }, enabled = tenths > 2) { Text("−", fontSize = 20.sp) }
        TextButton(onClick = { onChange((tenths + 1).coerceAtMost(20) / 10.0) }, enabled = tenths < 20) { Text("+", fontSize = 20.sp) }
    }
}

/** The Aa control: how large the Mac renders a window's text here (Viewport.scale), or leave the window alone. */
@Composable
fun ScalePanel(client: SillClient, modifier: Modifier, onDone: () -> Unit) {
    // TextScale: Off (the Mac leaves the window alone) is offered only where no scale leaves it alone,
    // which is everywhere but a window on the Mac's virtual display, where a window always fits.
    val offered = offersOff(client)
    val detents = TextScale.detents(offered)
    val position = TextScale.position(client.textScale, offered)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = TextScale.snap((dragging ?: position.toFloat()).toDouble(), offered)
    PanelFrame(modifier, "Text Size", onDone) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 14.dp)) {
            Note(
                if (offered) "Sizes the Mac window to this screen so its text shows at this scale. Off leaves the window as it is."
                else "Sizes the window on Sill’s display so its text shows at this scale.",
            )
            Text(TextScale.title(shown), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            androidx.compose.material3.Slider(
                value = (dragging ?: position.toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val p = TextScale.snap((dragging ?: position.toFloat()).toDouble(), offered)
                    dragging = null
                    client.chooseTextScale(TextScale.scaleAt(p))
                },
                valueRange = detents.first().toFloat()..detents.last().toFloat(),
                steps = detents.size - 2,
            )
            Row(Modifier.fillMaxWidth()) {
                detents.forEachIndexed { i, d ->
                    Text(TextScale.title(d), fontSize = 11.sp, color = SillColors.secondaryText,
                        textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; detents.lastIndex -> androidx.compose.ui.text.style.TextAlign.End; else -> androidx.compose.ui.text.style.TextAlign.Center },
                        modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The Mac's menus (MacMenuButton): the streamed app's menu bar, each menu read from the Mac as it
 * opens; an item chosen here is pressed there.
 */
@Composable
fun MenusPanel(client: SillClient, modifier: Modifier, onDone: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = client.menuRevision
    val path = remember { mutableStateListOf<MacMenuItem>() }
    val state = client.menuState
    val current = path.lastOrNull()
    PanelFrame(modifier, current?.title ?: (state.app ?: "Menus"), onDone, back = if (path.isEmpty()) null else ({ path.removeAt(path.lastIndex) })) {
        val opened = current?.id?.let { state.menus[it] }
        // A menu this panel shows that the client no longer holds (a reconnect): ask for it again.
        androidx.compose.runtime.LaunchedEffect(current, opened == null) { if (current != null && opened == null) client.openMenu(current) }
        val items = opened as? MenuState.Menu.Items
        val rows: List<MacMenuItem>? = if (current == null) state.top else items?.items
        val note = if (current == null) state.topNote else (opened as? MenuState.Menu.Failed)?.why ?: items?.note
        // A stale read (the app did not answer in time): shown, never chosen from.
        val stale = if (current == null) state.topStale else items?.stale == true
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            note?.let { n -> item { Text(n, fontSize = 13.sp, color = SillColors.secondaryText, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) } }
            if (rows == null && note == null) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
            }
            items(rows ?: emptyList()) { item ->
                if (item.separator) { HorizontalDivider(color = SillColors.outline, modifier = Modifier.padding(vertical = 4.dp)); return@items }
                val enabled = item.enabled && !(stale && current != null && !item.submenu)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = enabled || current == null) {
                        if (current == null || item.submenu) { path.add(item); client.openMenu(item) }
                        else { client.chooseMenuItem(item); onDone() }
                    }.padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(22.dp)) { item.mark?.let { Text(it, color = SillColors.accent, fontSize = 14.sp) } }
                    Text(item.title ?: "", fontSize = 15.sp, color = if (enabled) Color.White else SillColors.tertiaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    item.key?.let { Text(it, fontSize = 13.sp, color = SillColors.secondaryText) }
                    if (current == null || item.submenu) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SillColors.secondaryText)
                }
            }
            (current?.id?.let { state.menus[it] } as? MenuState.Menu.Items)?.more?.let { more ->
                item { Note("…and $more more on the Mac.") }
            }
        }
    }
}
