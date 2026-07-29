<h1>
  <img src="docs/icon.png" alt="Title Track icon" height="52" align="middle" />
  Title Track
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
  phrase. Silent, and on by default for that reason — an audible click on a phone speaker ends up
  inside the take. **It starts with the count-in**, running on negative time up to the downbeat and
  handing over to the take's own clock without breaking step, so the bar is already familiar by the
  time you play into it.
- **A click through the take** *(optional, off)* — for headphones, where there is nothing for the
  microphone to pick up. Off by default and warned about wherever it is offered, because on a
  speaker it is not a faint artefact: it is a metronome mixed into the take at whatever level the
  speaker managed, and it cannot be taken out afterwards. Long-press **Metronome** on the record row
  to reach it. One stream plays the count-in and the take together rather than handing over between
  two — separate `AudioTrack`s each start on their own latency, which put a gap exactly at the
  downbeat, the one moment a metronome is judged on. Beat positions are computed from the beat index
  rather than accumulated, so the grid cannot drift from itself over a long take. What you hear is
  still behind the take's own grid by the device's output latency, which no app can remove.
- **Listen before recording** *(optional, off)* — draws what the microphone hears before you press
  Record, so clipping and mic distance are settled while it still costs nothing. Nothing is
  written and the microphone is released the moment you leave the screen or start a take.
- **A level test, not a guess** — hold **Record** (or tap **Input** on the settings row) and play
  the loudest thing you are going to play. It reports the peak in dBFS and offers a gain that puts
  it at −9 dBFS, leaving room for the take to be louder than the rehearsal. That gain is then
  applied *while capturing*: Android exposes no microphone preamp to turn up, so lifting a quiet
  instrument means multiplying samples, and doing it at capture — where the device is still handing
  back float — costs no resolution, unlike boosting a finished 16-bit file. The value sits on the
  record screen beside Tempo, because a boost that applies to every take must be visible, and it is
  written into each take's WAV as `gain=+12`.
- **Your folder, your files** — pick any folder once (`Music/Recordings`, an SD card, a synced
  folder); takes land there as 44.1 kHz mono WAV. Browse it in the app, make sub-folders, rename,
  delete, share, play back.
- **Settings that stay out of the way** — tempo, count-in and the metronome sit on one row of the
  record screen as values you read rather than controls you wade through, since the usual case is
  checking them. Tempo opens to a slider, arrows, and a field you can type a number straight into,
  and a long press reaches what a row of values has no room for. The settings screen itself is two
  tabs, split by when you come to them rather than by what they are: **Recording** is everything
  that shapes the next take, **System** is the app's own set-up — where takes go, how the
  library sorts, what the screen does. One is visited often and the other twice.
- **Takes remember their tempo** — the bpm you played to is written into the WAV itself as a
  `LIST/INFO` comment, so it survives being copied to a computer. This is what the planned drum and
  bass tracks will lock to.
- **One amber, two steps — by job, not by theme** — the colour from the icon is a text colour as
  much as a button fill, and the two want different rungs of the same scale. Anything *drawn* takes
  the icon's own gold: filled buttons, picked chips, switch tracks, the play triangles and stars.
  Anything *set* takes a deeper step on the light theme, where a mid-scale amber on a white page
  reads 2.1:1 — the selected tab, the setting values. The line is drawn or set, and a glyph falls on
  the drawn side: an icon has no counters to lose, and beside a gold button the deeper step was
  reading brown. The dark theme needs no such split and uses the one gold throughout, its surfaces
  re-tinted onto the accent's own hue rather than left on Material's faintly violet greys, so the
  accent sits within its background's family instead of opposite it. The light theme is plain white
  and grey and lets the accent carry the warmth by itself: at a twentieth of a stop off white, a
  tinted page is not a decision anyone reads, only a page that looks unclean beside the system's.
- **A striped Record button, and red kept for what is wrong** — Record used to be a full-width red
  slab, which made the loudest thing in the app out of a screen tuned to be quiet. It is an accent
  button now, the same fill and label as Play, with oblique light stripes over it: texture is a
  channel the palette was not using, and it marks the button out without needing a colour of its own —
  no third amber to keep in agreement with the other two. Red is left to the two things that are not
  controls: the clock while a take runs, and the top of a level meter, where it means the converter is
  clipping. A red that turns up in the ordinary run of things stops being read as a warning.
- **Recorded honestly** — the least-processed microphone source the device offers
  (`UNPROCESSED` → `VOICE_RECOGNITION` → `MIC`), and no filtering of any kind on the way to disk.
  The voice-recorder effects are all trained on speech: noise suppression hears a sustained note or
  a reverb tail as background and gates it, and automatic gain control hears a decaying chord as a
  talker going quiet and pushes the tail back up. Compression and limiting are absent for a
  different reason — clipping happens in the ADC, before software sees a sample, so a limiter
  cannot rescue it and would only cost you dynamics. Headroom is the fix. The input gain above is
  the one exception, and it is not automatic: one number, chosen once by measuring, applied evenly
  to every sample. Nothing tracks the signal while you play — an AGC hears a decaying chord as a
  player getting quieter and swells the tail back up, which is precisely the thing that makes voice
  recorders useless for music.

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

It is drawn **in decibels**, over the same 60 dB window as the record screen and the level meter —
one shared `amplitudeToHeight()`, because these are three pictures of the same thing and have to
agree. Drawn linearly, a take peaking at −15 dBFS — a good acoustic guitar level — fills a sixth of
the height and reads as a failed recording, which is exactly the wrong thing to tell someone who has
just played it.

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
is where the arrow goes, not what you are looking at. The **star** sits beside it, being a thing you
change your mind about often, and everything else the take can be put through — rename, move, share,
delete — is one menu behind it, in the order a library row uses so the list is not learned twice.

The two edits, **trim** and **level**, are buttons above the transport instead: they are what you
came to the player to do, and they need the room. **Note** sits beside them rather than in the menu,
being the one thing on the screen you write rather than do — and a place to write that nobody can
find is a place nobody writes.

A note is whatever the take needs saying about it: the chords, the words, what the idea was. It
shows under the title, and tapping it opens the editor. Emptying it removes it. Notes are kept by
the app rather than written into the takes — the same trade the stars make, and for a stronger
reason: a WAV's comment chunk sits before the audio, so changing it means rewriting the file on
every edit, and it would leave the imported m4a and mp3 takes with nowhere to put one. The cost is
that a note is invisible from a desktop. Takes with one are marked in the list.

Normalising lifts a quiet take, and does it **in the file**. Takes are played straight off storage
by the system player, so there is no signal path to hang a fader on; and a level that existed only
inside Title Track would go missing from every copy shared out of it. So it asks two things, one at
a time. First how loud:

- **Peak** scales until the loudest moment sits at full scale. It cannot distort, and it cannot
  help a take that already touches the top once.
- **Loudness** aims at an average level (−14 dBFS RMS, capped at +18 dB), which is what the ear
  actually calls quiet. It has to push some peaks past full scale, where a tanh knee above 0.8
  saturates them smoothly rather than shattering them into hard-clipped edges.

Then where it goes: **overwrite this take**, which has no undo and says so, or **save a copy**
beside it as **FLAC or WAV**. Both are lossless, so that choice is only about size — FLAC is about
half of WAV — and FLAC is offered first. Either way the player switches to whatever now holds the
normalised audio, so you hear what you asked for rather than having to go and find it.

A take Title Track recorded is 16-bit PCM WAV, and is scaled sample for sample: two passes over the
file, measuring and then scaling, with the header copied through byte for byte so the tempo and
title survive. An overwrite goes to a cache file and replaces the original only once it is
complete, so a failure part-way leaves the take as it was; a copy needs none of that, because the
original is never opened for writing at all.

Imports get the same treatment by a different route. **An m4a, mp3, ogg or flac is decoded** —
whatever the device can play — and saved as a new file beside it, never back over the original:
putting the level into a lossy file means encoding it again, and a second generation of artefacts
is a poor price for a volume change. This is why FLAC leads the choice. A compressed take saved
back as WAV comes out around three times the size for no gain in quality.

A take that is already at level is left alone and says so, rather than being rewritten for a
fraction of a decibel.

**Trim** puts two handles on the waveform, borrowing the loop markers from RubberRing: a marker
line with a grip tab beside it, rounded only on the corners away from the line so it reads as
attached rather than floating. An edge moves only after a **still hold** on it — the waveform is a
seek control across its whole width, so without the hold every reach for a handle would seek
instead — and the line stays put under the finger rather than jumping to it. What is being thrown
away is greyed over, so the lit part is what survives.

Where the take carries a tempo, the beats are drawn faintly and a handle **snaps** to one when it
comes within an eighth of a beat, coming straight out again if you keep dragging. A magnet, not a
quantiser: a take played to a silent click is not on the grid to the millisecond, and the grid is
derived from one tempo and the first sample, so it is exactly right where the take begins and
progressively more of a guess after that. It is there to help you find the downbeat you are near,
never to decide where the cut goes. **Nudge buttons** move either edge by 10 ms, because a finger
covers about a tenth of a second of a take and "nearly right" is where every drag ends.

The waveform **zooms**, which is what makes trimming an edit rather than a gesture at a smudge: at
1x a thumb covers about a tenth of a second of a take, and the note attack you want to cut on is
inside that. Pinch to zoom, drag to pan, and drag the strip along the bottom to cover the whole file
in one movement — a viewport of zoom and offset mapping fractions to pixels, borrowed from
RubberRing along with the markers. Takes are read at 4096 peaks rather than the 420 a phone screen
has columns for, so zooming in shows more of the take rather than fatter bars; it is one pass over
the file either way, and 16 kB of floats.

All of it is one gesture detector, because these compete for the same finger: tap seeks, a held
handle drags, one finger pans when zoomed and scrubs at 1x, two fingers zoom. At 1x with a selection
open a stray drag does nothing at all — seeking out from under an edit is never what it meant.

A WAV cut to a WAV never decodes — the header is rebuilt for the new length and the selected bytes
copied straight through, so what survives is exactly what was recorded. A trim offers the same
FLAC-or-WAV choice as normalising, and overwriting stays WAV to WAV: a file called `.wav` should
not quietly become something else.

Whichever format a copy comes out as, the take's **tempo goes with it**, so an edit never costs a
take the thing the planned drum and bass tracks lock to.

A take this phone cannot decode is **refused, with a message** — a buffer is decoded before
playback starts, so a file that will not play says so instead of running a clock over silence.
Apple Lossless is turned away outright: the decoder on this device reports success and delivers
nothing. Convert those to FLAC, which every Android device can read.

**Double-tapping Play** starts the take from its first sample. Every tap still acts immediately —
the second one is what means "from the top" — rather than holding the first back for a fifth of a
second to see whether a second is coming, which is what a conventional double-click would do to the
one button that must never feel slow.

Playback outlives the screen it started from. A **mini player** sits above the tabs wherever you
are, with the take's name, a play/stop toggle, a seek bar, and a close button — stop keeps the take
and your place in it, close puts it away. Without it, wandering off to the Record tab would leave a
take playing with nothing anywhere to stop it.

## Planned

- Foreground service, so a take survives the app going to the background.
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
    Metronome.kt         the clicks: count-in, and the optional one through a take
    Wav.kt               RIFF header, with tempo in a LIST/INFO chunk
    Flac.kt              the FLAC container, with the same tempo comment as Wav.kt
    Gain.kt              measuring a take's level, and the maths of lifting it
    Waveform.kt          WAV -> peak envelope, and the bucketing both paths share
    AudioDecoder.kt      everything else -> PCM, via MediaCodec: peaks, or an edited copy
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
    SettingsScreen.kt · AboutScreen.kt · QuickHelp.kt · Common.kt · Theme.kt
```

## Known limitations

- Recording stops if the app leaves the foreground — Android takes the microphone away from
  background apps. A foreground service is the fix, and is planned.
- Mono only. Phone microphones are effectively mono; stereo would double the file size for nothing
  until there is an interface to record from.
- Waveforms are drawn from the whole file at a fixed ~420 columns. There is no zoom, which is fine
  for a take and would not be for an arrangement.
- The accent is not legible as *text* on the light theme: it reads 3.2:1 on a white page, where body
  text wants 4.5:1. Buttons, chips, switches and icons are unaffected — they take a brighter step
  that carries its own dark content. The step that would fix the text sits too close to the record
  red and reads brown on a white page, so this is a known trade rather than an oversight.

## License

GPL-3.0-**only** — version 3 of the GNU General Public License, and not "or any later version".
The full text is in [LICENSE](LICENSE), the copyright notice in [COPYRIGHT](COPYRIGHT), and every
source file carries `SPDX-License-Identifier: GPL-3.0-only`.

Fork it, sell it, ship it — the licence asks one thing in return: if you distribute a modified
version, publish your source under the same licence.

### Artwork and name

The wordmark and the icon are licensed separately, under **CC BY 4.0** — use them, modify them, sell
them, as long as you credit the author. [COPYRIGHT](COPYRIGHT) lists the files. A free licence
rather than a reservation on purpose: F-Droid weighs an app's assets as well as its source, and
artwork held back earns the NonFreeAssets flag. CC BY rather than the GPL because the GPL would
oblige the editable SVGs to ship as the artwork's source, and this repository holds only what is
generated from them.

The **name** is not licensed by either grant — give a fork its own. Nothing here is a registered
trademark and none of this is claiming to be one; it is a statement of what the licences cover.
