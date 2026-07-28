#!/usr/bin/env python3
"""
Measure where a take's beats actually are, by finding the app's own metronome clicks inside it.

Record with the audible metronome on and the phone on its **speaker**, playing nothing. The clicks
land in the take — normally the whole reason that setting is for headphones only — and here that is
the point: they are a reference the app itself emitted, so this can say, without anybody listening:

  * whether the take's first sample really is beat one, and
  * how far behind its own grid the audio the player heard actually was.

This is a matched filter, not onset detection. `Metronome.renderClick` synthesises the clicks, so
their exact waveform is known and is rebuilt here to correlate against; peaks in that correlation are
clicks, located to about a millisecond. The four things worth knowing:

  * **Do not use generic onset detection instead.** It was tried on a strummed take and found an
    "onset" every 156 ms, which produced a mean deviation near zero that looked like alignment and
    meant nothing — a uniform scatter gives the same number as a locked performance.
  * **A constant offset is latency; a slope is a wrong tempo; scatter is the player.** The three are
    told apart by the straight-line fit below, which is why it is reported rather than just a mean.
  * Only clicks about one beat from a neighbour are kept. Room noise and the odd knock correlate
    well enough to be picked, and they are not on the grid by definition.
  * The tempo comes from the take's own `LIST/INFO` comment, written there when it was recorded.
    `--bpm` overrides it, for a file that carries none.

The offset it reports is a *round trip*: the device's output latency, plus whatever the input path
adds. That is the honest figure to correct by, and more than `AudioTrack.getTimestamp()` can see on
its own — which is why this exists alongside the compensation in `Metronome`/`AudioRecorder` rather
than being replaced by it.

Usage: clickcheck.py TAKE.wav [--bpm 110]        (needs numpy)
"""
import struct
import sys

try:
    import numpy as np
except ImportError:
    sys.exit('clickcheck.py needs numpy: pip install numpy')

# Metronome.kt, verbatim — if the clicks are ever re-voiced, these move with them.
ACCENT_HZ = 1_100.0
PLAIN_HZ = 800.0
CLICK_MS = 30
PARTIAL = 2.76
DECAY = 9.0

argv = sys.argv[1:]
bpm_override = None
if '--bpm' in argv:
    i = argv.index('--bpm')
    bpm_override = float(argv[i + 1])
    del argv[i:i + 2]
if len(argv) != 1:
    sys.exit(__doc__.strip().splitlines()[-1])
path = argv[0]


def read_wav(name):
    """The chunks this app writes, and nothing else — anything odd is worth failing on."""
    raw = open(name, 'rb').read()
    assert raw[:4] == b'RIFF' and raw[8:12] == b'WAVE', f'{name} is not a RIFF/WAVE file'
    pos, fmt, data, tags = 12, None, None, {}
    while pos + 8 <= len(raw):
        cid = raw[pos:pos + 4]
        size = struct.unpack('<I', raw[pos + 4:pos + 8])[0]
        body = raw[pos + 8:pos + 8 + size]
        if cid == b'fmt ':
            _, channels, rate, _, _, bits = struct.unpack('<HHIIHH', body[:16])
            fmt = (channels, rate, bits)
        elif cid == b'data':
            data = body
        elif cid == b'LIST' and body[:4] == b'INFO':
            p = 4
            while p + 8 <= len(body):
                sid = body[p:p + 4].decode('latin1')
                ssize = struct.unpack('<I', body[p + 4:p + 8])[0]
                tags[sid] = body[p + 8:p + 8 + ssize].split(b'\0')[0].decode('latin1')
                p += 8 + ssize + (ssize & 1)
        pos += 8 + size + (size & 1)
    assert fmt and data, f'{name} has no fmt/data chunk'
    channels, rate, bits = fmt
    assert bits == 16, f'{name} is {bits}-bit; this reads the 16-bit takes the app records'
    x = np.frombuffer(data, dtype='<i2').astype(np.float64) / 32768.0
    if channels > 1:
        x = x.reshape(-1, channels).mean(axis=1)
    return x, rate, tags


def render_click(freq, rate):
    """Metronome.renderClick: a fundamental, an inharmonic partial, and a fast exponential decay."""
    n = rate * CLICK_MS // 1000
    t = np.arange(n) / rate
    env = np.exp(-DECAY * t * (1_000.0 / CLICK_MS))
    tone = np.sin(2 * np.pi * freq * t) + 0.6 * np.sin(2 * np.pi * freq * PARTIAL * t)
    return tone * env * 0.5


def correlate(x, template):
    """Matched filter over the whole take, by FFT — a direct sum is minutes rather than moments."""
    n = 1 << int(len(x) + len(template) - 1).bit_length()
    c = np.fft.irfft(np.fft.rfft(x, n) * np.conj(np.fft.rfft(template, n)), n)
    return np.abs(c[:len(x) - len(template) + 1])


def peaks(c, height, distance):
    """Local maxima above [height], no two within [distance] — strongest wins."""
    idx = np.flatnonzero(c > height)
    out = []
    for i in idx[np.argsort(c[idx])[::-1]]:
        if all(abs(i - j) >= distance for j in out):
            out.append(i)
    return np.array(sorted(out))


x, rate, tags = read_wav(path)
bpm = bpm_override
if bpm is None:
    for kv in tags.get('ICMT', '').split():
        if kv.startswith('bpm='):
            bpm = float(kv[4:])
if bpm is None:
    sys.exit('no bpm in the file and none given — pass --bpm')

beat = 60.0 / bpm
frames_per_beat = rate * beat
peak = float(np.abs(x).max())
print(f'{path}')
print(f'  {len(x) / rate:.2f} s at {rate} Hz | tags: {tags.get("ICMT", "(none)")}')
print(f'  peak {peak:.3f} ({20 * np.log10(peak or 1e-9):+.1f} dBFS)'
      + ('  ** clipped, and clipping smears the click **' if peak >= 0.999 else ''))
print(f'  grid: {bpm:g} bpm -> {frames_per_beat:.3f} frames/beat ({beat * 1000:.2f} ms)')

c = sum(correlate(x, t / np.linalg.norm(t))
        for t in (render_click(ACCENT_HZ, rate), render_click(PLAIN_HZ, rate)))
found = peaks(c, height=np.percentile(c, 98.5), distance=int(frames_per_beat * 0.55)) / rate

# Anything not about a beat from a neighbour is not a click of ours, whatever it correlated with.
gaps = np.diff(found)
keep = [i for i in range(len(found))
        if any(abs(gaps[j] - beat) < 0.06 for j in (i - 1, i) if 0 <= j < len(gaps))]
t = found[keep]
if len(t) < 4:
    sys.exit(f'only {len(t)} clicks found — was the metronome on, and on the speaker?')

k = np.round(t / beat)
dev = (t - k * beat) * 1000
slope, intercept = np.linalg.lstsq(np.vstack([k, np.ones_like(k)]).T, t, rcond=None)[0]
half = len(dev) // 2

print(f'\n  {len(t)} clicks, {t[0]:.3f} s .. {t[-1]:.3f} s')
print(f'  deviation from the grid: mean {dev.mean():+.1f} ms, median {np.median(dev):+.1f}, '
      f'sd {dev.std():.2f}, min {dev.min():+.1f}, max {dev.max():+.1f}')
print(f'  straight-line fit: beat {slope * 1000:.4f} ms ({60.0 / slope:.4f} bpm), '
      f'offset at beat one {intercept * 1000:+.1f} ms')
print(f'  drift: first half {dev[:half].mean():+.2f} ms -> second half {dev[half:].mean():+.2f} ms')

print()
if abs(dev.mean()) < 5:
    print('  VERDICT: on the grid. The take begins on the beat the player heard.')
else:
    print(f'  VERDICT: the clicks are {dev.mean():+.0f} ms off the take\'s own grid — '
          f'{abs(dev.mean()) / (beat * 1000) * 100:.0f}% of a beat.')
    print('           A constant offset like this is latency, not tempo: the take was recorded '
          'that far\n           ahead of what the player was listening to, and a band locked to '
          'the file will\n           sound that far out. See the compensation in Metronome/'
          'AudioRecorder.')
if abs(60.0 / slope - bpm) > 0.05:
    print(f'  ALSO: the clicks measure {60.0 / slope:.3f} bpm against a grid of {bpm:g} — '
          'that is a tempo fault,\n        not a latency one, and it will drift a whole bar out '
          'over a long take.')
