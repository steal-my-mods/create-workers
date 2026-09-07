#!/usr/bin/env python3
"""
Generates the worker profession's clothing overlay: hi-vis trim on the sleeves
and the hem of whatever robe the villager already wears.

A villager profession is drawn as a texture re-rendered over the same mesh
(VillagerProfessionLayer -> renderColoredCutoutModel), so whatever is left
transparent shows the villager's own biome robe through it. That is the whole
design here. The hard hat and the hi-vis vest are geometry -- see
WorkerGearModels -- and they already cover the head and the torso, so a full
outfit would be pixels nobody ever sees. What stays visible is the robe below
the vest and the sleeves either side of it, and those get the same orange the
vest itself is drawn in, sampled from worker_gear.png so the two cannot drift
apart.

The hat region is deliberately left empty. A profession texture is what draws a
farmer's straw hat, and a worker wears a hard hat instead.

Two files come out of this, because the profession overlay is looked up per
renderer: villagers ask for textures/entity/villager/profession/worker.png and
zombie villagers for textures/entity/zombie_villager/profession/worker.png.
Vanilla ships a full set under both, so a profession that skips the zombie one
renders as missing texture the first time a worker is bitten -- and a converted
villager keeps its VillagerData, so that happens.

The regions are computed from the models' own UV offsets rather than measured
off any Minecraft texture. A box at texOffs (u,v) sized (dx,dy,dz) lays its four
side faces in a row starting at v+dz -- right, front, left, back -- each dy
tall. Both models put the robe in the same place and differ only in the sleeve:

  jacket     texOffs(0,38)  8x20x6  ->  x 0..28,  rows 44..64  (both models)
  arms       texOffs(44,22) 4x8x4   ->  x 44..60, rows 26..34  (villager, one
                                        sleeve, the other mirrors these texels)
  right_arm  texOffs(44,22) 4x12x4  ->  x 44..60, rows 26..38  (zombie villager)

    python3 tools/generate_worker_profession.py [output-directory]
"""

import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate_logo import read_png, write_png

SIZE = 64

GEAR_TEXTURE = 'src/main/resources/assets/createworkers/textures/entity/worker_gear.png'
DEFAULT_ASSETS = 'src/main/resources/assets/createworkers/textures/entity'

# Where each model's side faces begin, and how tall they are. Everything else
# about the two layouts is the same.
ROBE_SIDES_TOP, ROBE_HEIGHT = 44, 20
SLEEVE_SIDES_TOP = 26
SLEEVE_HEIGHTS = {'villager': 8, 'zombie_villager': 12}

HEM_ROWS = 3
CUFF_ROWS = 2

# The villager vest on the gear sheet: texOffs(0,28), 8x9x6, so its side faces
# occupy x 0..28 from row 34. Sampling the whole block and taking the commonest
# colour finds the vest's orange body; the brightest is its reflective stripe,
# which is near-white rather than a lighter orange, and is used as one to edge
# the trim. Anything brighter added to that region would become the stripe.
VEST_REGION = (0, 28, 28, 44)

TRANSPARENT = (0, 0, 0, 0)


def vest_palette():
    width, height, pixels = read_png(GEAR_TEXTURE)
    left, top, right, bottom = VEST_REGION
    tally = collections.Counter()
    for y in range(top, min(bottom, height)):
        for pixel in pixels[y][left:min(right, width)]:
            if pixel[3] == 255:
                tally[pixel] += 1
    if not tally:
        raise SystemExit('{}: found no opaque vest pixels to sample'.format(GEAR_TEXTURE))

    body = tally.most_common(1)[0][0]
    stripe = max(tally, key=lambda pixel: pixel[0] + pixel[1] + pixel[2])
    return body, stripe


def band(rows, left, right, top, colours):
    """Paints one row per colour, downwards from `top`, across x in [left, right)."""
    for offset, colour in enumerate(colours):
        for x in range(left, right):
            rows[top + offset][x] = colour


def render(sleeve_height, palette):
    body, stripe = palette
    rows = [[TRANSPARENT] * SIZE for _ in range(SIZE)]

    # Hem: the last rows of the robe's side faces, which is the bottom three
    # units of a 20-unit robe -- around the shins, well clear of the vest.
    band(rows, 0, 28, ROBE_SIDES_TOP + ROBE_HEIGHT - HEM_ROWS, [stripe, body, body])

    # Cuffs: the last rows of a sleeve's side faces, at the wrist.
    band(rows, 44, 60, SLEEVE_SIDES_TOP + sleeve_height - CUFF_ROWS, [stripe, body])

    return rows


def main():
    assets = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ASSETS
    palette = vest_palette()
    for model, sleeve_height in sorted(SLEEVE_HEIGHTS.items()):
        target = os.path.join(assets, model, 'profession', 'worker.png')
        written = write_png(target, render(sleeve_height, palette))
        print('wrote {} ({}x{}, {} bytes) trim {} stripe {}'.format(target, SIZE, SIZE, written,
                                                                    *palette))


if __name__ == '__main__':
    main()
