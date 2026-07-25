<h1>
  <img src="docs/icon.png" alt="Spark Plug icon" height="52" align="middle" />
  Spark Plug
</h1>

An Android recorder for instruments rather than for voice memos. Count yourself in at a tempo you
choose, play, and if the third bar goes wrong hit **Restart** without looking. Takes are written as
plain WAV files into a folder you pick, so they are on your phone's storage where you can find them
— not locked inside the app.

Early-stage — the recording and take-management half is built; the accompaniment half is not.

## Features

- **Two buttons that matter while recording** — full-width, thumb-height **Done** and **Restart**.
  Done ends the take and offers to save it; Restart wipes it and counts you back in.
- **Count-in** — off, one bar or two, at a tempo you set, with an accented downbeat. Borrowed from
  RubberRing: the whole count-in is one static `AudioTrack` buffer, so the clicks are
  sample-accurate rather than at the mercy of the scheduler.
- **Silent visual metronome** — a swinging arm with a flash on the beat, driven from the sample
  count actually on disk. Silent by design: an audible click on a phone speaker ends up inside the
  take.
- **Your folder, your files** — pick any folder once (`Music/Recordings`, an SD card, a synced
  folder); takes land there as 44.1 kHz mono WAV. Browse it in the app, make sub-folders, rename,
  delete, share, play back.
- **Takes remember their tempo** — the bpm you played to is written into the WAV itself as a
  `LIST/INFO` comment, so it survives being copied to a computer. This is what the planned drum and
  bass tracks will lock to.
- **Recorded honestly** — the least-processed microphone source the device offers
  (`UNPROCESSED` → `VOICE_RECOGNITION` → `MIC`). Automatic gain control riding a decaying chord is
  audible in a way it is not in a phone call.

## Planned

- Foreground service, so a take survives the app going to the background.
- Waveform view and trim.
- Auto drum and bass tracks under playback, in the spirit of the late Apple Music Memos — the tempo
  is already stored, and chord detection can come from CrystalBall's chromagram and template
  matching.

## Tech stack

- **Language:** Kotlin (Java 17 target)
- **UI:** Jetpack Compose (Canvas for the metronome and the level meter)
- **Build:** Android Gradle Plugin 9.2.1 + Gradle 9.5.1 (via wrapper)
- **SDK:** `minSdk` 26 · `compile`/`targetSdk` 36
- **Capture:** `AudioRecord` → raw PCM in the cache → WAV on save
- **Storage:** Storage Access Framework tree grant (`DocumentsContract`)
- **Async:** Kotlin Coroutines + Flow

No native code, no NDK, no GPL dependencies — as in RubberRing and Crystal Ball.

## Building

Requires a JDK (17+) and the Android SDK. Point the build at your SDK with a `local.properties` in
the project root (git-ignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

Then:

```sh
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`; sideload it to a device.

## Project layout

```
app/src/main/java/de/singular/recorder/
  MainActivity.kt        Compose entry point, drawer, permission and folder pickers
  RecorderViewModel.kt   app state: settings, folder tree, recording, playback
  Settings.kt            persisted preferences
  audio/
    AudioRecorder.kt     microphone -> PCM cache file; pause / restart / save
    Metronome.kt         count-in clicks (adapted from RubberRing)
    Wav.kt               RIFF header, with tempo in a LIST/INFO chunk
  storage/
    RecordingStore.kt    the granted folder: list, create, rename, delete, write
  ui/
    RecordScreen.kt      the take in progress
    LibraryScreen.kt     browsing the folder tree
    VisualMetronome.kt   the swinging arm
    SettingsScreen.kt · AboutScreen.kt · Common.kt · Theme.kt
```

## Known limitations

- Recording stops if the app leaves the foreground — Android takes the microphone away from
  background apps. A foreground service is the fix, and is planned.
- Mono only. Phone microphones are effectively mono; stereo would double the file size for nothing
  until there is an interface to record from.
