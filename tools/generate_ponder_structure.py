#!/usr/bin/env python3
"""
Generates the structure template the hard hat's Ponder scene is staged on:
a checkerboard base plate with a Depot at two opposite corners.

Ponder scenes are normally built in a creative world and pulled out of a
structure block, which is fine for Create -- they have hundreds of them and an
in-game editing mode to tune them with. For one seven-by-seven plate that is a
lot of ceremony for a file nobody can then review, so this writes the NBT
directly. The layout below is the diff; the .nbt is a build product that happens
to be committed.

The plate is Create's own ponder convention -- white concrete and snow block
alternating -- because a scene that sits on a differently-coloured plate to every
other scene in the book reads as a mistake. It is a colour scheme, not an asset;
no Create art is copied here (see the Distribution notes in CLAUDE.md).

The two Depots sit on opposite corners rather than side by side so that the walk
between them is long enough to see, and so the middle of the plate stays clear
for the text windows to point into.

    python3 tools/generate_ponder_structure.py [output.nbt]
"""

import gzip
import os
import struct
import sys

OUTPUT = 'src/main/resources/assets/createworkers/ponder/hard_hat.nbt'

DATA_VERSION = 3955            # 1.21.1

SIZE = (7, 4, 7)               # the plate, plus headroom the villager stands in
PLATE = 7                      # matches configureBasePlate() in HardHatScene

PLATE_LIGHT = 'minecraft:white_concrete'
PLATE_DARK = 'minecraft:snow_block'
DEPOT = 'create:depot'

INPUT_DEPOT = (1, 1, 1)
OUTPUT_DEPOT = (5, 1, 5)


# --- the smallest NBT writer that will do -------------------------------------
# Only the five tag types a structure template uses. Names are the wire format's,
# not friendlier ones, so this stays checkable against the format description.

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def string_payload(value):
    encoded = value.encode('utf-8')
    return struct.pack('>H', len(encoded)) + encoded


def int_payload(value):
    return struct.pack('>i', value)


def list_payload(element_type, payloads):
    return struct.pack('>Bi', element_type, len(payloads)) + b''.join(payloads)


def compound_payload(entries):
    """entries: a sequence of (tag_type, name, payload), written in order."""
    out = b''
    for tag_type, name, payload in entries:
        out += struct.pack('>B', tag_type) + string_payload(name) + payload
    return out + struct.pack('>B', TAG_END)


def int_list(values):
    return list_payload(TAG_INT, [int_payload(v) for v in values])


# --- the scene ----------------------------------------------------------------

def build():
    palette = [PLATE_LIGHT, PLATE_DARK, DEPOT]
    blocks = []

    for x in range(PLATE):
        for z in range(PLATE):
            # Create's plates start light in the corner the structure's origin is in.
            blocks.append(((x, 0, z), 0 if (x + z) % 2 == 0 else 1))

    blocks.append((INPUT_DEPOT, palette.index(DEPOT)))
    blocks.append((OUTPUT_DEPOT, palette.index(DEPOT)))

    palette_tag = list_payload(TAG_COMPOUND, [
        compound_payload([(TAG_STRING, 'Name', string_payload(name))])
        for name in palette
    ])

    blocks_tag = list_payload(TAG_COMPOUND, [
        compound_payload([
            (TAG_LIST, 'pos', int_list(pos)),
            (TAG_INT, 'state', int_payload(state)),
        ])
        for pos, state in blocks
    ])

    root = compound_payload([
        (TAG_INT, 'DataVersion', int_payload(DATA_VERSION)),
        (TAG_LIST, 'size', int_list(SIZE)),
        (TAG_LIST, 'palette', palette_tag),
        (TAG_LIST, 'blocks', blocks_tag),
        (TAG_LIST, 'entities', list_payload(TAG_END, [])),
    ])

    return struct.pack('>B', TAG_COMPOUND) + string_payload('') + root


def main():
    destination = sys.argv[1] if len(sys.argv) > 1 else OUTPUT
    directory = os.path.dirname(destination)
    if directory:
        os.makedirs(directory, exist_ok=True)

    # mtime 0, so regenerating an unchanged scene does not produce a changed file.
    with open(destination, 'wb') as handle:
        with gzip.GzipFile(fileobj=handle, mode='wb', mtime=0) as compressed:
            compressed.write(build())

    print('wrote %s' % destination)


if __name__ == '__main__':
    main()
