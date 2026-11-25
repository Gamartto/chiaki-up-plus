
![Chiaki Logo](assets/chiaki_wide.png)

# Chiaki-Up+

**Disclaimer:** This project is not endorsed or certified by Sony Interactive Entertainment LLC.

An Android client for PlayStation 4 and PlayStation 5 Remote Play, forked from
[Chiaki-Up](https://github.com/gameblabla/chiaki-up), which is itself a fork of
[Chiaki](https://github.com/thestr4ng3r/chiaki) by Florian Märkl. Chiaki-ng does
not support Android; Chiaki-Up keeps the Android client alive, and this fork adds
what a handheld needs.

## Why this fork exists

On a handheld whose controller has no usable PS button there is no way to open
the PlayStation menu without enabling the on-screen controls, which cover half
the display. The Home key on those devices (Retroid, Anbernic, most generic pads)
sends `KEYCODE_HOME`, which Android intercepts and never delivers to the app, and
Chiaki-Up only maps `KEYCODE_BUTTON_C` and `KEYCODE_BUTTON_MODE` to PS.

Every change here is a small, self-contained patch over upstream so that rebasing
on it stays trivial. Developed and verified on a Retroid Pocket 5 (Android 13,
arm64) streaming from a PS5.

## What it adds

**PS button from the controller.** Start + Select together send an 80 ms PS tap.
Options and Share are held for a 120 ms window while a combo is pending, so
pressing either of them alone still works as before. A switch in Settings turns
the combination off for pads that have a real PS button.

**In-stream statistics.** Effective bitrate, packet loss and round trip time,
toggled with L1 + R1 + Start so the combination costs nothing during play. The
readout can sit at the top or the bottom of the screen and hides with the rest of
the UI.

There is deliberately **no frame rate readout**: the console streams at the
configured rate and duplicates frames when a game renders slower, so a 30 fps
title reads as 60 fps in any client-side counter and the number only misleads.
Bitrate comes from the compressed bytes counted in the decoder, loss from the
packet counters and RTT from the session, all of which are true of the network.

**Console keyboard.** Text fields on the console open an editor on the device.
Every keystroke is forwarded live, accept and reject are propagated back, and the
dialog closes by itself when the console ends the request. The lib already parsed
these messages and exported `chiaki_session_keyboard_set_text/accept/reject`;
nothing was calling them.

**Audio survives a route change.** Plugging headphones in or out mid-session used
to leave the app silent until the stream was restarted, because the Oboe error
callback only wrote a log line. The stream is reopened on the new device, falling
back to shared mode when exclusive is refused.

**Touchpad indicator on your terms.** The pad overlay can be always visible, only
while a finger is down, or never. There is also an optional tint that colours the
pad by where the finger is: violet at the left edge, white in the centre, cyan at
the right, as a continuous ramp rather than three zones, so there is no boundary to
notice. It brightens while the click is held. The console only receives
coordinates, so the tint is a visual aid, not a new protocol feature.

**Invisible PS button.** For handhelds whose controller has no PS key: a hit area
along the screen edge that sends the same 80 ms tap as Start + Select. Quarter
circles in the four corners, half circles at the middle of the top and bottom
edges, six positions and a size slider. Nothing is drawn unless you ask for it,
and it can appear always or only while the hold is in progress. Two details make
it safe: the hit test is the circle and not its bounding rectangle, so a corner
area cannot swallow the touchpad's touches, and a 0.6 s hold is required, because
a stray finger must not open the console menu mid-game. The usable radius is
capped by the gap between the pad and the screen edge, so the button is
automatically smaller on tall screens.

**Autoconnect.** With a single registered console the app skips the list: if the
console is on it connects, and if it is in rest mode it sends the wake packet and
polls every 2 s for up to a minute, connecting as soon as the console answers.
With several consoles the one marked *Use for autoconnect* is used; otherwise the
list is shown. Leaving a stream still returns to the list.

**Rumble strength and bitrate.** Rumble has a 0-150% scale over the motor instead
of on/off. The bitrate is a slider from 0 (Auto) to 100 Mbit, where Auto shows what
the preset table resolves to instead of pretending to be a guess by the console.
The stream overlay carries the current value and can change it mid-session: the
bitrate travels in the launch specification, so the app closes the session and
opens a new one with the new ceiling, keeping the player on the same screen. 45
Mbit remains the sensible ceiling over Wi-Fi; the console enforces its own limit
above that.

**A way to reproduce network faults.** Settings → Diagnostics can drop one in
every N video packets on purpose. It exists because frame recovery cannot be
verified by waiting for the Wi-Fi to misbehave: with 2-3% loss the artefacts are
reproducible in seconds.

**Packaging fixes.** The app could not be rebuilt under a different
`applicationId`: two CoordinatorLayout behaviors were referenced by bare class
name and the FileProvider authority was hardcoded, so a second package crashed on
start or collided with the installed one. `scripts/prepare-build.sh` covers the
toolchain side (oboe under clang 21, nanopb under protobuf 4).

## Versioning

The fork has its own version line: `v1.0.0` is the first release, independent of
the upstream number. The upstream base of each release is stated in the release
notes and in the commit history, since a rebase can move it without this fork's
own version changing. Inside the app, the version shown is the fork version, with
`-side` appended for the `sideapk` flavour; `versionCode` grows with every CI run
so an update always installs over the previous build.

## Install

Two flavours are built from the same source:

- **`standard`** — `com.gameblabla.chiaki`, the upstream package. It replaces an
  installed Chiaki-Up. Because the signature differs from the Play Store build,
  Android requires uninstalling first: export the settings, uninstall, install,
  import.
- **`sideapk`** — `com.gameblabla.chiaki.pscombo`. Installs **alongside** the Play
  Store build, with its own icon and its own console registration. Recommended for
  trying it out.

A registered console is not shared between packages, but the settings export
carries the console list including the registration key, so export/import avoids
registering again.

## Build

Requirements: JDK 17, Android SDK with `platforms;android-35`,
`build-tools;35.0.0`, `ndk;29.0.14206865`, `cmake;3.22.1`, and `protoc` on PATH
(protobuf 21.12 works).

```bash
git clone --recursive <this-repo> && cd <this-repo>
git submodule update --init --recursive \
  third-party/nanopb third-party/jerasure third-party/gf-complete android/app/src/main/cpp/oboe
./scripts/prepare-build.sh                    # oboe + nanopb patches

cd android
./gradlew assembleSideapkDebug -Parm64only    # or assembleStandardRelease
```

`-Parm64only` builds only `arm64-v8a`, which is faster and enough for current
handhelds; without it all four ABIs are built.

### Signing

`android/local.properties` (not versioned):

```properties
sdk.dir=/path/to/Android/sdk
chiakiKeystore=/path/to/chiaki.jks
chiakiKeystorePW=...
chiakiKeyAlias=chiaki
chiakiKeyPW=...
```

Without a keystore the build falls back to Gradle's debug signature, which
**cannot** update an existing install: each new APK would require uninstalling
the previous one. Generate one once and keep it safe.

### Building from a Python venv

The nanopb generator needs `pkg_resources`. If the `python3` on PATH lacks it, add
to the CMake `arguments` in `android/app/build.gradle`:

```
"-DPYTHON_EXECUTABLE=/usr/bin/python3"
```

## Tests

`./gradlew testSideapkDebugUnitTest` runs the JVM suite (Robolectric), which
inflates the real settings screen, checks that every preference in the XML has a
storage binding, and covers the touchpad palette, the invisible button geometry
and its hold behaviour. CI runs it before building, so a regression fails the
pipeline instead of reaching a device.

## Staying in sync with upstream

`master` follows `gameblabla/chiaki-up`. All changes live in separate, narrowly
scoped commits, so updating is a plain rebase:

```bash
git remote add upstream https://github.com/gameblabla/chiaki-up
git fetch upstream && git rebase upstream/master
```

## Limitations

- LAN only, exactly like upstream. Playing outside the home network needs the
  official app or port forwarding, and fails behind CGNAT.
- Builds from this repository are `debuggable=true`: fine for personal use, not
  for distribution as a store-quality release.
- Still missing from upstream and from here: microphone, HDR.

## Credits and disclaimer

Improved with an AI assistant under human direction, with the hardware in hand for
testing. This is an **unofficial, standalone fork**: all credit for the original
client, the protocol work and the Android port belongs to Florian Märkl, gameblabla
and the chiaki-ng contributors. It is not a GitHub fork of their repositories and
does not aim to be; it is not endorsed by Sony Interactive Entertainment either.

## About

Originally created by Florian Märkl, minor improvements by gameblabla, handheld
changes by this fork.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License version 3
as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

Additional permission under GNU AGPL version 3 section 7

If you modify this program, or any covered work, by linking or
combining it with the OpenSSL project's OpenSSL library (or a
modified version of that library), containing parts covered by the
terms of the OpenSSL or SSLeay licenses, the Free Software Foundation
grants you additional permission to convey the resulting work.
Corresponding Source for a non-source form of such a combination
shall include the source code for the parts of OpenSSL used as well
as that of the covered work.
