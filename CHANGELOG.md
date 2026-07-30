# Changelog

## 0.1 — unreleased

First cut: recording and take management.

- The app is **Title Track**, and its icon is a mirrored waveform in three ambers on a dark ground.
  The gold from it is the accent throughout, on both themes. Both leave their surfaces plain — white
  and grey on light, Material's own on dark — and let the accent carry the warmth by itself. Each
  was tinted onto the accent's hue at one point and each gave it up: a tint faint enough to keep a
  page neutral is not a decision anyone reads, and a tint strong enough to read makes the page a
  colour rather than a ground.

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
  take and the position rather than dismissing it. It stands on the tab bar's own ground, the two
  being stacked with nothing between them and any difference showing as a seam the width of the
  phone.
- Open shows a take in a player with its waveform: tap or drag it to seek. WAV is read straight
  through; anything else the device can decode (m4a, mp3, ogg, flac) is decoded to peaks through
  MediaCodec, so imported files draw too.
- Tempo written into each WAV as a `LIST/INFO` comment, so a take keeps it after being copied off
  the phone.
- Record and Library as bottom-bar tabs; Settings in the drawer, with **Quick help** pinned to its
  bottom past a rule — every gesture that has no affordance to find it by, and nothing that already
  has a label. About is the third tab in Settings instead: the drawer's bottom slot belongs to what
  you want mid-take.
- **Support Title Track** in the drawer, above the recents so it stays put as they grow: a dialog
  with three ways to say the app is worth keeping up — a like on a page of mine, a star on the
  repo, or Ko-fi. Each is a link out to the browser, so the app still asks for no INTERNET
  permission and the like is counted only when you press the button on the page.
- The recordings folder is asked for on first launch, and again if it stops being reachable.
- Settings in tabs, the two that set things split by when you come to them: Recording (microphone,
  time signature, metronome, saving) and System (recordings folder, library, screen and theme).
  Saving is under Recording, since naming a take happens at the end of one with the instrument still
  in hand. About is a third tab beside them — a destination rather than a setting, but one tap from
  either half instead of buried at the foot of one.
- An audible metronome through the take, off by default and meant for headphones — on a speaker the
  click lands in the take at whatever level the speaker managed and cannot be taken out afterwards.
  Behind a long press on the record screen's Metronome cell as well as in Settings; the cell reads
  On, Click, On + click or Off, so the row says whether the click is live without being opened. The
  count-in and the take are played by one stream, because two AudioTracks each start on their own
  latency and left an audible gap exactly at the downbeat.
- The Record button is an accent button with oblique light stripes over it, rather than a red slab.
  The button and the running clock were one colour on the argument that they are one thing, but they
  never appear at once — the button before a take, the clock during one — so the button takes the same
  fill and label as Play or Save, and the stripes are the whole of the difference. Red is left to the
  clock and to a clipping meter, which are states rather than controls.
- The stripes are a white tint at 20%, subtle by design: over a saturated amber it mostly desaturates
  rather than lightens, landing at about 1.15:1 against the fill. Dark hazard-tape banding was the
  first cut and is what the label rules out — a dark band is the amber-950 label's own colour thinned,
  and took it from 7.0:1 to 4.8:1 wherever one crossed a letter.
- The accent takes **two steps by job rather than one per theme**. Anything drawn — a filled button,
  a picked chip, a switch track, a play triangle, a star — is the icon's own gold on both themes.
  Anything set as type takes a deeper step on the light page, since the gold reads 2.1:1 there. The
  line is drawn or set: a glyph has no counters to lose, and beside a gold button the deeper step was
  reading brown. Accent text on light is 3.2:1 and does not clear the 4.5:1 bar — taken deliberately,
  because the step that would is browner still and nothing in the app is only this colour.
- While keep-screen-on is active an indicator sits in the app bar; tapping it explains the battery
  cost and offers a way to turn it off (as in RubberRing).
- Compact bottom tabs, and a library list of even rows with dividers and accented folder icons.
- The waveform panel sits a tenth of a stop off the page — enough to bound the waveform, not enough
  to be the heaviest thing on the record screen. It took the top of the surface ramp, matched to the
  tab bar so the two large tinted areas on a screen agreed; the two no longer meet anywhere, the bar
  having left the ramp for the mini player's ground.
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
- A note per take: chords, words, whatever the idea was. Written from the player, shown under the
  take's title, and marked in the list so a folder says which takes have one. Kept by the app, as
  the stars are, so it works for imported m4a and mp3 takes as well as for recorded WAVs — and it
  follows a take through a rename or a move, folders included. Emptying a note removes it; nothing
  else does, short of deleting the take.
- Trim, Level and Note are buttons above the transport; rename, move, share and delete are in the
  player's overflow, in the order a library row's menu uses. Normalising asks for a mode (peak or
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
