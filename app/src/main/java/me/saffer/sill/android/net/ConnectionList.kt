package me.saffer.sill.android.net

import org.json.JSONObject

/**
 * The connect screen's arrangement (rows, or tiles with each saved Mac's last picture), the groups a
 * saved Mac can be put in, and the stored pictures (iOSClient/ConnectionList.swift, issue #116).
 * Pure rules: no Android, checked by ConnectionListTest.
 */
object ConnectionList {
    enum class Style(val raw: String) {
        LIST("list"), TILES("tiles");

        val toggled: Style get() = if (this == LIST) TILES else LIST
    }

    const val STYLE_KEY = "connectionStyle"
    const val GROUPS_KEY = "macGroups"
    val presetGroups = listOf("Home", "Work", "Personal")

    /**
     * The section of the Macs with no group, once any Mac has one. Never a group's own name: a group
     * called Other would be a second section of that title.
     */
    const val OTHER_TITLE = "Other"
    const val MAX_GROUP_LENGTH = 32

    /** Two tile columns from this content width on (dp, as iOS's points). */
    const val TWO_COLUMN_MIN_WIDTH = 340f

    /**
     * The arrangement of an install whose person never chose one: tiles (iOS's default since
     * 2026-10-27). A choice made with the switch beside the title or a saved Mac's menu is stored and
     * always wins (`storedStyle`).
     */
    val defaultStyle = Style.TILES

    /** The style to show: the stored choice, "list" or "tiles", else the default (nothing stored, or a value no build writes). */
    fun storedStyle(raw: String?): Style = Style.entries.firstOrNull { it.raw == raw } ?: defaultStyle

    /**
     * Whether a session's end keeps a picture of the Mac's screen for its tile: only while tiles are
     * on (the list hides the pictures and keeps their files), only from the app on screen, and only
     * for a saved Mac (only its tile shows a picture).
     */
    fun takesPreview(style: Style, foreground: Boolean, saved: Boolean): Boolean = style == Style.TILES && foreground && saved

    /** Whether a picture taken as a session ended is kept when it lands: tiles still on, the Mac still saved. */
    fun keepsDecodedPicture(style: Style, saved: Boolean): Boolean = style == Style.TILES && saved

    /** What a launch or a change of style does to the tiles' pictures (`previewPlan`). */
    data class PreviewPlan(
        /** File names to delete: only those of Macs no longer saved. */
        val delete: List<String>,
        /** The saved Macs whose kept picture shows, in order. */
        val show: List<String>,
    )

    /**
     * A style never deletes a picture: with tiles every saved Mac with a file shows its picture; with
     * the list none shows, and every file stays for when tiles come back. Either way the files of Macs
     * no longer saved go. `files`: the names in the pictures' folder.
     */
    fun previewPlan(style: Style, files: List<String>, savedMacIDs: Set<String>): PreviewPlan {
        val delete = stalePreviewFiles(files, savedMacIDs)
        if (style != Style.TILES) return PreviewPlan(delete, emptyList())
        val show = files.filter { f ->
            val id = f.removeSuffix(".jpg")
            f.endsWith(".jpg") && id in savedMacIDs && previewFileName(id) == f
        }.map { it.removeSuffix(".jpg") }.sorted()
        return PreviewPlan(delete, show)
    }

    // MARK: The tile's picture

    /** The picture's width at most, in points, and its scale: 640 pixels at most, as the tile shows it. */
    const val PREVIEW_POINTS = 320
    const val PREVIEW_SCALE = 2

    /**
     * The picture's size in pixels for a video of `width` × `height` pixels: at most 640 wide, never
     * wider than the video, in its shape, the height rounded down (at least a pixel). Null for no video.
     */
    fun previewPixels(width: Int, height: Int): Pair<Int, Int>? {
        if (width < 1 || height < 1) return null
        val w = minOf(PREVIEW_POINTS * PREVIEW_SCALE, width)
        val h = maxOf(1, (height.toLong() * w / width).toInt())
        return w to h
    }

    /** The brightest a colour channel may be in a picture that counts as black. */
    const val BLACK_LEVEL = 24

    /**
     * Whether a picture is black: no pixel with a colour channel brighter than `BLACK_LEVEL`. A Mac's
     * screen always has something brighter (the menu bar, a window's text); a capture that came out
     * black (the surface before its first frame) must never replace a good picture. `pixels`: ARGB, as
     * Bitmap.getPixels gives them, `width` × `height` (alpha is not looked at).
     */
    fun looksBlack(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return true
        for (i in 0 until width * height) {
            val p = pixels[i]
            if ((p shr 16 and 0xFF) > BLACK_LEVEL || (p shr 8 and 0xFF) > BLACK_LEVEL || (p and 0xFF) > BLACK_LEVEL) return false
        }
        return true
    }

    /**
     * Safe file name for a saved Mac's picture (`<macID>.jpg`); null for anything that could traverse
     * paths or is not a valid identifier.
     */
    fun previewFileName(macID: String): String? {
        if (macID.isEmpty() || macID.length > 128) return null
        if (!macID.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) return null
        return "$macID.jpg"
    }

    /** `.jpg` names in the pictures' folder whose Mac ID is not in `keepingMacIDs`. */
    fun stalePreviewFiles(files: List<String>, keepingMacIDs: Set<String>): List<String> =
        files.filter { it.endsWith(".jpg") && it.removeSuffix(".jpg") !in keepingMacIDs }

    /** How many tile columns fit `widthDp`: 2 from 340 dp on, 1 on narrower screens or at the largest text sizes. */
    fun columns(widthDp: Float, accessibleText: Boolean): Int = if (!accessibleText && widthDp >= TWO_COLUMN_MIN_WIDTH) 2 else 1

    // MARK: Groups

    /**
     * Cleans a group name: whitespace collapsed to single spaces, at most `MAX_GROUP_LENGTH`
     * characters; a preset's name in any case is the preset ("work" is Work); null when blank or Other
     * in any case (where the Macs with no group already are).
     */
    fun cleanGroup(raw: String?): String? {
        raw ?: return null
        val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val joined = words.joinToString(" ")
        val name = takeCodePoints(joined, MAX_GROUP_LENGTH).trim()
        if (name.isEmpty() || name.equals(OTHER_TITLE, ignoreCase = true)) return null
        return presetGroups.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
    }

    private fun takeCodePoints(s: String, n: Int): String {
        if (s.codePointCount(0, s.length) <= n) return s
        return s.substring(0, s.offsetByCodePoints(0, n))
    }

    fun decodeGroups(text: String?): Map<String, String> {
        val o = runCatching { JSONObject(text ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (macID in o.keys()) {
            if (macID.isEmpty() || macID.length > 128) continue
            val group = o.opt(macID) as? String ?: continue
            cleanGroup(group)?.let { out[macID] = it }
        }
        return out
    }

    fun encodeGroups(groups: Map<String, String>): String {
        val o = JSONObject()
        for ((k, v) in groups.toSortedMap()) o.put(k, v)
        return o.toString()
    }

    /**
     * Puts `macID` in `group`, or in none for null, blank or Other. A name already in use in another
     * case ("home office" beside "Home Office") is that group, so one name never makes two sections.
     */
    fun settingGroup(group: String?, macID: String, groups: Map<String, String>): Map<String, String> {
        if (macID.isEmpty() || macID.length > 128) return groups
        val next = LinkedHashMap(groups)
        val clean = cleanGroup(group)
        if (clean != null) {
            next[macID] = groups.values.filter { it.equals(clean, ignoreCase = true) }.minOrNull() ?: clean
        } else {
            next.remove(macID)
        }
        return next
    }

    /** A saved record retired for a newer one of the same Mac gives its group to that one, unless it has its own. */
    fun carryingGroup(old: String, new: String, groups: Map<String, String>): Map<String, String> {
        if (old == new) return groups
        val group = groups[old] ?: return groups
        val next = LinkedHashMap(groups)
        next.remove(old)
        if (next[new] == null) next[new] = group
        return next
    }

    /** Only the groups of Macs still saved. */
    fun keepingSaved(groups: Map<String, String>, savedMacIDs: Set<String>): Map<String, String> =
        groups.filterKeys { it in savedMacIDs }

    /**
     * The order groups come in, in the Group menu and as sections, whatever order the Macs are listed
     * in (so a section never moves as Macs come and go): the presets in their order, then other names
     * as Finder sorts them (case-insensitive, numbers by value).
     */
    fun ordered(names: Set<String>): List<String> =
        presetGroups.filter { it in names } + (names - presetGroups.toSet()).sortedWith { a, b ->
            val order = finderCompare(a, b)
            if (order == 0) a.compareTo(b) else order
        }

    /** The Group menu's names: every preset, then the other names in use. */
    fun availableGroups(groups: Map<String, String>): List<String> =
        ordered(presetGroups.toSet() + groups.values.mapNotNull { cleanGroup(it) })

    data class Section<T>(val title: String?, val items: List<T>)

    /**
     * Groups `items` by each item's Mac ID in `groups`: the groups in `ordered`'s order, each keeping
     * the items' own order, then Other for the rest. When no item has a group, a single section with
     * no title, so the connect screen has no header and is the flat list it always was.
     */
    fun <T> sections(items: List<T>, macID: (T) -> String?, groups: Map<String, String>): List<Section<T>> {
        if (items.isEmpty()) return emptyList()
        val byGroup = LinkedHashMap<String, MutableList<T>>()
        val ungrouped = mutableListOf<T>()
        for (item in items) {
            val g = macID(item)?.let { cleanGroup(groups[it]) }
            if (g != null) byGroup.getOrPut(g) { mutableListOf() }.add(item) else ungrouped.add(item)
        }
        if (byGroup.isEmpty()) return listOf(Section(null, items))
        val out = ordered(byGroup.keys).mapNotNull { name -> byGroup[name]?.let { Section<T>(name, it) } }.toMutableList()
        if (ungrouped.isNotEmpty()) out.add(Section(OTHER_TITLE, ungrouped))
        return out
    }

    /** Finder's order (localizedStandardCompare's spirit): runs of digits by value, the rest ignoring case. */
    internal fun finderCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i; val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0'); val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length.compareTo(nb.length)
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
