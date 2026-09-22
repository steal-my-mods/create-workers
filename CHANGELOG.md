# Changelog

Notable changes to Create: Workers, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

`publishMods` reads the section matching `mod_version` and posts it as the release notes on
CurseForge and GitHub — so write entries for a player reading a download page, not for someone
reading the diff.

## [0.5.0] — 2026-09-22

A factory can hire and keep its own workforce. The **Worker Station** holds a rack of programmed
hats and takes villagers on to wear them, refilling a job when its worker is lost. Crews work in
three shifts around the clock, eat, sleep, and trade with you off duty.

### Added

- **The Worker Station hires villagers for you.** Put programmed hard hats in its rack — up to
  twelve jobs — and it takes on unemployed villagers from nearby to wear them. Lose a worker and it
  hires a replacement onto the same job, so a line that was running keeps running. The rack is a
  priority order: the job at the top is staffed first, which is how you say what matters when the
  village is short of people.
- **A lamp per job on the front of the Station**, so "is this line short-handed?" is answerable
  without opening anything. Lit means every shift that job runs has somebody on it, dim means the
  job is programmed and short, dark means the place is empty. Lit lamps draw full-bright, so a
  working Station reads across a dark factory.
- **Three shifts.** Each job can run a day, an evening and a night crew — three villagers keeping
  the same hours at different times, which is a job staffed around the clock. Crews are told apart
  by the colour of their hi-vis. A Station short of people fills whole shifts before starting the
  next, because a line missing one worker usually produces nothing rather than less.
- **Workers keep hours: they muster, work, have time to themselves, and go to bed.** They walk to
  their post shortly before the shift starts, so the next crew is in place before the last one
  stops, and off the clock they behave like any other villager until it is time to sleep. A night
  crew really does sleep, in daylight.
- **Sneak and right-click a bed to say where a job sleeps.** It names a dormitory rather than a
  mattress: a job's workers share one hat, so whoever gets there first takes that bed and the rest
  find a free one beside it instead of wandering off to whatever is nearest their work.
- **Name a job from the Station's screen**, with no anvil and no experience. The name goes on the
  hat and onto whoever is wearing it, which is the short answer to "which villager is that?".
- **The Canteen feeds your workers.** Workers eat while they are on the clock and slow to a crawl
  when they run out — they never stop, but a starving line is a slow one, and it says so with the
  same unhappy particles a lost worker throws. A Canteen feeds every villager in range, through
  walls, out of its own stock, and nobody walks to it: put one where the people are. It takes
  bread, carrots, potatoes and beetroot through a funnel, a chute, a belt or a hopper, like any
  other container. It deliberately stops a little short of the point at which villagers will breed.
- **The Canteen shows what is in it.** Its top is its nine slots laid out one to one, so a stocked
  trough is a heap of food and an empty one is an empty trough — and bread, carrots, potatoes and
  beetroot each look like themselves rather than four colours of the same lump. A part-full slot
  draws less than a brimming one. There is a level on each of the four sides for when the block is
  built into a wall, a comparator reads how full it is, and goggles say what is in it.
- **Workers trade.** Off the clock a Worker will trade: 86 offers across five levels that **buy
  what your factory makes** — andesite alloy, shafts, cogwheels, casings, pressed sheets, and
  higher up the components that are a real chore to automate — and **sell you assembled machines**
  in return. What it offers follows what you are building rather than what you can afford: andesite
  kinetics and your first water wheel early, then logistics, then processing, then contraptions and
  fluids, and the package network last. Hauling counts towards its trading level. It will never
  sell you a Hard Hat.
- **Three more Ponder pages.** Hold W over a Hard Hat and Ponder now walks through a Station taking
  a villager on, then shifts and sleep, then feeding a crew and trading with it. The last one is on
  the Canteen as well.
- **New settings** for the above: `stationRange`, `workingHours`, `clockOn`, `requireFood`,
  `ticksPerFoodPoint`, `hungryPace`, `canteenRange`, `bedSearchRadius`, `absenteeTimeout` and
  `recallStuckWorkers`.

### Changed

- **Villagers are hired by a Station now, rather than by hand.** Right-clicking a villager with a
  hat no longer employs it — put the hat in a Station and it finds somebody. Firing one is taking
  the hat out of the rack, or breaking the block. **Endermen are unchanged**: they have no
  profession and no job site, so no Station could ever employ one, and they are still hired and
  retired by hand. A villager that already has a job still cannot be hired; break its workstation
  first, exactly as the game makes you.
- **A job's hat stays in the Station and its worker wears a copy.** Nothing is handed back when a
  worker dies, because the job never left the block — which is what lets the Station refill it.
- **The Worker Station and the Canteen wear Create's own andesite casing**, and connect to it. Set
  either beside an Andesite Casing block, or beside each other, and the seam between them
  disappears the way it does between Create's own blocks. Both keep a face of their own in an
  inventory: the Station's lamps and the Canteen's gauges are drawn on the item as well, and the
  Station's icon is turned so its lamps are on the lit side of the picture rather than the shaded
  one — neither is a plain casing cube in a chest or in JEI.

### Removed

- **`hireChildren` is gone.** With villagers hired by a Station, the game's own rule decides it — a
  child never takes a job site and so never arrives at one — and the setting had nothing left to
  decide.

### Note

A villager you hired by hand before this update keeps its hat and carries on working. Retiring it
now leaves an ordinary unemployed villager for the village to give a job to, rather than putting
back the profession it had when you hired it: a Station only ever employs villagers with no job to
give back, so that record is no longer kept.

## [0.4.0] — 2026-09-07

Workers turn up in uniform in mods that replace villagers, and taking the hat is now a real change
of job: a hired villager hands its workstation back to the village and wears the hi-vis instead of
its old profession, then goes back to that profession — trades and all — when you take the hat off it.

### Added

- **A hired villager becomes a Worker.** It hands the workstation it was using back to the village,
  so another villager can take the composter or the lectern it was sitting on, and wears hi-vis trim
  on its sleeves and hem in place of its old profession's clothes. Nothing is lost by hiring the
  wrong villager: retire it and the job it had comes back exactly as it was, trades included.
- **Workers carry their cargo in their hand** when the mod drawing them gives villagers proper arms.
  Vanilla villagers have no hands to hold anything in, so theirs is still carried against the chest,
  as before.

### Changed

- A worker bitten by a zombie, or struck by lightning, now drops its hat where it stood and goes
  back to its old profession on the way out. Both used to vanish with it — a converted villager is
  replaced rather than killed, so nothing dropped — and a cured one came back stuck in a job it
  could not do.
- Hired villagers sit at trade level 2 while they work, which shows as a slightly different badge.
  It is not a real promotion: without it the game takes a worker for an unemployed villager whose
  workstation was destroyed and clears its job out from under it.

### Fixed

- **The hard hat and hi-vis vest now appear on villagers from mods that replace them**, such as
  Villagers Reborn ([#1](https://github.com/steal-my-mods/create-workers/issues/1)). Those mods add
  villagers of their own, and the gear was only ever fitted to vanilla's, so the workers hauled
  perfectly well and turned up in plain clothes. The gear is now measured against whichever model a
  villager is actually drawn with, which fits mods this one has never heard of — including the ones
  that draw the same villager two different ways depending on their own settings.
- A farmer's straw hat no longer draws through the crown of the hard hat.

### Note

Villagers already at work when you update keep their old profession until you retire and hire them
again. Their workstation stays theirs until then.

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
