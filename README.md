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
- **A live waveform while you play** — the last eight seconds as a scrolling peak envelope, the
  same shape the player draws for a finished take. Scaled in decibels rather than linearly: a
  guitar picked at a sensible level sits around −20 dBFS, which drawn linearly is a tenth of the
  height and looks like a fault. Anything near full scale draws red, so clipping is visible as it
  happens.
- **Silent visual metronome** — one dot per beat under the buttons, the lit one walking the bar,
  driven from the sample count actually on disk. Position in the bar, not just the pulse: a glance
  tells you where you are, which is what you need to come back in after counting yourself out of a
  phrase. Silent by design — an audible click on a phone speaker ends up inside the take.
- **Listen before recording** *(optional, off)* — draws what the microphone hears before you press
  Record, so clipping and mic distance are settled while it still costs nothing. Nothing is
  written and the microphone is released the moment you leave the screen or start a take.
- **Your folder, your files** — pick any folder once (`Music/Recordings`, an SD card, a synced
  folder); takes land there as 44.1 kHz mono WAV. Browse it in the app, make sub-folders, rename,
  delete, share, play back.
- **Settings that stay out of the way** — tempo, count-in and the metronome sit on one row of the
  record screen as values you read rather than controls you wade through, since the usual case is
  checking them. Tempo opens to a slider, arrows, and a field you can type a number straight into.
- **Takes remember their tempo** — the bpm you played to is written into the WAV itself as a
  `LIST/INFO` comment, so it survives being copied to a computer. This is what the planned drum and
  bass tracks will lock to.
- **Recorded honestly** — the least-processed microphone source the device offers
  (`UNPROCESSED` → `VOICE_RECOGNITION` → `MIC`), and no filtering of any kind on the way to disk.
  The voice-recorder effects are all trained on speech: noise suppression hears a sustained note or
  a reverb tail as background and gates it, and automatic gain control hears a decaying chord as a
  talker going quiet and pushes the tail back up. Compression and limiting are absent for a
  different reason — clipping happens in the ADC, before software sees a sample, so a limiter
  cannot rescue it and would only cost you dynamics. Headroom is the fix.

## The player

Tapping a take's name in the library opens it in the player; the triangle beside the name plays it
where it stands, without leaving the list. The shape here is the same one the record screen drew
while you were playing — a take looks the same afterwards as it did being made.

The player draws the take's **waveform**, and that is the point of the screen. A seek bar tells you
where you are in a take. The shape tells you where the *playing* is — where the count-in ends,
where the chord you fluffed sits, where it trails off into you putting the guitar down — which is
what you are actually looking for when you re-open a take at all. Touch anywhere on it to seek, or
drag to scrub: the whole width is the control, because on a waveform the thing you want to reach is
a feature you can see rather than a fraction you can calculate.

What is drawn is a **peak envelope**, one peak per column rather than an average. An average of a
quiet passage and a loud one is a medium-loud passage, which is a lie about what is in the file;
peaks are what every editor draws and what the eye recognises as the shape of a take.

Two ways in, depending on the file:

- **WAV** is read straight through. It is already PCM, so the RIFF chunks are walked to `data` and
  the samples reduced as they stream past — starting a codec would cost more than the read.
- **Everything else** — an m4a from the stock recorder, an mp3 dropped in from a desktop — goes
  through `MediaExtractor` + `MediaCodec`. Whatever the device can play, it can draw. A folder of
  recordings collects more than this app puts in it, and a waveform is exactly as useful for those.

Either way the PCM is never kept: it arrives a buffer at a time, is folded into about 420 floats,
and is dropped. A five-minute take is 26 MB of samples and nothing worth holding on to. Decoding
stops as soon as the last column is filled, and is cancelled if you leave the screen, so a long
file that is mostly tail costs no more than the part that gets drawn.

The waveform takes the whole height that is left, on the same panel with the same zero line the
record screen draws on, so a take looks the same played back as it did being made.

Every view says what a file **is** — `WAV`, `M4A`, `MP3` — in the list, in the mini player and here.
The folder is the user's own, so it holds imports as well as recordings, and the format decides what
can be done to a take.

The app bar names **the folder the take came from** rather than repeating the take's name the screen
already shows in full — "Library" for a take at the top level, the sub-folder's name below that. It
is where the arrow goes, not what you are looking at. **Share** sits beside it, because that is
something you do with the take rather than to it, and the two edits — **rename** and **normalise** —
are buttons above the transport where they can be seen rather than remembered.

Normalising lifts a quiet take, and does it **in the file**. Takes are played straight off storage
by the system player, so there is no signal path to hang a fader on; and a level that existed only
inside Spark Plug would go missing from every copy shared out of it. So it asks two things, one at
a time. First how loud:

- **Peak** scales until the loudest moment sits at full scale. It cannot distort, and it cannot
  help a take that already touches the top once.
- **Loudness** aims at an average level (−14 dBFS RMS, capped at +18 dB), which is what the ear
  actually calls quiet. It has to push some peaks past full scale, where a tanh knee above 0.8
  saturates them smoothly rather than shattering them into hard-clipped edges.

Then where it goes: **overwrite this take**, which has no undo and says so, or **save a normalised
copy** beside it, which leaves the original alone. Either way the player switches to whatever now
holds the normalised audio, so you hear what you asked for rather than having to go and find it.

A take Spark Plug recorded is 16-bit PCM WAV, and is scaled sample for sample: two passes over the
file, measuring and then scaling, with the header copied through byte for byte so the tempo and
title survive. An overwrite goes to a cache file and replaces the original only once it is
complete, so a failure part-way leaves the take as it was; a copy needs none of that, because the
original is never opened for writing at all.

Imports get the same treatment by a different route. **An m4a, mp3, ogg or flac is decoded** —
whatever the device can play — and **saved as a new WAV**, which the dialog says before it does it.
Never back over the original: putting the level into a lossy file means encoding it again, and a
second generation of artefacts is a poor price for a volume change. The decode happens once, with
the samples going to a cache file and being measured on the way past, so the gain is known by the
time there is anything to scale — and the length of that file is what the WAV header needs, which
is not knowable in advance. Expect the result to be roughly ten times the size of the m4a it came
from, and lossless.

A take that is already at level is left alone and says so, rather than being rewritten for a
fraction of a decibel.

Playback outlives the screen it started from. A **mini player** sits above the tabs wherever you
are, with the take's name, a play/stop toggle, a seek bar, and a close button — stop keeps the take
and your place in it, close puts it away. Without it, wandering off to the Record tab would leave a
take playing with nothing anywhere to stop it.

## Planned

- Foreground service, so a take survives the app going to the background.
- Trimming a take, now that there is a waveform to trim against.
- Auto drum and bass tracks under playback, in the spirit of the late Apple Music Memos — the tempo
  is already stored, and chord detection can come from CrystalBall's chromagram and template
  matching.

## Tech stack

- **Language:** Kotlin (Java 17 target)
- **UI:** Jetpack Compose (Canvas for the metronome and the level meter)
- **Build:** Android Gradle Plugin 9.2.1 + Gradle 9.5.1 (via wrapper)
- **SDK:** `minSdk` 26 · `compile`/`targetSdk` 36
- **Capture:** `AudioRecord` → raw PCM in the cache → WAV on save
- **Playback:** `MediaPlayer`; waveforms from `MediaExtractor` + `MediaCodec` (RIFF read directly)
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
  MainActivity.kt        Compose entry point, tabs and drawer, permission and folder pickers
  RecorderViewModel.kt   app state: settings, folder tree, recording, playback
  Settings.kt            persisted preferences
  audio/
    AudioRecorder.kt     microphone -> PCM cache file; finish / restart / save; level monitoring
    Metronome.kt         count-in clicks (adapted from RubberRing)
    Wav.kt               RIFF header, with tempo in a LIST/INFO chunk
    Gain.kt              measuring a take's level, and the maths of lifting it
    Waveform.kt          WAV -> peak envelope, and the bucketing both paths share
    AudioDecoder.kt      everything else -> PCM, via MediaCodec: peaks, or a normalised WAV
  storage/
    RecordingStore.kt    the granted folder: list, create, rename, delete, write, normalise
  ui/
    RecordScreen.kt      the take in progress
    LiveWaveform.kt      the scrolling input envelope, recording and listening
    LibraryScreen.kt     browsing the folder tree
    PlayerScreen.kt      one take, its waveform, its transport and its edits
    MiniPlayer.kt        the bar above the tabs, so playback is never orphaned
    VisualMetronome.kt   the beat dots and the count-in
    TabBar.kt            the compact bottom tabs
    SettingsScreen.kt · AboutScreen.kt · Common.kt · Theme.kt
```

## Known limitations

- Recording stops if the app leaves the foreground — Android takes the microphone away from
  background apps. A foreground service is the fix, and is planned.
- Mono only. Phone microphones are effectively mono; stereo would double the file size for nothing
  until there is an interface to record from.
- Waveforms are drawn from the whole file at a fixed ~420 columns. There is no zoom, which is fine
  for a take and would not be for an arrangement.
- The record colour is derived from the accent — red at the accent's own saturation and lightness.
  A red or orange accent would land on top of it and lose the distinction it exists to make.
