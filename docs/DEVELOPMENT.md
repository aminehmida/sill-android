# Sill for Android: development notes

These are Omar Marei's original notes from [Saffsanity/sill#89](https://github.com/Saffsanity/sill/pull/89), with the paths adjusted for this repository, where the Android project is at the root. References to Swift files (`Sources/StreamProtocol`, `StreamClient`, …) point at the upstream [Sill](https://github.com/Saffsanity/sill) repository.

The client half of Sill for Android phones, tablets and foldables: a Mac window, or the whole
Desktop, on the device, used with touch, a trackpad, a keyboard, a mouse or the S Pen. It speaks
the same wire protocol as the iPhone and iPad app (`Sources/StreamProtocol`), so **Sill for Mac
needs no change to serve it**: any Sill for Mac from 0.4.0 on (TLS doors with pairing); the clipboard, the Mac's sound, the locked Mac's
cover and key repeat need Sill for Mac 0.6.0, and your own desktop while another user has the screen 0.6.1.

## Using it over Tailscale

1. On the Mac: Sill for Mac with **Remote Access** on (Sill › Settings › Remote Access), and
   Tailscale running.
2. On the Android device: the Tailscale app, signed in to the same tailnet.
3. On the Mac, choose **Pair iPhone or iPad…** in Sill's menu. Its window shows a QR code, a
   12-digit code and the address to type (the Mac's MagicDNS name, `mac.tailXXXX.ts.net`, with
   its `100.x` address under it).
4. In Sill on Android, **Add a Mac**: tap **Scan Code**, or type the address and the code, then
   **Pair**. The device pairs once and connects; from then on a tap on the Mac connects, from
   anywhere on the tailnet.

At home, on the Mac's own Wi‑Fi, the paired Mac's row reads **Wi‑Fi** and the session goes
through the Mac's home door (home quality, the LAN's latency), Tailscale only when that does not
answer. A Mac on the same Wi‑Fi that this device has not paired with is listed under **On this
network**: a tap asks the Mac to show its code (as on iOS), then type it.

Away from home the Mac streams at its **away quality** (Low · Standard unless changed); Settings on
the device changes it like the Mac's own menu does.

## Building

Needs a JDK 17 or later and the Android SDK with platform 37 (`sdkmanager "platforms;android-37.0"`).

```
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest             # the pairing vectors, framing, JSON shapes, ledger, menus
./gradlew assembleRelease               # minified; signed with the debug key unless SILL_KEYSTORE_FILE is set (see README)
```

Android 10 (API 29) or later: TLS 1.3 and ALPN on `SSLSocket` are what the Mac's doors require.

## How it maps to the iOS app

| Android | iOS / shared |
|---|---|
| `protocol/Wire.kt`, `Payloads.kt`, `Blobs.kt` | `StreamMessage.swift` and the JSON of `Switcher`, `Input`, `Viewport`, `HostSettings`, `Remote`, `Compatibility`, `Pointer`, `MacMenu`, `Gesture` |
| `crypto/Pairing.kt` | `Pairing.swift`, `RemoteIdentity.swift` (Mac ID, SPKI pins, Damm code, PBKDF2 and HMAC proofs, the recognition tag, `sill://pair`) |
| `crypto/Identity.kt` | `DeviceIdentity`, `RemoteTLS.swift`: a P-256 key in the Android Keystore, TLS 1.3, one ALPN, SNI `sill`, the Mac's key pinned inside the handshake |
| `net/Pairer.kt`, `Session.kt`, `SavedMacs.kt`, `HomeDiscovery.kt` | `StreamClient`, `StreamClient+Remote`, `StreamClient+Home`, `RemoteConnector`, `RemoteDialPolicy`, `SavedMacs`, `MessageReader` |
| `client/SillClient.kt` | `StreamClient`: the session's life, the catalog, kinds 0–28, `GoodbyePolicy`, the reconnect |
| `client/SettingsLedger.kt`, `MenuState.kt`, `Keys.kt` | `HostSettingsLedger`, `MacMenuState`, `KeyChords` |
| `video/VideoDecoder.kt` | `HEVCDisplayView`: MediaCodec HEVC, Annex B, rendered as decoded |
| `ui/input/*` | `InputOverlay`, `TrackpadView`, `TrackpadGestures`, `ScrollMomentum` (the same thresholds) |
| `ui/StreamScreen.kt`, `Panels.kt`, `VideoPanel.kt`, `ConnectScreen.kt` | `StreamScreen`, `PortraitStreamScreen`, `HostSettingsPanel`, `MacMenuButton`, `ContentView` |

## Not like iOS (yet)

- **Decoder size.** Android phones' HEVC decoders often stop below a Retina capture (the Galaxy A15:
  2560×1440). When the Mac's stream is larger than this device decodes, Sill asks the Mac for
  Standard resolution by itself and says so.
- No USB-cable pairing, Direct Wireless (AWDL is Apple's) or moving a live session between paths:
  a session that loses its path reconnects (home door first, then Tailscale).
- No first-run tour, and no window reordering in the strip.
- A touchpad on Android 13 and earlier scrolls as fingers do (its scroll events need Android 14).
- The Mac's sound follows AudioPlayout's rules, timed from AudioTrack's timestamps instead of
  AVAudioPlayerNode's render time; no audio focus is requested (iOS mixes with other apps too).
- The clipboard has no change count: the chip keys on the clip's timestamp, and Android 13+ shows its
  own notice when Sill writes text from the Mac. Paste reads the clipboard on the tap, so Android
  12+ shows its "pasted" toast.
- A foldable's laptop pose is read with Jetpack WindowManager (FoldingFeature), not iOS's hinge API.

## What Sill for Mac 0.6 added, and where it lives here

| Android | iOS / shared |
|---|---|
| `protocol/Policies.kt` | `ViewportBounds`, `TextScale`, `LockedMac`, `ScrollDirection`, `AwayCopy` |
| `client/Modules.kt` | the two modules' interfaces, which `SillClient` drives |
| `client/KeyframeRecovery.kt` | `KeyframeRecovery.swift` (kind 44) and `DeviceIdlePolicy.swift` |
| `audio/*` | `Audio.swift` (kind 43), `AudioDecoder`, `AudioPlayout`, `AudioOutput`, `StreamClient+Audio` |
| `clipboard/*` | `Clipboard.swift`, `ClipboardPolicy.swift` (kind 32), `StreamClient+Clipboard`, `ClipboardChip` |
| `ui/input/StreamZoom.kt`, `StreamZoomState.kt` | `StreamZoom.swift` and InputOverlay's use of it |
| `ui/input/TextComposition.kt` | `TextComposition.swift` |
| `ui/input/HardwareKeys.kt` | `HardwareKeyboardPolicy.swift`, KeyChords' `ForwardedKeys` and `KeyTyping` |
| `net/ConnectionList.kt`, `net/MacSnapshots.kt`, `ui/MacTiles.kt` | `ConnectionList.swift`, the tiles' pictures and groups |
| `net/DiscoveryPolicy.kt` | `DiscoveryPolicy.swift` (#69: browsing again, the reconnect's clock) |

## Testing without a Mac app (DEBUG builds)

A synthetic host on loopback, reached through `adb reverse` (no firewall prompt, nothing posted):

```
mkdir -m 700 /tmp/sillhost
SILL_TEST_LOOPBACK=1 SILL_TEST_SOFTWARE_ENCODER=1 SILL_TEST_NO_ROUTER=1 SILL_TEST_ORIGIN=vpn \
SILL_TEST_REMOTE_DIR=/tmp/sillhost SILL_TEST_INPUT_LOG=1 .build/release/SillHost --synthetic --remote=47455
adb reverse tcp:47455 tcp:47455
adb shell am start -n me.saffer.sill.android/.MainActivity --es pairAddress 127.0.0.1:47455 --es pairCode <the code it printed>
```

DEBUG-only extras: `pairUrl` (a `sill://pair` link, no confirmation), `pairAddress` with `pairCode`,
`connect` (the first saved Mac), `forget`, `hud`, and for the home door `serviceType`
(`_silltest._tcp`), `homeOverride` (`127.0.0.1:PORT`, what the browser lists is reached there),
`tapHome` and `homeCode`. `SILL_TEST_MENU_PID` (with `Scripts/menufixture.swift`) and
`SILL_TEST_POINTER_PATH` exercise the menus and the Mac's pointer as on iOS.
