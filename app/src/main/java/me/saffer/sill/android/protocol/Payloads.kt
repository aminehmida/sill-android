package me.saffer.sill.android.protocol

import org.json.JSONArray
import org.json.JSONObject

// The JSON payloads of Sources/StreamProtocol, written and read by hand with org.json so they come
// out exactly as Swift's JSONEncoder and JSONDecoder have them: an enum with associated values is
// {"case":{"_0":first unlabeled value, "label":labeled value…}}, a case without values is
// {"case":{}}, and an optional left nil is a missing key. The rules of HostSettings.swift hold on
// this side too: fields added later are optional, unknown keys are ignored, nothing is renamed.

// MARK: device → host

/** StreamSource (Switcher.swift). */
sealed class StreamSource {
    data object None : StreamSource()
    data object Desktop : StreamSource()
    data class Window(val id: Long) : StreamSource()

    fun toJson(): JSONObject = when (this) {
        None -> JSONObject().put("none", JSONObject())
        Desktop -> JSONObject().put("desktop", JSONObject())
        is Window -> JSONObject().put("window", JSONObject().put("_0", id))
    }

    companion object {
        fun parse(o: JSONObject?): StreamSource {
            if (o == null) return None
            if (o.has("desktop")) return Desktop
            o.optJSONObject("window")?.let { w -> return Window(w.optLong("_0", -1)) }
            return None
        }
    }
}

enum class PointerAction(val wire: String) { MOVE("move"), LEFT_DOWN("leftDown"), LEFT_UP("leftUp"), RIGHT_DOWN("rightDown"), RIGHT_UP("rightUp") }

enum class ScrollPhase(val wire: String) { BEGAN("began"), ENDED("ended"), MOMENTUM_BEGAN("momentumBegan"), MOMENTUM_ENDED("momentumEnded") }

/** InputEvent (Input.swift). Positions are fractions of the video frame. */
object Input {
    fun pointer(action: PointerAction, x: Double, y: Double): String =
        JSONObject().put("pointer", JSONObject().put("_0", action.wire).put("x", fin(x)).put("y", fin(y))).toString()

    fun scroll(x: Double, y: Double, dx: Double, dy: Double): String =
        JSONObject().put("scroll", JSONObject().put("x", fin(x)).put("y", fin(y)).put("dx", fin(dx)).put("dy", fin(dy))).toString()

    fun scrollGesture(phase: ScrollPhase, x: Double, y: Double): String =
        JSONObject().put("scrollGesture", JSONObject().put("_0", phase.wire).put("x", fin(x)).put("y", fin(y))).toString()

    /** "\n", "\t" and "\u0008" (backspace) reach the Mac as their keys; everything else is typed. */
    fun text(s: String): String = JSONObject().put("text", JSONObject().put("_0", s)).toString()

    /** A USB HID usage with UIKeyModifierFlags bits (KeyMods). */
    fun key(hidUsage: Int, down: Boolean, modifiers: Long): String =
        JSONObject().put("key", JSONObject().put("hidUsage", hidUsage).put("down", down).put("modifiers", modifiers)).toString()

    private fun fin(v: Double): Double = if (v.isFinite()) v else 0.0
}

/** UIKeyModifierFlags, which the Mac reads as CGEventFlags' shift/control/option/command. */
object KeyMods {
    const val SHIFT = 1L shl 17
    const val CONTROL = 1L shl 18
    const val OPTION = 1L shl 19
    const val COMMAND = 1L shl 20
}

object Payloads {
    fun select(source: StreamSource): String = source.toJson().toString()

    fun launchApp(bundleID: String): String = JSONObject().put("bundleID", bundleID).toString()

    fun windowCommand(id: Long, action: String): String = JSONObject().put("id", id).put("action", action).toString()

    /** Viewport.swift: the stream panel in device points (dp here), the Aa scale or none, the rate. */
    fun viewport(width: Double, height: Double, scale: Double?, fps: Int, localCursor: Boolean = true): String {
        val o = JSONObject().put("width", width).put("height", height).put("fps", fps).put("localCursor", localCursor)
        if (scale != null) o.put("scale", scale)
        return o.toString()
    }

    fun ping(clock: Double): ByteArray = java.nio.ByteBuffer.allocate(8).putLong(java.lang.Double.doubleToRawLongBits(clock)).array()

    fun clientStats(fps: Int, frameAgeMs: Int, rttMs: Int, device: String, frameAgeMaxMs: Int, rttMaxMs: Int): String =
        JSONObject().put("fps", fps).put("frameAgeMs", frameAgeMs).put("rttMs", rttMs).put("device", device)
            .put("frameAgeMaxMs", frameAgeMaxMs).put("rttMaxMs", rttMaxMs).toString()

    /** Kind 23, the first message of every session connection. */
    fun hello(appVersion: String, build: String, device: String): String =
        JSONObject().put("appVersion", appVersion).put("build", build).put("protocol", 1).put("device", device).toString()

    /** Kind 17: only the fields one control changed, and a token. */
    fun changeSettings(token: Int, change: SettingsChange): String {
        val o = JSONObject().put("token", token)
        change.maxFPS?.let { o.put("maxFPS", it) }
        change.bitrate?.let { o.put("bitrate", it) }
        change.captureScale?.let { o.put("captureScale", it) }
        change.prioritizeSpeed?.let { o.put("prioritizeSpeed", it) }
        change.virtualDisplay?.let { o.put("virtualDisplay", it) }
        change.directWireless?.let { o.put("directWireless", it) }
        return o.toString()
    }

    /** Kind 27: without an id, the subscription to the top level. */
    fun fetchMenu(token: Int, version: Int? = null, id: String? = null, title: String? = null): String {
        val o = JSONObject().put("token", token)
        version?.let { o.put("version", it) }
        id?.let { o.put("id", it) }
        title?.let { o.put("title", it) }
        return o.toString()
    }

    fun pressMenuItem(token: Int, version: Int?, id: String, title: String?): String {
        val o = JSONObject().put("token", token).put("id", id)
        version?.let { o.put("version", it) }
        title?.let { o.put("title", it) }
        return o.toString()
    }

    fun gesture(name: String, fingers: Int): String = JSONObject().put("gesture", name).put("fingers", fingers).toString()

    /** Kind 19. */
    fun pairRequest(method: String, proof: String, name: String, model: String): String =
        JSONObject().put("v", 1).put("method", method).put("proof", proof).put("name", name).put("model", model).toString()
}

// MARK: host → device

data class WindowInfo(val id: Long, val title: String, val appName: String, val bundleID: String, val width: Int, val height: Int)

data class AppInfo(val name: String, val bundleID: String)

data class WindowList(
    val macName: String,
    val windows: List<WindowInfo>,
    val active: StreamSource,
    val launchID: String?,
    val hostVersion: String?,
    val protocol: Int?,
    val gestures: Int?,
) {
    companion object {
        fun parse(bytes: ByteArray): WindowList? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = o.optJSONArray("windows") ?: JSONArray()
            val windows = (0 until arr.length()).mapNotNull { i ->
                val w = arr.optJSONObject(i) ?: return@mapNotNull null
                WindowInfo(w.optLong("id"), w.optString("title"), w.optString("appName"), w.optString("bundleID"), w.optInt("width"), w.optInt("height"))
            }
            WindowList(
                macName = o.optString("macName", "Mac"),
                windows = windows,
                active = StreamSource.parse(o.optJSONObject("active")),
                launchID = o.optStringOrNull("launchID"),
                hostVersion = o.optStringOrNull("hostVersion"),
                protocol = o.optIntOrNull("protocol"),
                gestures = o.optIntOrNull("gestures"),
            )
        }.getOrNull()
    }
}

fun parseAppList(bytes: ByteArray): List<AppInfo> = runCatching {
    val arr = JSONArray(String(bytes, Charsets.UTF_8))
    (0 until arr.length()).mapNotNull { i ->
        val a = arr.optJSONObject(i) ?: return@mapNotNull null
        AppInfo(a.optString("name"), a.optString("bundleID"))
    }
}.getOrDefault(emptyList())

data class StreamSettings(
    val maxFPS: Int,
    val bitrate: Int,
    val captureScale: Double,
    val prioritizeSpeed: Boolean,
    val virtualDisplay: Boolean,
    val directWireless: Boolean?,
)

data class SettingsChange(
    val maxFPS: Int? = null,
    val bitrate: Int? = null,
    val captureScale: Double? = null,
    val prioritizeSpeed: Boolean? = null,
    val virtualDisplay: Boolean? = null,
    val directWireless: Boolean? = null,
) {
    val isEmpty get() = maxFPS == null && bitrate == null && captureScale == null && prioritizeSpeed == null && virtualDisplay == null && directWireless == null

    fun applied(s: StreamSettings) = s.copy(
        maxFPS = maxFPS ?: s.maxFPS,
        bitrate = bitrate ?: s.bitrate,
        captureScale = captureScale ?: s.captureScale,
        prioritizeSpeed = prioritizeSpeed ?: s.prioritizeSpeed,
        virtualDisplay = virtualDisplay ?: s.virtualDisplay,
        directWireless = directWireless ?: s.directWireless,
    )
}

data class RunningStream(val width: Int, val height: Int, val fps: Int, val mbps: Int, val onVirtualDisplay: Boolean)

data class AwayQuality(
    val homeBitrate: Int, val homeCaptureScale: Double, val awayBitrate: Int, val awayCaptureScale: Double,
    val thisConnectionAway: Boolean, val awayRunning: Boolean,
)

data class LinkReport(
    val state: String, val withheldPerSecond: Int, val bitrate: Int, val carriedKbps: Int?,
    val suggestedBitrate: Int?, val suggestedCaptureScale: Double?,
) {
    val isBehind get() = state == "behind"
}

data class HostSettingsState(
    val settings: StreamSettings,
    val persistent: Boolean,
    val virtualDisplayAvailable: Boolean,
    val virtualDisplayNote: String?,
    val softwareEncoder: Boolean,
    val stream: RunningStream?,
    val answering: Int?,
    val away: AwayQuality?,
    val link: LinkReport?,
) {
    companion object {
        fun parse(bytes: ByteArray): HostSettingsState? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val s = o.getJSONObject("settings")
            val settings = StreamSettings(
                maxFPS = s.getInt("maxFPS"),
                bitrate = s.getInt("bitrate"),
                captureScale = s.getDouble("captureScale"),
                prioritizeSpeed = s.getBoolean("prioritizeSpeed"),
                virtualDisplay = s.getBoolean("virtualDisplay"),
                directWireless = if (s.has("directWireless") && !s.isNull("directWireless")) s.getBoolean("directWireless") else null,
            )
            val stream = o.optJSONObject("stream")?.let {
                RunningStream(it.optInt("width"), it.optInt("height"), it.optInt("fps"), it.optInt("mbps"), it.optBoolean("onVirtualDisplay"))
            }
            val away = o.optJSONObject("away")?.let {
                AwayQuality(
                    it.optInt("homeBitrate"), it.optDouble("homeCaptureScale", 2.0), it.optInt("awayBitrate"),
                    it.optDouble("awayCaptureScale", 1.0), it.optBoolean("thisConnectionAway"), it.optBoolean("awayRunning"),
                )
            }
            val link = o.optJSONObject("link")?.let {
                LinkReport(
                    it.optString("state"), it.optInt("withheldPerSecond"), it.optInt("bitrate"), it.optIntOrNull("carriedKbps"),
                    it.optIntOrNull("suggestedBitrate"), it.optDoubleOrNull("suggestedCaptureScale"),
                )
            }
            HostSettingsState(
                settings = settings,
                persistent = o.optBoolean("persistent"),
                virtualDisplayAvailable = o.optBoolean("virtualDisplayAvailable"),
                virtualDisplayNote = o.optStringOrNull("virtualDisplayNote"),
                softwareEncoder = o.optBoolean("softwareEncoder"),
                stream = stream,
                answering = o.optIntOrNull("answering"),
                away = away,
                link = link,
            )
        }.getOrNull()
    }
}

/** QualityPreset (HostSettings.swift): bits per second per 60 fps. */
enum class QualityPreset(val bitrate: Int, val title: String) {
    LOW(4_000_000, "Low"), EFFICIENT(8_000_000, "Efficient"), BALANCED(15_000_000, "Balanced"), HIGH(25_000_000, "High"),
    PRO(40_000_000, "Pro"), ULTRA(80_000_000, "Ultra"), EXTREME(150_000_000, "Extreme");

    companion object {
        fun name(bitrate: Int): String = entries.firstOrNull { it.bitrate == bitrate }?.title ?: "${mbps(bitrate)} Mbps"
        fun shortTitle(bitrate: Int, captureScale: Double) = "${name(bitrate)} · ${if (captureScale >= 1.5) "Retina" else "Standard"}"
        fun mbps(bps: Int): String = if (bps % 1_000_000 == 0) "${bps / 1_000_000}" else String.format("%.1f", bps / 1_000_000.0)
    }
}

data class MacMenuItem(
    val id: String?, val title: String?, val separator: Boolean, val enabled: Boolean, val mark: String?, val key: String?, val submenu: Boolean,
)

data class MacMenu(
    val version: Int?, val app: String?, val bundleID: String?, val menus: List<MacMenuItem>?, val answering: Int?,
    val menu: String?, val items: List<MacMenuItem>?, val more: Int?, val pressed: Boolean?, val stale: Boolean, val note: String?,
) {
    companion object {
        fun parse(bytes: ByteArray): MacMenu? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            fun items(key: String): List<MacMenuItem>? = o.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val it = arr.optJSONObject(i) ?: return@mapNotNull null
                    MacMenuItem(
                        it.optStringOrNull("id"), it.optStringOrNull("title"), it.optBoolean("separator"),
                        if (it.has("enabled")) it.optBoolean("enabled") else true, it.optStringOrNull("mark"),
                        it.optStringOrNull("key"), it.optBoolean("submenu"),
                    )
                }
            }
            MacMenu(
                o.optIntOrNull("version"), o.optStringOrNull("app"), o.optStringOrNull("bundleID"), items("menus"),
                o.optIntOrNull("answering"), o.optStringOrNull("menu"), items("items"), o.optIntOrNull("more"),
                if (o.has("pressed")) o.optBoolean("pressed") else null, o.optBoolean("stale"), o.optStringOrNull("note"),
            )
        }.getOrNull()
    }
}

data class MacPointer(val x: Double?, val y: Double?, val inside: Boolean, val seen: Int) {
    val position: Pair<Double, Double>? get() = if (inside && x != null && y != null && x.isFinite() && y.isFinite()) x to y else null

    companion object {
        fun parse(bytes: ByteArray): MacPointer? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            MacPointer(o.optDoubleOrNull("x"), o.optDoubleOrNull("y"), o.optBoolean("inside"), o.optInt("seen", 0))
        }.getOrNull()
    }
}

data class Goodbye(val reason: String, val message: String?, val minimumVersion: String?, val reconnect: Boolean?) {
    companion object {
        fun parse(bytes: ByteArray): Goodbye? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            Goodbye(
                o.getString("reason"), o.optStringOrNull("message"), o.optStringOrNull("minimumVersion"),
                if (o.has("reconnect")) o.optBoolean("reconnect") else null,
            )
        }.getOrNull()
    }
}

data class PairResult(
    val ok: Boolean, val proof: String?, val macID: String?, val name: String?, val recognitionKey: String?,
    val reason: String?, val triesLeft: Int?, val retryAfter: Double?, val method: String?, val message: String?,
) {
    companion object {
        fun parse(bytes: ByteArray): PairResult? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            PairResult(
                o.optBoolean("ok"), o.optStringOrNull("proof"), o.optStringOrNull("macID"), o.optStringOrNull("name"),
                o.optStringOrNull("recognitionKey"), o.optStringOrNull("reason"), o.optIntOrNull("triesLeft"),
                o.optDoubleOrNull("retryAfter"), o.optStringOrNull("method"), o.optStringOrNull("message"),
            )
        }.getOrNull()
    }
}

data class MacAddress(val host: String, val port: Int?, val kind: String, val via: String)

data class MacInfo(
    val macID: String, val name: String, val issuedAt: Double, val remoteAccess: Boolean, val remotePort: Int,
    val internet: Boolean, val addresses: List<MacAddress>,
) {
    companion object {
        fun parse(bytes: ByteArray): MacInfo? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = o.optJSONArray("addresses") ?: JSONArray()
            val addresses = (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                MacAddress(a.getString("host"), a.optIntOrNull("port"), a.optString("kind"), a.optString("via"))
            }
            MacInfo(
                o.getString("macID"), o.optString("name", "Mac"), o.optDouble("issuedAt", 0.0), o.optBoolean("remoteAccess"),
                o.optInt("remotePort", 7455), o.optBoolean("internet"), addresses,
            )
        }.getOrNull()
    }
}

/** Kind 18's envelope: `info` is the exact JSON bytes the Mac signed, so they are verified as sent. */
data class SignedMacInfo(val info: ByteArray, val key: ByteArray, val sig: ByteArray) {
    companion object {
        fun parse(bytes: ByteArray): SignedMacInfo? = runCatching {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val dec = java.util.Base64.getDecoder()
            SignedMacInfo(dec.decode(o.getString("info")), dec.decode(o.getString("key")), dec.decode(o.getString("sig")))
        }.getOrNull()
    }
}

// MARK: org.json helpers

fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
