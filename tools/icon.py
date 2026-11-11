"""Draws the Slashboard mod icon as 32x32 pixel art and saves it scaled to 128x128 (hard pixel edges).

Run: uv run --with pillow tools/icon.py [preview.png]
The optional argument also writes a 512px preview.
"""
import sys
from pathlib import Path

from PIL import Image

N = 32
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/slashboard/icon.png"

# Palette matches the in-game screen.
TILE, TILE_HI, TILE_LO, EDGE = (23, 24, 31), (42, 44, 56), (12, 13, 17), (5, 5, 8)
BLUE, BLUE_HI, BLUE_LO = (79, 139, 255), (157, 190, 255), (44, 95, 196)
SEL, SEL_HI = (43, 78, 156), (169, 198, 255)
ROW, ROW_DIM = (110, 110, 122), (74, 75, 85)

img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
px = img.load()


def put(x, y, c):
    px[x, y] = (*c, 255)


def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(x, y, c)


def corner_cut(x, y):
    """True for the pixels trimmed off each corner to round the tile."""
    dx, dy = min(x - 1, 30 - x), min(y - 1, 30 - y)
    return dx + dy < 2


# Tile with a dark outline and a Minecraft-style bevel: light top/left, dark bottom/right.
for y in range(1, 31):
    for x in range(1, 31):
        if corner_cut(x, y):
            continue
        put(x, y, TILE)
for y in range(1, 31):
    for x in range(1, 31):
        if corner_cut(x, y):
            continue
        neighbours = [(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if any(not (1 <= nx <= 30 and 1 <= ny <= 30) or corner_cut(nx, ny) for nx, ny in neighbours):
            put(x, y, EDGE)
rect(3, 2, 28, 2, TILE_HI)
rect(2, 3, 2, 28, TILE_HI)
rect(3, 29, 28, 29, TILE_LO)
rect(29, 3, 29, 28, TILE_LO)

# The slash: 4px wide, stepping one pixel left every 2 rows (a proper "/" lean), lit on its left edge.
for y in range(6, 26):
    c = 15 - (y - 6) // 2
    end = y in (6, 25)  # square ends: no highlight or shadow poking out
    put(c - 1, y, BLUE if end else BLUE_HI)
    put(c, y, BLUE)
    put(c + 1, y, BLUE)
    put(c + 2, y, BLUE if end else BLUE_LO)

# Menu rows: the top one is "selected" like in the screen.
rect(20, 8, 27, 11, SEL)
rect(21, 9, 25, 10, SEL_HI)
rect(21, 15, 26, 16, ROW)
rect(21, 21, 24, 22, ROW_DIM)

OUT.parent.mkdir(parents=True, exist_ok=True)
img.resize((128, 128), Image.NEAREST).save(OUT)
print(f"wrote {OUT}")
if len(sys.argv) > 1:
    img.resize((512, 512), Image.NEAREST).save(sys.argv[1])
    print(f"wrote {sys.argv[1]}")
