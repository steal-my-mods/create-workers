#!/usr/bin/env python3
"""
Generates the Worker Station's block textures, and checks they still describe the
same block as the model and the renderer do.

Five sheets, every pixel this project's own:

  worker_station_casing   the sides and the top: boards in an andesite frame
  worker_station_bottom   plain, seen only from underneath
  worker_station_front    the same casing, with the lamps mounted on its boards
  worker_station_lamps    the three states of one lamp, side by side (48x16)

**The palette follows Create's, measured rather than guessed.** Counting the shades in
Create's own casings turned up three things this mod's art had been getting wrong:
andesite casing is *not* grey but a neutral ramp and a warm tan one in nearly equal
measure (copper casing does the same with orange); Create carries seventeen to nineteen
shades in a sheet where this had six; and nothing in andesite casing is darker than a
luma of 57, where the panel here had been at 16, which is why it read as a hole cut in
the block rather than as a fitting on it. Every value below is this project's own,
chosen inside those ranges -- no pixel, pattern or layout of Create's is reproduced,
because a palette is a fact about a texture and not the texture.

The warm ramp settles an argument the block had been losing both ways: a grey cast
panel reads as Create and a timber one reads as a vanilla profession block, and a
Station has to be both. A timber frame around a cast plate is both.

**What the lamps mean is a design decision, not a drawing decision.** One lamp per
place in the rack: lit when the job is fully staffed for the shifts it runs, dim when
it is programmed and short, dark when the slot is empty. So a board with any dim lamp
on it is a station that needs people, which is the question a player actually walks
over to ask.

    python3 tools/generate_block_textures.py [output-directory]
"""

import math
import os
import struct
import sys
import zlib

OUTPUT_DIR = 'src/main/resources/assets/createworkers/textures/block'

SIZE = 16

# --- the palette ---------------------------------------------------------------
# The inside of the Canteen. Deliberately far darker than anything Create's casing carries:
# that describes a cast *surface*, and a trough's opening is the one place on either block
# where there is no surface to describe.
CAVITY = (0x2A, 0x21, 0x18, 255)
CAVITY_LIT = (0x3B, 0x2F, 0x22, 255)
BEZEL = (0x24, 0x28, 0x26)
LAMP_LIT = (0xFF, 0xD3, 0x5C)
LAMP_DIM = (0xC2, 0x83, 0x2E)
LAMP_OFF = (0x3A, 0x3F, 0x3B)

# --- where the lamps go ---------------------------------------------------------
# In block units, on the front face, authored facing north. WorkerStationRenderer
# restates these and check_against_model() holds the two together; the lamp panel
# drawn below has to contain them, which it also checks.
LAMP_COLUMNS = 4
LAMP_ROWS = 3
LAMP_PITCH_X = 2.45
LAMP_PITCH_Y = 3.0
LAMP_CENTRE_X = 8.0
LAMP_CENTRE_Y = 8.0
LAMP_SIZE = 2.6

# The sprite is transparent outside seven sixteenths of its width, so the lamp a player
# sees is smaller than the quad it is drawn on. Spacing has to be judged against this.
LAMP_VISIBLE = LAMP_SIZE * 7.0 / 8.0

# Two pixels of andesite trim, then one of shadow, leaving a ten-by-ten panel. The
# widths are Create's: its casings all carry a two-pixel border and a one-pixel ring.
TRIM = 2
PANEL_BOX = (TRIM + 1, TRIM + 1, SIZE - 2 - TRIM, SIZE - 2 - TRIM)


def lamp_spots():
    """Every lamp's centre, in block units. Even by construction, not by choosing numbers."""
    return [(LAMP_CENTRE_X + (column - (LAMP_COLUMNS - 1) / 2.0) * LAMP_PITCH_X,
             LAMP_CENTRE_Y - (row - (LAMP_ROWS - 1) / 2.0) * LAMP_PITCH_Y)
            for row in range(LAMP_ROWS) for column in range(LAMP_COLUMNS)]


def blank(colour, width=SIZE, height=SIZE):
    return [[colour for _ in range(width)] for _ in range(height)]



def trough():
    """The Canteen's cavity, alone on a transparent sheet.

    **It is a plate over Create's casing rather than a face of our own**, which is the whole
    reason a bank of Canteens has no seams: a face with a trough baked into it cannot connect
    to anything. The model stands it a hair proud of the top face; CanteenRenderer clears that
    same hair before drawing the food, and check_canteen_grid holds the two numbers together.

    The cavity is far darker than anything a casing carries. That rule describes a cast
    *surface* and the inside of a box is not one. Depth comes from the far wall catching the
    light -- a recess lit like a bump reads as a bump.
    """
    pixels = blank(CLEAR)
    x1, y1, x2, y2 = PANEL_BOX
    for y in range(y1, y2 + 1):
        for x in range(x1, x2 + 1):
            near = min(x - x1, y - y1)
            far = min(x2 - x, y2 - y)
            pixels[y][x] = CAVITY_LIT if far < near else CAVITY
    return pixels


# --- where the food goes -------------------------------------------------------------
# In texels, and restated in CanteenRenderer, which draws into them. check_canteen_grid()
# holds the two together and checks every piece lands inside the cavity the texture draws --
# the Station shipped its lamps a fiftieth of a block *inside* an opaque board for want of
# exactly this check.
# The Canteen's slots, restated from CanteenBlockEntity.SLOTS.
CANTEEN_SLOTS = 9
CAVITY_CELLS = 3
CAVITY_PITCH = 2
CAVITY_PIECE = 4
CAVITY_SPOTS = 2
# The furthest a spot offset plus its nudge can push a piece off its cell, on either axis.
CAVITY_REACH = 2

# Which cell each slot claims, and where its pieces sit inside one. Restated from
# CanteenRenderer, which is the thing that actually draws them, and read back out of it by
# check_canteen_grid(). They are here so that tools/generate_page_art.py can show a Canteen
# with food in it: the stock is drawn with quads at run time, so a plain model render is an
# empty trough -- the block at its least appealing, and the same reason the Station's card
# has to be handed its lamps.
CAVITY_CELL_ORDER = ((1, 1), (1, 0), (0, 1), (2, 1), (1, 2), (0, 0), (2, 0), (0, 2), (2, 2))
CAVITY_SPOT_OFFSETS = ((0, 0), (1, 1))

# The gauge on the flank: two texels wide, which on a sixteen-texel face straddles the centre
# exactly where three cannot, and nine tall because the block has nine slots and a row is one.
GAUGE_X1, GAUGE_X2 = 7, 8
GAUGE_Y1, GAUGE_Y2 = 4, PANEL_BOX[3]


FOOD_SHAPES = (
    ('bread', ['LLL',
               'BBB',
               '.S.']),
    ('carrot', ['.G.',
                'LLL',
                'BBB',
                '.S.']),
    ('potato', ['.L.',
                'LBB',
                '.S.']),
    ('beetroot', ['LLL',
                  'BBB',
                  '.B.',
                  '.S.']),
)

FOOD_COLOURS = {
    'bread': ((0xAE, 0x80, 0x49), (0xC8, 0x9A, 0x60), (0x8C, 0x64, 0x39)),
    'carrot': ((0xD9, 0x6B, 0x1B), (0xF0, 0x8C, 0x33), (0xA8, 0x50, 0x14)),
    'potato': ((0xC2, 0x9C, 0x5E), (0xDA, 0xB8, 0x7C), (0x9A, 0x79, 0x45)),
    'beetroot': ((0x8C, 0x2B, 0x3A), (0xA8, 0x3C, 0x4C), (0x66, 0x1C, 0x28)),
}
LEAF = (0x4E, 0x7A, 0x2E)
CLEAR = (0, 0, 0, 0)


def canteen_food():
    """Everything the renderer draws the stock with, on one sheet: shapes, swatches, a slot.

    One sheet rather than six, because a piece is one quad and a quad wants one texture. Three
    bands of four-texel cells, so every UV in the renderer is a quarter and there is no
    arithmetic to get wrong:

      row 0  the four silhouettes, each in a four-texel cell with its own transparent margin
      row 1  a flat swatch per food, which is what the gauge's filled rows sample
      row 2  the empty gauge -- dark, and the only thing on this sheet that is not food

    The order is CanteenBlockEntity.DRAWN_FOODS, and that is the order this file, the renderer
    and the block all count in.
    """
    pixels = blank(CLEAR)
    for index, (name, rows) in enumerate(FOOD_SHAPES):
        base, lit, shade = (colour + (255,) for colour in FOOD_COLOURS[name])
        ink = {'L': lit, 'B': base, 'S': shade, 'G': LEAF + (255,)}
        left = index * CAVITY_PIECE
        for dy, row in enumerate(rows):
            for dx, symbol in enumerate(row):
                if symbol != '.':
                    pixels[dy][left + dx] = ink[symbol]
        for dy in range(CAVITY_PIECE):
            for dx in range(CAVITY_PIECE):
                # Lit along the top of a row, so a stacked gauge has a line between its slots.
                pixels[CAVITY_PIECE + dy][left + dx] = lit if dy == 0 else base
    # The gauge's backing: flat and dark, drawn under every row. It is what separates the bar
    # from the timber -- a bread swatch alone is within a few shades of the boards behind it,
    # and the first build of this had an invisible gauge on a full Canteen for exactly that.
    for dy in range(CAVITY_PIECE):
        for dx in range(CAVITY_PIECE):
            pixels[2 * CAVITY_PIECE + dy][dx] = BEZEL + (255,)
    return pixels

def gauge_backing():
    """The bezel behind the gauge, as (x, y, width, height) in texels.

    A texel proud of the bar on three sides and standing on the floor of the panel on the
    fourth -- GAUGE_Y2 *is* the floor, so there is no room under it. Restated from the backing
    quad CanteenRenderer draws, which check_canteen_grid() reads back out of it.
    """
    return (GAUGE_X1 - 1, GAUGE_Y1 - 1,
            GAUGE_X2 - GAUGE_X1 + 3, GAUGE_Y2 - GAUGE_Y1 + 2)


def nudge(seed):
    """CanteenRenderer.hash, which is what keeps a heap from landing on a grid.

    Java's int wraps and this one does not, so the product is masked to thirty-two bits before
    the shift. Left unmasked it is a different number, and the page would then show a tidier
    heap than the game draws.
    """
    return ((seed * 1103515245 + 12345) & 0xFFFFFFFF) >> 16 & 0xFF


def stock(servings, casing):
    """The sheets a Canteen with food in it draws from, laid out the way the game lays it out.

    `servings` is one (index into FOOD_SHAPES, how full the slot is) per slot -- what
    CanteenBlockEntity.servings() hands its renderer -- and `casing` is the andesite casing the
    block wears, which lives in Create's jar rather than here. The return is keyed the way
    render_block_model's `sheets` is: a bare reference, or a (reference, face) pair where only
    some faces carry it.

    **Nothing ships any of this.** The stock is a thing the block knows and the renderer draws
    it from what the block knows, which is the point of the design -- but the project page
    renders a *model*, and a model knows nothing. Without this the Canteen's card is an empty
    box, which is exactly the picture the food was taken off the texture to stop it being.
    """
    import math

    top = trough()
    flank = [list(row) for row in casing]
    food = canteen_food()
    x1, y1, x2, y2 = PANEL_BOX

    # The heap, piece by piece, out of the same tables and the same nudge as the renderer.
    for slot, (index, fraction) in enumerate(servings[:len(CAVITY_CELL_ORDER)]):
        if index < 0 or fraction <= 0:
            continue
        pieces = max(1, min(CAVITY_SPOTS, int(math.ceil(fraction * CAVITY_SPOTS))))
        for piece in range(pieces):
            jitter = nudge(slot * 31 + piece)
            left = x1 + CAVITY_CELL_ORDER[slot][0] * CAVITY_PITCH \
                + CAVITY_SPOT_OFFSETS[piece][0] + (jitter & 1)
            down = x1 + CAVITY_CELL_ORDER[slot][1] * CAVITY_PITCH \
                + CAVITY_SPOT_OFFSETS[piece][1] + ((jitter >> 1) & 1)
            for dy in range(CAVITY_PIECE):
                for dx in range(CAVITY_PIECE):
                    texel = food[dy][index * CAVITY_PIECE + dx]
                    if texel[3]:
                        top[down + dy][left + dx] = texel

    # The gauge: its bezel, then a row per stocked slot, filling from the floor. Both colours
    # are sampled out of the food sheet at the texel the renderer's own UVs name, so a swatch
    # that gets redrawn is redrawn here with it.
    bezel = food[2 * CAVITY_PIECE + 1][1]
    bx, by, bw, bh = gauge_backing()
    for y in range(by, by + bh):
        for x in range(bx, bx + bw):
            flank[y][x] = bezel
    for slot, (index, fraction) in enumerate(servings[:GAUGE_Y2 - GAUGE_Y1 + 1]):
        if index < 0 or fraction <= 0:
            continue
        for x in range(GAUGE_X1, GAUGE_X2 + 1):
            flank[GAUGE_Y2 - slot][x] = food[CAVITY_PIECE + 1][index * CAVITY_PIECE + 1]

    # The gauge is on the flanks only; the casing sheet is the same on all six faces, so a
    # gauge painted into it bare would appear on the top and the bottom as well.
    sheets = {'createworkers:block/canteen_trough': top,
              'create:block/andesite_casing': casing}
    for face in ('north', 'south', 'east', 'west'):
        sheets[('create:block/andesite_casing', face)] = flank
    return sheets


def lamp(colour):
    """One lamp in its bezel, as a 16x16 cell.

    Two things give it depth and both are about where the light is. The bezel is a
    *hole*, so with the light at the top left the far inner wall catches it and the
    near wall is shadowed -- dark at the top left, lighter at the bottom right, the
    same inversion that makes the panel look sunk. And the bulb is convex, so its
    catch-light sits up and to the left; a highlight in the middle of a disc reads as
    a button rather than as a lamp. A flat ring around a flat disc is flat however
    dark you make it, which is how this shipped once already.
    """
    red, green, blue = colour
    near = tuple(max(0, channel - 12) for channel in BEZEL) + (255,)
    far = tuple(min(255, channel + 46) for channel in BEZEL) + (255,)
    face = (red, green, blue, 255)
    shine = (min(255, red + 70), min(255, green + 70), min(255, blue + 70), 255)

    rows = []
    for y in range(SIZE):
        line = []
        for x in range(SIZE):
            dx, dy = x - 7.5, y - 7.5
            distance = (dx * dx + dy * dy) ** 0.5
            if distance > 7.0:
                line.append((0, 0, 0, 0))
            elif distance > 5.2:
                line.append(near if (dx + dy) < 0 else far)
            elif ((dx + 1.6) ** 2 + (dy + 1.6) ** 2) ** 0.5 < 2.0:
                line.append(shine)
            else:
                line.append(face)
        rows.append(line)
    return rows


def lamps():
    """The three states side by side, so one texture and one draw call serve all twelve."""
    cells = [lamp(LAMP_OFF), lamp(LAMP_DIM), lamp(LAMP_LIT)]
    return [sum((cell[y] for cell in cells), []) for y in range(SIZE)]


# --- the item models ------------------------------------------------------------
# Where a block's readout goes when there is no block entity to draw it.
ITEM_MODELS = os.path.join('src', 'main', 'resources', 'assets', 'createworkers',
                           'models', 'item')
BLOCK_MODELS = os.path.join('src', 'main', 'resources', 'assets', 'createworkers',
                            'models', 'block')

# How far a plate stands off the face it is drawn on, in the model's own sixteenths.
# The Canteen's trough uses the same number and CanteenRenderer calls it PLATE in blocks;
# check_canteen_grid() holds all three together.
PLATE_UNITS = 0.016

# The lamps sheet is the three states side by side, so the dark one is the first third.
LAMP_STATES = 3

# The angle an inventory icon is drawn at, in the item model's own `display.gui` block.
#
# Vanilla shows a block item at block/block's [30, 225, 0], which puts the model's north face on
# the right of the icon and its east face on the left -- and the two sides are not lit alike. An
# item in a GUI gets no per-face shade at all; what lights it is Lighting.setupFor3DItems, two
# directional lights, and through the GUI's own pose they land on the left flank at 0.651 and on
# the right at 0.400. That is the floor the formula can produce, and the same value the *bottom*
# of a block gets. A Station draws its readout on its front, so at vanilla's angle every lamp sat
# on the darkest face in the picture: the item read as a stack of andesite casing, which is the
# one thing baking the readout into the model was meant to stop. A quarter turn the other way
# brings the front to the lit side. check_icon_lighting() is what holds that.
#
# Only `gui` is overridden -- in the hand, on the ground and in an item frame the block is shown
# the way it is authored. A display entry *replaces* the parent's for its context rather than
# merging with it, so the translation and scale are restated from block/block here: an omitted
# scale is 1.0 rather than 0.625, which draws the icon half again its size.
VANILLA_TURN = [30, 225, 0]
GUI_TURN = [30, 135, 0]
GUI_TRANSLATION = [0, 0, 0]
GUI_SCALE = [0.625, 0.625, 0.625]


def rounded(value):
    """Model coordinates at a fixed precision, so a re-run is byte-identical."""
    if isinstance(value, float):
        return round(value, 6)
    if isinstance(value, list):
        return [rounded(item) for item in value]
    if isinstance(value, dict):
        return {key: rounded(item) for key, item in value.items()}
    return value


def lamp_plates():
    """One unlit lamp per place in the rack, where WorkerStationRenderer would draw it.

    The positions come from lamp_spots(), which is the same function check_against_model()
    holds the renderer to -- so the item and the block cannot drift apart. The sheet is the
    one the renderer samples, and the cell is the dark state, because a Station that has just
    been crafted has no jobs in it.
    """
    half = LAMP_SIZE / 2.0
    cell = SIZE / float(LAMP_STATES)
    plates = []
    for x, y in lamp_spots():
        plates.append({
            '__comment': 'An unlit lamp. Item model only -- in the world these are quads.',
            'from': [x - half, y - half, -PLATE_UNITS],
            'to': [x + half, y + half, -PLATE_UNITS],
            'faces': {'north': {'texture': '#lamps', 'uv': [0.0, 0.0, cell, float(SIZE)]}},
        })
    return plates


def gauge_plates():
    """The empty gauge on all four flanks, where CanteenRenderer would draw its backing.

    The rectangle is gauge_backing() and the colour is the bezel cell of the food sheet, which
    is exactly what an empty Canteen shows: the bar is the backing with nothing over it.
    """
    x, y, width, height = gauge_backing()
    low, high = SIZE - (y + height), SIZE - y
    cell = float(CAVITY_PIECE)
    uv = [0.0, 2 * cell, cell, 3 * cell]
    plates = []
    for face, box in (
            ('north', ([x, low, -PLATE_UNITS], [x + width, high, -PLATE_UNITS])),
            ('south', ([x, low, SIZE + PLATE_UNITS], [x + width, high, SIZE + PLATE_UNITS])),
            ('west', ([-PLATE_UNITS, low, x], [-PLATE_UNITS, high, x + width])),
            ('east', ([SIZE + PLATE_UNITS, low, x], [SIZE + PLATE_UNITS, high, x + width]))):
        plates.append({
            '__comment': 'The empty gauge. Item model only -- in the world these are quads.',
            'from': box[0],
            'to': box[1],
            'faces': {face: {'texture': '#food', 'uv': uv}},
        })
    return plates


def item_model(block, textures, plates, turn=None):
    """A block's item model: its own geometry, plus the readout a renderer would have drawn.

    **A block entity renderer does not exist in an inventory**, nor in JEI, nor on a dropped
    item -- so a Station's lamps and a Canteen's gauges are simply absent there. That was
    survivable while the blocks wore a casing of their own; now that both wear Create's, what
    is left without them is a cube a player already has stacks of under a different name.

    These plates go in the *item* model alone. The block in the world is untouched, so there is
    nothing here for the renderer to fight with, and no risk of the two drawing the same lamp a
    fraction of a block apart. The geometry is read out of the block model rather than restated,
    so the item is the block plus a readout and cannot become some other shape.

    A `turn` overrides the angle the icon is drawn at, for a block whose readout is on one face.
    """
    import json

    with open(os.path.join(BLOCK_MODELS, '%s.json' % block)) as handle:
        model = json.load(handle)
    model.pop('_comment', None)
    model = {
        '_comment': 'Generated by tools/generate_block_textures.py -- do not edit. The block '
                    'model plus the readout its block entity renderer draws, which an inventory '
                    'has no renderer for.',
        'parent': model.get('parent', 'block/block'),
        'textures': dict(model.get('textures', {}), **textures),
        'elements': model['elements'] + plates,
    }
    if turn is not None:
        model['display'] = {'gui': {'rotation': turn,
                                    'translation': GUI_TRANSLATION,
                                    'scale': GUI_SCALE}}
    return rounded(model)


def item_models():
    return {
        'worker_station': item_model(
            'worker_station', {'lamps': 'createworkers:block/worker_station_lamps'},
            lamp_plates(), turn=GUI_TURN),
        'canteen': item_model(
            'canteen', {'food': 'createworkers:block/canteen_food'}, gauge_plates()),
    }


# --- the check that the files still describe one block --------------------------

MODEL = 'src/main/resources/assets/createworkers/models/block/worker_station.json'
RENDERER = 'src/main/java/com/createworkers/client/WorkerStationRenderer.java'
STATION = 'src/main/java/com/createworkers/block/WorkerStationBlockEntity.java'

FACES = {'down': (1, -1), 'up': (1, 1), 'north': (2, -1),
         'south': (2, 1), 'west': (0, -1), 'east': (0, 1)}


def json_model():
    import json
    with open(MODEL) as handle:
        return json.load(handle)


def face_of(box, name):
    axis, sign = FACES[name]
    plane = box['to'][axis] if sign > 0 else box['from'][axis]
    others = [i for i in (0, 1, 2) if i != axis]
    return plane, [(box['from'][i], box['to'][i]) for i in others]


def cells(rectangle):
    (u1, u2), (v1, v2) = rectangle
    return {(u, v) for u in range(u1, u2) for v in range(v1, v2)}


def check_faces():
    """Every face of every element is either covered by something or drawn, never both.

    Two ways to get this wrong and the Station shipped one of each while it was still a
    bench. A face nothing covers and nobody declares is a **hole** -- the plinth was 16
    wide with a 14-wide carcass on it, and the one-pixel ledge between them was a gap
    you could see down through the block and out of its culled bottom. Two faces
    declared in one plane facing each other **stipple**, because which wins is
    depth-buffer rounding and changes with the camera.

    The Station is a single full cube now and cannot have either, which is most of why
    it is one. The check stays because the next model to be written here will not be.
    """
    boxes = json_model()['elements']
    for index, box in enumerate(boxes):
        for name in FACES:
            axis, sign = FACES[name]
            plane, rectangle = face_of(box, name)
            declared = name in box['faces']
            boundary = (plane == 0 and sign < 0) or (plane == SIZE and sign > 0)

            uncovered = cells(rectangle)
            if not boundary:
                for other, beyond in enumerate(boxes):
                    if other == index:
                        continue
                    if sign > 0 and not beyond['from'][axis] <= plane < beyond['to'][axis]:
                        continue
                    if sign < 0 and not beyond['from'][axis] < plane <= beyond['to'][axis]:
                        continue
                    uncovered -= cells(face_of(beyond, name)[1])

            where = '%s of element %d %s' % (name, index, box['from'])
            if uncovered and not declared:
                raise AssertionError('a hole: %s is open to the air and undrawn' % where)
            if not uncovered and declared:
                raise AssertionError('buried: %s is inside the block and still drawn' % where)
            if declared and 'cullface' in box['faces'][name]:
                assert boundary and len(uncovered) == SIZE * SIZE, \
                    'cullface on %s, which does not span the block boundary' % where

    for index, box in enumerate(boxes):
        for name, (axis, sign) in FACES.items():
            if name not in box['faces']:
                continue
            plane, rectangle = face_of(box, name)
            opposite = next(o for o in FACES if FACES[o] == (axis, -sign))
            for other, against in enumerate(boxes):
                if other <= index or opposite not in against['faces']:
                    continue
                theirs, rectangle_of_theirs = face_of(against, opposite)
                if theirs == plane and cells(rectangle) & cells(rectangle_of_theirs):
                    raise AssertionError(
                        'stipple: %s of element %d meets %s of element %d in %s=%d'
                        % (name, index, opposite, other, 'xyz'[axis], plane))


def java_constants(path, pattern):
    import re
    with open(path) as handle:
        source = handle.read()
    values = {}
    for name, expression in re.findall(pattern, source):
        values[name] = eval(  # noqa: S307 -- our own source, no builtins reach it
            re.sub(r'(?<=[\d.])[FfDdLl]\b', '', expression), {'__builtins__': {}}, dict(values))
    return values


CANTEEN_MODEL = os.path.join(
    'src', 'main', 'resources', 'assets', 'createworkers', 'models', 'block',
    'canteen.json')

CANTEEN_RENDERER = os.path.join(
    'src', 'main', 'java', 'com', 'createworkers', 'client', 'CanteenRenderer.java')


def check_quad_layers():
    """Every quad the Canteen draws over another one has to say where it sits.

    **This has now been got wrong twice**, in the two places it can be. The heap's pieces overlap
    each other, and the gauge's rows cover its backing; both were stood off the face by a flat
    STANDOFF, which puts them at one depth. Coplanar quads do not layer, they are undefined --
    the depth buffer picks between them per fragment and per camera angle, which reads as the
    block tearing itself apart on the top and as the level bleeding through black on the flanks.

    Nothing about a constant is wrong in either case, so this reads the translate expressions
    the way check_top_face_quad reads the winding.
    """
    import re
    source = open(CANTEEN_RENDERER).read()
    for name, following in (('flat', 'private void upright('), ('upright', 'private static void vertex(')):
        body = source[source.index('private void %s(' % name):source.index(following)]
        assert 'layer * LAYER' in body, \
            ('%s() stands its quads off the face without a layer. Anything drawn over something '
             'else here needs its own depth, or the two are undefined rather than stacked.' % name)
        assert re.search(r'\bint layer\b', body), \
            '%s() should take the layer rather than assume one' % name


def check_top_face_quad():
    """The heap's quad has to lie on the top face and point upwards, and neither is a constant.

    Both of these shipped wrong and both were invisible to everything else here. The grid was
    right -- check_canteen_grid passed, and the preview that replays these same constants drew
    the heap correctly -- because the mistake was in the transform the quad is drawn *through*,
    which no amount of checking the numbers reaches. Exactly the shape of the bug that put the
    Station's lamps inside its own board, so it is caught the same way: by reading the source
    rather than the fields beside it.

    Two things are asserted.

    **The offset along the texture's y is positive.** After the rotation, local +Y is world +Z,
    so the texture's own y runs north to south; a negated offset puts the whole heap a block to
    the north, inside whatever is standing there -- which is why it looked like nothing was
    drawn at all rather than like something drawn wrongly.

    **The winding gives a normal of local -Z**, which is world up. entityCutout culls, unlike
    entityCutoutNoCull, so the obvious order faces the quad at the floor and it is never drawn.
    """
    import re
    source = open(CANTEEN_RENDERER).read()
    body = source[source.index('private void flat('):source.index('private void upright(')]

    move = re.search(r'poseStack\.translate\(([^;]+)\);\s*\n\s*PoseStack\.Pose', body)
    assert move, 'flat() should end its transform with a translate onto the face'
    across, along, _ = [part.strip() for part in move.group(1).split(',')]
    assert across.startswith('x') and along.startswith('y'), \
        ('flat() offsets by (%s, %s); the texture\'s x and y map straight onto the face, and a '
         'negated y puts the heap a block north of the block it belongs to' % (across, along))

    corners = re.findall(r'vertex\(consumer, pose, ([^,]+), ([^,]+),', body)
    assert len(corners) == 4, 'a quad has four corners; found %d' % len(corners)

    def axis(text):
        text = text.strip()
        return 0.0 if text.startswith('0.0') else 1.0

    points = [(axis(x), axis(y)) for x, y in corners]
    (x0, y0), (x1, y1), (x2, y2) = points[0], points[1], points[2]
    # The z of (v1 - v0) x (v2 - v1). Local -Z is world up, so this has to come out negative.
    winding = (x1 - x0) * (y2 - y1) - (y1 - y0) * (x2 - x1)
    assert winding < 0, \
        ('the heap\'s quad winds to a normal of local +Z, which is world *down*. entityCutout '
         'culls, so it is drawn at the floor and never seen. Wind it the other way round.')


def check_canteen_grid():
    """Hold the Canteen's texture and its renderer to one another.

    The stock is drawn by CanteenRenderer and the trough it is drawn *into* is drawn here, so
    the two restate the same grid and nothing else would notice them drifting. That is the
    Station's lesson paid for once already: its renderer shipped hanging every lamp a
    fiftieth of a block inside an opaque board, which is not drawn badly but not drawn.

    The containment assertion is the one that matters. A piece that overhung the cavity would
    land on the andesite trim -- the frame that holds this block to the Station's -- and a
    heap spilling onto the frame reads as a broken texture rather than as a full trough.
    """
    renderer = java_constants(
        CANTEEN_RENDERER, r'private static final (?:int|float|double) (\w+) = ([^;]+);')

    for name, ours in (('CAVITY_CELLS', CAVITY_CELLS), ('CAVITY_PITCH', CAVITY_PITCH),
                       ('CAVITY_PIECE', CAVITY_PIECE), ('CAVITY_SPOTS', CAVITY_SPOTS),
                       ('CAVITY_REACH', CAVITY_REACH), ('GAUGE_X1', GAUGE_X1), ('GAUGE_Y1', GAUGE_Y1), ('GAUGE_Y2', GAUGE_Y2)):
        assert name in renderer, '%s is not declared in CanteenRenderer' % name
        assert renderer[name] == ours, \
            '%s: renderer has %s, textures have %s' % (name, renderer[name], ours)

    # The layout tables and the nudge, which the project page draws its stocked trough with. A
    # drift here is not a broken block -- it is a picture of a block nobody has, which is the
    # harder kind to notice.
    import re

    source = open(CANTEEN_RENDERER).read()
    for name, ours in (('CELLS', CAVITY_CELL_ORDER), ('SPOTS', CAVITY_SPOT_OFFSETS)):
        body = re.search(r'int\[\]\[\] %s = \{(.*?)\};' % name, source, re.S)
        assert body, '%s is not declared in CanteenRenderer' % name
        theirs = tuple(tuple(int(n) for n in pair.split(','))
                       for pair in re.findall(r'\{([^}]*)\}', body.group(1)))
        assert theirs == ours, \
            ('%s: the renderer lays the heap out as %s and the page draws it as %s'
             % (name, theirs, ours))

    hashed = re.search(r'int value = seed \* (\d+) \+ (\d+);', source)
    assert hashed, 'CanteenRenderer no longer nudges its pieces with a multiply and an add'
    # Over a run of seeds rather than one. The hash keeps a byte out of the middle of a
    # product, so two multipliers a few apart agree on most single seeds and disagree on the
    # heap -- a one-seed comparison here passed a changed multiplier outright.
    multiplier, addend = int(hashed.group(1)), int(hashed.group(2))
    assert all(nudge(seed) == ((seed * multiplier + addend) & 0xFFFFFFFF) >> 16 & 0xFF
               for seed in range(256)), \
        'the renderer nudges its pieces by a different hash than the page draws them with'

    # The bezel behind the bar, read off the quad rather than off the constants beside it. Its
    # margins are arithmetic in the call -- a texel proud on three sides and flush with the panel
    # floor on the fourth -- so nothing about a constant is wrong when they drift.
    backing = re.search(
        r'upright\(consumer, poseStack, facing, ([^,]+), ([^,]+), ([^,]+),\s*([^,]+), 0,', source)
    assert backing, 'CanteenRenderer no longer draws a backing quad behind its gauge'
    scope = dict(renderer)
    scope['rows'] = GAUGE_Y2 - GAUGE_Y1 + 1
    theirs = tuple(eval(  # noqa: S307 -- our own source, no builtins reach it
        group.strip(), {'__builtins__': {}}, scope) for group in backing.groups())
    assert theirs == gauge_backing(), \
        ('the renderer backs its gauge with %s and the page draws %s' % (theirs, gauge_backing()))

    assert renderer['GAUGE_WIDTH'] == GAUGE_X2 - GAUGE_X1 + 1, \
        ('the renderer draws a gauge %d texels wide into a column of %d'
         % (renderer['GAUGE_WIDTH'], GAUGE_X2 - GAUGE_X1 + 1))

    x1, y1, x2, y2 = PANEL_BOX
    assert renderer['CAVITY_X1'] == x1 and renderer['CAVITY_X2'] == x2, \
        ('the renderer draws into %s..%s but the trough is %s..%s'
         % (renderer['CAVITY_X1'], renderer['CAVITY_X2'], x1, x2))

    # Nine cells for nine slots, or a slot has nowhere to put its food.
    assert CAVITY_CELLS ** 2 == CANTEEN_SLOTS, \
        ('%d cells for %d slots: every slot owns one, which is what makes the top the block\'s '
         'own inventory rather than a gauge' % (CAVITY_CELLS ** 2, CANTEEN_SLOTS))

    # Every piece, in every cell, at every spot, with the nudge at its worst -- inside the trough.
    span = (CAVITY_CELLS - 1) * CAVITY_PITCH + CAVITY_REACH + CAVITY_PIECE
    assert span <= (x2 - x1 + 1), \
        ('a piece can reach %d texels into a %d-texel trough, so the heap would spill onto the '
         'trim' % (span, x2 - x1 + 1))

    # The trough is a plate in the model and the heap is drawn above it, so the two files state
    # one height between them. A plate the heap does not clear is the coplanar-quad fault this
    # block has already shipped twice, in the other direction.
    import json

    with open(CANTEEN_MODEL) as handle:
        plate = [box for box in json.load(handle)['elements']
                 if any(face.get('texture') == '#trough'
                        for face in box['faces'].values())]
    assert len(plate) == 1, 'the Canteen model should carry exactly one trough plate'
    proud = (plate[0]['from'][1] - SIZE) / float(SIZE)
    assert abs(proud - renderer['PLATE']) < 1e-9, \
        ('the model stands the trough %s of a block proud and CanteenRenderer calls it %s'
         % (proud, renderer['PLATE']))
    assert plate[0]['from'][1] == plate[0]['to'][1], 'the trough plate is flat'
    assert abs(PLATE_UNITS / float(SIZE) - renderer['PLATE']) < 1e-9, \
        ('the item models stand their plates %s of a block proud and CanteenRenderer calls it %s'
         % (PLATE_UNITS / float(SIZE), renderer['PLATE']))
    assert renderer['STANDOFF'] > renderer['PLATE'], \
        ('the heap is drawn at STANDOFF and the trough sits at PLATE, so STANDOFF has to be the '
         'larger or the food is inside the trough rather than on it')

    check_top_face_quad()
    check_quad_layers()

    # The gauge: a row per slot, standing on the floor of the panel and under its own bezel.
    assert GAUGE_Y2 - GAUGE_Y1 + 1 == CANTEEN_SLOTS, \
        ('the gauge has %d rows for %d slots; a row is a slot, so nothing has to be scaled'
         % (GAUGE_Y2 - GAUGE_Y1 + 1, CANTEEN_SLOTS))
    assert GAUGE_Y2 == y2, 'the gauge should stand on the floor of the panel, not on a shelf'
    assert x1 <= GAUGE_X1 - 1 and GAUGE_X2 + 1 <= x2, \
        'the gauge and its bezel have to sit inside the panel, clear of the frame'

    # Nothing the page draws may land outside the panel, on either face. The heap's own reach is
    # bounded above, analytically; this bounds everything stock() actually writes, which is how
    # the gauge's backing was caught standing a row below the panel floor -- a dark notch in the
    # frame, on a picture of a block, which is about as quiet as a defect gets.
    # Every food, because they are not all the same size: a loaf is three texels tall in its
    # four-texel cell, so a heap checked in bread alone can hang a row into the frame and write
    # nothing there. It is the carrot and the beetroot that fill a cell. The casing underneath
    # is blank here because Create's sheet is not in this repo -- what is being asserted is that
    # nothing is drawn outside the panel, and a blank base says that as well as any other.
    blank_casing = blank(CLEAR)
    for food in range(len(FOOD_SHAPES)):
        full = stock([(food, 1.0)] * CANTEEN_SLOTS, blank_casing)
        drawn = (('canteen_trough', full['createworkers:block/canteen_trough'], trough()),
                 ('the casing', full[('create:block/andesite_casing', 'north')], blank_casing))
        for name, sheet, plain in drawn:
            for y in range(SIZE):
                for x in range(SIZE):
                    if x1 <= x <= x2 and y1 <= y <= y2:
                        continue
                    assert sheet[y][x] == plain[y][x], \
                        ('a Canteen full of %s draws over %s at (%d, %d), which is outside the '
                         'panel and so is the frame that holds this block to the Station\'s'
                         % (FOOD_SHAPES[food][0], name, x, y))


def check_against_model():
    """Hold the sheets, the model, the renderer and the roster's size to one another.

    Four files restate the same handful of numbers and nothing in the build tied them
    together, which is how the renderer once shipped hanging every hat a fiftieth of a
    block *inside* an opaque board -- not drawn badly, not drawn, with a full station
    and an empty one looking identical and nothing anywhere to say so.
    """
    boxes = json_model()['elements']
    assert len(boxes) == 1, 'the Station is one full cube; %d elements found' % len(boxes)
    assert boxes[0]['from'] == [0, 0, 0] and boxes[0]['to'] == [16, 16, 16], \
        'the Station must fill its block, or it cannot occlude and light like a solid one'
    assert all('cullface' in face for face in boxes[0]['faces'].values()), \
        'every face of a full cube spans the boundary and should cull'

    # Both sides keep these in the model's own units -- sixteenths of a block -- so the
    # comparison is an equality and not a conversion. A conversion here is somewhere for
    # a factor of sixteen to hide, and this file has already paid for one sign error.
    renderer = java_constants(
        RENDERER, r'private static final (?:int|float|double) (\w+) = ([^;]+);')
    for name in ('LAMP_COLUMNS', 'LAMP_ROWS', 'LAMP_PITCH_X', 'LAMP_PITCH_Y',
                 'LAMP_CENTRE_X', 'LAMP_CENTRE_Y', 'LAMP_SIZE'):
        assert name in renderer, '%s is not declared in WorkerStationRenderer' % name
        assert abs(renderer[name] - globals()[name]) < 1e-6, \
            '%s: renderer has %s, textures have %s' % (name, renderer[name], globals()[name])

    import re
    with open(STATION) as handle:
        found = re.search(r'MAX_SLOTS\s*=\s*(\d+)', handle.read())
    assert found, 'MAX_SLOTS is not declared in WorkerStationBlockEntity'
    slots = int(found.group(1))
    assert LAMP_COLUMNS * LAMP_ROWS == slots, \
        'the board has %d lamps for %d slots in the rack' % (LAMP_COLUMNS * LAMP_ROWS, slots)

    # The sign, read out of the expression rather than off the constants beside it. The defect this
    # whole check was written for was never a constant: a standoff *subtracted* where it should have
    # been added puts every sprite a fiftieth of a block inside an opaque cube, which is not drawn
    # badly -- it is not drawn at all, and a full station looks exactly like an empty one with the
    # renderer registered and running and nothing anywhere to say so. A check over the declared
    # fields passes that happily, which is what the first draft of this did.
    with open(RENDERER) as handle:
        source = handle.read()
    assert renderer['STANDOFF'] > 0, 'STANDOFF is the gap in front of the face, so it is positive'
    stood = re.search(r'poseStack\.translate\([^;]*?0\.5F\s*([-+])\s*STANDOFF\s*\)', source)
    assert stood, 'the lamp quad is no longer pushed out to the front face by 0.5F +/- STANDOFF'
    assert stood.group(1) == '+', \
        'the standoff is subtracted from the half-block face offset, which draws every lamp inside ' \
        'the block -- the exact bug this check exists for'

    spots = lamp_spots()
    xs = sorted({round(x, 6) for x, _ in spots})
    ys = sorted({round(y, 6) for _, y in spots})
    for axis, values in (('column', xs), ('row', ys)):
        gaps = [round(b - a, 6) for a, b in zip(values, values[1:])]
        assert len(set(gaps)) == 1, '%s spacing is uneven: %s' % (axis, gaps)
    assert abs((xs[0] + xs[-1]) / 2 - 8.0) < 1e-6, 'the lamps are not centred across the face'

    # And every lamp is inside the sunk panel. A texture column c covers block x from
    # 15-c to 16-c on a north face, which is the conversion that makes the panel drawn
    # on the sheet and the lamps drawn by the renderer comparable at all.
    x1, y1, x2, y2 = PANEL_BOX
    left, right = SIZE - 1 - x2, SIZE - x1
    bottom, top = SIZE - 1 - y2, SIZE - y1
    half = LAMP_VISIBLE / 2.0
    for x, y in spots:
        assert left <= x - half and x + half <= right, \
            'a lamp at x=%s runs off the panel (%s..%s)' % (x, left, right)
        assert bottom <= y - half and y + half <= top, \
            'a lamp at y=%s runs off the panel (%s..%s)' % (y, bottom, top)

    for first, second in zip(xs, xs[1:]):
        assert second - first >= LAMP_VISIBLE, 'the lamps overlap each other'

    # Which food owns which cell of the sheet, read back out of the block. The two lists are the
    # same fact in two languages and nothing tied them together: reorder either and every Canteen
    # draws the wrong food for every slot, silently. No game test can load a renderer to notice, and
    # the generator's own diff gate stays green because each file is self-consistent.
    canteen = os.path.join('src', 'main', 'java', 'com', 'createworkers', 'block',
                           'CanteenBlockEntity.java')
    with open(canteen) as handle:
        drawn = re.search(r'DRAWN_FOODS\s*=\s*List\.of\(([^;]*)\);', handle.read(), re.S)
    assert drawn, 'DRAWN_FOODS is not declared in CanteenBlockEntity'
    theirs = tuple(name.lower() for name in re.findall(r'Items\.(\w+)', drawn.group(1)))
    ours = tuple(name for name, _ in FOOD_SHAPES)
    assert theirs == ours, \
        ('the block draws %s and this file draws %s; a food is a cell of the sheet, so the two '
         'orders are one fact' % (theirs, ours))

    # And the sheet has room for them. A fifth food runs the last cell off the right-hand edge, and
    # the renderer's u2 goes past 1.0 rather than failing.
    assert len(FOOD_SHAPES) * CAVITY_PIECE <= SIZE, \
        ('%d foods at %d texels apiece do not fit across a %d-texel sheet'
         % (len(FOOD_SHAPES), CAVITY_PIECE, SIZE))

    # And the item model carries the readout, because an inventory has no renderer to draw it.
    # Without this the Station's item is a plain andesite casing cube -- which is a block players
    # already have stacks of, under a different name.
    models = item_models()
    station_plates = [box for box in models['worker_station']['elements']
                      if any(face.get('texture') == '#lamps' for face in box['faces'].values())]
    assert len(station_plates) == slots, \
        ('the Station\'s item model carries %d lamps for %d places in the rack'
         % (len(station_plates), slots))
    gauges = [box for box in models['canteen']['elements']
              if any(face.get('texture') == '#food' for face in box['faces'].values())]
    assert len(gauges) == 4, \
        'the Canteen\'s item model should carry a gauge on each of its four flanks, not %d' \
        % len(gauges)


# --- what the inventory makes of a readout ---------------------------------------

# Lighting.DIFFUSE_LIGHT_0/1, and the frame GlStateManager.setupGui3DDiffuseLighting puts them
# in before the shader ever sees them. Restated from vanilla rather than approximated, because
# "which side of an icon is the lit one" is exactly the sort of thing that gets settled by eye
# and settled wrongly: the two flanks differ by more than the top of a block differs from its
# side, and the dark one is the floor.
GUI_LIGHTS = ((0.2, 1.0, -0.7), (-0.2, 1.0, 0.7))
GUI_LIGHT_TURNS = ((-math.pi / 8, math.pi * 3 / 4), (1.0821041, 3.2375858))

# Which readout belongs to which block: the texture its plates are drawn with.
READOUTS = {'worker_station': '#lamps', 'canteen': '#food'}


def spun(vector, axis, radians):
    """One basis rotation of a vector, right-handed, the way a display block means it."""
    x, y, z = vector
    cos, sin = math.cos(radians), math.sin(radians)
    if axis == 'x':
        return (x, y * cos - z * sin, y * sin + z * cos)
    if axis == 'y':
        return (x * cos + z * sin, y, z * cos - x * sin)
    return (x * cos - y * sin, x * sin + y * cos, z)


def unit(vector):
    length = math.sqrt(sum(component * component for component in vector))
    return tuple(component / length for component in vector)


def gui_lights():
    """The two lights an icon is lit by, in the frame the shader compares its normals in."""
    lights = []
    for light in GUI_LIGHTS:
        for yaw, pitch in GUI_LIGHT_TURNS:
            light = spun(spun(light, 'x', pitch), 'y', yaw)
        lights.append(unit((light[0], -light[1], light[2])))
    return lights


def icon_face(name, rotation):
    """A face of a block item as its icon shows it: which way it points, and how brightly lit.

    The model is turned by its display transform and then mirrored in y, which is what
    GuiGraphics' scale(16, -16, 16) does to the item and to its normals alike. After that the
    camera looks along -z and x runs to the right, so a face is on screen when its z is positive
    and on the left of the icon when its x is negative. The brightness is minecraft_mix_light:
    the two lights and nothing else, since no per-face shade reaches an item.
    """
    axis, sign = FACES[name]
    normal = tuple(float(sign) if index == axis else 0.0 for index in (0, 1, 2))
    pitch, yaw, roll = rotation
    normal = spun(normal, 'z', math.radians(roll))
    normal = spun(normal, 'y', math.radians(yaw))
    normal = spun(normal, 'x', math.radians(pitch))
    normal = (normal[0], -normal[1], normal[2])
    lit = sum(max(0.0, sum(a * b for a, b in zip(light, normal))) for light in gui_lights())
    return normal, min(1.0, lit * 0.6 + 0.4)


def check_icon_lighting():
    """A block's readout has to land on the lit side of its own inventory icon.

    Both blocks wear Create's andesite casing, so what is left of either in an inventory without
    its readout is a cube the player already has stacks of under another name -- which is the
    whole reason the lamps and the gauges are baked into the item models. Drawing them is only
    half of it. The icon shows a block's north face on the right and its east face on the left,
    and the light is on the left: 0.651 against 0.400, which is the floor the formula can reach
    and what the underside of a block gets. The Station's lamps are on its front, and the front
    was the dark one, so the readout was drawn into shadow on a casing cube and the item read as
    plain casing anyway.

    Nothing else here could say so. The grid checks put every lamp where the renderer would draw
    it, and they would go on passing with the whole readout in the dark.
    """
    # The reproduction is worth something only if it agrees with what anybody has seen a thousand
    # times: the top of a block icon is its brightest face and the underside its darkest.
    assert abs(icon_face('up', VANILLA_TURN)[1] - 1.0) < 1e-9, \
        'the top of a block icon is fully lit; this reproduction of the GUI lights disagrees'
    assert abs(icon_face('down', VANILLA_TURN)[1] - 0.4) < 1e-9, \
        'the underside of a block icon is at the floor; this reproduction of it disagrees'

    models = item_models()
    for block, texture in sorted(READOUTS.items()):
        model = models[block]
        gui = model.get('display', {}).get('gui')
        assert gui is None or sorted(gui) == ['rotation', 'scale', 'translation'], \
            ('%s turns its icon without restating all of rotation, translation and scale. A '
             'display entry replaces the parent\'s for that context rather than merging with '
             'it, so an omitted scale is 1.0 where block/block has 0.625.' % block)
        rotation = gui['rotation'] if gui else VANILLA_TURN

        drawn = {face for box in model['elements'] for face, spec in box['faces'].items()
                 if spec.get('texture') == texture}
        flanks = {}
        for name in FACES:
            if name in ('up', 'down'):
                continue
            normal, lit = icon_face(name, rotation)
            if normal[2] > 0:
                flanks[name] = lit

        shown = sorted(drawn & set(flanks))
        assert shown, \
            ('%s draws its readout on %s and its icon shows none of them, so the item is a bare '
             'casing cube' % (block, ', '.join(sorted(drawn)) or 'nothing'))
        brightest = max(flanks.values())
        assert max(flanks[face] for face in shown) >= brightest, \
            ('%s draws its readout on the shaded flank of its icon -- %s, against %.3f for the '
             'side the light is on. An unlit lamp on a shaded casing face is exactly the andesite '
             'casing cube this readout exists to stop the item looking like.'
             % (block, ', '.join('%s at %.3f' % (face, flanks[face]) for face in shown),
                brightest))


def luma(pixel):
    return 0.299 * pixel[0] + 0.587 * pixel[1] + 0.114 * pixel[2]


def warm(pixel):
    high, low = max(pixel[:3]), min(pixel[:3])
    return high > 0 and (high - low) / float(high) > 0.30


# --- the smallest PNG writer that will do ---------------------------------------

def png(pixels):
    height, width = len(pixels), len(pixels[0])
    raw = b''
    for row in pixels:
        raw += b'\x00'
        for r, g, b, a in row:
            raw += struct.pack('BBBB', r, g, b, a)

    def chunk(kind, payload):
        return (struct.pack('>I', len(payload)) + kind + payload
                + struct.pack('>I', zlib.crc32(kind + payload) & 0xFFFFFFFF))

    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


SHEETS = {
    'worker_station_lamps': lamps,
    'canteen_food': canteen_food,
    'canteen_trough': trough,
}

# Left behind by two shapes this family has been through: the bench-and-board Station, and
# the casings both blocks wore before they were put on Create's own. Removing them here
# rather than by hand is what keeps a re-run of the generators a no-op, which both
# workflows check.
RETIRED = ('worker_station_side', 'worker_station_board', 'worker_station_board_back',
           'worker_station_top', 'worker_station_casing', 'worker_station_bottom',
           'worker_station_front', 'canteen_side', 'canteen_top', 'canteen_bottom')


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else OUTPUT_DIR
    if directory == OUTPUT_DIR:
        check_faces()
        check_against_model()
        check_canteen_grid()
        check_icon_lighting()
    os.makedirs(directory, exist_ok=True)

    for name in RETIRED:
        stale = os.path.join(directory, '%s.png' % name)
        if os.path.exists(stale):
            os.remove(stale)
            print('removed %s' % stale)

    for name, draw in sorted(SHEETS.items()):
        destination = os.path.join(directory, '%s.png' % name)
        with open(destination, 'wb') as handle:
            handle.write(png(draw()))
        print('wrote %s' % destination)

    if directory == OUTPUT_DIR:
        import json

        for name, model in sorted(item_models().items()):
            destination = os.path.join(ITEM_MODELS, '%s.json' % name)
            with open(destination, 'w') as handle:
                handle.write(json.dumps(model, indent=2) + '\n')
            print('wrote %s' % destination)


if __name__ == '__main__':
    main()
