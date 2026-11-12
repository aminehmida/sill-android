package me.saffer.sill.android.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Each saved Mac's last picture, for its tile on the connect screen (StreamClient's savePreview,
 * removePreview and pruneSavedPreviews; ConnectionList's rules): a JPEG of at most 640 pixels wide
 * per Mac ID in the app's files (`previews/<macID>.jpg`; the app allows no backup), written when a
 * session the person was looking at ends with tiles on, deleted when the Mac is forgotten, Remove
 * Preview Picture is chosen, or the Mac is no longer saved. The list hides the pictures and keeps
 * their files, so switching back to tiles shows them again at once.
 *
 * Files are read and written on one background thread, in order; `load` blocks (call it off the main
 * thread). `versions` changes for a Mac whenever its picture does, for the screen to load it again.
 */
object MacSnapshots {
    private const val TAG = "SillTiles"
    private const val JPEG_QUALITY = 72

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sill-snapshots").apply { isDaemon = true } }
    /** The pictures read or written this launch, downscaled. */
    private val memory = ConcurrentHashMap<String, Bitmap>()
    private val _versions = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** A number per Mac ID that changes whenever that Mac's picture does (saved, removed). */
    val versions: StateFlow<Map<String, Int>> = _versions

    private fun dir(context: Context) = File(context.applicationContext.filesDir, "previews")

    /**
     * A session with `macID` ended (a frame of its stream, from PixelCopy): its tile's picture. Any
     * thread; returns at once, having taken its own downscaled copy, so the caller may recycle
     * `bitmap`. Kept only while tiles are on and the Mac is still saved (ConnectionList
     * .keepsDecodedPicture), and never a black picture (a surface before its first frame), which must
     * never replace a good one. The caller takes it only from a session the person was looking at
     * (ConnectionList.takesPreview: the app on screen).
     */
    fun save(context: Context, macID: String, bitmap: Bitmap) {
        val app = context.applicationContext
        val name = ConnectionList.previewFileName(macID) ?: return
        if (bitmap.isRecycled) return
        val (w, h) = ConnectionList.previewPixels(bitmap.width, bitmap.height) ?: return
        val small = runCatching {
            val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            Bitmap.createScaledBitmap(src, w, h, true).let { if (it === bitmap) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        }.getOrElse { Log.w(TAG, "the picture of $macID could not be scaled: ${it.message}"); return }
        io.execute {
            val style = ConnectionPrefs(app).style
            if (!ConnectionList.keepsDecodedPicture(style, saved = SavedMacs(app).byID(macID) != null)) {
                Log.i(TAG, "the picture of $macID not kept ($style, ${if (SavedMacs(app).byID(macID) != null) "saved" else "not saved"})")
                return@execute
            }
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            if (ConnectionList.looksBlack(pixels, small.width, small.height)) {
                Log.i(TAG, "the picture of $macID came out black: the one kept stays")
                return@execute
            }
            val folder = dir(app).apply { mkdirs() }
            val tmp = File(folder, "$name.tmp")
            val ok = runCatching {
                tmp.outputStream().use { out -> small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
                tmp.renameTo(File(folder, name))
            }.getOrDefault(false)
            if (!ok) { tmp.delete(); Log.w(TAG, "the picture of $macID could not be written"); return@execute }
            memory[macID] = small
            bump(macID)
            Log.i(TAG, "kept the picture of $macID, ${small.width}×${small.height}, ${File(folder, name).length() / 1024} KB")
        }
    }

    /** The kept picture of `macID`, or null. Blocks: off the main thread. */
    fun load(context: Context, macID: String): Bitmap? {
        memory[macID]?.let { return it }
        val name = ConnectionList.previewFileName(macID) ?: return null
        val file = File(dir(context), name)
        if (!file.isFile) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()?.also { memory[macID] = it }
    }

    /** Remove Preview Picture, and Forget: the Mac's picture goes, file and all. */
    fun remove(context: Context, macID: String) {
        val app = context.applicationContext
        memory.remove(macID)
        bump(macID)
        io.execute {
            ConnectionList.previewFileName(macID)?.let { File(dir(app), it).delete() }
        }
    }

    /** Whether `macID` has a kept picture (a file). Blocks briefly: a file check. */
    fun has(context: Context, macID: String): Boolean =
        memory.containsKey(macID) || ConnectionList.previewFileName(macID)?.let { File(dir(context), it).isFile } == true

    /** The pictures of Macs no longer saved go (ConnectionList.previewPlan's `delete`), whatever the style. */
    fun prune(context: Context, savedMacIDs: Set<String>) {
        val app = context.applicationContext
        memory.keys.retainAll(savedMacIDs)
        io.execute {
            val folder = dir(app)
            val files = folder.list()?.toList() ?: return@execute
            for (stale in ConnectionList.stalePreviewFiles(files, savedMacIDs)) File(folder, stale).delete()
            files.filter { it.endsWith(".tmp") }.forEach { File(folder, it).delete() }
        }
    }

    private fun bump(macID: String) {
        val now = _versions.value
        _versions.value = now + (macID to ((now[macID] ?: 0) + 1))
    }
}

/** This device's own choices for the connect screen (ConnectionList): rows or tiles, and each saved Mac's group. */
class ConnectionPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sill", Context.MODE_PRIVATE)

    var style: ConnectionList.Style
        get() = ConnectionList.storedStyle(prefs.getString(ConnectionList.STYLE_KEY, null))
        set(value) { prefs.edit().putString(ConnectionList.STYLE_KEY, value.raw).apply() }

    var groups: Map<String, String>
        get() = ConnectionList.decodeGroups(prefs.getString(ConnectionList.GROUPS_KEY, null))
        set(value) { prefs.edit().putString(ConnectionList.GROUPS_KEY, ConnectionList.encodeGroups(value)).apply() }
}
