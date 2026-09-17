#!/usr/bin/env python3
"""
Generates the Worker Station's block textures.

Five 16x16 sheets, written by hand the way the logo and the profession clothing
are, so the art is this mod's own rather than borrowed or traced:

  worker_station_base          the plinth's sides, planks under a hi-vis stripe
  worker_station_base_top      its top, a worn surface with a border
  worker_station_post          the two posts holding the board up
  worker_station_board         the board, with three empty pegs
  worker_station_board_staffed the same board with a hard hat on a peg

The board is the part that changes with the block state, so a station with a job
in it can be told from an empty one at a glance and from any angle -- which the
old top-face-only version could not, a block being something you mostly see from
the side.

**The board's art sits in the window x 2..14, y 1..11.** The model gives that face
no explicit UVs, so Minecraft derives them from the element's own coordinates
(x 2..14, y 5..15 becomes uv 2,1 -> 14,11), which is what keeps the drawn pixels
square and on the block grid instead of stretched across it. Move the board in
the model and this window moves with it.

The palette is the hat's own yellow over spruce-ish browns, which is what ties
the block to the item without either being a copy of the other.

    python3 tools/generate_station_textures.py [output-directory]
"""

import os
import struct
import sys
import zlib

OUTPUT_DIR = 'src/main/resources/assets/createworkers/textures/block'

SIZE = 16

# Where the board's front face actually shows, derived from the model. See above.
BOARD_WINDOW = (2, 1, 14, 11)

# Spruce-ish planks, dark to light, plus the hat's yellow and its shadow.
PLANK_DARK = (0x3B, 0x2A, 0x1B, 255)
PLANK = (0x4A, 0x36, 0x23, 255)
PLANK_LIGHT = (0x59, 0x41, 0x2B, 255)
DESK = (0x6B, 0x4F, 0x33, 255)
DESK_LIGHT = (0x7C, 0x5C, 0x3D, 255)
BOARD = (0x8A, 0x6A, 0x46, 255)
BOARD_LIGHT = (0x9B, 0x79, 0x51, 255)
BOARD_EDGE = (0x5A, 0x42, 0x2B, 255)
HI_VIS = (0xF2, 0xC2, 0x3B, 255)
HI_VIS_DARK = (0xC2, 0x96, 0x22, 255)
IRON = (0x8A, 0x8A, 0x8A, 255)
IRON_DARK = (0x5E, 0x5E, 0x5E, 255)
PAPER = (0xD8, 0xD0, 0xBE, 255)


def blank(colour):
    return [[colour for _ in range(SIZE)] for _ in range(SIZE)]


def speckle(pixels, colour, scatter):
    """A repeatable grain, so a flat face is not a flat colour."""
    multiplier, modulus = scatter
    for y in range(SIZE):
        for x in range(SIZE):
            if (x * multiplier + y * 9) % modulus == 0:
                pixels[y][x] = colour
    return pixels


def base_side():
    """Horizontal boards, with a seam every four rows and a hi-vis stripe."""
    pixels = blank(PLANK)
    for y in range(SIZE):
        for x in range(SIZE):
            if y % 4 == 0:
                pixels[y][x] = PLANK_DARK
            elif (x * 7 + y * 3) % 11 == 0:
                pixels[y][x] = PLANK_LIGHT

    # The stripe sits low, because only the bottom three pixels of this sheet are
    # ever on the plinth -- the rest of the block is board and posts.
    for x in range(SIZE):
        pixels[13][x] = HI_VIS_DARK
        pixels[14][x] = HI_VIS
        pixels[15][x] = HI_VIS_DARK
    return pixels


def base_top():
    """A worn surface with a border, so the plinth reads as a thing you put things on."""
    pixels = speckle(blank(DESK), DESK_LIGHT, (5, 13))
    for i in range(SIZE):
        pixels[0][i] = PLANK_DARK
        pixels[SIZE - 1][i] = PLANK_DARK
        pixels[i][0] = PLANK_DARK
        pixels[i][SIZE - 1] = PLANK_DARK
    return pixels


def post():
    """Dark timber with iron banding, which is what carries the board's weight."""
    pixels = speckle(blank(PLANK_DARK), PLANK, (3, 7))
    for band in (3, 12):
        for x in range(SIZE):
            pixels[band][x] = IRON_DARK
            pixels[band + 1][x] = IRON
    return pixels


def board(hat):
    """The board, with three pegs, and optionally work hung on the middle one."""
    pixels = speckle(blank(BOARD), BOARD_LIGHT, (5, 9))

    left, top, right, bottom = BOARD_WINDOW
    for x in range(left, right):
        pixels[top][x] = BOARD_EDGE
        pixels[bottom - 1][x] = BOARD_EDGE
    for y in range(top, bottom):
        pixels[y][left] = BOARD_EDGE
        pixels[y][right - 1] = BOARD_EDGE

    pegs = (left + 2, (left + right) // 2 - 1, right - 3)
    for peg in pegs:
        pixels[top + 3][peg] = IRON_DARK
        pixels[top + 4][peg] = IRON

    if not hat:
        return pixels

    # A hard hat hung on the middle peg: a dome with its brim, seen face on.
    middle = pegs[1]
    for y in range(top + 5, top + 8):
        for x in range(middle - 3, middle + 4):
            reach = abs(x - middle)
            if y == top + 5 and reach > 1:
                continue
            if y == top + 6 and reach > 2:
                continue
            pixels[y][x] = HI_VIS if reach < 2 else HI_VIS_DARK
    for x in range(middle - 4, middle + 5):
        pixels[top + 8][x] = HI_VIS_DARK

    # A docket pinned beside it, because a board with one thing on it reads as
    # decoration and a board with two reads as in use.
    for y in range(top + 4, top + 9):
        for x in range(left + 1, left + 4):
            pixels[y][x] = PAPER
    for x in range(left + 1, left + 4):
        pixels[top + 4][x] = IRON_DARK
    return pixels


# --- the smallest PNG writer that will do -------------------------------------

def png(pixels):
    raw = b''
    for row in pixels:
        raw += b'\x00'
        for r, g, b, a in row:
            raw += struct.pack('BBBB', r, g, b, a)

    def chunk(kind, payload):
        return (struct.pack('>I', len(payload)) + kind + payload
                + struct.pack('>I', zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack('>IIBBBBB', SIZE, SIZE, 8, 6, 0, 0, 0)
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', header)
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


SHEETS = {
    'worker_station_base': base_side,
    'worker_station_base_top': base_top,
    'worker_station_post': post,
    'worker_station_board': lambda: board(False),
    'worker_station_board_staffed': lambda: board(True),
}


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else OUTPUT_DIR
    os.makedirs(directory, exist_ok=True)

    for name, draw in sorted(SHEETS.items()):
        destination = os.path.join(directory, '%s.png' % name)
        with open(destination, 'wb') as handle:
            handle.write(png(draw()))
        print('wrote %s' % destination)


if __name__ == '__main__':
    main()
