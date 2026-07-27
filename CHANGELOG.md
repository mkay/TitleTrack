# Changelog

## 0.1 — unreleased

First cut: recording and take management.

- The app is **Title Track**, and its icon is a mirrored waveform in two greens on a dark ground.
  The lime from it is the accent throughout.

- Record to a folder of your choosing (Storage Access Framework tree grant), as 44.1 kHz mono WAV.
- Count-in of 0, 1 or 2 bars at a settable tempo, with an accented downbeat.
- Done → Save, and a Restart that wipes the take and counts back in. Save writes straight
  away under a date-and-time name unless "Prompt for a filename" is turned on.
- A live waveform on the record screen: the last eight seconds as a scrolling peak envelope,
  scaled in decibels, with anything near full scale drawn red. It sits on a panel with a zero line
  that is there from the first launch, so the screen says where the take will appear before there
  is one.
- Silent visual metronome: one dot per beat below the buttons, the lit one walking the bar. It
  starts on the first click of the count-in and carries straight into the take, in step.
- "Listen before recording" (off by default) draws the input before a take starts, for catching
  clipping and setting mic distance. Nothing is written; the microphone is released on leaving the
  screen and before a take opens it.
- Tempo, count-in and the metronome on one row of the record screen. Tempo opens to a slider,
  arrows, and a field to type a number into.
- Input gain, measured rather than guessed: hold Record (or tap Input on the settings row), play,
  and the level test suggests a gain that puts your loudest moment at −9 dBFS. Applied during
  capture — from float, where the boost costs no resolution — and stamped into the WAV as `gain=`.
  One fixed number, evenly applied: nothing tracks the signal, so decays are not swelled back up.
- The player's waveform is drawn in decibels over the same 60 dB window as the record screen and the
  level meter, instead of linearly. A well-recorded take no longer looks like a failed one.
- Browse the recordings folder in place: sub-folders, playback with a seek bar, rename, delete,
  share. The triangle plays a take in place; its name opens it in the player.
- A mini player above the tabs: it shows whatever take is loaded wherever you are, with a
  play/stop toggle, a seek bar and a tap on the name to open the full player. Stopping keeps the
  take and the position rather than dismissing it.
- Open shows a take in a player with its waveform: tap or drag it to seek. WAV is read straight
  through; anything else the device can decode (m4a, mp3, ogg, flac) is decoded to peaks through
  MediaCodec, so imported files draw too.
- Tempo written into each WAV as a `LIST/INFO` comment, so a take keeps it after being copied off
  the phone.
- Record and Library as bottom-bar tabs; Settings and About in the drawer.
- The recordings folder is asked for on first launch, and again if it stops being reachable.
- Settings: time signature, listen-before-recording, prompt-for-filename, keep-screen-on,
  System/Light/Dark theme.
- The record colour is derived from the accent rather than fixed, so the two stay a matched pair —
  per theme, and deeper the closer the accent comes to red, so the two can never merge.
- A darker accent in light mode: the icon's lime, darkened to a green that holds its hue. The
  accent is a text colour as well as a button fill, and the lime measures 1.4:1 on a white page —
  invisible. The green reads at 4.9:1, with white on it at 5.1:1.
- While keep-screen-on is active an indicator sits in the app bar; tapping it explains the battery
  cost and offers a way to turn it off (as in RubberRing).
- Compact bottom tabs, and a library list of even rows with dividers and accented folder icons.
- The player's app bar names where the back arrow goes — the take's folder, or "Library" at the top
  level — instead of repeating the take name shown on the screen below it. Share moved up beside it.
- The player's waveform fills the height that is left, on the record screen's panel and zero line.
- Every take says its format (WAV, M4A, …) in the library list, the mini player and the player.
- Messages appear over the middle of the screen rather than pinned to the bottom, where they used
  to cover the very button they were reporting on. Translucent, outlined, and in the app's own
  surface colours so they follow the theme instead of inverting it.
- Double-tap Play to start the take from the beginning, without delaying the single tap.
- The player's waveform zooms: pinch to zoom, drag to pan, a scroll strip along the bottom, and
  peaks read at 4096 buckets so zooming in resolves more of the take instead of stretching bars.
- Trim, in the player: two grip-tab handles on the waveform (hold to grab, as RubberRing's loop
  markers), the discarded parts greyed over, 10 ms nudge buttons for either edge, and a faint beat
  grid that magnets handles within an eighth of a beat without ever forcing them onto it. WAVs are
  cut on frame boundaries without decoding; other formats come out as a trimmed WAV copy.
- Edits in the player's overflow: rename, and normalise. Normalising asks for a mode (peak or
  loudness) and then a destination — overwrite the take, or save a normalised copy beside it. The
  header, tempo and title are carried through. Imported m4a, mp3, ogg and flac takes are decoded
  and saved as a normalised WAV beside the original, which is never re-encoded.
- Renaming keeps the file's extension. The field shows the name without it, because a rename is not
  a way to change what a file is — and typing a bare name no longer left a take with no extension at
  all, invisible to the app that had just renamed it.
- Move takes between folders: from a row's menu, from the player's overflow, and from the selection
  bar for a whole batch. The destination is browsed in a dialog rather than typed, so the list you
  are standing in stays where it is. The provider moves the file where it can, and the take is
  copied across and the original removed where it cannot; stars follow the take either way.
- Starring a take fills its star at once but leaves the row where it is for a moment before the list
  re-sorts. A row that rises to the top under your thumb reads as having starred the wrong take.
- A take this device cannot decode is refused rather than pretended at: the transport says "Nothing
  on this device can decode that file" instead of running a clock over silence, and the player's
  Play button is disabled for a take whose waveform could not be read. Apple Lossless is refused
  outright, before any codec starts — the vendor decoder on this phone rejects valid ALAC and can
  take the app down with it. Convert such a file to FLAC: lossless, no larger, and a format every
  Android device decodes.
- Editing into a copy asks what to write — FLAC or WAV, FLAC first — for both normalise and trim.
  Both are lossless, so it is a question of size: a copy of a compressed take used to be a WAV
  nearly three times the original.
  Cutting a WAV to a WAV is still a byte copy that never starts a codec; cutting to FLAC encodes,
  losslessly. The container is written here rather than by a muxer, and the tempo travels with
  the take, out of a WAV's `LIST/INFO` and into a FLAC's Vorbis comment. Overwriting is unchanged
  and still WAV-only: a file called `.wav` should not quietly become something else.
