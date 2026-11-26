# Known issues

Found while reviewing the code and not fixed yet, with the reason they are still
here. Everything in the changelog was fixed; this file is the rest.

## Session lifecycle runs on the UI thread

`StreamSession.resume()` builds and starts the session on whatever thread calls it,
and `StreamActivity.onResume()` calls it from the main thread. Building it opens the
log file, initialises the decoders and resolves the host name, so entering a stream
can block the UI for hundreds of milliseconds, and longer when the console is
unreachable.

The same happens on the way out: `shutdown()` joins the native session thread with
no timeout, called from `onPause` and `onDestroy`.

This is the next real piece of work, and it is a refactor rather than a patch: the
session has to move to its own executor with the state machine fed through
`postValue`, and teardown has to happen without the UI waiting for the join. It
touches every path that was validated by hand — reconnect, the bitrate restart, the
PIN dialog — so it needs testing on the device.

## No recovery when the decoder gives up

A permanent `MediaCodec` error used to spin a core at 100 %; it now logs once and
stops the output thread, but the session stays alive with black video until the user
leaves and reconnects. The fix is a watchdog: if no frame has been rendered for N
seconds, request an IDR; if the decoder is the one that died, rebuild it.

## Rumble restarts a one-shot per event

Each rumble event cancels the current vibration and starts a new one-second
one-shot, on the main thread. Continuous rumble becomes an IPC round trip per event
and does not follow the console's envelope. It should compose a single effect with
the amplitude scaled by the preference and coalesce events to ~10 Hz.

## Allocations in the input path

`StreamInput.controllerState` builds two or three fresh `ControllerState` copies per
call and is called from every sensor event — the accelerometer, gyroscope and
rotation vector are registered at 250 Hz — so a session produces roughly a thousand
throwaway objects per second on the UI thread.

## Registration log grows quadratically

`ChiakiRxLog` appends each native line with `cur + "\n" + line` into one string held
by a `BehaviorSubject`, and the activity re-assigns the whole text to the `TextView`
on each emission. A long pairing session copies megabytes and gets slower as it goes.

## Discovery subject is not serialised

`DiscoveryManager` feeds a plain `BehaviorSubject` from the native callback thread
and from the main thread. RxJava subjects are not thread-safe: use `toSerialized()`.

## HDR and microphone

Both are missing upstream too. HDR needs a different surface path and the protocol
supports it only partially; the microphone needs an audio capture path that does not
exist in this client.
