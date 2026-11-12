package me.saffer.sill.android.client

import me.saffer.sill.android.protocol.WindowList

/**
 * The Mac's sound on this device (kind 43; iOSClient's StreamClient+Audio, AudioPlayout, AudioOutput,
 * AudioDecoder). SillClient owns one for its life and drives it; the implementation is in `audio/`.
 */
interface SoundPlayer {
    /** The codecs of the Mac's sound this device plays, best first (the hello's `audio`); empty: none. */
    val codecs: List<String>

    /** Mute on this device alone: the Mac keeps sending, nothing is heard. Main thread. */
    var muted: Boolean

    /** One kind 43 payload as it came, with its header's time stamp (host wall clock, s). Reader thread. */
    fun onMessage(payload: ByteArray, hostTimestamp: Double)

    /** One video frame as it came, with its header's time stamp (host wall clock, s). Reader thread. */
    fun onVideoFrame(hostTimestamp: Double)

    /**
     * Stop playing and forget the stream: the session ended, the app left the screen, the Mac locked,
     * Send Audio went off. The next format starts it again. Any thread.
     */
    fun stop()

    /**
     * ClientStats' sound fields for the second just ended, then a new second: `audioBehindMs` (the
     * median ms the sound trailed the picture, -1 for a second without sound) and `audioLate`
     * (packets too late to play); nil while no sound has played this session. Timer thread.
     */
    fun takeSecond(): Pair<Int, Int>?

    /** The app is going away for good. */
    fun release()
}

/**
 * The clipboard both ways (kind 32; Clipboard.swift, ClipboardPolicy.swift, iOSClient's
 * StreamClient+Clipboard and ClipboardChip). SillClient owns one and drives it; the implementation is
 * in `clipboard/`.
 */
interface ClipboardBridge {
    /**
     * This device's Share Clipboard switch: SillClient remembers it and sets it here at start and on
     * every change; turning it off on a live session sends `{"stop":true}`, on sends `{}`. Main thread.
     */
    var enabled: Boolean

    /**
     * A session's window list (every one, the first included), with the connection to send kind 32
     * on: subscribe, stop, or say nothing, as the list's `clipboard` and `locked` allow. Main thread.
     */
    fun onWindowList(list: WindowList, send: (ByteArray) -> Unit)

    /** A kind 32 from the Mac. Main thread. */
    fun onMessage(payload: ByteArray)

    /** The session ended (or was replaced): nothing is subscribed any more. Main thread. */
    fun onSessionEnded()

    /** The app came back to the screen: the device's clipboard may have changed meanwhile. Main thread. */
    fun onForeground()
}
