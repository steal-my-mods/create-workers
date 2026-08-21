# Changelog

Notable changes to Create: Workers, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

`publishMods` reads the section matching `mod_version` and posts it as the release notes on
CurseForge and GitHub — so write entries for a player reading a download page, not for someone
reading the diff.

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
