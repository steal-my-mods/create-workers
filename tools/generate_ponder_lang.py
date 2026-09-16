#!/usr/bin/env python3
"""
Writes the Ponder scenes' lang entries out of the storyboards themselves.

Ponder text does not fall back to the string in the code. The English handed to
`.text(...)` in a storyboard is only a default for a lang generator; with editing
mode off, PonderLocalization.getSpecific goes straight to I18n.get, so a beat with
no `createworkers.ponder.<scene>.text_<n>` key renders as the raw key in front of
a player.

The `n` is an incrementing counter over the `.text(` calls **in the order the
storyboard makes them**, which is the part that goes wrong: insert a beat in the
middle and every line after it silently moves onto the wrong step. That is a
diff nobody can see by reading either file, so it is generated rather than
maintained, and the release workflow's "re-run the generators and fail on a diff"
gate is what now notices.

Only the block of `createworkers.ponder.*` keys is rewritten. Everything else in
the file -- ordering, spacing, the blank lines between sections -- is left exactly
as it was, because this is a file people also edit by hand.

    python3 tools/generate_ponder_lang.py [lang-file]
"""

import io
import json
import os
import re
import sys

LANG = 'src/main/resources/assets/createworkers/lang/en_us.json'
PONDER = 'src/main/java/com/createworkers/client/ponder'

# In the order Ponder is told to register them, which is the order a player pages
# through. The header is the scene's title, and `scene.title` takes the same key.
SCENES = (
    ('hard_hat', 'HardHatScene.java'),
    ('worker_station', 'WorkerStationScene.java'),
    ('working_hours', 'WorkingHoursScene.java'),
)

TITLE = re.compile(r'scene\.title\("([^"]+)",\s*"((?:[^"\\]|\\.)*)"\)')
TEXT = re.compile(r'\.text\("((?:[^"\\]|\\.)*)"\)')


def unescape(literal):
    return literal.replace('\\"', '"').replace('\\\\', '\\')


def scene_entries(scene, source):
    body = io.open(source, encoding='utf-8').read()

    title = TITLE.search(body)
    if title is None:
        raise SystemExit('%s: no scene.title(...) to take a header from' % source)
    if title.group(1) != scene:
        raise SystemExit('%s: titles itself "%s" but is registered as "%s"'
                         % (source, title.group(1), scene))

    entries = [('createworkers.ponder.%s.header' % scene, unescape(title.group(2)))]
    for n, text in enumerate(TEXT.findall(body), start=1):
        entries.append(('createworkers.ponder.%s.text_%d' % (scene, n), unescape(text)))
    return entries


def render(blocks):
    """One JSON line per entry, scenes separated by a blank line, as the file already reads."""
    lines = []
    for block in blocks:
        if lines:
            lines.append('')
        for key, value in block:
            lines.append('  %s: %s,' % (json.dumps(key), json.dumps(value, ensure_ascii=False)))
    return lines


def main():
    lang = sys.argv[1] if len(sys.argv) > 1 else LANG
    blocks = [scene_entries(scene, os.path.join(PONDER, source)) for scene, source in SCENES]

    original = io.open(lang, encoding='utf-8').read()
    lines = original.split('\n')

    ponder = [n for n, line in enumerate(lines) if line.lstrip().startswith('"createworkers.ponder.')]
    if not ponder:
        raise SystemExit('%s: found no ponder keys to replace' % lang)
    first, last = ponder[0], ponder[-1]

    # The last entry in the file carries no trailing comma, so whichever entry ends
    # up last has to match whatever the line being replaced was doing.
    replacement = render(blocks)
    if not lines[last].rstrip().endswith(','):
        replacement[-1] = replacement[-1].rstrip(',')

    rebuilt = '\n'.join(lines[:first] + replacement + lines[last + 1:])
    json.loads(rebuilt)

    with io.open(lang, 'w', encoding='utf-8') as handle:
        handle.write(rebuilt)

    print('wrote %s (%d ponder keys over %d scenes)'
          % (lang, sum(len(block) for block in blocks), len(blocks)))


if __name__ == '__main__':
    main()
