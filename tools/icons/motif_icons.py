#!/usr/bin/env python3
"""
The needafile app icons with drawn motifs (since 0.101.0): Layers (the standard), Blueprint, Network and Transfer.
Dark plate, light technical lines, gray in three tones, orange as the accent and a little turquoise.

    python3 tools/icons/motif_icons.py [--svg DIR]

Writes per motif needafile_icon_<motif>_background/_foreground/_monochrome.xml to app/src/main/res/drawable and the
adaptive icon needafile_icon_<motif>.xml to app/src/main/res/mipmap-anydpi; with --svg also <motif>.svg (the icon on
its rounded plate, e.g. for the store) and <motif>-mono.svg (the single-color layer, to check it) into DIR.

Drawn in 512 × 512, the visible 72 dp of the adaptive icon; the background fills the whole 108 dp (768 × 768), the
foreground is moved by 128 into it. Vector drawables know no dashes, so dashed lines are cut into pieces here. The
single-color layer for themed icons can't paint over what lies behind, so every shape in it is clipped by the opaque
shapes drawn after it (a clip path: a large square with that shape as a hole).
"""
import math
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"

FULL = 768
OFFSET = 128

INK = "#0C1015"
PLATE = "#151B22"
PLATE2 = "#1C232C"
LINE = "#DDE3EA"
DIM = "#4A5562"
G_TOP = "#DDE3EA"
G_LEFT = "#8E98A5"
G_RIGHT = "#5E6875"
OR = "#FF5A1F"
OR_TOP = "#FF8A57"
OR_DARK = "#C23E0E"
TEAL = "#3CC8B4"
BP = "#12365C"
BP_LINE = "#D6E6F5"
BP_GRID = "#1A4470"

# how strongly each color shows in the single-color layer; 0 = only hides what lies behind
MONO_ALPHA = {
    INK: 0, PLATE: 0, PLATE2: 0, BP: 0,
    LINE: 0.8, BP_LINE: 0.8, DIM: 0.35,
    G_TOP: 0.55, G_LEFT: 0.38, G_RIGHT: 0.22,
    OR: 1.0, OR_TOP: 0.85, OR_DARK: 0.7,
    TEAL: 0.9,
}


@dataclass
class Op:
    """A polygon or polyline, filled, stroked or both; [clip] keeps it inside another polygon."""
    pts: list
    fill: str | None = None
    stroke: str | None = None
    width: float = 0.0
    alpha: float = 1.0
    closed: bool = True
    dash: tuple | None = None
    cap: str = "round"
    join: str = "round"
    clip: list | None = None


class Canvas:
    def __init__(self, bg, fit=True):
        self.bg = bg
        self.back = []
        self.ops = []
        self.fit = fit

    def poly(self, ps, fill=None, stroke=None, w=0.0, alpha=1.0, closed=True, dash=None, cap="round", join="round", clip=None):
        self.ops.append(Op(list(ps), fill, stroke, w, alpha, closed, dash, cap, join, clip))

    def line(self, p, q, stroke, w, alpha=1.0, dash=None, cap="round", clip=None):
        self.poly([p, q], None, stroke, w, alpha, False, dash, cap, clip=clip)


# ---------- projection and shapes ----------

C30 = math.cos(math.radians(30))


def iso(ox, oy, k):
    """Isometric: x to the lower right, y to the lower left, z up."""
    def f(x, y, z=0.0):
        return (ox + (x - y) * C30 * k, oy + (x + y) * 0.5 * k - z * k)
    return f


def circle(cx, cy, r, n=48, a0=0.0, a1=2 * math.pi):
    return [(cx + r * math.cos(a0 + (a1 - a0) * i / n), cy + r * math.sin(a0 + (a1 - a0) * i / n)) for i in range(n + 1)]


def rrect(x0, y0, x1, y1, r, n=8):
    ps = []
    for cx, cy, a in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for i in range(n + 1):
            t = math.radians(a + 90 * i / n)
            ps.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    return ps


def box(s, P, x, y, z, w, d, h, top, left, right):
    A, B, C = P(x, y + d, z), P(x + w, y + d, z), P(x + w, y, z)
    At, Bt, Ct, Dt = P(x, y + d, z + h), P(x + w, y + d, z + h), P(x + w, y, z + h), P(x, y, z + h)
    s.poly([A, B, Bt, At], left)
    s.poly([B, C, Ct, Bt], right)
    s.poly([Dt, Ct, Bt, At], top)


def extrude_xz(s, P, poly, y0, t, front, top, right):
    """An outline in the (x, z) plane pulled forward from y0 to y0 + t; the front (y0 + t), tops and right sides show.
    [poly] counterclockwise (x to the right, z up)."""
    quads = []
    n = len(poly)
    for i in range(n):
        (x1, z1), (x2, z2) = poly[i], poly[(i + 1) % n]
        nx, nz = (z2 - z1), -(x2 - x1)
        if nx + nz <= 1e-9:
            continue
        col = top if nz >= abs(nx) * 0.7 else right
        q = [P(x1, y0 + t, z1), P(x2, y0 + t, z2), P(x2, y0, z2), P(x1, y0, z1)]
        quads.append(((x1 + x2) / 2 + (z1 + z2) / 2, q, col))
    for _, q, col in sorted(quads, key=lambda e: e[0]):
        s.poly(q, col)
    s.poly([P(x, y0 + t, z) for x, z in poly], front)


def standing_folder(s, P, x, y, z, w, h, t):
    """A standing folder in orange: back with the tab, in front the somewhat lower flap."""
    tw, th, sh = w * 0.38, h * 0.16, h * 0.12
    gap = t * 0.6
    back = [(x, z), (x + w, z), (x + w, z + h), (x + tw + sh, z + h), (x + tw, z + h + th), (x, z + h + th)]
    extrude_xz(s, P, back, y, t, OR_DARK, OR_TOP, OR_DARK)
    flap = [(x, z), (x + w, z), (x + w, z + h * 0.84), (x, z + h * 0.84)]
    extrude_xz(s, P, flap, y + t + gap, t, OR, OR_TOP, OR_DARK)


def server(s, P, x, y, z, w, d, units, uh):
    for u in range(units):
        zu = z + u * (uh + 0.06)
        box(s, P, x, y, zu, w, d, uh, G_TOP, G_LEFT, G_RIGHT)
        s.line(P(x + 0.12, y + d, zu + uh / 2), P(x + w * 0.55, y + d, zu + uh / 2), INK, 2.4, alpha=0.75)
        c = P(x + w * 0.8, y + d, zu + uh / 2)
        s.poly(circle(c[0], c[1], 3.2, 12), TEAL)


# ---------- motifs ----------

def layers():
    """Exploded view: storage at the bottom, a folder with files in the middle, an orange folder with a padlock on top."""
    s = Canvas(INK)
    O = (300.0, 112.0)
    ax = (118.0, 39.3)
    ay = (-196.0, 65.3)

    def P(x, y, z=0.0):
        return (O[0] + x * ax[0] + y * ay[0], O[1] + x * ax[1] + y * ay[1] - z)

    def folder_plate(z):
        return [P(0.13, 0, z), P(1, 0, z), P(1, 1, z), P(0, 1, z), P(0, 0.62, z), P(0.13, 0.53, z)]

    zs = (0, 92, 184)
    s.poly([P(0, 0, 0), P(1, 0, 0), P(1, 1, 0), P(0, 1, 0)], PLATE2, LINE, 3.5)
    for i in range(1, 8):
        v = i / 8
        s.line(P(v, 0.04, 0), P(v, 0.96, 0), LINE, 1.6, alpha=0.32)
    for i in range(3):
        c = P(0.9, 0.12 + i * 0.07, 0)
        s.poly(circle(c[0], c[1], 3.4, 16), TEAL)
    for x, y in ((1, 0), (1, 1), (0, 1)):
        s.line(P(x, y, zs[0]), P(x, y, zs[1]), LINE, 2, alpha=0.6, dash=(7, 6), cap="butt")
    s.poly(folder_plate(zs[1]), PLATE, LINE, 3.5)
    for r in range(2):
        for c in range(4):
            x0, y0 = 0.3 + r * 0.32, 0.12 + c * 0.21
            q = [P(x0, y0, zs[1]), P(x0 + 0.22, y0, zs[1]), P(x0 + 0.22, y0 + 0.15, zs[1]), P(x0, y0 + 0.15, zs[1])]
            s.poly(q, None, LINE, 2.4)
    for x, y in ((1, 0), (1, 1), (0, 1)):
        s.line(P(x, y, zs[1]), P(x, y, zs[2]), LINE, 2, alpha=0.6, dash=(7, 6), cap="butt")
    z = zs[2]
    s.poly(folder_plate(z), PLATE, OR, 4.2)
    cx, cy = 0.62, 0.5
    shackle = [P(cx - 0.02 - 0.2 * math.sin(t), cy + 0.1 * math.cos(t), z) for t in [math.pi * i / 24 for i in range(25)]]
    s.poly(shackle, None, OR, 7, closed=False, cap="butt")
    s.poly([P(cx - 0.02, cy - 0.17, z), P(cx + 0.24, cy - 0.17, z), P(cx + 0.24, cy + 0.17, z), P(cx - 0.02, cy + 0.17, z)], OR)
    k = P(cx + 0.1, cy, z)
    s.poly(circle(k[0], k[1], 5.5, 20), PLATE)
    return s


def blueprint():
    """A folder as a technical drawing with dimensions on blueprint blue, a hatched orange padlock in front."""
    s = Canvas(BP, fit=False)
    for i in range(-OFFSET, FULL - OFFSET + 1, 32):
        s.back.append(Op([(i, -OFFSET), (i, FULL - OFFSET)], None, BP_GRID, 1.4, closed=False, cap="butt"))
        s.back.append(Op([(-OFFSET, i), (FULL - OFFSET, i)], None, BP_GRID, 1.4, closed=False, cap="butt"))
    x0, x1, y0, y1 = 140, 352, 178, 352
    s.poly([(x0, y1), (x0, y0 - 10), (x0 + 74, y0 - 10), (x0 + 92, y0 + 8), (x1, y0 + 8), (x1, y1)], None, BP_LINE, 6)
    s.line((x0, y0 + 46), (x1, y0 + 46), BP_LINE, 6)
    s.line((246, 118), (246, 400), BP_LINE, 2, alpha=0.55, dash=(10, 5, 2, 5), cap="butt")
    yd = 128
    s.line((x0, yd), (x1, yd), BP_LINE, 2.2, cap="butt")
    for x in (x0, x1):
        s.line((x, yd - 14), (x, y0 - 18), BP_LINE, 1.6, alpha=0.7, cap="butt")
        s.line((x - 7, yd + 7), (x + 7, yd - 7), BP_LINE, 2.6)
    xd = 394
    s.line((xd, y0 - 10), (xd, y1), BP_LINE, 2.2, cap="butt")
    for y in (y0 - 10, y1):
        s.line((x1 + 10, y), (xd + 12, y), BP_LINE, 1.6, alpha=0.7, cap="butt")
        s.line((xd - 7, y + 7), (xd + 7, y - 7), BP_LINE, 2.6)
    lx0, lx1, ly0, ly1 = 292, 378, 300, 372
    body = rrect(lx0, ly0, lx1, ly1, 8)
    s.poly(body, BP)
    for i in range(-90, 100, 11):
        s.line((lx0 + i, ly1), (lx0 + i + 80, ly1 - 80), OR, 3, cap="butt", clip=body)
    s.poly(body, None, OR, 5)
    cx = (lx0 + lx1) / 2
    s.poly(circle(cx, ly0 + 2, 25, 32, math.pi, 2 * math.pi), None, OR, 7, closed=False, cap="butt")
    s.poly(circle(cx, 330, 8, 24), OR)
    s.poly([(cx - 4, 334), (cx + 4, 334), (cx + 6, 352), (cx - 6, 352)], OR)
    return s


def network():
    """An orange folder on a plate, linked with three servers."""
    s = Canvas(INK)
    P = iso(256, 250, 46)
    R = 1.75
    s.poly([P(-R, -R), P(R, -R), P(R, R), P(-R, R)], PLATE, DIM, 2)
    hub = (0.0, -0.15)
    sz = 0.72
    nodes = [(-1.6, 0.5), (0.5, -1.6), (0.85, 0.85)]
    for nx, ny in nodes:
        s.line(P(*hub), P(nx + sz / 2, ny + sz / 2), TEAL, 3, dash=(8, 7), cap="butt")
    h = P(*hub)
    s.poly(circle(h[0], h[1], 5, 16), TEAL)
    for nx, ny in sorted(nodes[:2], key=lambda e: e[0] + e[1]):
        server(s, P, nx, ny, 0, sz, sz, units=3, uh=0.27)
    standing_folder(s, P, -0.62, -0.72, 0.0, 1.24, 1.05, 0.16)
    server(s, P, 0.85, 0.85, 0, sz, sz, units=2, uh=0.27)
    return s


def transfer():
    """A phone sends an orange block of data over to a server."""
    s = Canvas(INK)
    P = iso(256, 236, 44)
    server(s, P, 0.7, -2.2, 0, 1.1, 1.1, units=4, uh=0.42)
    a, b = (0.2, 1.6, 1.3), (1.2, -1.0, 1.9)

    def on_arc(u):
        return (a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u + 1.5 * math.sin(math.pi * u))

    s.poly([P(*on_arc(i / 40)) for i in range(41)], None, TEAL, 3.2, closed=False, dash=(9, 8), cap="butt")
    cx, cy, cz = on_arc(0.5)
    box(s, P, cx - 0.38, cy - 0.38, cz - 0.38, 0.76, 0.76, 0.76, OR_TOP, OR, OR_DARK)
    extrude_xz(s, P, rrect(-1.9, 0.0, -0.4, 2.7, 0.28), 1.2, 0.18, PLATE2, G_TOP, G_LEFT)
    s.poly([P(x, 1.38, z) for x, z in rrect(-1.75, 0.18, -0.55, 2.52, 0.16)], PLATE)
    for i in range(3):
        zz = 2.15 - i * 0.42
        s.line(P(-1.55, 1.38, zz), P(-0.75, 1.38, zz), LINE, 2.6, alpha=0.75)
    s.poly([P(-1.6, 1.38, 0.4), P(-1.05, 1.38, 0.4), P(-1.05, 1.38, 0.85), P(-1.6, 1.38, 0.85)], OR)
    return s


MOTIFS = {"layers": layers, "blueprint": blueprint, "network": network, "transfer": transfer}


# ---------- output ----------

def placed(canvas, size=330.0, max_scale=1.4):
    """The foreground ops moved (and scaled) into the 768 grid, centered, dashes cut into pieces."""
    if canvas.fit:
        pts = [p for op in canvas.ops for p in op.pts]
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        sc = min(size / (max(xs) - min(xs)), size / (max(ys) - min(ys)), max_scale)
        cx, cy = (max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2
    else:
        sc, cx, cy = 1.0, 256.0, 256.0

    def tf(p):
        return (OFFSET + 256 + sc * (p[0] - cx), OFFSET + 256 + sc * (p[1] - cy))

    return [moved(op, tf, sc) for op in canvas.ops], [moved(op, lambda p: (p[0] + OFFSET, p[1] + OFFSET), 1.0) for op in canvas.back]


def moved(op, tf, sc):
    pts = [tf(p) for p in op.pts]
    clip = [tf(p) for p in op.clip] if op.clip else None
    pieces = dashed(pts, [d * sc for d in op.dash]) if op.dash else [pts]
    return [Op(piece, op.fill, op.stroke, op.width * sc, op.alpha, op.closed and not op.dash, None, op.cap, op.join, clip) for piece in pieces]


def dashed(pts, pattern):
    pieces, current, drawing, k = [], [pts[0]], True, 0
    left = pattern[0]
    for a, b in zip(pts, pts[1:]):
        length = math.dist(a, b)
        pos = 0.0
        while length - pos > left:
            pos += left
            p = (a[0] + (b[0] - a[0]) * pos / length, a[1] + (b[1] - a[1]) * pos / length)
            if drawing:
                current.append(p)
                pieces.append(current)
            else:
                current = [p]
            drawing = not drawing
            k = (k + 1) % len(pattern)
            left = pattern[k]
        left -= length - pos
        if drawing:
            current.append(b)
    if drawing and len(current) > 1:
        pieces.append(current)
    return pieces


def path_data(pts, closed):
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + ("Z" if closed else "")


def area(pts):
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(pts, pts[1:] + pts[:1]))


def hole(pts):
    """A square larger than the icon with [pts] cut out (nonzero rule: the hole runs the other way round)."""
    if area(pts) > 0:
        pts = list(reversed(pts))
    return "M-100,-100 L868,-100 L868,868 L-100,868Z " + path_data(pts, True)


def bbox(op):
    xs, ys = [p[0] for p in op.pts], [p[1] for p in op.pts]
    m = op.width
    return min(xs) - m, min(ys) - m, max(xs) + m, max(ys) + m


def overlaps(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def mono_ops(ops):
    """The single-color layer: each shape with its alpha, clipped by the opaque shapes after it."""
    result = []
    for i, op in enumerate(ops):
        fa = MONO_ALPHA[op.fill] * op.alpha if op.fill else 0
        sa = MONO_ALPHA[op.stroke] * op.alpha if op.stroke else 0
        if fa == 0 and sa == 0:
            continue
        box_i = bbox(op)
        covers = [later.pts for later in ops[i + 1:] if later.fill and later.closed and later.alpha == 1 and not later.clip and overlaps(box_i, bbox(later))]
        result.append((op, fa, sa, covers))
    return result


VD_HEAD = (
    '<?xml version="1.0" encoding="utf-8"?>\n<!-- {what}; written by tools/icons/motif_icons.py -->\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp"\n    android:height="108dp"\n'
    f'    android:viewportWidth="{FULL}"\n    android:viewportHeight="{FULL}">\n'
)


def vd_color(c):
    return "#FF" + c.lstrip("#").upper()


def vd_path(op, indent, fill=None, fill_alpha=1.0, stroke=None, stroke_alpha=1.0):
    a = [f'android:pathData="{path_data(op.pts, op.closed)}"']
    if fill:
        a.append(f'android:fillColor="{fill}"')
        if fill_alpha != 1:
            a.append(f'android:fillAlpha="{fill_alpha:.3f}"')
    if stroke:
        a.append(f'android:strokeColor="{stroke}"')
        a.append(f'android:strokeWidth="{op.width:.2f}"')
        if stroke_alpha != 1:
            a.append(f'android:strokeAlpha="{stroke_alpha:.3f}"')
        a.append(f'android:strokeLineCap="{op.cap}"')
        a.append(f'android:strokeLineJoin="{op.join}"')
    sep = "\n" + indent + "    "
    return f"{indent}<path{sep}" + sep.join(a) + " />\n"


def vd_colored(ops, what):
    out = VD_HEAD.format(what=what)
    for op in ops:
        kw = dict(
            fill=vd_color(op.fill) if op.fill else None, fill_alpha=op.alpha,
            stroke=vd_color(op.stroke) if op.stroke else None, stroke_alpha=op.alpha,
        )
        if op.clip:
            out += f'    <group>\n        <clip-path android:pathData="{path_data(op.clip, True)}" />\n'
            out += vd_path(op, "        ", **kw) + "    </group>\n"
        else:
            out += vd_path(op, "    ", **kw)
    return out + "</vector>\n"


def vd_mono(ops, what):
    out = VD_HEAD.format(what=what)
    for op, fa, sa, covers in mono_ops(ops):
        kw = dict(fill="#FFFFFFFF" if fa else None, fill_alpha=fa, stroke="#FFFFFFFF" if sa else None, stroke_alpha=sa)
        clips = ([path_data(op.clip, True)] if op.clip else []) + [hole(c) for c in covers]
        if clips:
            out += "    <group>\n" + "".join(f'        <clip-path android:pathData="{c}" />\n' for c in clips)
            out += vd_path(op, "        ", **kw) + "    </group>\n"
        else:
            out += vd_path(op, "    ", **kw)
    return out + "</vector>\n"


def svg_path(op, fill=None, fill_alpha=1.0, stroke=None, stroke_alpha=1.0):
    a = f'<path d="{path_data(op.pts, op.closed)}" fill="{fill or "none"}"'
    if fill and fill_alpha != 1:
        a += f' fill-opacity="{fill_alpha:.3f}"'
    if stroke:
        a += f' stroke="{stroke}" stroke-width="{op.width:.2f}" stroke-linecap="{op.cap}" stroke-linejoin="{op.join}"'
        if stroke_alpha != 1:
            a += f' stroke-opacity="{stroke_alpha:.3f}"'
    return a + "/>"


def svg_doc(body, defs, plate):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="{OFFSET} {OFFSET} 512 512">'
        f'<defs><clipPath id="plate"><rect x="{OFFSET}" y="{OFFSET}" width="512" height="512" rx="115"/></clipPath>{defs}</defs>'
        f'<g clip-path="url(#plate)"><rect x="0" y="0" width="{FULL}" height="{FULL}" fill="{plate}"/>{body}</g></svg>'
    )


def svg_colored(ops, back, bg):
    defs, body = [], []
    for op in back + ops:
        el = svg_path(op, op.fill, op.alpha, op.stroke, op.alpha)
        if op.clip:
            n = len(defs)
            defs.append(f'<clipPath id="c{n}"><path d="{path_data(op.clip, True)}"/></clipPath>')
            el = f'<g clip-path="url(#c{n})">{el}</g>'
        body.append(el)
    return svg_doc("".join(body), "".join(defs), bg)


def svg_mono(ops):
    defs, body = [], []
    for op, fa, sa, covers in mono_ops(ops):
        el = svg_path(op, "#1B1B1F" if fa else None, fa, "#1B1B1F" if sa else None, sa)
        for c in ([path_data(op.clip, True)] if op.clip else []) + [hole(c) for c in covers]:
            n = len(defs)
            defs.append(f'<clipPath id="c{n}"><path d="{c}"/></clipPath>')
            el = f'<g clip-path="url(#c{n})">{el}</g>'
        body.append(el)
    return svg_doc("".join(body), "".join(defs), "#E8E4F0")


def main():
    svg_dir = Path(sys.argv[sys.argv.index("--svg") + 1]) if "--svg" in sys.argv else None
    for name, draw in MOTIFS.items():
        canvas = draw()
        fg_pieces, back_pieces = placed(canvas)
        ops = [op for pieces in fg_pieces for op in pieces]
        back = [Op([(0, 0), (FULL, 0), (FULL, FULL), (0, FULL)], canvas.bg)] + [op for pieces in back_pieces for op in pieces]
        base = f"needafile_icon_{name}"
        (RES / "drawable" / f"{base}_background.xml").write_text(vd_colored(back, f"needafile app icon {name}, background"))
        (RES / "drawable" / f"{base}_foreground.xml").write_text(vd_colored(ops, f"needafile app icon {name}, foreground"))
        (RES / "drawable" / f"{base}_monochrome.xml").write_text(vd_mono(ops, f"needafile app icon {name}, single-color layer for themed icons"))
        (RES / "mipmap-anydpi" / f"{base}.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- needafile app icon {name}; written by tools/icons/motif_icons.py -->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@drawable/{base}_background" />\n'
            f'    <foreground android:drawable="@drawable/{base}_foreground" />\n'
            f'    <monochrome android:drawable="@drawable/{base}_monochrome" />\n'
            '</adaptive-icon>\n'
        )
        if svg_dir:
            svg_dir.mkdir(parents=True, exist_ok=True)
            (svg_dir / f"{name}.svg").write_text(svg_colored(ops, back, canvas.bg))
            (svg_dir / f"{name}-mono.svg").write_text(svg_mono(ops))
    print("written:", ", ".join(MOTIFS))


if __name__ == "__main__":
    main()
