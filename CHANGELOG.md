# Changelog

The app keeps upstream's numbering out of the way: this fork starts at 1.0.0 and
each entry says which upstream release it was rebased on.

## 1.3.1

Fixes found by a review of the native layer. None of them were reachable by using
the app normally, and all of them were real:

- Decoder: `set_surface()` deadlocked on a non-recursive mutex and the no-codec
  branch left it locked; the codec is now deleted while holding the lock.
- Decoder: the output thread spun a core at 100 % after a permanent codec error.
- Frame processor: off-by-one on the unit index bound, which let one packet write
  past the slot array.
- Audio: the frame callback returned holding the codec mutex; empty buffers could
  make the copy loop spin; teardown waited forever on the input buffer and joined
  without a limit.
- Stream connection: the out-of-memory path kept `state_mutex`, freezing the next
  stop.
- Settings: importing a file logged the console keys to logcat.
- Stream screen: dialogs were never tracked, because the builder's local variable
  shadowed the property and the property's type did not even match.
- Touchpad: a cancelled gesture left the pad pressed in the console.
- Session: the generation counter is now `@Volatile`.

## 1.3.0

- Settings → Diagnostics: simulate packet loss on purpose, to reproduce network
  artefacts in seconds instead of waiting for the Wi-Fi to misbehave.
- Release builds in CI, so published APKs are no longer debuggable.
- README: screenshots, the AI disclaimer and the licence of the additions.

## 1.2.1

- Touchpad tint as a ten-stop ramp with smoothstep, recomputed only when the hue
  changes.

## 1.2.0

- Invisible PS button: six positions, three visibility states, 0.6 s hold, circular
  hit test that keeps clear of the touchpad.
- Typed bindings between `preferences.xml` and `DataStore`, with a test that fails
  when the two drift apart.

## 1.1.0

- Touchpad indicator: always, only while touched, or never, with a zone tint.
- Autoconnect: skips the host list, wakes the console from rest, counts down the
  attempts.

## 1.0.0

First public build, rebased on chiaki-up 2.1.1:

- PS button from the controller with Start + Select.
- In-stream statistics: effective bitrate, loss, round trip time.
- Console keyboard, audio that survives a route change.
- Rumble strength, bitrate slider with mid-session changes, view modes.
