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

import os
import struct
import sys
import zlib

OUTPUT_DIR = 'src/main/resources/assets/createworkers/textures/block'

SIZE = 16

# --- the palette ---------------------------------------------------------------
# Neutral ramp: **eleven** steps, saturation about 0.05, luma 60..172. Eleven rather
# than eight because a ramp that steps in thirteens can give a trim ring either a
# smooth gradient or a decent count of shades and not both -- andesite's greys step
# by seven to ten and carry seven shades on a ring whose neighbours differ by 5.6.
GREY = [(0x38, 0x3D, 0x3B, 255), (0x42, 0x47, 0x44, 255), (0x4C, 0x51, 0x4E, 255),
        (0x56, 0x5B, 0x58, 255), (0x60, 0x65, 0x61, 255), (0x6B, 0x70, 0x6C, 255),
        (0x76, 0x7C, 0x77, 255), (0x83, 0x89, 0x84, 255), (0x92, 0x98, 0x92, 255),
        (0xA2, 0xA8, 0xA2, 255), (0xB2, 0xB8, 0xB2, 255)]

# Warm ramp: seven steps, saturation about 0.55, luma 62..110. **Seven, not five.**
# Andesite's own warm shades step by four to nine; a five-step ramp over the same
# range steps by thirteen, and every difference between neighbouring boards then
# lands at double the contrast Create's does. That is most of what reads as 'noisy'
# when the two are put side by side, and no statistic here ever reported it.
TIMBER = [(0x4E, 0x36, 0x1F, 255), (0x5A, 0x40, 0x26, 255), (0x65, 0x48, 0x2B, 255),
          (0x71, 0x51, 0x31, 255), (0x7C, 0x5A, 0x37, 255), (0x87, 0x63, 0x3D, 255),
          (0x90, 0x6B, 0x43, 255)]

# The inside of the Canteen, and the food in it. Deliberately outside the casing ranges
# the rest of this file is held to: those describe a cast *surface*, and a trough's
# opening is the one place on either block where there is no surface to describe.
CAVITY = (0x2A, 0x21, 0x18, 255)
CAVITY_LIT = (0x3B, 0x2F, 0x22, 255)
BREAD = (0xAE, 0x80, 0x49, 255)
BREAD_LIT = (0xC8, 0x9A, 0x60, 255)

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



# How far each trim ring slides between its lit and its shadowed end, as indices into
# GREY. The outer runs wider and darker, the inner narrower and brighter, which is what
# the ring-by-ring measurement of Create's casings says they do.
OUTER_RANGE = (5, 1)
INNER_RANGE = (9, 7)

TRIM_SEED = 4242
# Sparser on the outer ring than the inner. Measured along the ring, andesite's outer
# steps average 5.6 against its inner's 12.3 -- the outer edge is the smoother of the
# two, and a single density for both leaves it roughly twice as busy as Create's.
TRIM_DITHER = (7, 4)


def andesite_trim(pixels):
    """Two rings of cast grey, each sliding from a lit corner to a shadowed one.

    **A gradient, not a switch.** Choosing one tone for the lit sides and another for
    the shadowed ones puts the whole of the bevel into the two corners where they meet,
    so every pixel pair across a corner jumps two ramp steps and the ring reads as
    mottled. Create's outer ring carries the same eighteen luma of bevel at a third of
    the roughness, which no setting of a switch can do: it slides.

    The slide runs on x + y, which is zero at the top left and largest at the bottom
    right -- the direction Minecraft's own light comes from.
    """
    reach = 2.0 * (SIZE - 1)
    for x in range(SIZE):
        for y in range(SIZE):
            edge = min(x, y, SIZE - 1 - x, SIZE - 1 - y)
            if edge > 1:
                continue
            light, dark = OUTER_RANGE if edge == 0 else INNER_RANGE
            index = light - (light - dark) * (x + y) / reach
            if grain_hash(TRIM_SEED, x, y) % TRIM_DITHER[edge] == 0:
                index -= 1
            pixels[y][x] = GREY[max(0, min(len(GREY) - 1, int(round(index))))]

    # Corner marks, and they shade *down* from whatever the gradient has put there.
    # They used to be a bright rivet against a mid-grey ring -- a sixty-luma jump, eight
    # pixels of it, which on a ring of sixty was most of the roughness the whole gradient
    # was introduced to remove. Create's corners are darker patches, not highlights.
    # Kept to the outer ring. Put on ring 1 they darken it, and the two rings then sit
    # close enough together that the frame stops reading as a casting with an edge --
    # which is the one thing about this trim that was hardest to find and easiest to
    # lose again.
    corners = ((0, 0), (1, 0), (SIZE - 1, 0), (SIZE - 2, 0),
               (0, SIZE - 1), (1, SIZE - 1), (SIZE - 1, SIZE - 1), (SIZE - 2, SIZE - 1))
    for x, y in corners:
        here = GREY.index(pixels[y][x])
        pixels[y][x] = GREY[max(0, here - 2)]
    return pixels


def inner_shadow(pixels):
    """The panel's own outermost ring, dark.

    Ring 2 in Create's casings is 100% warm at a luma of 68 -- it is wood, not trim: the
    shadow the frame throws onto the boards. It was previously described here as an
    "inner trim", which is what sent the two grey rings into one.

    Being timber, it carries figure like the rest of the panel. What has to stay true
    is that its four sides still *average* the same: uniform all the way round is what
    makes it read as a shadow rather than as a bevel, and that is a fact about the
    means, not about every pixel.
    """
    low, high = TRIM, SIZE - 1 - TRIM
    for i in range(low, high + 1):
        for y, x in ((low, i), (high, i), (i, low), (i, high)):
            pixels[y][x] = TIMBER[2] if grain_hash(SHADOW_SEED, y, x) % 4 == 0 else TIMBER[1]
    return pixels


# Where the boards are divided, as offsets into the panel: two columns, then four, then
# two, spaced the way andesite's are at three to five columns between single dark lines.
#
# **Uneven on purpose, and it took a change elsewhere to afford it.** These widths were
# tried once and abandoned, because back then a board drifted between two tones as a
# whole and a four-wide one therefore moved forty per cent of the panel in a single row,
# drawing a band across the grain that no stagger could hide. Even widths of three, three
# and two were the workaround. The grain is short marks that alternate and cancel now
# rather than a drift, so a wide board no longer swings the panel -- the biggest jump
# across the grain measures 2.7 here against 2.9 at even widths -- and the irregularity
# reads better, closer to sawn stock than to machined panelling.
SEPARATORS = (2, 7)

# One base tone per board, in order. Similar to each other on purpose: Create's three
# boards are close in colour and told apart by their texturing, not by their shade.
BOARD_TONES = (2, 4, 3, 5, 2, 4)

# The tone a grain line sits at, and the step a grain mark shifts by. One step on this
# ramp is about eight luma -- enough to see, far short of the thirteen that made the
# panel read as contrasty.
# The darkest step. A grain line's marks all shade upward from here, which gives it the
# light and dark down its length while keeping its average well below the boards'.
GRAIN_TONE = 0

# A seed of its own for the shadow ring, so its figure is nobody else's repeated.
SHADOW_SEED = 9001
GRAIN_STEP = 1


def grain_hash(*key):
    """FNV-1a over small integers: well mixed, and the same every run.

    Well mixed because the entire point is that no two boards and no two grain lines
    come out looking alike. The same every run because both workflows re-run the
    generators and fail on a diff -- varied is not the same as random.
    """
    value = 2166136261
    for part in key:
        value = ((value ^ (part & 0xFFFFFFFF)) * 16777619) & 0xFFFFFFFF
    return value


def streaks(seed, column, span, dense=False):
    """Where the grain marks fall down one column, and which way each shades.

    Marks rather than a drift. A drift between two tones gives a column one boundary,
    and boundaries that line up across the boards draw a band at right angles to the
    grain -- which they did, twice. A handful of short marks at positions nobody chose
    has no boundary to line up.
    """
    picked = grain_hash(seed, column)
    out = []
    # **Alternating directions, so the marks pay for themselves.** A mark shifts the
    # average of the column it sits in, and a column that shifts far enough stops
    # reading as part of its board -- which is how this drew narrow stripes twice, and
    # why the marks were once thinned to a coin flip per column. Made to alternate, a
    # column's marks cancel: its face can carry two or three of them while its average
    # barely moves, so there can be figure and wide boards at the same time.
    #
    # A grain line carries more of them than a board's face does. The light and dark
    # down a line is what makes it read as grain rather than as something ruled.
    count = (3 if dense else 2) + picked % 2
    for index in range(count):
        mixed = grain_hash(seed, column, index)
        out.append((mixed % span,                      # where it starts
                    1 + (mixed >> 8) % 2,              # one or two long
                    # A board's marks alternate so they cancel. A grain line's all
                    # shade *lighter*, because it sits at the bottom of the ramp and
                    # has to stay clearly darker than the boards it parts -- balanced
                    # marks lifted one line's average until it stopped reading as a
                    # parting at all.
                    (GRAIN_STEP * (1 + index % 2) if dense
                     else (GRAIN_STEP if index % 2 == 0 else -GRAIN_STEP))))
    return out


def planks(pixels, along_y=True):
    """Boards with the grain running along them, each one textured differently.

    Wide boards of a shared base tone, parted by dark lines, with short grain marks
    down them. What makes it read as timber rather than as corduroy is that the marks
    are *per column and per board*: the boards share a colour and differ in their
    figure, which is what Create's do.
    """
    low, high = TRIM + 1, SIZE - 2 - TRIM
    span = high - low + 1

    boards_at, board = {}, 0
    for offset in range(span):
        if offset in SEPARATORS:
            board += 1
        else:
            boards_at[offset] = board

    for x in range(low, high + 1):
        for y in range(low, high + 1):
            along, across = (x, y) if along_y else (y, x)
            offset, place = along - low, across - low

            if offset in SEPARATORS:
                base = GRAIN_TONE
                # A seed of its own per line, so the two are not the same line twice.
                seed = 1000 + SEPARATORS.index(offset)
            else:
                base = BOARD_TONES[boards_at[offset] % len(BOARD_TONES)]
                seed = boards_at[offset]

            shift = 0
            for begins, length, direction in streaks(seed, offset, span,
                                                     dense=offset in SEPARATORS):
                if begins <= place < begins + length:
                    shift = direction
                    break
            pixels[y][x] = TIMBER[max(0, min(len(TIMBER) - 1, base + shift))]
    return pixels


def casing():
    """A wood panel in an andesite frame, which is the way round Create does it.

    This block had it inverted -- a thin timber frame around a cast plate -- until the
    warm pixels in Create's own casings were counted by position rather than by
    quantity. Andesite casing is 0% warm on its border and 100% warm inside it.
    """
    pixels = planks(blank(TIMBER[2]))
    inner_shadow(pixels)
    andesite_trim(pixels)
    return pixels


def casing_bottom():
    pixels = planks(blank(TIMBER[1]))
    for y in range(TRIM + 1, SIZE - 1 - TRIM):
        for x in range(TRIM + 1, SIZE - 1 - TRIM):
            pixels[y][x] = TIMBER[1] if (x + y) % 3 else TIMBER[0]
    inner_shadow(pixels)
    andesite_trim(pixels)
    return pixels


def front():
    """The same casing. The lamps are mounted straight on the boards.

    No sunk panel and no nameplate: with the interior already wood, a dark inset would
    be a fourth material on a block that reads correctly with three, and it would put
    the lamps somewhere other than where Create would put a fitting -- which is on the
    panel, not in a hole cut through it.
    """
    return casing()


def canteen_bottom():
    return casing_bottom()


def canteen_top():
    """The empty trough. What is *in* it is drawn by CanteenRenderer, not baked here.

    The one face that has to say what the block is, and the first version did not say it.
    Drawn as boards a couple of steps down the timber ramp it read as a lid -- which is what
    a panel always reads as, however dark, because a panel is a surface and this has to be an
    absence of one. The cavity is therefore far darker than anything else in the family: the
    rule that nothing in a Create casing goes below a luma of 57 is a rule about *casings*,
    and the inside of a box is not one.

    Depth comes from the far wall catching the light -- the same trick the lamp bezels use,
    and the opposite of how a raised face is shaded. A recess lit like a bump reads as a bump.

    **The food used to be painted on here and is not any more.** Three loaves in fixed places
    said "this is a canteen" and nothing else: a full one and a nearly empty one were the same
    picture, which is the same mistake the Station's board made when it drew a hat instead of
    counting one. The stock is a thing the block knows, so it is drawn from what the block
    knows -- see CanteenRenderer, and CAVITY_* below for the grid the two share.
    """
    pixels = blank(TIMBER[0])
    x1, y1, x2, y2 = PANEL_BOX
    for y in range(y1, y2 + 1):
        for x in range(x1, x2 + 1):
            near = min(x - x1, y - y1)
            far = min(x2 - x, y2 - y)
            pixels[y][x] = CAVITY_LIT if far < near else CAVITY

    inner_shadow(pixels)
    andesite_trim(pixels)
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

# The gauge on the flank: two texels wide, which on a sixteen-texel face straddles the centre
# exactly where three cannot, and nine tall because the block has nine slots and a row is one.
GAUGE_X1, GAUGE_X2 = 7, 8
GAUGE_Y1, GAUGE_Y2 = 4, PANEL_BOX[3]


def canteen_side():
    """The Canteen's sides: the same casing the Station wears, untouched.

    Shared construction on purpose. Create's own blocks are a family before they are
    individuals -- a dozen of them carry andesite casing on their flanks and are told apart by
    the face that does something -- and two blocks from one addon that read as the same kit is
    the intended effect, not a missed opportunity to differentiate.

    **The stock gauge is not cut into this sheet**, for the reason the Station's lamps are not
    cut into its front: a panel is checked as boards, and a slot of dark pixels in the middle
    of one is not boards. Both blocks draw their readout over an unbroken casing from a sheet
    of its own, which also means the readout can move without the panel being redrawn.
    """
    return casing()


# One silhouette per food, because a hue is not a shape: four lumps of the same seven pixels
# in four colours is a palette, not a larder. Read them as pictures -- bread lies down, a
# carrot stands up and comes to a point under its tuft, a potato is a round lump, a beetroot
# is a bulb with a tail. L lit, B base, S shade, G leaf, . nothing.
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


CANTEEN_RENDERER = os.path.join(
    'src', 'main', 'java', 'com', 'createworkers', 'client', 'CanteenRenderer.java')


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

    check_top_face_quad()

    # The gauge: a row per slot, standing on the floor of the panel and under its own bezel.
    assert GAUGE_Y2 - GAUGE_Y1 + 1 == CANTEEN_SLOTS, \
        ('the gauge has %d rows for %d slots; a row is a slot, so nothing has to be scaled'
         % (GAUGE_Y2 - GAUGE_Y1 + 1, CANTEEN_SLOTS))
    assert GAUGE_Y2 == y2, 'the gauge should stand on the floor of the panel, not on a shelf'
    assert x1 <= GAUGE_X1 - 1 and GAUGE_X2 + 1 <= x2, \
        'the gauge and its bezel have to sit inside the panel, clear of the frame'


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


def luma(pixel):
    return 0.299 * pixel[0] + 0.587 * pixel[1] + 0.114 * pixel[2]


def warm(pixel):
    high, low = max(pixel[:3]), min(pixel[:3])
    return high > 0 and (high - low) / float(high) > 0.30


# What a Create casing measures like, taken off Create's own andesite, copper and
# railway casings. These are the ranges those three fall in, widened only enough to
# hold all of them. Not a style invented here, and not values copied from anywhere
# either -- a measurement of a convention, kept so the art cannot drift back out of
# family the way it already has once.
# What a Create casing measures like, ring by ring, off Create's own andesite and
# copper casings. Aggregates over the whole border were what made this block wrong
# twice: averaging ring 0 and ring 1 together gives andesite a single "trim luma" of
# 113, which this generator matched exactly while the block still looked nothing like
# one -- because there are two trims, at 89 and 140, and the mean of them is a colour
# that appears nowhere.
#
#              ring 0        ring 1        ring 2          panel
#   andesite  89, grey     140, grey      68, dark wood   83, boards
#   copper   106, warm     140, warm      78, grey        86
HOUSE_STYLE = {
    'outer ring luma': (78, 118),
    'inner ring luma': (125, 155),
    'trim step': 30,              # how much brighter the inner ring must be
    'ring bevel': (8, 34),
    # (outer, inner). Smooth along the ring, but not dead flat.
    'ring roughness': ((3.0, 10.0), (4.0, 15.0)),        # each ring lit from the top left, and gently
    'shadow ring luma': (55, 85), # ring 2: the shadow the frame throws on the boards
    'field luma': (70, 105),
    'shades': (10, 24),
    # Create's panels match their vertical neighbour about half the time and their
    # horizontal neighbour almost never: andesite 48% down against 11% across. The
    # grain is vertical, and no isotropic statistic can see that -- shade counts and
    # isolated-pixel fractions called this sheet and Create's identical while one was
    # built from rows and the other from columns.
    'grain ratio': 2.0,
    'column runs': 35,
    # And the boards have room: andesite's column means run 84 to 101 between dark
    # separators. A panel whose columns all average the same is corduroy, not timber,
    # and that is exactly what an earlier version of this drew.
    # Andesite's own boards span 84 to 101, so the bound is 14 rather than anything
    # tighter: a check set above what the reference itself measures is a check that
    # fails good work.
    'board spread': 14,
    'partings': 2,
    'cross-grain step': 3.0,
    'warm ramp step': 11.0,
    'within a board': 6.0,
}


def anisotropy(rows):
    """(equal %, mean |dluma|) across the panel and down it, ignoring the trim."""
    low, high = PANEL_BOX[0], PANEL_BOX[2]
    out = []
    for dx, dy in ((1, 0), (0, 1)):
        same = total = 0
        deltas = []
        for y in range(low, high + 1):
            for x in range(low, high + 1):
                nx, ny = x + dx, y + dy
                if nx > high or ny > high:
                    continue
                total += 1
                same += rows[y][x][:3] == rows[ny][nx][:3]
                deltas.append(abs(luma(rows[y][x]) - luma(rows[ny][nx])))
        out.append((100.0 * same / total, sum(deltas) / len(deltas)))
    return out


def ring(rows, k):
    """The k-th square ring in from the edge, as a flat list of pixels."""
    size = len(rows)
    low, high = k, size - 1 - k
    out = []
    for i in range(low, high + 1):
        out += [rows[low][i], rows[high][i]]
        if low < i < high:
            out += [rows[i][low], rows[i][high]]
    return out


def ring_path(rows, k):
    """The k-th ring walked *in order*, so consecutive entries really are neighbours.

    `ring` gathers the same pixels for counting and interleaves opposite sides while it
    does, which is fine for a mean and useless for anything about adjacency -- measured
    over that order this ring reads as stepping 19.8 when it steps 6.4.
    """
    size = len(rows)
    low, high = k, size - 1 - k
    out = [rows[low][x] for x in range(low, high + 1)]
    out += [rows[y][high] for y in range(low + 1, high + 1)]
    out += [rows[high][x] for x in range(high - 1, low - 1, -1)]
    out += [rows[y][low] for y in range(high - 1, low, -1)]
    return out


def sides(rows, k):
    """Mean luma of one square ring's four sides."""
    size = len(rows)
    low, high = k, size - 1 - k
    top = [luma(rows[low][x]) for x in range(low, high + 1)]
    bottom = [luma(rows[high][x]) for x in range(low, high + 1)]
    left = [luma(rows[y][low]) for y in range(low, high + 1)]
    right = [luma(rows[y][high]) for y in range(low, high + 1)]
    return [sum(v) / len(v) for v in (top, bottom, left, right)]


def check_trim(name, rows):
    """The cast frame, which every block in this family wears and which holds them together.

    Split out of check_house_style because it covers more sheets than the panel rules do.
    A Canteen's top is the inside of a box rather than a face of boards, so asking it about
    grain direction or board spread would be asking it to be something it is not -- but its
    *frame* is what makes it read as the same kit as the Station, and a frame that quietly
    stopped matching is exactly how a block family drifts apart.
    """
    outer, inner, shadow = (ring(rows, k) for k in range(3))
    assert not any(warm(p) for p in outer + inner), \
        '%s: the trim is warm; Create puts neutral trim around a warm panel' % name
    assert all(warm(p) for p in shadow), \
        '%s: ring 2 is the shadow cast on the boards and should be timber, not trim' % name

    outer_luma = sum(luma(p) for p in outer) / len(outer)
    inner_luma = sum(luma(p) for p in inner) / len(inner)
    for label, value, bounds in (('outer', outer_luma, HOUSE_STYLE['outer ring luma']),
                                 ('inner', inner_luma, HOUSE_STYLE['inner ring luma'])):
        low, high = bounds
        assert low <= value <= high, \
            '%s: %s trim ring luma %.0f outside %s' % (name, label, value, bounds)
    assert inner_luma - outer_luma >= HOUSE_STYLE['trim step'], \
        ('%s: the two trim rings are %.0f apart. A Create casing has a dark outer edge '
         'and a bright inner one -- andesite runs 89 then 140 -- and collapsing them '
         'to one mid grey is the single thing that stops a frame reading as a casting.'
         % (name, inner_luma - outer_luma))

    for k, label in ((0, 'outer'), (1, 'inner')):
        # How rough the ring is *along itself*. This is the measurement that caught
        # the trim reading as mottled beside a gearbox: picking one tone for the lit
        # sides and another for the shadowed ones puts the whole bevel into the two
        # corners where they meet, so neighbouring pixels there jump two ramp steps.
        # Create carries the same bevel at a third of the roughness by sliding the
        # tone round the ring instead, which a switch cannot do at any setting --
        # andesite's outer ring steps 5.6 on average, this one used to step 19.2.
        around = ring_path(rows, k)
        steps = [abs(luma(around[i]) - luma(around[(i + 1) % len(around)]))
                 for i in range(len(around))]
        rough = sum(steps) / len(steps)
        low_r, high_r = HOUSE_STYLE['ring roughness'][k]
        assert low_r <= rough <= high_r, \
            ('%s: the %s trim ring steps %.1f between neighbouring pixels, wanted %s. '
             'Andesite steps 5.6 on its outer ring and 12.3 on its inner.'
             % (name, label, rough, (low_r, high_r)))

        spread = max(sides(rows, k)) - min(sides(rows, k))
        low, high = HOUSE_STYLE['ring bevel']
        assert low <= spread <= high, \
            ('%s: the %s trim ring spreads %.0f across its sides, wanted %s -- Create '
             'lights both rings from the top left and does it gently'
             % (name, label, spread, (low, high)))


def check_house_style():
    """Hold the sheets to what Create's own casings measure like, ring by ring.

    Every earlier version of this check compared *aggregates over the whole border* and
    every one of them passed a block that was visibly wrong, because a mean hides
    structure. The frame was worn inside out (timber outside, cast inside, where every
    Create casing is the reverse); the two grey trims were collapsed into one flat mid
    grey; the shadow ring was bevelled where all of Create's are uniform; and the boards
    were drawn as an even alternation with no room between them. Four faults, none of
    which moved a single aggregate outside its range.

    So this reads each ring separately, and it reads the panel's columns separately.
    """
    # The frame first, over every sheet that wears one -- including the Canteen's top and both
    # bottoms, which have no panel of boards for the rules below to read.
    for name in TRIM_SHEETS:
        check_trim(name, SHEETS[name]())

    for name in CASING_SHEETS:
        rows = SHEETS[name]()

        shadow_sides = sides(rows, 2)
        low, high = HOUSE_STYLE['shadow ring luma']
        assert low <= sum(shadow_sides) / 4 <= high, \
            '%s: shadow ring luma %.0f outside %s' % (name, sum(shadow_sides) / 4, (low, high))
        assert max(shadow_sides) - min(shadow_sides) <= 12, \
            ('%s: the shadow ring varies %.0f between its sides; every Create casing keeps '
             'it uniform all the way round' % (name, max(shadow_sides) - min(shadow_sides)))

        low, high = PANEL_BOX[0], PANEL_BOX[2]
        field = [rows[y][x] for y in range(low, high + 1) for x in range(low, high + 1)]
        assert all(warm(p) for p in field), '%s: the panel is meant to be boards' % name
        panel = sum(luma(p) for p in field) / len(field)
        bounds = HOUSE_STYLE['field luma']
        assert bounds[0] <= panel <= bounds[1], \
            '%s: panel luma %.0f outside %s' % (name, panel, bounds)

        # Create's warm shades step by four to nine; a ramp half as fine over the same
        # range puts every difference between neighbouring boards at double the contrast,
        # which is most of what reads as "noisy" beside a real casing -- and a shade
        # *count* cannot see it, because a coarse ramp and a fine one can carry the same
        # number of colours.
        warm_lumas = sorted({luma(p) for row in rows for p in row if warm(p)})
        gaps = [b - a for a, b in zip(warm_lumas, warm_lumas[1:])]
        assert max(gaps) <= HOUSE_STYLE['warm ramp step'], \
            ('%s: the warm ramp jumps %.0f between neighbouring shades (%s). Andesite never '
             'steps more than nine, and a coarse ramp doubles the contrast of every board '
             'edge on the panel.'
             % (name, max(gaps), ' '.join('%.0f' % v for v in warm_lumas)))

        shades = len({p[:3] for row in rows for p in row})
        low_s, high_s = HOUSE_STYLE['shades']
        assert low_s <= shades <= high_s, \
            '%s: %d shades, wanted %s -- Create carries 15 to 19 in a sheet' \
            % (name, shades, (low_s, high_s))

        # The grain runs along the boards rather than across them, measured on the panel
        # alone: including the trim edge is including a material boundary, which is not
        # noise, and it hides the difference completely.
        across, down = anisotropy(rows)
        strong, weak = (across, down) if across[1] > down[1] else (down, across)
        assert strong[1] > HOUSE_STYLE['grain ratio'] * weak[1], \
            ('%s: the panel has no grain direction -- |dluma| is %.1f one way and %.1f the '
             'other. A Create casing is built from lines, not from an even field.'
             % (name, across[1], down[1]))
        assert weak[0] >= HOUSE_STYLE['column runs'], \
            '%s: only %.0f%% of neighbours match along the grain; Create holds about half' \
            % (name, weak[0])

        # No banding at right angles to the grain. The tone drift has to be staggered
        # between boards; unstaggered, every board steps at the same point along the
        # grain and draws a stripe across the panel. It survives the spread bound --
        # 85 90 85 90 spreads no further than a smooth run does -- so what is measured
        # is how many *distinct* values the means take: andesite's six against the two
        # a clean repeat produces.
        lines_across = [[luma(rows[y][x]) for x in range(low, high + 1)]
                        for y in range(low, high + 1)]
        cross_means = [sum(line) / len(line) for line in lines_across]

        # **The jump, not the variety.** An earlier version of this counted distinct
        # values and wanted four, which is the wrong quantity twice over: andesite's
        # cross-grain means spread 11 -- wider than this block's ever did -- and read
        # perfectly calm, because they drift. A stripe is an abrupt *step* repeated, so
        # what is measured is the largest move between neighbouring rows, with the two
        # edge rows left out because the frame's shadow legitimately drops there.
        steps = [abs(b - a) for a, b in zip(cross_means[1:-2], cross_means[2:-1])]
        assert max(steps) <= HOUSE_STYLE['cross-grain step'], \
            ('%s: the mean across the grain jumps %.1f between neighbouring rows (%s) -- '
             'the boards are drifting in step, or one is wide enough to move the panel on '
             'its own, and either draws a stripe at right angles to the grain. Andesite '
             'never jumps more than 2.'
             % (name, max(steps), ' '.join('%.0f' % v for v in cross_means)))

        # A board is one board across its width. This is the fault that put eight narrow
        # stripes where a gearbox has three wide ones -- the tone was indexed by column
        # rather than by board -- and nothing else here could see it: the spacing, the
        # spread between boards, the grain direction and the banding were all still
        # right, and the panel still looked nothing like a casing.
        runs, current = [], []
        for offset in range(high - low + 1):
            if offset in SEPARATORS:
                if current:
                    runs.append(current)
                current = []
            else:
                current.append(offset)
        if current:
            runs.append(current)
        # A board reads as one piece of timber and still has figure in it. Both halves
        # matter and each has been got wrong once: tones dealt per column made three
        # boards draw as eight narrow stripes, and the correction for that -- demanding
        # every column of a board be identical -- flattened them, which is not what
        # Create's are either. Its boards are close in colour and told apart by their
        # texturing.
        patterns = []
        for board in runs:
            columns = [[rows[y][low + o][:3] for y in range(low, high + 1)] for o in board]
            means = [sum(luma(p) for p in column) / len(column) for column in columns]
            assert max(means) - min(means) <= HOUSE_STYLE['within a board'], \
                ('%s: the columns of one board average %s -- that is a spread of %.0f, so it '
                 'reads as that many narrow stripes rather than as one board.'
                 % (name, ' '.join('%.0f' % v for v in means), max(means) - min(means)))
            assert any(column != columns[0] for column in columns) or len(columns) == 1, \
                ('%s: a board is completely flat across its width. Create gives its boards '
                 'figure; taking all of it out is the other way to get this wrong.' % name)
            patterns.append(columns)

        # And nothing repeats. Two grain lines with the same light and dark pixels down
        # them, or two boards built to the same pattern, is the thing that reads as
        # manufactured however good the individual pattern is.
        lines = [[rows[y][low + offset][:3] for y in range(low, high + 1)]
                 for offset in SEPARATORS]
        assert len(lines) == len({tuple(line) for line in lines}), \
            ('%s: two grain lines are pixel for pixel the same. Each wants its own light '
             'and dark, or the panel reads as one line stamped twice.' % name)
        flattened = [tuple(tuple(c) for c in board) for board in patterns]
        assert len(flattened) == len(set(flattened)), \
            '%s: two boards are drawn identically' % name

        # And the boards differ from one another, not merely within themselves.
        lines = [[luma(rows[y][x]) for y in range(low, high + 1)] for x in range(low, high + 1)]
        if name == 'worker_station_top':
            lines = [[luma(rows[y][x]) for x in range(low, high + 1)] for y in range(low, high + 1)]
        means = sorted(sum(line) / len(line) for line in lines)
        # Separators are far darker than any board -- andesite's sit at 67 against
        # boards of 84 and up -- so they come off relative to the panel's own mean
        # rather than against a fixed line that would clip a genuinely dark board.
        boards = [m for m in means if m > panel - 15]

        # There have to *be* separators. A panel of boards with no dark lines between
        # them is an even field however much its tones vary, and that is the whole of
        # "more space between the grain" -- andesite parts its boards every three to
        # five columns and this block used to alternate every one or two.
        partings = [m for m in means if m <= panel - 15]
        assert len(partings) >= HOUSE_STYLE['partings'], \
            ('%s: %d dark lines part the boards, wanted at least %d. Without them the '
             'panel is one field of timber rather than boards laid side by side.'
             % (name, len(partings), HOUSE_STYLE['partings']))

        assert max(boards) - min(boards) >= HOUSE_STYLE['board spread'], \
            ('%s: every board averages the same tone (spread %.0f). Sawn boards differ from '
             'their neighbours -- andesite runs 84 to 101 -- and a panel that does not is '
             'corduroy rather than timber.' % (name, max(boards) - min(boards)))

        # Which *way* the grain runs is only meaningful on a face that has an up. A top
        # face's two axes are both horizontal, so requiring boards to run down it would be
        # asserting something that does not mean anything -- and the top's boards are laid
        # the other way on purpose, so it is not the side turned round.
        if name != 'worker_station_top':
            assert across[1] > down[1], \
                ('%s: the boards run across the face rather than down it. On a wall face '
                 'Create stands them up: andesite matches its vertical neighbour 48%% of '
                 'the time and its horizontal neighbour 11%%.' % name)


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
    'worker_station_casing': casing,
    'worker_station_bottom': casing_bottom,
    'worker_station_front': front,
    'worker_station_lamps': lamps,
    'canteen_side': canteen_side,
    'canteen_food': canteen_food,
    'canteen_bottom': canteen_bottom,
    'canteen_top': canteen_top,
}

# Every sheet that is a panel in a cast frame, which is what check_house_style holds to
# Create's own casings. The Canteen's top is deliberately not among them: its panel is
# the inside of a box rather than a face of boards, so the grain and board-spread rules
# would be asking it to be something it is not. **Its trim still is** -- the rings are
# checked separately below, because a frame that stopped matching is exactly how this
# block family would drift apart.
CASING_SHEETS = ('worker_station_casing', 'worker_station_front', 'canteen_side')
TRIM_SHEETS = CASING_SHEETS + ('canteen_top', 'canteen_bottom', 'worker_station_bottom')

# Left behind by the bench-and-board shape. Removing them here rather than by hand is
# what keeps a re-run of the generators a no-op, which both workflows check.
RETIRED = ('worker_station_side', 'worker_station_board', 'worker_station_board_back',
           'worker_station_top')


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else OUTPUT_DIR
    if directory == OUTPUT_DIR:
        check_faces()
        check_against_model()
        check_canteen_grid()
        check_house_style()
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


if __name__ == '__main__':
    main()
