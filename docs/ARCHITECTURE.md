# Architecture

How the Android app, the JNI bridge and the C library fit together. Written for
anyone opening this fork, and as a map for changes: it says where a feature
belongs before you go looking.

## Layers

```
android/app/src/main/java/…       Kotlin UI and state             ~6.0k lines, 67 files
android/app/src/main/cpp/         JNI bridge, decoder, audio      chiaki-jni.c, video-decoder.c, audio-output.cpp
lib/                              C library: protocol, transport  ~13.8k lines
protobuf/                         nanopb definitions              takion.proto, ctrl.proto, videoconfig.proto
gui/ cli/ switch/                 desktop/console frontends       NOT built for Android
```

Kotlin is organised by feature, not by type:

| Package | Holds |
|---|---|
| `main` | host list, discovery, auto connect, the settings entry points |
| `regist` | PIN registration flow against a console |
| `stream` | `StreamActivity`, the overlay, stats polling, view transforms |
| `session` | `StreamSession` (lifecycle), `StreamInput` (keyboard, sensors) |
| `touchcontrols` | the on-screen controller and the touchpad, all custom views |
| `settings` | preference screens and the `PreferenceDataStore` bridge |
| `common` | Room database, `Preferences`, export/import, `DisplayHost` |
| `lib` | `Chiaki.kt`: the whole JNI surface in one file |
| `discovery` | UDP broadcast discovery of consoles on the LAN |

## Data flow

**Launch → connect.** `MainActivity` observes `MainViewModel.displayHosts`, which
merges Room (`RegisteredHost`, `ManualHost`) with the live discovery list into
`DisplayHost` objects. Tapping one builds a `ConnectInfo` (target, address,
registration key, rp key, video profile) and passes it by `Intent` to
`StreamActivity`. Auto connect does the same thing without the tap, waking the
console first if discovery reports `STANDBY`.

**Session.** `StreamActivity` creates `StreamViewModel` → `StreamSession` →
`ChiakiNative.sessionCreate(connectInfo)` → `chiaki-jni.c` → `session.c`. The
`Surface` from the `SurfaceView` is handed to the decoder with
`sessionSetSurface` (`ANativeWindow`), and `chiaki_session_start` runs the
session on its own thread. Failures come back as events, not return codes.

**Transport.** Inside `lib/`: `session.c` orchestrates, `streamconnection.c`
performs the handshake and sends the `launch_spec` (resolution, fps, bitrate,
codec, `enable_keyboard`), `takion.c` carries the packets, `fec.c` repairs loss,
`congestioncontrol.c` adapts the actual rate under the ceiling from the launch
spec.

**Video.** `videoreceiver.c` assembles frames → callback into
`video-decoder.c`, which owns an `AMediaCodec` and renders straight to the
`ANativeWindow`. There is no GPU post-processing step: no upscalers, no HDR.
Compressed bytes are counted here for the stats overlay; frame rate is
deliberately not measured (the console duplicates frames when a game renders
slower, so a client-side counter reports the stream rate, not the game's).

**Audio.** `audioreceiver.c` → Opus decode in `lib/` → `audio-output.cpp`
(Oboe) → the active output device. `onErrorAfterClose` reopens the stream when
the route changes (headphones in or out).

**Input.** Two sources feed one struct. `StreamInput` translates hardware keys,
the PS combo and the motion sensors; `touchcontrols` builds `ControllerState`
from the on-screen views. Both end in `session.setControllerState` →
`chiaki_ctrl_set_controller_state` → `ctrl.c` → `takion.c`.

## Where this fork differs from upstream

| Area | Files |
|---|---|
| PS button from Start + Select, stats toggle on L1+R1+Start | `session/StreamInput.kt` |
| Console keyboard, all three `sessionKeyboard*` calls | `cpp/chiaki-jni.c`, `lib/Chiaki.kt`, `session/StreamSession.kt`, `stream/StreamActivity.kt` |
| Stats overlay (bitrate, loss, RTT), position, decoder byte counter | `stream/StreamActivity.kt`, `cpp/video-decoder.c`, `cpp/chiaki-jni.c` |
| Audio survives a route change | `cpp/audio-output.cpp` |
| Touchpad indicator modes and zone glow | `touchcontrols/TouchpadView.kt`, `TouchpadOnlyFragment.kt`, `TouchControlsFragment.kt` |
| Auto connect and wake | `main/MainActivity.kt` |
| Bitrate slider and reconnect at a new bitrate | `common/Preferences.kt`, `stream/StreamActivity.kt` |
| Install flavours, FileProvider authority, layout behaviour names | `app/build.gradle`, `AndroidManifest.xml`, `common/LogManager.kt`, `res/layout/activity_main.xml` |

## Things that break quietly

- **The JNI resolves seven session callbacks and two discovery methods by name**
  (`eventConnected`, `eventRumble`, `eventKeyboardOpen`, `eventQuit`,
  `eventLoginPinRequest`, `eventKeyboardTextChange`,
  `eventKeyboardRemoteClose`, plus `hostsUpdated` and the discovery getters).
  Renaming any of them compiles fine and crashes on the first event. The
  release build keeps the package (`-dontobfuscate` plus a `-keep`), which is
  what makes this survivable at all.
- **`targetSdk 35` eats `KEYCODE_BACK`** unless the activity opts out; the
  manifest carries `enableOnBackInvokedCallback="false"` for that reason.
- **New switches must be whitelisted in `DataStore`** (`settings/SettingsFragment.kt`),
  otherwise `PreferenceDataStore` discards the write and the UI keeps a value the
  code never sees. This has bitten twice.
- **The stream overlay bar positions its switches with fixed margins** (200 dp for
  "Touchpad only"), so anything added there competes for the same space on
  narrower screens.
- **The video profile is read from preferences when `StreamActivity` starts**, not
  from the intent, so a bitrate change can simply recreate the activity. Keep it
  that way.
- **No automated tests exist** in either language. The safety net is the CI build
  plus manual runs on the device.

## Build and CI

`scripts/prepare-build.sh` patches oboe (clang 21) and nanopb (protobuf 4) before
the NDK build; `android/local.properties` carries the SDK path and the keystore.
CI (`.github/workflows/android.yml`) builds both flavours on a tag and attaches
the APKs to the release, signing with the keystore stored in repository secrets.
