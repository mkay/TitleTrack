# Changelog

## 0.1 — unreleased

First cut: recording and take management.

- The app is **Title Track**, and its icon is a mirrored waveform in three ambers on a dark ground.
  The gold from it is the accent throughout, on both themes, and the surfaces are tinted onto its
  hue: a warm near-black and a warm off-white rather than Material's faintly violet greys.

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
- The Record button is an accent button with oblique light stripes over it, rather than a red slab.
  The button and the running clock were one colour on the argument that they are one thing, but they
  never appear at once — the button before a take, the clock during one — so the button takes the same
  fill and label as Play or Save, and the stripes are the whole of the difference. Red is left to the
  clock and to a clipping meter, which are states rather than controls.
- The stripes are a white tint, subtle by design, and its alpha differs per theme so that the visible
  texture does not: white lightens the light theme's deeper accent readily and mostly desaturates the
  dark theme's saturated one, so 8% and 20% both land at about 1.15:1. Dark hazard-tape banding was
  the first cut and is what the labels rule out — on the dark theme a dark band is the label's own
  colour thinned, and took it from 7.0:1 to 4.8:1 wherever one crossed a letter.
- The accent takes a step per theme rather than one for both: legible as text on the light page for
  the first time, at 4.8:1 where every previous palette shipped a washed-out 1.9–2.1:1. Amber is the
  first family where the fix is two rungs down rather than four, and the collision that used to rule
  it out — a deep accent beside a red fill — went away with the red button.
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
- A take this device cannot decode now says so instead of appearing to play. Play is refused with a
  message, and the player's button is disabled for a take whose waveform could not be read. Apple
  Lossless is turned away outright: the decoder here claims the file and then delivers silence.
  Convert those to FLAC — lossless, no larger, and readable on any Android device.
- Normalise and trim can save a copy as **FLAC or WAV**, FLAC first. Both are lossless, so the
  choice is only size — a copy of a compressed take used to come out around three times bigger as
  a WAV. The take's tempo travels into either format, so an edit never costs a take the thing the
  planned drum and bass tracks lock to. Overwriting is unchanged and still WAV to WAV.
