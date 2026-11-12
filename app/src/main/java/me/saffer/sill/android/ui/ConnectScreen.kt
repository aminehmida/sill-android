package me.saffer.sill.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.net.ConnectionList
import me.saffer.sill.android.net.ConnectionPrefs
import me.saffer.sill.android.net.MacSnapshots

/**
 * The connect screen (iOSClient/ContentView.swift's ConnectScreen, ConnectionList.swift): every Mac
 * this network lists and every saved Mac, as tiles with each Mac's screen as it was last left, or as
 * a list (the button beside Connect to a Mac; remembered). Touch and hold a saved Mac to put it in a
 * group (Home, Work, Personal, or a new one), shown as sections; the Macs in none come under Other.
 * A Mac on this Wi‑Fi this device has not paired with is a row or tile of its own ("Not paired"): a
 * tap asks it to show its code. Add a Mac, last, pairs by the Mac's QR code or by its address and
 * 12-digit code; over Tailscale the address is the one the Mac's pairing window shows.
 */
@Composable
fun ConnectScreen(client: SillClient) {
    val context = LocalContext.current
    val connecting = client.phase as? SillClient.Phase.Connecting
    val prefs = remember { ConnectionPrefs(context) }
    var style by remember { mutableStateOf(prefs.style) }
    var groups by remember { mutableStateOf(prefs.groups) }
    /** The saved Mac whose New Group dialog is up. */
    var newGroupFor by remember { mutableStateOf<String?>(null) }

    // A Mac forgotten anywhere (here, a DEBUG extra, a re-pairing) leaves no group and no picture behind.
    val savedIDs = client.savedMacs.map { it.macID }.toSet()
    LaunchedEffect(savedIDs) {
        val kept = ConnectionList.keepingSaved(groups, savedIDs)
        if (kept != groups) { groups = kept; prefs.groups = kept }
        MacSnapshots.prune(context, savedIDs)
    }

    val actions = MacMenuActions(
        groups = groups,
        style = style,
        setGroup = { g, id -> groups = ConnectionList.settingGroup(g, id, groups); prefs.groups = groups },
        newGroup = { id -> newGroupFor = id },
        toggleStyle = { style = style.toggled; prefs.style = style },
        removePicture = { id -> MacSnapshots.remove(context, id) },
        forget = { mac ->
            client.forget(mac)
            groups = ConnectionList.settingGroup(null, mac.macID, groups); prefs.groups = groups
            MacSnapshots.remove(context, mac.macID)
        },
    )
    val entries = remember(client.savedMacs, client.homeMacs) { macEntries(client.savedMacs, client.homeMacs) }
    val connectingID = if (connecting != null) client.currentMac?.macID else null
    val onTap: (MacEntry) -> Unit = { e ->
        val saved = e.saved
        val home = e.home
        if (saved != null) client.connect(saved) else if (home != null) client.tapHome(home)
    }

    Box(Modifier.fillMaxSize().background(SillColors.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Sill", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("Your Mac’s windows on this ${deviceWord(context)}.", color = SillColors.secondaryText, fontSize = 17.sp)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The switch between rows and tiles beside the title: always, whichever style is on and
                // whether or not a Mac is listed, so a choice of the list always has its way back.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Connect to a Mac", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
                    IconButton(onClick = actions.toggleStyle) {
                        val tiles = style == ConnectionList.Style.TILES
                        Icon(if (tiles) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                            contentDescription = if (tiles) "Show as list" else "Show as tiles", tint = SillColors.accent)
                    }
                }

                client.status?.let { s ->
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (connecting != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(s, color = if (connecting != null) SillColors.secondaryText else SillColors.danger, fontSize = 15.sp)
                    }
                }

                // Every Mac in its group, the groups in their order, then Other; one untitled section,
                // the flat list it always was, while no Mac has a group.
                val sections = ConnectionList.sections(entries, { it.macID }, groups)
                sections.forEach { section ->
                    section.title?.let { SectionTitle(it) }
                    if (style == ConnectionList.Style.TILES) {
                        MacTileGrid(section.items, connectingID, client.asking, actions, onTap)
                    } else {
                        MacRowCard(section.items, connectingID, client.asking, actions, onTap)
                    }
                }
            }

            AddMacCard(client, expandedAtStart = entries.isEmpty())

            Text(
                "Needs the free Sill app on your Mac, with Remote Access on (Sill › Settings › Remote Access). " +
                    "Away from home, run Tailscale on the Mac and on this device; the Mac’s pairing window shows the address to type.",
                color = SillColors.tertiaryText, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
    }

    // New Group…: a blank name, or Other (where the Macs with no group already are), names no group.
    newGroupFor?.let { macID ->
        var name by remember(macID) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { newGroupFor = null },
            title = { Text("New Group") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                TextButton(enabled = ConnectionList.cleanGroup(name) != null, onClick = { actions.setGroup(name, macID); newGroupFor = null }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { newGroupFor = null }) { Text("Cancel") } },
        )
    }

    // The Mac opened its pairing window for this device's ask: its code, over the same door.
    client.homeAsk?.let { ask ->
        var code by remember(ask) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { client.cancelHomeAsk() },
            title = { Text("Enter the Code from ${ask.found.serviceName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${ask.found.serviceName} shows a 12-digit code now.", color = SillColors.secondaryText)
                    OutlinedTextField(
                        value = code, onValueChange = { v -> code = v.filter { it.isDigit() }.take(12); client.clearPairError() },
                        label = { Text("Code") }, singleLine = true, visualTransformation = CodeGrouping,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    )
                    client.pairError?.let { Text(it, color = SillColors.danger, fontSize = 14.sp) }
                }
            },
            confirmButton = { TextButton(enabled = code.length == 12, onClick = { client.pairHomeCode(code) }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { client.cancelHomeAsk() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = SillColors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() })
}

@Composable
private fun AddMacCard(client: SillClient, expandedAtStart: Boolean) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(expandedAtStart) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SillColors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text("Add a Mac", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!expanded) Text("Pair", color = SillColors.accent)
        }
        if (!expanded) return@Column
        Text(
            "On your Mac, choose Pair iPhone or iPad… in Sill’s menu. Scan its code, or type the address and the 12-digit code it shows.",
            color = SillColors.secondaryText, fontSize = 14.sp, lineHeight = 19.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(enabled = !client.pairing, onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let { client.pairWithLink(it, address.takeIf { a -> a.isNotBlank() }) } }
                    .addOnFailureListener { client.clearPairError() }
            }) {
                Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Scan Code")
            }
            TextButton(enabled = !client.pairing, onClick = {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text != null && text.trim().startsWith("sill://")) client.pairWithLink(text, address.takeIf { it.isNotBlank() })
                else client.notice("The clipboard holds no sill:// pairing link.")
            }) {
                Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Paste Link")
            }
        }
        OutlinedTextField(
            value = address, onValueChange = { address = it.trim(); client.clearPairError() },
            label = { Text("Mac’s address") }, placeholder = { Text("mac.tail1234.ts.net") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code, onValueChange = { v -> code = v.filter { it.isDigit() }.take(12); client.clearPairError() },
            label = { Text("Code") }, placeholder = { Text("1234 5678 9012") }, singleLine = true,
            visualTransformation = CodeGrouping,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        client.pairError?.let { Text(it, color = SillColors.danger, fontSize = 14.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !client.pairing && address.isNotBlank() && code.length == 12, onClick = { client.pairWithCode(address, code) }) {
                Text("Pair")
            }
            if (client.pairing) {
                Spacer(Modifier.size(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp)); Text("Pairing…", color = SillColors.secondaryText)
            }
        }
    }
}

/** "123456789012" shown as "1234 5678 9012". */
private val CodeGrouping = androidx.compose.ui.text.input.VisualTransformation { text ->
    val raw = text.text
    val out = raw.chunked(4).joinToString(" ")
    val spaces = if (raw.isEmpty()) 0 else (raw.length - 1) / 4
    val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
        override fun originalToTransformed(offset: Int) = (offset + minOf(offset / 4, spaces)).coerceIn(0, out.length)
        override fun transformedToOriginal(offset: Int) = (offset - offset / 5).coerceIn(0, raw.length)
    }
    androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(out), mapping)
}

fun deviceWord(context: Context): String {
    val sw = context.resources.configuration.smallestScreenWidthDp
    return if (sw >= 600) "tablet" else "phone"
}
