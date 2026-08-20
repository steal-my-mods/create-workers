#!/usr/bin/env python3
"""
Generates the mod logo: the Create-family badge, a white-ringed circle of blue
graph paper with the mod's own item drawn large in front of it.

The badge palette and proportions were sampled from Create's own icon.png:
transparent corners, a pure white ring about nine pixels thick, an azure field
around rgb(75,139,193) lightening towards rgb(104,172,217), faint wide grid
lines, and the subject given a white stroke and a soft shadow.

The subject is the hard hat's own item sprite, scaled up by a whole number so the
pixels stay square. Two other approaches were tried and abandoned first:

  * Smooth 2D shapes. A dome on an ellipse brim reads as a bowler if the brim
    rings the shell evenly, and as a bread roll if it does not. The peak and the
    comb are what identify a hard hat, and both are three-dimensional.
  * Isometric voxels, projecting the real boxes from WorkerGearModels, which is
    what Create does for its wrench. It came out looking like a stepped plinth.
    A wrench survives being cut into boxes because a wrench is genuinely
    angular; a hard hat is defined by its rounded shell, which at this size a
    handful of axis-aligned boxes cannot express.

The sprite is the one depiction already known to read as a hard hat, and it has
the side benefit of matching exactly what the player sees in their inventory.
It is left square to the pixel grid rather than tilted like Create's wrench,
because rotating pixel art muddies it.

    python3 tools/generate_logo.py [output.png]
"""

import math
import os
import struct
import sys
import zlib

SPRITE = 'src/main/resources/assets/createworkers/textures/item/hard_hat.png'

OUT = 256                      # final size, matching Create's icon
SS = 3                         # supersampling factor per axis
N = OUT * SS
CX = CY = OUT / 2.0
RADIUS = 124.0                 # outer edge of the badge
RING = 9.0                     # white ring thickness

# --- palette -------------------------------------------------------------------
WHITE       = (255.0, 255.0, 255.0)
FIELD_LIGHT = (104.0, 172.0, 217.0)
FIELD       = ( 75.0, 139.0, 193.0)
FIELD_DEEP  = ( 56.0, 114.0, 168.0)
GRID        = (126.0, 190.0, 228.0)
SHADOW      = ( 30.0,  64.0, 100.0)

# --- graph paper ---------------------------------------------------------------
GRID_SPACING = 46.0
GRID_HALF_WIDTH = 2.5
GRID_ALPHA = 0.28

# --- presentation --------------------------------------------------------------
SPRITE_SCALE = 11              # whole number, so sprite pixels stay square
STROKE = 6.0                   # white outline thickness, in output pixels
SHADOW_DX, SHADOW_DY = 6.0, 8.0
SHADOW_ALPHA = 0.26


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


def read_png(path):
    """
    Minimal reader for the 8-bit RGBA, non-interlaced PNGs this project writes.
    Keeps the tool self-contained rather than shelling out to an image library.
    """
    with open(path, 'rb') as handle:
        data = handle.read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('{}: not a PNG'.format(path))

    width = height = None
    compressed = b''
    offset = 8
    while offset < len(data):
        length = struct.unpack('>I', data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        if kind == b'IHDR':
            width, height, depth, colour, compression, filtering, interlace = \
                struct.unpack('>IIBBBBB', payload)
            if (depth, colour, interlace) != (8, 6, 0):
                raise ValueError('{}: expected 8-bit RGBA, non-interlaced'.format(path))
            if (compression, filtering) != (0, 0):
                raise ValueError('{}: unexpected compression or filter method'.format(path))
        elif kind == b'IDAT':
            compressed += payload
        elif kind == b'IEND':
            break
        offset += 12 + length

    raw = zlib.decompress(compressed)
    stride = width * 4
    rows = []
    previous = bytearray(stride)
    position = 0
    for _ in range(height):
        filter_type = raw[position]
        position += 1
        line = bytearray(raw[position:position + stride])
        position += stride
        for i in range(stride):
            left = line[i - 4] if i >= 4 else 0
            up = previous[i]
            upper_left = previous[i - 4] if i >= 4 else 0
            if filter_type == 0:
                pass
            elif filter_type == 1:
                line[i] = (line[i] + left) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + up) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif filter_type == 4:
                estimate = left + up - upper_left
                da, db, dc = abs(estimate - left), abs(estimate - up), abs(estimate - upper_left)
                nearest = left if (da <= db and da <= dc) else (up if db <= dc else upper_left)
                line[i] = (line[i] + nearest) & 0xFF
            else:
                raise ValueError('{}: unknown filter {}'.format(path, filter_type))
        rows.append([tuple(line[x * 4:x * 4 + 4]) for x in range(width)])
        previous = line
    return width, height, rows


def opaque_bounds(width, height, pixels):
    """Bounding box of the visible part, so the badge centres on the art not the canvas."""
    min_x, min_y, max_x, max_y = width, height, -1, -1
    for y in range(height):
        for x in range(width):
            if pixels[y][x][3] > 0:
                min_x = min(min_x, x)
                max_x = max(max_x, x)
                min_y = min(min_y, y)
                max_y = max(max_y, y)
    if max_x < 0:
        raise ValueError('sprite is entirely transparent')
    return min_x, min_y, max_x + 1, max_y + 1


def place_sprite():
    """Blows the sprite up to badge scale, returning a supersampled colour buffer."""
    width, height, pixels = read_png(SPRITE)
    min_x, min_y, max_x, max_y = opaque_bounds(width, height, pixels)

    drawn_width = (max_x - min_x) * SPRITE_SCALE
    drawn_height = (max_y - min_y) * SPRITE_SCALE
    left = CX - drawn_width / 2.0 - min_x * SPRITE_SCALE
    top = CY - drawn_height / 2.0 - min_y * SPRITE_SCALE

    step = SPRITE_SCALE * SS
    buffer = [None] * (N * N)
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[y][x]
            if a == 0:
                continue
            packed = (r, g, b)
            x0 = int(round((left + x * SPRITE_SCALE) * SS))
            y0 = int(round((top + y * SPRITE_SCALE) * SS))
            for gy in range(max(0, y0), min(N, y0 + step)):
                row = gy * N
                for gx in range(max(0, x0), min(N, x0 + step)):
                    buffer[row + gx] = packed
    return buffer


def outline_distance(buffer, reach):
    """
    Chamfer distance from the subject, in supersampled pixels, so the white stroke can
    be taken as a band around it. Two sweeps, which is plenty for so short a reach.
    """
    far = float(reach + 2)
    distance = [0.0 if cell is not None else far for cell in buffer]
    straight, diagonal = 1.0, 1.41421356

    for y in range(N):
        row = y * N
        previous = row - N
        for x in range(N):
            index = row + x
            best = distance[index]
            if best == 0.0:
                continue
            if x > 0:
                best = min(best, distance[index - 1] + straight)
            if y > 0:
                best = min(best, distance[previous + x] + straight)
                if x > 0:
                    best = min(best, distance[previous + x - 1] + diagonal)
                if x < N - 1:
                    best = min(best, distance[previous + x + 1] + diagonal)
            distance[index] = best

    for y in range(N - 1, -1, -1):
        row = y * N
        following = row + N
        for x in range(N - 1, -1, -1):
            index = row + x
            best = distance[index]
            if best == 0.0:
                continue
            if x < N - 1:
                best = min(best, distance[index + 1] + straight)
            if y < N - 1:
                best = min(best, distance[following + x] + straight)
                if x < N - 1:
                    best = min(best, distance[following + x + 1] + diagonal)
                if x > 0:
                    best = min(best, distance[following + x - 1] + diagonal)
            distance[index] = best

    return distance


def background(x, y):
    """The graph-paper field at one point, before the subject is laid over it."""
    glow = math.hypot(x - (CX - 44.0), y - (CY - 52.0)) / (RADIUS * 1.55)
    colour = lerp(FIELD_LIGHT, FIELD, min(1.0, glow))
    distance = math.hypot(x - CX, y - CY)
    rim = min(1.0, max(0.0, (distance / RADIUS - 0.55) / 0.45)) ** 1.4
    colour = lerp(colour, FIELD_DEEP, rim)
    for coordinate in (x, y):
        offset = abs(((coordinate + GRID_SPACING / 2.0) % GRID_SPACING) - GRID_SPACING / 2.0)
        if offset < GRID_HALF_WIDTH:
            colour = lerp(colour, GRID, GRID_ALPHA)
    return colour


def render():
    buffer = place_sprite()
    reach = STROKE * SS
    distance = outline_distance(buffer, reach)

    shadow_dx = int(round(SHADOW_DX * SS))
    shadow_dy = int(round(SHADOW_DY * SS))
    inner = RADIUS - RING

    rows = []
    samples = SS * SS
    for py in range(OUT):
        row = []
        for px in range(OUT):
            r = g = b = a = 0.0
            for sy in range(SS):
                gy = py * SS + sy
                y = (gy + 0.5) / SS
                for sx in range(SS):
                    gx = px * SS + sx
                    x = (gx + 0.5) / SS

                    from_centre = math.hypot(x - CX, y - CY)
                    if from_centre > RADIUS:
                        continue
                    if from_centre > inner:
                        colour = WHITE
                    else:
                        index = gy * N + gx
                        cell = buffer[index]
                        if cell is not None:
                            colour = (float(cell[0]), float(cell[1]), float(cell[2]))
                        elif distance[index] <= reach:
                            colour = WHITE
                        else:
                            colour = background(x, y)
                            sx0, sy0 = gx - shadow_dx, gy - shadow_dy
                            if 0 <= sx0 < N and 0 <= sy0 < N:
                                cast = sy0 * N + sx0
                                if buffer[cast] is not None or distance[cast] <= reach:
                                    colour = lerp(colour, SHADOW, SHADOW_ALPHA)

                    r += colour[0]
                    g += colour[1]
                    b += colour[2]
                    a += 1.0

            if a <= 0.0:
                row.append((0, 0, 0, 0))
                continue
            row.append((
                int(round(min(255.0, r / a))),
                int(round(min(255.0, g / a))),
                int(round(min(255.0, b / a))),
                int(round(255.0 * a / samples)),
            ))
        rows.append(row)
    return rows


def write_png(path, rows):
    height, width = len(rows), len(rows[0])
    raw = b''.join(b'\x00' + b''.join(struct.pack('BBBB', *p) for p in row) for row in rows)

    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data
                + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)
    return len(png)


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/createworkers_icon.png'
    size = write_png(target, render())
    print("wrote {} ({}x{}, {} bytes)".format(target, OUT, OUT, size))


if __name__ == '__main__':
    main()
