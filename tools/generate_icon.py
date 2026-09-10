#!/usr/bin/env python3
"""Generate the StarField icon: black squares projected the way the app projects stars.

StarfieldRenderer.kt puts every star in 3D world space and projects it with a
perspective + look-at matrix. This script does the exact same thing offline: a
projection matrix turns each square's world position into its centre (x, y) and
its projected size -- which is all an SVG <rect> needs. Every square is kept
entirely inside the circle inscribed in the canvas.

Run:  python tools/generate_icon.py [output.svg]
"""

import math
import random
import sys
import time
from pathlib import Path

# --- the icon -------------------------------------------------------------
CANVAS = 512  # side of the square icon, in px
STAR_COUNT = 50  # how many squares to project
SEED = int(time.time())
SQUARE_SIZE = 0.1  # side of a square, world units
FIELD_SPREAD = 1.00  # half-extent of the star field, in near-plane half-heights
DEAD_ZONE = 0.10  # keep squares out of the vanishing point in the middle
FRAME_MARGIN = 0.01  # squares stay this far inside the inscribed circle (fraction of canvas)
MIN_GAP = 0.01  # smallest gap between two squares (fraction of canvas)
CANDIDATES = 32  # worlds tried per square; the roomiest one wins (even spread)
COLOR = "#000000"
BACKGROUND = "#ffffff"
OUTPUT = Path(__file__).resolve().parent.parent / "icons" / f"starfield-icon_{SEED}.svg"

# --- camera / projection (same setup as the renderer) ---------------------
FOV_Y = 60.0  # perspectiveM(..., 60f, ratio, ...)
ASPECT = 1.0  # square icon -> ratio 1
NEAR_PLANE = 1.0  # perspectiveM(..., 1f, ...)
FAR_PLANE = 10000.0
EYE = (0.0, 0.0, 0.0)  # setLookAtM(..., eye ...)
TARGET = (0.0, 0.0, -1.0)  # ... looking down -z
UP = (0.0, 1.0, 0.0)

# --- depth of the field: these two numbers make the funnel ----------------
NEAR_DIST = 1.4  # closest square (biggest)
FAR_DIST = 3.2  # farthest square (smallest)


# --- 4x4 matrices, row-major (world -> clip) ------------------------------
def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def perspective(fov_y_deg, aspect, near, far):
    f = 1.0 / math.tan(math.radians(fov_y_deg) / 2.0)
    return [
        [f / aspect, 0.0, 0.0, 0.0],
        [0.0, f, 0.0, 0.0],
        [0.0, 0.0, (far + near) / (near - far), 2.0 * far * near / (near - far)],
        [0.0, 0.0, -1.0, 0.0],
    ]


def look_at(eye, target, up):
    def sub(a, b):
        return [a[i] - b[i] for i in range(3)]

    def dot(a, b):
        return sum(a[i] * b[i] for i in range(3))

    def cross(a, b):
        return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]

    def norm(v):
        n = math.sqrt(dot(v, v))
        return [c / n for c in v]

    f = norm(sub(target, eye))  # forward
    s = norm(cross(f, up))  # right
    u = cross(s, f)  # up
    return [
        [s[0], s[1], s[2], -dot(s, eye)],
        [u[0], u[1], u[2], -dot(u, eye)],
        [-f[0], -f[1], -f[2], dot(f, eye)],
        [0.0, 0.0, 0.0, 1.0],
    ]


def project(mvp, vertex):
    x, y, z = vertex
    return [sum(mvp[i][j] * (x, y, z, 1.0)[j] for j in range(4)) for i in range(4)]


def to_pixels(point):
    """Perspective divide + map NDC [-1, 1] to px (y grows downwards in SVG)."""
    x, y, _, w = point
    return (x / w * 0.5 + 0.5) * CANVAS, (1.0 - (y / w * 0.5 + 0.5)) * CANVAS


def project_square(mvp, centre, size):
    """Project a world-space square -> (x, y, width, height) in px.

    The renderer draws each star as an axis-aligned quad, from (x, y) to
    (x - size, y - size) at a constant z, so the matrix gives us both the
    projected centre and the projected side length, one axis per screen axis.
    """
    cx, cy = to_pixels(project(mvp, centre))
    ex, ey = to_pixels(project(mvp, (centre[0] - size, centre[1] - size, centre[2])))
    width, height = abs(cx - ex), abs(cy - ey)
    return cx - width / 2.0, cy - height / 2.0, width, height


# --- the star field -------------------------------------------------------
def separation(a, b):
    """Gap between two projected squares: 0 when they overlap."""
    dx = abs(a[0] + a[2] / 2.0 - (b[0] + b[2] / 2.0)) - (a[2] + b[2]) / 2.0
    dy = abs(a[1] + a[3] / 2.0 - (b[1] + b[3] / 2.0)) - (a[3] + b[3]) / 2.0
    return math.hypot(max(0.0, dx), max(0.0, dy))


def fits_circle(rect):
    """True when the whole rect lies inside the circle inscribed in the canvas.

    The circle has the canvas' width and height as its diameter, so it touches
    all four edges and cuts the corners. A circle is convex, so a rect is
    entirely inside it exactly when all four of its corners are.
    """
    radius = CANVAS / 2.0 - FRAME_MARGIN * CANVAS
    cx = cy = CANVAS / 2.0
    x, y, w, h = rect
    return all(
        math.hypot(px - cx, py - cy) <= radius
        for px, py in ((x, y), (x + w, y), (x, y + h), (x + w, y + h))
    )


def sample_world(rng, spread):
    """A star in world space, sampled like the renderer: spread and dead zone."""
    while True:
        u, v = rng.uniform(-1.0, 1.0), rng.uniform(-1.0, 1.0)
        if max(abs(u), abs(v)) >= DEAD_ZONE:
            break
    # uniform in 1/depth, so near (big) squares are as likely as far (small) ones
    depth = NEAR_DIST / rng.uniform(NEAR_DIST / FAR_DIST, 1.0)
    return u * spread, v * spread, -depth


def squares():
    """Project up to STAR_COUNT stars; returns SVG rects, far ones first."""
    mvp = matmul(perspective(FOV_Y, ASPECT, NEAR_PLANE, FAR_PLANE), look_at(EYE, TARGET, UP))
    # one fixed slab of world space, whatever the depth: that is what makes
    # distant squares crowd the vanishing point while near ones reach the edges
    spread = FIELD_SPREAD * math.tan(math.radians(FOV_Y) / 2.0) * NEAR_DIST
    min_gap = MIN_GAP * CANVAS
    rng = random.Random(SEED)
    rects = []
    for _ in range(STAR_COUNT):
        best, best_gap = None, -1.0
        for _ in range(CANDIDATES):
            rect = project_square(mvp, sample_world(rng, spread), SQUARE_SIZE)
            if not fits_circle(rect):
                continue
            gap = min((separation(rect, other) for other in rects), default=math.inf)
            if gap > best_gap:
                best, best_gap = rect, gap
        if best is not None and best_gap >= min_gap:
            rects.append(best)
    # paint far (small) squares first so near ones overlap on top, like the GL depth test
    return sorted(rects, key=lambda r: r[2])


def to_svg(rects):
    body = "\n".join(
        f'    <rect x="{x:.2f}" y="{y:.2f}" width="{w:.2f}" height="{h:.2f}"/>'
        for x, y, w, h in rects
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}"'
        f' viewBox="0 0 {CANVAS} {CANVAS}">\n'
        f'  <rect width="{CANVAS}" height="{CANVAS}" fill="{BACKGROUND}"/>\n'
        f'  <g fill="{COLOR}">\n{body}\n  </g>\n</svg>\n'
    )


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else OUTPUT
    rects = squares()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(to_svg(rects), encoding="utf-8")
    print(f"{out} ({len(rects)} squares)")


if __name__ == "__main__":
    main()
