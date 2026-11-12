package me.saffer.sill.android.clipboard

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.saffer.sill.android.ui.SillColors

private val Pill: Shape = RoundedCornerShape(percent = 50)
private val NoteShape: Shape = RoundedCornerShape(18.dp)
private val Hairline = Color.White.copy(alpha = 0.10f)
private val WarningColor = Color(0xFFFF9F0A)
/** iOS holds the chip's words to xxLarge Dynamic Type; here the font scale to 1.3. */
private const val MAX_FONT_SCALE = 1.3f

/**
 * The clipboard's chip over the stream (iOSClient's ClipboardChip): "Paste on ‹Mac›" over where the text
 * goes ("Where the cursor is." with Paste on ‹Mac› Right Away on, else "Then press ⌘V there."), with
 * Paste and Dismiss; for a moment after a Paste, its note in the same place, over the chip. Nothing when
 * neither shows, and no touch outside its own buttons.
 *
 * Place it in the stream panel's corner (ClipboardPolicy.chipCorner), 8 dp in, only while nothing is
 * open over the stream; it reports whether the chip itself shows (`onChipShown`, ClipboardSync.chipShown),
 * which runs its 20 s and tells TalkBack the first time.
 *
 * `mac`: the Mac's name (empty: "the Mac"). `device`: "phone" or "tablet". `chip`, `note`, `rightAway`:
 * ClipboardSync's `chip`, `note` and `pasteRightAway`. `onPaste`: ClipboardSync.paste with the session's
 * ping and keys; `onDismiss`: ClipboardSync.dismissChip.
 */
@Composable
fun ClipboardChip(
    mac: String,
    device: String,
    chip: Boolean,
    note: ClipboardNote?,
    rightAway: Boolean,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    onChipShown: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val macWord = mac.ifEmpty { "the Mac" }
    val showsChip = chip && note == null
    val report by rememberUpdatedState(onChipShown)
    DisposableEffect(showsChip) {
        report(showsChip)
        onDispose { if (showsChip) report(false) }
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAX_FONT_SCALE))) {
        AnimatedContent(
            targetState = note ?: if (chip) ChipShows else null,
            modifier = modifier,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) using SizeTransform(clip = false) },
            contentAlignment = Alignment.CenterEnd,
            label = "clipboard chip",
        ) { face ->
            when (face) {
                is ClipboardNote -> NoteView(face)
                ChipShows -> ChipView(macWord, device, rightAway, onPaste, onDismiss)
                else -> Box(Modifier)
            }
        }
    }
}

private object ChipShows

@Composable
private fun ChipView(mac: String, device: String, rightAway: Boolean, onPaste: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .shadow(16.dp, Pill, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(Pill)
            .background(SillColors.bar)
            .border(1.dp, Hairline, Pill)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = SillColors.secondaryText, modifier = Modifier.size(15.dp))
        // One element for the words; Paste and Dismiss are their own.
        Column(
            Modifier.weight(1f, fill = false).clearAndSetSemantics { contentDescription = ClipboardPolicy.Copy.chipSpoken(mac, device, rightAway) },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                ClipboardPolicy.Copy.chipTitle(mac), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
            Text(ClipboardPolicy.Copy.chipSubtitle(rightAway), fontSize = 12.sp, color = SillColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ClipboardPasteButton(hint = ClipboardPolicy.Copy.pasteHint(mac, device, rightAway), onClick = onPaste)
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = ClipboardPolicy.Copy.DISMISS },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = SillColors.secondaryText, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun NoteView(note: ClipboardNote) {
    Row(
        Modifier
            .shadow(16.dp, NoteShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(NoteShape)
            .background(SillColors.bar)
            .border(1.dp, Hairline, NoteShape)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .clearAndSetSemantics { contentDescription = note.spoken },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (note.warning) Icons.Filled.Warning else Icons.Filled.CheckCircle, contentDescription = null,
            tint = if (note.warning) WarningColor else SillColors.accent, modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Text(note.text, fontSize = 13.sp, lineHeight = 17.sp, color = Color.White)
    }
}

/**
 * Paste (iOS's system Paste control, with its "Paste" and icon): for the chip and the Settings row.
 * `hint`: ClipboardPolicy.Copy.pasteHint, read by TalkBack after "Paste". `enabled`: false while the
 * device's clipboard has no text (ClipboardSync.deviceHasText), as iOS's control is disabled then.
 */
@Composable
fun ClipboardPasteButton(hint: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fg = if (enabled) Color(0xFF0B1A3D) else SillColors.tertiaryText
    Row(
        modifier
            .clip(Pill)
            .background(if (enabled) SillColors.accent else SillColors.surfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${ClipboardPolicy.Copy.PASTE}. $hint" }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(ClipboardPolicy.Copy.PASTE, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/**
 * iOS's haptic on an iPhone when a note comes: success, or warning for "Too long…" and "Didn’t
 * reach…". Phones only, Android 11 and later (CONFIRM, REJECT). Compose it once on the stream screen,
 * whatever is open, with ClipboardSync.note.
 */
@Composable
fun ClipboardHaptics(note: ClipboardNote?) {
    val view = LocalView.current
    val phone = LocalConfiguration.current.smallestScreenWidthDp < 600
    var felt by remember { mutableStateOf(note?.at) }
    LaunchedEffect(note?.at) {
        val n = note ?: return@LaunchedEffect
        if (n.at == felt) return@LaunchedEffect
        felt = n.at
        if (phone && Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(if (n.warning) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONFIRM)
        }
    }
}
