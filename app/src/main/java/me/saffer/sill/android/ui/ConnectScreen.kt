package me.saffer.sill.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.net.Endpoint
import me.saffer.sill.android.net.SavedMac

/**
 * The connect screen (iOSClient/ContentView.swift): the Macs this device paired with, and Add a Mac,
 * which pairs by the Mac's QR code or by its address and 12-digit code. Over Tailscale the address is
 * the one the Mac's pairing window shows (its MagicDNS name, or its 100.x address).
 */
@Composable
fun ConnectScreen(client: SillClient) {
    val context = LocalContext.current
    val connecting = client.phase as? SillClient.Phase.Connecting
    Box(Modifier.fillMaxSize().background(SillColors.background).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Sill", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("Your Mac’s windows on this ${deviceWord(context)}.", color = SillColors.secondaryText, fontSize = 17.sp)

            client.status?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (connecting != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(s, color = if (connecting != null) SillColors.secondaryText else SillColors.danger, fontSize = 15.sp)
                }
            }

            if (client.savedMacs.isNotEmpty()) {
                SectionTitle("Your Macs")
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(SillColors.surface)) {
                    client.savedMacs.forEachIndexed { i, mac ->
                        if (i > 0) HorizontalDivider(color = SillColors.outline, modifier = Modifier.padding(start = 56.dp))
                        MacRow(mac, connecting?.macName == mac.name && client.currentMac?.macID == mac.macID, home = client.homeOf(mac) != null,
                            onTap = { client.connect(mac) }, onForget = { client.forget(mac) })
                    }
                }
            }

            // Macs on this Wi‑Fi this device has not paired with: a tap asks the Mac to show its code.
            val nearby = client.unpairedHomeMacs
            if (nearby.isNotEmpty()) {
                SectionTitle("On this network")
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(SillColors.surface)) {
                    nearby.forEachIndexed { i, h ->
                        if (i > 0) HorizontalDivider(color = SillColors.outline, modifier = Modifier.padding(start = 56.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable { client.tapHome(h) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Computer, null, tint = SillColors.secondaryText, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.size(16.dp))
                            Text(h.serviceName, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (client.asking == h.serviceName) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(
                                when (h.door) {
                                    me.saffer.sill.android.net.HomeMac.Door.PLAIN -> "Update Sill"
                                    else -> "Not paired"
                                },
                                color = SillColors.secondaryText, fontSize = 14.sp,
                            )
                        }
                    }
                }
            }

            AddMacCard(client, expandedAtStart = client.savedMacs.isEmpty() && nearby.isEmpty())

            Text(
                "Needs the free Sill app on your Mac, with Remote Access on (Sill › Settings › Remote Access). " +
                    "Away from home, run Tailscale on the Mac and on this device; the Mac’s pairing window shows the address to type.",
                color = SillColors.tertiaryText, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
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
    Text(text, color = SillColors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun MacRow(mac: SavedMac, connecting: Boolean, home: Boolean, onTap: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Computer, null, tint = SillColors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(mac.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            val via = when {
                mac.revoked -> "Removed this device. Pair it again."
                home -> "Wi‑Fi"
                mac.addresses.any { Endpoint.isTailscale(it.host) } || mac.typedAddresses.any { Endpoint.isTailscale(it.substringBefore(':')) } -> "Remote · Tailscale"
                else -> "Remote"
            }
            Text(via, color = if (mac.revoked) SillColors.danger else SillColors.secondaryText, fontSize = 13.sp)
        }
        if (connecting) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More for ${mac.name}", tint = SillColors.secondaryText) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Connect") }, onClick = { menu = false; onTap() })
                DropdownMenuItem(text = { Text("Forget This Mac", color = SillColors.danger) }, onClick = { menu = false; onForget() })
            }
        }
    }
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
