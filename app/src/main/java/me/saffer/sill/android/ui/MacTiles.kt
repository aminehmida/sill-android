package me.saffer.sill.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saffer.sill.android.crypto.Base64Url
import me.saffer.sill.android.crypto.RecognitionTag
import me.saffer.sill.android.net.ConnectionList
import me.saffer.sill.android.net.Endpoint
import me.saffer.sill.android.net.HomeMac
import me.saffer.sill.android.net.MacSnapshots
import me.saffer.sill.android.net.SavedMac

/**
 * A row or tile of the connect screen (iOS's FoundMac): a Mac this network lists (a saved one, by
 * its recognition tag, or one this device has not paired with), or a saved Mac no row lists (Remote).
 */
internal data class MacEntry(val key: String, val name: String, val saved: SavedMac?, val home: HomeMac?) {
    val macID: String? get() = saved?.macID

    /** The word at the end of the row (iOS's RowWord): how a tap reaches the Mac, or why it cannot. */
    val word: String
        get() = when {
            saved?.revoked == true -> "Removed this device. Pair it again."
            saved != null && home != null -> "Wi‑Fi"
            saved != null -> if (saved.addresses.any { Endpoint.isTailscale(it.host) } ||
                saved.typedAddresses.any { Endpoint.isTailscale(Endpoint.parse(it, saved.remotePort)?.host ?: it) }) "Remote · Tailscale" else "Remote"
            home?.door == HomeMac.Door.PLAIN -> "Update Sill"
            else -> "Not paired"
        }
}

/**
 * The rows in iOS's order (StreamClient.recomputeMacs): every Mac the network lists, in the order it
 * listed them, a saved one as itself (by its recognition tag, never a plain door: SillClient.homeOf),
 * then the saved Macs no row lists. A saved Mac listed twice is one row.
 */
internal fun macEntries(saved: List<SavedMac>, home: List<HomeMac>): List<MacEntry> {
    val keys = saved.associate { it.macID to it.recognitionKey?.let { k -> Base64Url.decode(k) } }
    fun savedFor(h: HomeMac): SavedMac? {
        if (h.door == HomeMac.Door.PLAIN) return null
        val tag = h.tag ?: return null
        return saved.firstOrNull { m -> keys[m.macID]?.let { RecognitionTag.matches(tag, it) } == true }
    }
    val out = mutableListOf<MacEntry>()
    val listed = HashSet<String>()
    for (h in home) {
        val m = savedFor(h)
        if (m != null) {
            if (listed.add(m.macID)) out += MacEntry("home:${h.serviceName}", m.name, m, h)
        } else {
            out += MacEntry("home:${h.serviceName}", h.serviceName, null, h)
        }
    }
    for (m in saved) if (m.macID !in listed) out += MacEntry("saved:${m.macID}", m.name, m, null)
    return out
}

/** What a saved Mac's touch and hold (or its ⋯ button) offers: its group, the style, its picture, Forget. */
internal class MacMenuActions(
    val groups: Map<String, String>,
    val style: ConnectionList.Style,
    val setGroup: (String?, String) -> Unit,
    val newGroup: (String) -> Unit,
    val toggleStyle: () -> Unit,
    val removePicture: (String) -> Unit,
    val forget: (SavedMac) -> Unit,
)

/** The picture a tile shows: the Mac's kept one, read off the main thread, again whenever it changes. */
@Composable
internal fun rememberMacPicture(macID: String?, shown: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val versions by MacSnapshots.versions.collectAsState()
    val version = macID?.let { versions[it] } ?: 0
    val picture by produceState<ImageBitmap?>(null, macID, version, shown) {
        value = if (!shown || macID == null) null else withContext(Dispatchers.IO) { MacSnapshots.load(context, macID)?.asImageBitmap() }
    }
    return picture
}

/**
 * Tiles, `ConnectionList.columns` across (two from 340 dp, one at the largest text sizes), each the
 * Mac's last picture over its name and word.
 */
@Composable
internal fun MacTileGrid(
    entries: List<MacEntry>, connectingID: String?, askingName: String?, actions: MacMenuActions, onTap: (MacEntry) -> Unit,
) {
    val accessibleText = LocalDensity.current.fontScale >= 1.5f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = ConnectionList.columns(maxWidth.value, accessibleText)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.chunked(cols).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    line.forEach { e ->
                        Box(Modifier.weight(1f)) {
                            MacTile(e, connecting = e.macID != null && e.macID == connectingID,
                                highlighted = e.saved == null && e.home?.serviceName == askingName, actions = actions, onTap = { onTap(e) })
                        }
                    }
                    repeat(cols - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MacTile(e: MacEntry, connecting: Boolean, highlighted: Boolean, actions: MacMenuActions, onTap: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val picture = rememberMacPicture(e.macID, shown = actions.style == ConnectionList.Style.TILES)
    val shape = RoundedCornerShape(12.dp)
    Box {
        Column(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(if (highlighted) SillColors.accent.copy(alpha = 0.22f) else SillColors.surface)
                .border(1.dp, if (highlighted) SillColors.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f), shape)
                .combinedClickable(
                    onClickLabel = "Connect",
                    onLongClickLabel = if (e.saved != null) "Group and more" else null,
                    onLongClick = if (e.saved != null) ({ menu = true }) else null,
                    onClick = onTap,
                )
                .semantics(mergeDescendants = true) { contentDescription = "${e.name}, ${e.word}" },
        ) {
            // The band sets the size; the picture fills it, cropped, and never sizes the tile.
            Box(Modifier.fillMaxWidth().height(92.dp).background(SillColors.surfaceHigh), contentAlignment = Alignment.Center) {
                if (picture != null) {
                    Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                } else {
                    Icon(Icons.Filled.Computer, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(28.dp))
                }
                if (connecting || highlighted) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.word, color = if (e.saved?.revoked == true) SillColors.danger else SillColors.secondaryText, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        e.saved?.let { mac -> MacMenu(mac, menu, picture != null, actions) { menu = false } }
    }
}

/** The rows of a section, in one rounded card, as the list always looked. */
@Composable
internal fun MacRowCard(entries: List<MacEntry>, connectingID: String?, askingName: String?, actions: MacMenuActions, onTap: (MacEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SillColors.surface)) {
        entries.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = SillColors.outline, modifier = Modifier.padding(start = 56.dp))
            MacListRow(e, connecting = e.macID != null && e.macID == connectingID,
                asking = e.saved == null && e.home?.serviceName == askingName, actions = actions, onTap = { onTap(e) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MacListRow(e: MacEntry, connecting: Boolean, asking: Boolean, actions: MacMenuActions, onTap: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val saved = e.saved
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClickLabel = "Connect",
                onLongClickLabel = if (saved != null) "Group and more" else null,
                onLongClick = if (saved != null) ({ menu = true }) else null,
                onClick = onTap,
            )
            .padding(start = 16.dp, end = if (saved != null) 4.dp else 16.dp, top = if (saved != null) 12.dp else 14.dp, bottom = if (saved != null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Computer, null, tint = if (saved != null) SillColors.accent else SillColors.secondaryText, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(16.dp))
        if (saved != null) {
            Column(Modifier.weight(1f)) {
                Text(e.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(e.word, color = if (saved.revoked) SillColors.danger else SillColors.secondaryText, fontSize = 13.sp)
            }
            if (connecting) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More for ${e.name}", tint = SillColors.secondaryText) }
                MacMenu(saved, menu, hasPicture = false, actions) { menu = false }
            }
        } else {
            // A Mac on this Wi‑Fi this device has not paired with: a tap asks it to show its code.
            Text(e.name, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(e.word, color = SillColors.secondaryText, fontSize = 14.sp)
        }
    }
}

/**
 * A saved Mac's menu (ConnectScreen.menu(for:)): Group (None, the presets, the names in use, New
 * Group…), Show as List or Tiles, Remove Preview Picture while tiles show one, and Forget. The Group
 * choice opens in the menu's place, as a submenu.
 */
@Composable
private fun MacMenu(mac: SavedMac, expanded: Boolean, hasPicture: Boolean, actions: MacMenuActions, dismiss: () -> Unit) {
    var groupsPage by remember { mutableStateOf(false) }
    val close = { groupsPage = false; dismiss() }
    val current = ConnectionList.cleanGroup(actions.groups[mac.macID])
    DropdownMenu(expanded = expanded, onDismissRequest = close) {
        if (!groupsPage) {
            DropdownMenuItem(
                text = { Text(current?.let { "Group: $it" } ?: "Group") },
                leadingIcon = { Icon(Icons.Filled.Folder, null) },
                trailingIcon = { Text("›", color = SillColors.secondaryText, fontSize = 18.sp) },
                onClick = { groupsPage = true },
            )
            val tiles = actions.style == ConnectionList.Style.TILES
            DropdownMenuItem(
                text = { Text(if (tiles) "Show as List" else "Show as Tiles") },
                leadingIcon = { Icon(if (tiles) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, null) },
                onClick = { close(); actions.toggleStyle() },
            )
            if (tiles && hasPicture) {
                DropdownMenuItem(
                    text = { Text("Remove Preview Picture", color = SillColors.danger) },
                    leadingIcon = { Icon(Icons.Filled.HideImage, null, tint = SillColors.danger) },
                    onClick = { close(); actions.removePicture(mac.macID) },
                )
            }
            DropdownMenuItem(
                text = { Text("Forget ${mac.name}", color = SillColors.danger) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = SillColors.danger) },
                onClick = { close(); actions.forget(mac) },
            )
        } else {
            // One choice of None and the groups, the current one checked.
            DropdownMenuItem(
                text = { Text("None") },
                leadingIcon = { if (current == null) Icon(Icons.Filled.Check, "Selected") else Spacer(Modifier.size(24.dp)) },
                onClick = { close(); actions.setGroup(null, mac.macID) },
            )
            ConnectionList.availableGroups(actions.groups).forEach { g ->
                DropdownMenuItem(
                    text = { Text(g) },
                    leadingIcon = { if (current == g) Icon(Icons.Filled.Check, "Selected") else Spacer(Modifier.size(24.dp)) },
                    onClick = { close(); actions.setGroup(g, mac.macID) },
                )
            }
            HorizontalDivider(color = SillColors.outline)
            DropdownMenuItem(
                text = { Text("New Group…") },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                onClick = { close(); actions.newGroup(mac.macID) },
            )
        }
    }
}
