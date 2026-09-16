#!/usr/bin/env python3
"""
Generates the worker profession's clothing overlay, which is deliberately blank.

A villager profession is drawn as a texture re-rendered over the same mesh
(VillagerProfessionLayer -> renderColoredCutoutModel), so whatever is left
transparent shows the villager's own biome robe through it. Everything here is
transparent, on purpose.

An earlier version painted hi-vis cuffs at the wrists and a hi-vis hem at the
shins, reasoning that the hard hat and the vest are geometry covering only the
head and the torso, so the sleeves and the robe below were free space. They are,
and it looked wrong: orange around the hands and feet reads as a costume rather
than as safety gear, and the one thing a worker should be recognised by is the
vest. A hi-vis vest is a hi-vis vest.

The file still has to exist, and in both layouts. A profession with no texture
renders as missing texture, and the lookup is per renderer -- villagers ask for
textures/entity/villager/profession/worker.png and zombie villagers for the
zombie_villager path, so a worker that is bitten needs the second one. A fully
transparent sheet satisfies both and draws nothing.

If a worker ever needs reading across a room by shift -- which shift rotation
wants -- that colour belongs on the vest geometry, which is already the hi-vis
thing, and not back on the sleeves.

    python3 tools/generate_worker_profession.py [output-directory]
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate_logo import write_png

SIZE = 64

DEFAULT_ASSETS = 'src/main/resources/assets/createworkers/textures/entity'

# One per renderer that looks a profession up. Nothing else differs now that
# neither sheet has anything drawn on it.
MODELS = ('villager', 'zombie_villager')

TRANSPARENT = (0, 0, 0, 0)


def render():
    return [[TRANSPARENT] * SIZE for _ in range(SIZE)]


def main():
    assets = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ASSETS
    for model in MODELS:
        target = os.path.join(assets, model, 'profession', 'worker.png')
        written = write_png(target, render())
        print('wrote {} ({}x{}, {} bytes, blank)'.format(target, SIZE, SIZE, written))


if __name__ == '__main__':
    main()
