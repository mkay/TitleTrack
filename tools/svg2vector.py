#!/usr/bin/env python3
"""
Turn one of the Figma-exported SVGs into an Android vector drawable.

Deliberately not a general SVG converter — it handles exactly what these exports contain, and
asserts on anything else rather than quietly dropping it. The four things worth knowing:

  * `fill` is an inherited property, and these files set `fill="none"` on the root. A path with no
    `fill` of its own is therefore outline-only, NOT the spec's bare default of black. Getting this
    wrong paints solid shapes over the artwork.
  * Figma wraps a frame's contents in `<g clip-path="url(...)">`, and the artwork really does run
    past that frame, so the clip is load-bearing rather than decoration. It becomes a `<clip-path>`
    on a group of its own.
  * The mark's shading is `userSpaceOnUse` gradients, which map onto a vector drawable's gradient
    directly — no transform to unpick — as long as they have two stops.
  * `--square` pads a non-square source out to a square viewport, which is what an adaptive icon's
    foreground has to be. It centres the artwork rather than stretching it.
  * `--map` swaps colours as they are written, so a second theme's drawable stays generated from the
    one source rather than being a hand-recoloured copy of the first. Every colour in the source
    must be listed, so a re-export that adds one fails here instead of half-recolouring.

Usage: svg2vector.py SRC.svg OUT.xml WIDTH_DP [SCALE] [--square] [--map OLD=NEW,...]
"""
import re, sys
import xml.etree.ElementTree as ET

SVG = '{http://www.w3.org/2000/svg}'

argv = sys.argv[1:]
square = '--square' in argv
cmap = {}
if '--map' in argv:
    i = argv.index('--map')
    for pair in argv[i + 1].split(','):
        old, new = pair.split('=')
        cmap[old.upper()] = new.upper()
    del argv[i:i + 2]
args = [a for a in argv if a != '--square']
src, out, width_dp = args[0], args[1], float(args[2])
scale = float(args[3]) if len(args) > 3 else None

root = ET.parse(src).getroot()
assert root.get('fill') == 'none', 'root no longer sets fill=none; re-check inheritance'
vb = [float(x) for x in root.get('viewBox').split()]
W, H = vb[2], vb[3]

# aapt2 cannot put a string over 32767 bytes in the resource pool: it silently substitutes
# STRING_TOO_LARGE and the build still succeeds, so an oversized path fails at *runtime*, invisibly.
# Figma merges a whole word into one path, which lands well over that.
STRING_LIMIT = 30000


def subpath_groups(d):
    """Split path data into letter-sized groups that are safe to draw separately.

    Counters — the holes in O, A, R — are subpaths wound against their outer contour, and they only
    read as holes while they share a path with it, because the fill rule is evaluated per path. So
    the split may only happen between letters, never inside one: a subpath whose box sits inside the
    group's outer box belongs to that group.
    """
    subs = re.findall(r'[Mm][^Mm]*', d)
    assert not any(x[0] == 'm' for x in subs), 'relative moveto; containment test assumes absolute'

    def box(sd):
        n = [float(x) for x in re.findall(r'-?\d+(?:\.\d+)?', sd)]
        xs, ys = n[0::2], n[1::2]
        return min(xs), min(ys), max(xs), max(ys)

    def inside(a, b):
        return a[0] >= b[0] and a[1] >= b[1] and a[2] <= b[2] and a[3] <= b[3]

    groups = []
    for sd in subs:
        b = box(sd)
        if groups and inside(b, groups[-1][1]):
            groups[-1][0].append(sd)
        else:
            groups.append(([sd], b))
    out, cur = [], ''
    for g, _ in groups:
        piece = ''.join(g)
        if cur and len(cur) + len(piece) > STRING_LIMIT:
            out.append(cur)
            cur = piece
        else:
            cur += piece
    if cur:
        out.append(cur)
    return out


def hexof(c):
    if c == 'white':  return '#FFFFFFFF'
    if c == 'black':  return '#FF000000'
    assert c.startswith('#') and len(c) == 7, f'unexpected colour {c!r}'
    c = c.upper()
    if cmap:
        assert c in cmap, f'{c} is not in --map; the source has a colour the mapping does not'
        c = cmap[c]
    return '#FF' + c[1:]


defs = root.find(f'{SVG}defs')
grads, clips = {}, {}
for g in defs.iter() if defs is not None else []:
    tag = g.tag[len(SVG):]
    if tag == 'linearGradient':
        assert g.get('gradientUnits') == 'userSpaceOnUse', f'{g.get("id")}: not in user space'
        stops = [s.get('stop-color') for s in g]
        assert len(stops) == 2, f'{g.get("id")}: {len(stops)} stops, only two are handled'
        grads[g.get('id')] = [
            '                <gradient android:type="linear"',
            f'                    android:startX="{g.get("x1")}" android:startY="{g.get("y1")}"',
            f'                    android:endX="{g.get("x2")}" android:endY="{g.get("y2")}"',
            f'                    android:startColor="{hexof(stops[0])}"',
            f'                    android:endColor="{hexof(stops[1])}" />']
    elif tag == 'clipPath':
        rect, = list(g)
        assert rect.tag == f'{SVG}rect', 'clip is not a plain rect'
        t = rect.get('transform')
        x, y = (0.0, 0.0)
        if t:
            m = re.fullmatch(r'translate\(([-\d.]+)(?:[ ,]+([-\d.]+))?\)', t)
            assert m, f'unhandled clip transform {t!r}'
            x, y = float(m.group(1)), float(m.group(2) or 0)
        w, h = float(rect.get('width')), float(rect.get('height'))
        clips[g.get('id')] = (f'M{x:g},{y:g} H{x + w:g} V{y + h:g} H{x:g} Z')


def paint_attrs(a, gid):
    """The fill/stroke lines shared by every chunk of one element."""
    out = []
    fill = a.get('fill')
    # No `fill` of its own means it inherits, and the root sets none: outline-only, not black.
    if gid is None and fill is not None:
        out.append(f'            android:fillColor="{hexof(fill)}"')
    if 'fill-opacity' in a:
        out.append(f'            android:fillAlpha="{a["fill-opacity"]}"')
    if 'stroke' in a:
        out.append(f'            android:strokeColor="{hexof(a["stroke"])}"')
        out.append(f'            android:strokeWidth="{a.get("stroke-width", "1")}"')
        if 'stroke-opacity' in a:
            out.append(f'            android:strokeAlpha="{a["stroke-opacity"]}"')
    return out


count = [0, 0]  # elements, outline-only


def path_lines(el):
    a = el.attrib
    d = a['d']
    fill = a.get('fill')
    count[0] += 1
    count[1] += 'fill' not in a
    gid = re.match(r'url\(#([^)]*)\)', fill).group(1) if fill and fill.startswith('url(') else None
    chunks = subpath_groups(d) if len(d) > STRING_LIMIT else [d]
    if len(chunks) > 1:
        print(f'  split a {len(d)}-char path into {len(chunks)} '
              f'(largest {max(len(c) for c in chunks)})')
    body = []
    for chunk in chunks:
        lines = ['        <path', f'            android:pathData="{chunk}"'] + paint_attrs(a, gid)
        if gid is not None:
            lines[-1] += '>'
            body += lines + ['            <aapt:attr name="android:fillColor">'] \
                + grads[gid] + ['            </aapt:attr>', '        </path>']
        else:
            lines[-1] += ' />'
            body += lines
    return body


body = []
for el in root:
    tag = el.tag[len(SVG):]
    if tag == 'defs':
        continue
    elif tag == 'path':
        body += path_lines(el)
    elif tag == 'g':
        cid = re.fullmatch(r'url\(#([^)]*)\)', el.get('clip-path')).group(1)
        body += ['        <group>', f'            <clip-path android:pathData="{clips[cid]}" />']
        for child in el:
            assert child.tag == f'{SVG}path', f'unhandled {child.tag} inside clipped group'
            body += path_lines(child)
        body += ['        </group>']
    else:
        raise AssertionError(f'unhandled top-level <{tag}>')

# A square viewport wide enough for the longer side, with the artwork centred in it.
side = max(W, H) if square else None
vpW, vpH = (side, side) if square else (W, H)
head = ['<?xml version="1.0" encoding="utf-8"?>',
        f'<!-- Generated from {src.rsplit("/", 1)[-1]} by tools/svg2vector.py. Do not hand-edit. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        f'    android:width="{width_dp:g}dp"',
        f'    android:height="{width_dp * vpH / vpW:g}dp"',
        f'    android:viewportWidth="{vpW:g}"',
        f'    android:viewportHeight="{vpH:g}">']
tail = ['</vector>']
if square and (W != side or H != side):
    head += [f'    <group android:translateX="{(side - W) / 2:g}"',
             f'        android:translateY="{(side - H) / 2:g}">']
    tail = ['    </group>'] + tail
if scale:
    head += [f'    <group android:pivotX="{W / 2:g}" android:pivotY="{H / 2:g}"',
             f'        android:scaleX="{scale}" android:scaleY="{scale}">']
    tail = ['    </group>'] + tail
open(out, 'w').write('\n'.join(head + body + tail) + '\n')
paths = sum(1 for line in body if line.strip() == '<path')
print(f'{out}: viewBox {W:g}x{H:g}'
      + (f' padded to {side:g}x{side:g}' if square else '')
      + f', {count[0]} elements -> {paths} paths, {count[1]} outline-only'
      + (f', scaled {scale}' if scale else ''))
