#!/usr/bin/env python3
"""
Draws the evening and night crews' vests onto worker_gear.png, from the day one.

A worker's crew has to be readable across a room, and the vest is the thing to
say it with: it is the hi-vis garment, it is the largest flat area on the model,
and it is already the part a player looks at. The hard hat stays the same colour
on every crew, so a worker still reads as a worker first.

Three colours means three sets of UVs, because the vest is geometry rather than a
tint -- a multiply over an orange texture cannot produce white, and the stripe
would go with it. So the day vest is the drawn artwork and the other two are
derived from it here, which is what keeps them in step: redraw the day vest by
hand and the others follow on the next run.

The recolouring keeps each pixel's shading and moves only the colour. A pixel's
brightness is taken as its largest channel and used to scale a flat base, so the
ramp the artwork already has survives; the reflective stripe is picked out by
being nearly grey and given a base of its own, because a white night vest needs a
dark stripe where an orange one needs a light one.

Everything is laid out inside the existing 128x64 sheet. The day vests occupy the
left of rows 28-43; the evening pair goes to their right and the night pair on the
free rows below, so nothing else on the sheet moves.

    python3 tools/generate_gear_shifts.py [gear-texture]
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate_logo import read_png, write_png

GEAR = 'src/main/resources/assets/createworkers/textures/entity/worker_gear.png'
MODEL = 'src/main/java/com/createworkers/client/model/WorkerGearModels.java'

# A vest box is 8 wide, 9 tall and `depth` deep, and texOffs lays its faces out in
# a block 2*(8+depth) wide by depth+9 tall. Both of the drawn ones, with the size
# that block works out at.
SOURCES = (
    ('deep', 6, (0, 28), (28, 15)),
    ('slim', 4, (32, 28), (24, 13)),
)

# Where each crew's copy of each box goes. Day is the artwork and is not written.
DESTINATIONS = {
    ('evening', 'deep'): (60, 28),
    ('evening', 'slim'): (92, 28),
    ('night', 'deep'): (0, 44),
    ('night', 'slim'): (32, 44),
}

# body, then the reflective stripe. Scaled by each source pixel's own brightness.
PALETTES = {
    'evening': ((255, 218, 46), (235, 239, 245)),
    'night': ((245, 248, 252), (150, 157, 166)),
}

# How close a pixel's channels must be before it counts as the stripe rather than
# as vest. The artwork's orange is nowhere near this; its stripe is nearly grey.
GREY_TOLERANCE = 30

TRANSPARENT = (0, 0, 0, 0)


def recolour(pixel, body, stripe):
    red, green, blue, alpha = pixel
    if alpha == 0:
        return TRANSPARENT

    brightest = max(red, green, blue)
    base = stripe if brightest - min(red, green, blue) < GREY_TOLERANCE else body
    return tuple(min(255, round(channel * brightest / 255.0)) for channel in base) + (alpha,)


def check_against_model(sources, destinations):
    """Fails if the model is reading its vests from anywhere but where these were written.

    The coordinates live twice -- here, and in WorkerGearModels' VEST_REGIONS -- with nothing
    tying them together but this. Get them out of step and a worker wears whatever happens to be
    at those texels, which on a mostly-empty sheet is nothing at all: an invisible vest, and no
    error anywhere to say why. Reading the table back is cheap, and the release workflow runs it.
    """
    import re

    body = open(MODEL, encoding='utf-8').read()
    table = re.search(r'VEST_REGIONS = \{(.*?)\n\t\};', body, re.S)
    if table is None:
        raise SystemExit('%s: no VEST_REGIONS table to check against' % MODEL)

    found = [(int(d), int(u), int(v))
             for d, u, v in re.findall(r'new VestRegion\((\d+), (\d+), (\d+)\)', table.group(1))]

    expected = []
    for shift in ('day',) + tuple(sorted(PALETTES)):
        for name, depth, origin, _size in sources:
            u, v = origin if shift == 'day' else destinations[(shift, name)]
            expected.append((depth, u, v))

    if found != expected:
        raise SystemExit('%s: VEST_REGIONS is %s, but this script writes %s'
                         % (MODEL, found, expected))


def main():
    gear = sys.argv[1] if len(sys.argv) > 1 else GEAR
    check_against_model(SOURCES, DESTINATIONS)
    width, height, rows = read_png(gear)

    for shift, (body, stripe) in sorted(PALETTES.items()):
        for name, _depth, (u, v), (box_width, box_height) in SOURCES:
            to_u, to_v = DESTINATIONS[(shift, name)]
            if to_u + box_width > width or to_v + box_height > height:
                raise SystemExit('%s %s vest does not fit the %dx%d sheet' % (shift, name, width, height))

            for y in range(box_height):
                for x in range(box_width):
                    rows[to_v + y][to_u + x] = recolour(rows[v + y][u + x], body, stripe)

    written = write_png(gear, rows)
    print('wrote %s (%dx%d, %d bytes, %d crews)' % (gear, width, height, written, len(PALETTES) + 1))


if __name__ == '__main__':
    main()
