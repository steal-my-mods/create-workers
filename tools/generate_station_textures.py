#!/usr/bin/env python3
"""
Generates the Worker Station's block textures.

Five 16x16 sheets, written by hand the way the logo and the profession clothing
are, so the art is this mod's own rather than borrowed or traced:

  worker_station_side       the bench's sides, planks under a hi-vis stripe
  worker_station_top        the counter, worn and bordered
  worker_station_bottom     plain boards, seen only from underneath
  worker_station_board      the board's front, pegged and ready to have work hung on it
  worker_station_board_back its back and edges, plain boarding

**Nothing here says whether the station has a job in it.** That was two attempts:
a hat painted on the top face, which you could only see by standing over the
block, and then a hat painted on the board, which was a small drawing of a hat
next to nothing. It is drawn by WorkerStationRenderer now, as the actual hats
that are in the rack -- which is unambiguous, needs no art, and counts.

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

# The board is 16 wide and 5 tall, so its front face uses the sheet's full width
# and its top five rows. The pegs go there.
BOARD_ROWS = 5

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


def bench_side():
    """Horizontal boards with a hi-vis stripe just under the counter's lip."""
    pixels = blank(PLANK)
    for y in range(SIZE):
        for x in range(SIZE):
            if y % 4 == 0:
                pixels[y][x] = PLANK_DARK
            elif (x * 7 + y * 3) % 11 == 0:
                pixels[y][x] = PLANK_LIGHT

    # Rows 5 and 6, which is under the lip of an eleven-pixel bench. The stripe is
    # what says "site equipment" across a room, so it wants to be on the part of the
    # sheet the bench actually shows.
    for x in range(SIZE):
        pixels[5][x] = HI_VIS
        pixels[6][x] = HI_VIS_DARK
    return pixels


def bench_top():
    """The counter: worn, bordered, and scuffed where work lands."""
    pixels = speckle(blank(DESK), DESK_LIGHT, (5, 13))
    for i in range(SIZE):
        pixels[0][i] = PLANK_DARK
        pixels[SIZE - 1][i] = PLANK_DARK
        pixels[i][0] = PLANK_DARK
        pixels[i][SIZE - 1] = PLANK_DARK
    for y in range(6, 11):
        for x in range(4, 12):
            if (x + y) % 3:
                pixels[y][x] = DESK_LIGHT
    return pixels


def bench_bottom():
    """Plain boards. Nobody sees this, but somebody will look."""
    return speckle(blank(PLANK_DARK), PLANK, (3, 7))


def board_back():
    """The board's back and edges: plain boarding, no pegs."""
    return speckle(blank(BOARD), BOARD_LIGHT, (5, 9))


def board_front():
    """The board a station hangs its work on: a row of pegs, and nothing on them.

    Empty on purpose. What is in a station is drawn as the hats themselves -- see the
    note at the top of this file -- so the art here is the furniture, not the state.
    """
    pixels = board_back()

    # Only the top BOARD_ROWS of this sheet ever show on the board's face, the box
    # being five pixels tall; everything below is edge nobody sees from the front.
    for x in range(SIZE):
        pixels[0][x] = BOARD_EDGE
        pixels[BOARD_ROWS - 1][x] = BOARD_EDGE

    for peg in range(2, SIZE - 1, 3):
        pixels[1][peg] = IRON_DARK
        pixels[2][peg] = IRON
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
    'worker_station_side': bench_side,
    'worker_station_top': bench_top,
    'worker_station_bottom': bench_bottom,
    'worker_station_board': board_front,
    'worker_station_board_back': board_back,
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
