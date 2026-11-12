package me.saffer.sill.android

import me.saffer.sill.android.crypto.Base64Url
import me.saffer.sill.android.net.ConnectionList
import me.saffer.sill.android.net.ConnectionList.PreviewPlan
import me.saffer.sill.android.net.ConnectionList.Section
import me.saffer.sill.android.net.ConnectionList.Style
import me.saffer.sill.android.net.HomeMac
import me.saffer.sill.android.net.SavedMac
import me.saffer.sill.android.net.SavedMacs
import me.saffer.sill.android.ui.macEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** iOSClient/ConnectionList.swift's rules, against Tests/checks/connection-list's cases. */
class ConnectionListTest {
    private val C = ConnectionList

    @Test fun styleDefaultsToTilesAndAStoredChoiceWins() {
        assertEquals(Style.TILES, C.defaultStyle)
        assertEquals(Style.TILES, C.storedStyle(null))
        assertEquals(Style.TILES, C.storedStyle(""))
        assertEquals(Style.TILES, C.storedStyle("grid"))
        assertEquals(Style.TILES, C.storedStyle("List"))
        assertEquals(Style.LIST, C.storedStyle("list"))
        assertEquals(Style.TILES, C.storedStyle("tiles"))
        assertEquals(Style.TILES, Style.LIST.toggled)
        assertEquals(Style.LIST, Style.TILES.toggled)
        var shown = C.defaultStyle
        repeat(4) { shown = C.storedStyle(shown.toggled.raw) }
        assertEquals("four toggles from the default come back to it", C.defaultStyle, shown)
    }

    @Test fun picturesAreTakenAndKeptOnlyWithTilesForASavedMac() {
        for (style in Style.entries) for (fg in listOf(true, false)) for (saved in listOf(true, false)) {
            assertEquals(style == Style.TILES && fg && saved, C.takesPreview(style, fg, saved))
        }
        for (style in Style.entries) for (saved in listOf(true, false)) {
            assertEquals(style == Style.TILES && saved, C.keepsDecodedPicture(style, saved))
        }
    }

    @Test fun theListHidesThePicturesAndKeepsTheirFiles() {
        val onDisk = listOf("M1.jpg", "M2.jpg", "GONE.jpg", "notes.txt")
        val saved = setOf("M1", "M2")
        assertEquals(PreviewPlan(listOf("GONE.jpg"), listOf("M1", "M2")), C.previewPlan(Style.TILES, onDisk, saved))
        val kept = onDisk - "GONE.jpg"
        assertEquals("switched to the list: nothing shows, nothing deleted", PreviewPlan(emptyList(), emptyList()), C.previewPlan(Style.LIST, kept, saved))
        assertEquals("back to tiles: every kept picture again", PreviewPlan(emptyList(), listOf("M1", "M2")), C.previewPlan(Style.TILES, kept, saved))
        assertEquals(PreviewPlan(listOf("GONE.jpg"), emptyList()), C.previewPlan(Style.LIST, onDisk, saved))
        assertEquals(PreviewPlan(listOf("M2.jpg"), listOf("M1")), C.previewPlan(Style.TILES, listOf("M1.jpg", "M2.jpg"), setOf("M1")))
        assertEquals(listOf("M2"), C.previewPlan(Style.TILES, listOf("M1.png", "M1.jpg.jpg", ".jpg", "M2.jpg"), saved).show)
        assertEquals(listOf("M1", "M2"), C.previewPlan(Style.TILES, listOf("M2.jpg", "M1.jpg"), saved).show)
    }

    @Test fun previewFileNamesRefusePathsAndStaleFilesArePruned() {
        assertEquals("041061050R3GG28A.jpg", C.previewFileName("041061050R3GG28A"))
        for (bad in listOf("", "../secret", "a/b", "a.b", "é", "x".repeat(129))) assertNull(bad, C.previewFileName(bad))
        assertEquals(listOf("M2.jpg"), C.stalePreviewFiles(listOf("M1.jpg", "M2.jpg", "M3.jpg", "notes.txt", "M2.png"), setOf("M1", "M3")))
    }

    @Test fun columnsAndPixels() {
        assertEquals(2, C.columns(380f, false)); assertEquals(2, C.columns(340f, false))
        assertEquals(1, C.columns(339f, false)); assertEquals(1, C.columns(380f, true))
        fun px(w: Int, h: Int) = C.previewPixels(w, h)?.let { "${it.first}x${it.second}" } ?: "null"
        assertEquals("640x415", px(3024, 1964))
        assertEquals("640x360", px(1920, 1080)); assertEquals("640x360", px(6016, 3384))
        assertEquals("640x400", px(640, 400)); assertEquals("640x400", px(641, 401))
        assertEquals("500x300", px(500, 300)); assertEquals("1x1", px(1, 1))
        assertEquals("640x1137", px(1080, 1920))
        assertEquals("640x1", px(7680, 1))
        assertEquals("null", px(0, 100)); assertEquals("null", px(100, 0)); assertEquals("null", px(-5, 10))
    }

    @Test fun aBlackCaptureNeverReplacesAPicture() {
        fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
        val black = IntArray(40) { argb(0, 0, 0) }
        assertTrue(C.looksBlack(black, 8, 5))
        assertFalse(C.looksBlack(IntArray(40) { argb(96, 96, 96) }, 8, 5))
        assertTrue(C.looksBlack(IntArray(40) { argb(24, 24, 24) }, 8, 5))
        assertFalse(C.looksBlack(IntArray(40) { argb(25, 25, 25) }, 8, 5))
        assertFalse("one bright pixel, the last", C.looksBlack(black.copyOf().also { it[39] = argb(0, 0, 200) }, 8, 5))
        assertFalse(C.looksBlack(black.copyOf().also { it[0] = argb(0, 200, 0) }, 8, 5))
        assertTrue("alpha is not looked at", C.looksBlack(IntArray(40) { argb(0, 0, 0, 255) }, 8, 5))
        assertTrue("no picture counts as black", C.looksBlack(IntArray(0), 0, 5))
        assertTrue("too few pixels counts as black", C.looksBlack(IntArray(10) { argb(200, 200, 200) }, 8, 5))
    }

    @Test fun groupNamesAreCleaned() {
        assertNull(C.cleanGroup(null)); assertNull(C.cleanGroup("   \n\t "))
        assertEquals("Work Office", C.cleanGroup("  Work \n Office "))
        assertEquals(32, C.cleanGroup("A".repeat(40))!!.length)
        assertEquals("a name cut at the cap ends without a space", "B".repeat(31), C.cleanGroup("B".repeat(31) + " C"))
        assertNull(C.cleanGroup("Other")); assertNull(C.cleanGroup(" other ")); assertNull(C.cleanGroup("OTHER"))
        assertEquals("Others", C.cleanGroup("Others")); assertEquals("Other Macs", C.cleanGroup("Other Macs"))
        assertEquals("Home", C.cleanGroup("home")); assertEquals("Work", C.cleanGroup("WORK")); assertEquals("Personal", C.cleanGroup(" personal "))
        assertEquals("a surrogate pair is never cut in half", 32, C.cleanGroup("🏠".repeat(40))!!.codePointCount(0, 64))
    }

    @Test fun groupsAreStoredSetAndCarried() {
        assertTrue(C.decodeGroups(null).isEmpty()); assertTrue(C.decodeGroups("not json").isEmpty())
        var groups = C.settingGroup(" work ", "M1", emptyMap())
        groups = C.settingGroup("home", "M2", groups)
        assertEquals(mapOf("M1" to "Work", "M2" to "Home"), groups)
        assertEquals(groups, C.decodeGroups(C.encodeGroups(groups)))
        assertEquals("""{"M1":"Work","M2":"Home"}""", C.encodeGroups(mapOf("M2" to "Home", "M1" to "Work")))
        groups = C.settingGroup("  ", "M1", groups)
        assertEquals(mapOf("M2" to "Home"), groups)
        assertTrue(C.settingGroup("other", "M2", groups).isEmpty())
        assertEquals(mapOf("M2" to "Lab"), C.decodeGroups("""{"":"Work","M1":"   ","M2":"Lab","M3":"Other","M4":7}"""))
        assertEquals(mapOf("M1" to "Home Office", "M3" to "Home Office"), C.settingGroup("home office", "M3", mapOf("M1" to "Home Office")))
        assertEquals(mapOf("M1" to "Studio", "M3" to "Lab"), C.settingGroup("Lab", "M3", mapOf("M1" to "Studio")))
        assertEquals(mapOf("NEW" to "Work", "M1" to "Home"), C.carryingGroup("OLD", "NEW", mapOf("OLD" to "Work", "M1" to "Home")))
        assertEquals(mapOf("NEW" to "Lab"), C.carryingGroup("OLD", "NEW", mapOf("OLD" to "Work", "NEW" to "Lab")))
        assertEquals(mapOf("M1" to "Home"), C.carryingGroup("M1", "M1", mapOf("M1" to "Home")))
        assertEquals(mapOf("M1" to "Home"), C.keepingSaved(mapOf("M1" to "Home", "GONE" to "Work"), setOf("M1", "M2")))
    }

    @Test fun groupsComeInAStableOrder() {
        assertEquals(listOf("Home", "Work", "Personal"), C.availableGroups(emptyMap()))
        assertEquals(listOf("Home", "Work", "Personal", "Client", "Studio"), C.availableGroups(mapOf("M1" to "Studio", "M2" to "Work", "M3" to "Client")))
        assertEquals(listOf("Home", "Work", "Personal", "Attic", "lab", "Room 9", "Room 10"),
            C.availableGroups(mapOf("M1" to "lab", "M2" to "Attic", "M3" to "Room 10", "M4" to "Room 9")))
    }

    data class Row(val name: String, val macID: String?)

    @Test fun sectionsFollowTheGroups() {
        val rows = listOf(Row("A", "M1"), Row("B", "M2"), Row("C", null), Row("D", "M3"))
        assertTrue(C.sections(emptyList<Row>(), { it.macID }, mapOf("M1" to "Work")).isEmpty())
        assertEquals(listOf(Section(null, rows)), C.sections(rows, { it.macID }, emptyMap()))
        assertEquals(listOf(Section(null, rows)), C.sections(rows, { it.macID }, mapOf("OTHER" to "Work")))
        assertEquals(
            listOf(Section("Home", listOf(rows[1])), Section("Work", listOf(rows[0], rows[3])), Section("Other", listOf(rows[2]))),
            C.sections(rows, { it.macID }, mapOf("M1" to "Work", "M3" to "Work", "M2" to "Home")),
        )
        val customs = mapOf("M1" to "Studio", "M2" to "Client", "M3" to "Personal")
        assertEquals(listOf("Personal", "Client", "Studio", "Other"), C.sections(rows, { it.macID }, customs).map { it.title })
        assertEquals(listOf("Personal", "Client", "Studio", "Other"), C.sections(rows.reversed(), { it.macID }, customs).map { it.title })
        assertEquals(listOf(Section("Home", listOf(rows[0], rows[1]))), C.sections(listOf(rows[0], rows[1]), { it.macID }, mapOf("M1" to "Home", "M2" to "Home")))
    }

    @Test fun keepableAddresses() {
        for (h in listOf("192.168.1.20", "100.101.102.103", "mac.tail1234.ts.net", "fearless.ts.net", "fe80.example.com", "2001:db8::1",
            "fd7a:115c:a1e0::1", "[2001:db8::2]", "::ffff:192.168.1.2", "febz.local")) {
            assertTrue(h, SavedMacs.keepable(h, allowLoopback = false))
        }
        for (h in listOf("", "169.254.10.1", "fe80::1", "FE80::1", "fe9f::1", "febf::1", "fe80::1%wlan0", "127.0.0.1", "::1", "localhost",
            "::ffff:169.254.1.1", "[fe80::2]")) {
            assertFalse(h, SavedMacs.keepable(h, allowLoopback = false))
        }
        assertTrue(SavedMacs.keepable("127.0.0.1", allowLoopback = true))
        assertTrue(SavedMacs.keepable("::1", allowLoopback = true))
        assertTrue(SavedMacs.keepable("fec0::1", allowLoopback = false))
    }

    // MARK: the rows (ui/MacTiles.kt)

    private fun tag(key: ByteArray, nonce: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6)): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal("sill-tag-v1".toByteArray() + nonce).copyOfRange(0, 6)
        return Base64Url.encode(nonce + mac)
    }

    private fun saved(id: String, key: ByteArray?) = SavedMac(
        macID = id, fingerprint = ByteArray(32), name = "Mac $id", recognitionKey = key?.let { Base64Url.encode(it) }, remotePort = 7455,
        addresses = emptyList(), typedAddresses = listOf("mac.tail1234.ts.net"), infoIssuedAt = 0.0, lastWorked = null, pairedAt = 1.0, lastConnectedAt = null,
    )

    @Test fun rowsAreTheNetworkInItsOrderThenTheRemoteMacs() {
        val k1 = ByteArray(32) { 1 }; val k2 = ByteArray(32) { 2 }
        val m1 = saved("M1", k1); val m2 = saved("M2", k2); val m3 = saved("M3", null)
        val home = listOf(
            HomeMac("Office iMac", "192.168.1.9", 7456, HomeMac.Door.PAIRING_REQUIRED, null),
            HomeMac("Mac mini", "192.168.1.10", 7456, HomeMac.Door.PAIRING_REQUIRED, tag(k2)),
            HomeMac("Mac mini (2)", "192.168.1.11", 7456, HomeMac.Door.PAIRING_REQUIRED, tag(k2, byteArrayOf(9, 9, 9, 9, 9, 9))),
            HomeMac("Old Mac", "192.168.1.12", 7456, HomeMac.Door.PLAIN, tag(k1)),
        )
        val rows = macEntries(listOf(m1, m2, m3), home)
        assertEquals(listOf("Office iMac", "Mac M2", "Old Mac", "Mac M1", "Mac M3"), rows.map { it.name })
        assertEquals(listOf(null, "M2", null, "M1", "M3"), rows.map { it.macID })
        assertEquals(listOf("Not paired", "Wi‑Fi", "Update Sill", "Remote · Tailscale", "Remote · Tailscale"), rows.map { it.word })
        assertEquals("every key is distinct", rows.size, rows.map { it.key }.toSet().size)
        val revoked = macEntries(listOf(m3.copy(revoked = true)), emptyList()).single()
        assertEquals("Removed this device. Pair it again.", revoked.word)
        assertEquals("Remote", macEntries(listOf(m3.copy(typedAddresses = listOf("203.0.113.5:7455"))), emptyList()).single().word)
    }
}
