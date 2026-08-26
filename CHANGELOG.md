# Changelog

Notable changes to Create: Workers, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

`publishMods` reads the section matching `mod_version` and posts it as the release notes on
CurseForge and GitHub — so write entries for a player reading a download page, not for someone
reading the diff.

## [0.3.0] — 2026-08-26

Mostly a performance release. Four things a worker did had no upper bound on them, and each one is
cheap in a test world and expensive on a server that has been running a while — so this is the one
to take before you put workers on a shared world.

### Added

- **A limit on how many blocks one hat may be programmed with**, `maxTargets` in the server
  config, default 24. Past it the hat refuses the click and says so. It is a performance setting
  rather than a taste one: a worker with nothing to do prices every input slot against every
  output, so what an idle worker costs grows with the size of its programme.
- **`hireChildren`**, off by default. Right-clicking a baby villager with a programmed hat now
  turns it away and leaves the hat in your hand. Nothing is lost by the refusal — hand the same
  villager the same hat once it has grown up and it takes the job.

### Changed

- A hat carrying more than `maxTargets` blocks — only reachable from creative mode or a world made
  before this version — still works, but only the first 24 blocks on it are used, with a line in
  the server log saying so. Raise `maxTargets` to keep using the rest.

### Fixed

- **A worker given a block it can never reach no longer asks the game for a route to it forever.**
  A funnel on a wall, a belt across a gap — anything usable from arm's length but impossible to
  stand beside — had the worker path to it, walk as far as it could, arrive nowhere and
  immediately path again, several times a second, out of a mob that looks like it is standing
  still. Every walk now gives up after a while and sets that block aside for a spell before trying
  it again. Only the haul used to do this; the idle rounds, which is the default idle behaviour,
  and the walk back from a wander did not.
- **A target outside the loaded world no longer drags its chunk in and drops it again about once a
  second**, generating that chunk first if nobody had ever been there. A worker held in place by a
  chunk loader, with a target a few chunks outside the loaded area, did it for as long as the
  world existed, with nothing to show why the server was busy. An unloaded target is now skipped
  until its chunk is back.
- **A target broken and put back is picked up again.** It used to stay missing for the rest of
  that worker's life.
- **Programming a hat away from its beat no longer quietly shortens it.** Blocks whose chunk had
  left view were forgotten, so walking off and clicking one more inventory pushed back a programme
  with the earlier blocks dropped from it.
- A busy worker no longer sets off on its idle rounds between every item it moves.
- An idle enderman no longer works through its own programme writing off every inventory on it.
- Workers no longer walk at a block they can already use but could never be said to have reached.
- Employed endermen cost less per tick, and their long hops do far less work choosing where to
  land — each hop still has to close the distance, as before.

## [0.2.0] — 2026-08-24

### Added

- **A Ponder scene for the Hard Hat.** Hold **W** over a hat in your inventory and Create's own
  in-game explainer walks through the whole job: assigning a Depot as an input, right-clicking a
  second one twice to make it an output, hiring a villager, and watching them carry an ingot
  across the yard and clock off again.

## [0.1.0] — 2026-08-21

First release.

### Added

- **Hard Hat.** Craft it from andesite alloy and yellow dye, then right-click inventories to
  program it exactly as you would a Mechanical Arm: each click cycles a block between *take
  from* and *deposit to*, left-click removes it, and your selection is outlined in the world
  while you hold the hat. Unlike an arm, a programmed hat can be picked up and edited.
- **Villager workers.** Right-click a villager with a programmed hat and they clock in, walking
  between the blocks on the hat and hauling items the way an arm moves them. They pathfind
  around obstacles, keep to their patch instead of wandering off, and still flee from mobs.
- **Enderman workers.** The same job, done by teleporting — several hops for a long haul, each
  one closing the distance, with a cooldown between them, and no landing in water, rain, fire
  or lava. Employed endermen stop being hostile, stop blinking off at random in the daylight,
  and leave the blocks around them where they are.
- **Idle rounds.** With nothing to haul, a worker ambles between its own assigned blocks and
  stands at each a while. Configurable via `idleBehaviour`: `PATROL`, `HOLD_STATION` or
  `WANDER`.
- **Package sorting.** Workers deliver addressed packages through Create's own Package Filter
  on a Brass Funnel, including glob-pattern addresses, and will not pick up a package they have
  nowhere to deliver.
- **Wearable hat.** A real helmet worth the same protection as a leather cap, rendered as the
  same 3D hat the workers wear rather than as a texture painted on your head.
- **Visible cargo**, a hi-vis vest, and a hat that survives save/reload; a worker drops its hat
  and cargo on death, and hands both back when you retire it with a sneaking empty-hand
  right-click.
- Crafting a programmed hat by itself clears its program, the way a Create filter clears.
- Nine server config options in `config/createworkers-server.toml`, including `maxTargetSpread`
  — the diameter of a worker's beat, enforced as you assign blocks rather than silently later.

### Known limitations

- Workers accept exactly the blocks a Mechanical Arm accepts, so a plain chest, barrel or
  hopper is not a valid target. Put a funnel on it, the same as you would for an arm.
- The hat and vest are built from code with generated textures — functional placeholders rather
  than proper art.
