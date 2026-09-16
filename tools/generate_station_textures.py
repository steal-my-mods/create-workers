#!/usr/bin/env python3
"""
Generates the Worker Station's block textures.

Three 16x16 sheets, written by hand the way the logo and the profession clothing
are, so the art is this mod's own rather than borrowed or traced:

  worker_station_side        a plank face with a hi-vis stripe near the top
  worker_station_top         a bare desk top with a peg to hang a hat on
  worker_station_top_staffed the same desk with a hard hat hung on it

The top is what changes with the block state, so a player can tell a station
with a job in it from an empty one by looking down at it. The palette is the
hat's own yellow over spruce-ish browns, which is what ties the block to the
item without either being a copy of the other.

    python3 tools/generate_station_textures.py [output-directory]
"""

import os
import struct
import sys
import zlib

OUTPUT_DIR = 'src/main/resources/assets/createworkers/textures/block'

SIZE = 16

# Spruce-ish planks, dark to light, plus the hat's yellow and its shadow.
PLANK_DARK = (0x3B, 0x2A, 0x1B, 255)
PLANK = (0x4A, 0x36, 0x23, 255)
PLANK_LIGHT = (0x59, 0x41, 0x2B, 255)
DESK = (0x6B, 0x4F, 0x33, 255)
DESK_LIGHT = (0x7C, 0x5C, 0x3D, 255)
HI_VIS = (0xF2, 0xC2, 0x3B, 255)
HI_VIS_DARK = (0xC2, 0x96, 0x22, 255)
IRON = (0x8A, 0x8A, 0x8A, 255)


def blank(colour):
    return [[colour for _ in range(SIZE)] for _ in range(SIZE)]


def plank_face():
    """Horizontal boards, with a seam every four rows and a hi-vis stripe."""
    pixels = blank(PLANK)
    for y in range(SIZE):
        for x in range(SIZE):
            if y % 4 == 0:
                pixels[y][x] = PLANK_DARK
            elif (x * 7 + y * 3) % 11 == 0:
                pixels[y][x] = PLANK_LIGHT

    # The stripe: two rows of hi-vis with a darker line under it, the way the
    # vest is drawn, so the block reads as site equipment from across a room.
    for x in range(SIZE):
        pixels[2][x] = HI_VIS
        pixels[3][x] = HI_VIS_DARK
    return pixels


def desk_top(hat):
    """A desk with a peg in the middle, and optionally a hat hanging on it."""
    pixels = blank(DESK)
    for y in range(SIZE):
        for x in range(SIZE):
            if (x * 5 + y * 9) % 13 == 0:
                pixels[y][x] = DESK_LIGHT
    # A border, so the top reads as a surface rather than as noise.
    for i in range(SIZE):
        pixels[0][i] = PLANK_DARK
        pixels[SIZE - 1][i] = PLANK_DARK
        pixels[i][0] = PLANK_DARK
        pixels[i][SIZE - 1] = PLANK_DARK

    if not hat:
        # The bare peg: a small iron hook, waiting for a hat.
        for y in range(7, 10):
            pixels[y][8] = IRON
        pixels[9][7] = IRON
        return pixels

    # A hard hat seen from above: a dome with a brim, and the ridge down it.
    for y in range(4, 12):
        for x in range(4, 12):
            dx, dy = x - 7.5, y - 7.5
            distance = dx * dx + dy * dy
            if distance <= 12.5:
                pixels[y][x] = HI_VIS
            elif distance <= 16.5:
                pixels[y][x] = HI_VIS_DARK
    for y in range(5, 11):
        pixels[y][7] = HI_VIS_DARK
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
    'worker_station_side': plank_face,
    'worker_station_top': lambda: desk_top(False),
    'worker_station_top_staffed': lambda: desk_top(True),
}


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else OUTPUT_DIR
    os.makedirs(directory, exist_ok=True)

    for name, draw in SHEETS.items():
        destination = os.path.join(directory, '%s.png' % name)
        with open(destination, 'wb') as handle:
            handle.write(png(draw()))
        print('wrote %s' % destination)


if __name__ == '__main__':
    main()
