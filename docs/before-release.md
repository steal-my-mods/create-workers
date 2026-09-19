# Before the next release

Things deliberately left for later, which later is now. Each one names what would settle it, because
"revisit this" without that is a note nobody can act on.

This is not a backlog of ideas — `docs/` holds those, one file per feature. It is the list of things
that are **wrong, unmeasured or unfinished in code that is about to ship**, and it should be empty or
explicitly accepted before a `v*` tag is pushed.

## Blocking

- **The changelog has no section for anything since 0.4.0, and `mod_version` still says `0.4.0`.**
  `## [Unreleased]` now covers the trade rework, the Canteen's breeding cap, the bed anchor and the
  hiring preference, and nothing else: Stations and their screen, the lamp readout, shifts, muster,
  leisure, the Canteen itself and food all have no player-facing entry at all.
  `publishMods` reads the section whose heading names the current `mod_version` and fails when there
  isn't one — so as things stand it would find the **0.4.0** heading and ship the previous release's
  notes under a new tag, which is worse than failing. Bump the version and write the section, for a
  player reading a download page rather than for somebody reading the diff.

- **The project page needs its screenshots, and nothing else.** `docs/curseforge-page.md` and the
  images `tools/generate_page_art.py` draws are the only part of this mod most people will ever see.
  The prose has been brought up to date — trading has a section of its own, the bed is described as
  the dormitory anchor it is, and the Canteen's section covers its stock readout, what it accepts and
  its stopping short of the breeding threshold — and every image has been regenerated. What is left:
  - **Nine screenshot placeholders are unfilled** — every `> **📷 Screenshot wanted**` block,
    including the hero shot the page is built around. They are the one thing here that cannot be
    generated, and the only reason this item is still open.
  - **The card art is not simply "whatever the models say", which cost a round.** A model knows
    nothing about a readout, so both of the Canteen's drawn faces came out blank the way the
    Station's lamps once did — `generate_block_textures.stock()` composites them now, out of the
    renderer's own tables, and `check_canteen_grid()` reads those tables back. **Anything else that
    grows a block entity renderer needs the same treatment**, or its card quietly shows a block
    nobody has.
  Re-run the generator and read the page end to end against the mod before tagging.

- **The Ponder scenes are behind the mod — mostly settled.** `CanteenScene` now covers hunger, the
  Canteen and trades, and `WorkingHoursScene`'s bed line says a job's bed rather than a worker's. What
  is left is nothing: **the Station's screen and lamps get no scene**, decided rather than deferred.
  Create does not generally Ponder a UI, and the lamp readout is useful decoration rather than
  something a player has to understand to use the block. Original note follows.
- **(Settled above.) The Ponder scenes are behind the mod, which is the failure this project has
  already had once.**
  They are the only documentation shipped inside the jar and nothing in the game contradicts them, so
  a stale page is worse than a missing one — the hiring scene taught "right-click a villager with a
  hat" for several versions after Stations took that away. Three gaps now:
  - **`WorkingHoursScene` still says a bed is where *that* Worker sleeps.** It is a dormitory anchor:
    a job's shift-workers share one hat and therefore one bed, and the ones who miss out take a free
    bed beside it. Same wording problem as the project page, and the same fix.
  - **No scene mentions the Canteen, food or hunger.** A Worker that slows to a crawl and throws angry
    particles has no in-game explanation at all, and the block that feeds it is undocumented.
  - **No scene mentions trades.** 86 listings across five levels, invisible to anyone who does not
    right-click a Worker on spec.
  CLAUDE.md's rule is that when a mechanic changes the scene is part of the change; this session
  changed four and wrote none. `tools/generate_ponder_lang.py` regenerates the text and checks the
  beat timing, so the cost is the storyboards rather than the bookkeeping.

## Found by the high-effort review, plausible but not chased

Reported with a `PLAUSIBLE` verdict and left alone: each was read in the code but not run down, so
the failure scenario is reasoning rather than observation.

- **`stationSlots` — settled by deletion.** It was enforced only on insertion: `loadAdditional`
  accepted any index below `MAX_SLOTS`, every roster loop ran to `MAX_SLOTS`, and `setStackInSlot`
  bypassed it entirely, so an admin cutting it from 12 to 4 got no reduction on a rack already built.
  It is retired rather than repaired, because the other two ways to meter workers — how many hats you
  rack and how many shifts you switch on — are already the player's, in the world, and a server
  setting that half-works is worse than one that does not exist. If a shared server ever does need a
  hard cap, the thing to bound is villagers rather than slots, and it has to hold at load and through
  the capability, not just at the door.
- **The lamp self-consistency check is one-dimensional.** The overlap and centring assertions in
  `generate_block_textures.py` both walk `xs` only, so changing `LAMP_PITCH_Y` in both files leaves
  the two-file equality intact, all three rows on the panel, and the generator reporting success while
  the rendered lamps overlap vertically.

## Numbers that were chosen rather than measured

- **The Canteen's nine slots are still a guess, and the arithmetic now says they are generous.**
  Measured off the defaults: a delivery cycle is `20d + 20` ticks for a beat `d` blocks wide at the
  mod's own ~10 ticks per block, so an 8000-tick shift is 24–80 deliveries — about 44 on a typical
  eight-block beat. At `deliveriesPerFoodPoint` 10 that is ~1.1 loaves a shift, so a full nine-slot
  canteen of bread (2304 points) feeds a maximum 36-worker station for **about a fortnight**, and a
  four-worker line for **months**.
  So capacity is not the binding constraint and never was — the drain is, which is why that moved and
  this did not. Nine slots stays until somebody has played with it; the number to watch is whether
  restocking a canteen feels like a chore or like something you never think about.
  For comparison: a Create Item Vault is 20 slots per block, a vanilla chest 27, a dispenser 9.
  It is one constant (`CanteenBlockEntity.SLOTS`) and the comparator scales off it automatically.
- **`ticksPerFoodPoint` is 1800, and it is now exact rather than estimated.** Charging by time
  removed the guesswork: a worker eats 8000/1800 = 4.4 points a shift, always, whatever its beat looks
  like. 1800 was chosen so that an ordinary eight-block beat consumes exactly what it did under the
  old per-delivery scheme, so nothing rebalanced when the unit changed.
  What is still unvalidated is whether that *rate* is the right one to want. A villager holds at most
  twelve points, so this number also sets **how far a worker can stray from a canteen** — about three
  shifts at the default. Watch for workers limping in a base that has plenty of bread in the wrong
  place.
- **The hungry slowdown floor** (`hungryPace` 0.35) — **accepted; ships as it stands.** Seen in play
  by starving a worker on purpose (`/tick sprint 44000` with the Canteen out of range). It works, and
  the unhappy-villager particles are what make it legible.
  Two things were learned and are worth having before anyone retunes it. It is **not** a double
  penalty: the walk is multiplied by `hungryPace` and the transfer cooldown divided by it, so every
  part of a haul cycle stretches by the same 2.86x and **throughput lands at exactly 0.35** — one
  number, one meaning. But that one number does **two jobs**: the walk is what a player *sees* (0.35
  of villager pace is a crawl, and "very slow, maybe too slow" was the verdict), while the cooldown is
  what a factory *measures*. They are coupled only because it was simpler.
  **Revisit only if the crawl reads as punishing during ordinary production** rather than during a
  deliberate starvation test. The remedy is already worked out: split them, walk at ~0.6 and leave the
  cooldown alone, and the cost is unchanged while the limp is less grim.

- **The trade table's buy-side income — accepted; ships unplayed.** Two things about the table are
  settled and need no further thought. There is no emerald loop:
  `theTradeTableHasNoEmeraldLoop` walks the server's own recipes and is mutation-checked, and it is
  what removed the Zinc Ingot sell (a zinc ingot reaches 54 cogwheels through `create:cutting`, which
  returned 4 emeralds on every 1 spent). And the prices sit at roughly the cheapest general-play
  comparator measured — Create: Engineers, A Distant Journey, flesh-and-steel — rather than under all
  of them as they did before: Drill 3e against their 5, Fan 2e against 3, Mixer 3e against 4.
  What has **not** been played is the size of the buy side, which is the new thing. It is metered by
  `maxUses` rather than by price: a purchase yields at most 16 emeralds before the Worker must
  restock, which it can only do twice a day, and any one Worker shows only two of a level's listings.
  On paper a full station is a few hundred emeralds a day.
  **Revisit if players report a factory minting emeralds faster than it makes anything else**, and
  turn `maxUses` down rather than prices up. Cheaper to settle at the same time: whether four Create
  foods on the buy side is three too many, since they compete for the two listings a level shows.

## Found by review, judged and deferred

From the `/code-review` pass over the leisure and muster work. **All four are now dealt with**, three
by code and one by deleting the settings that made it reachable.

- **`parts()` reserving nothing for rest — settled by deletion.** `clockOff`, `leisureLength` and
  `musterLength` are gone; the span is `Shift.OFFSET` and the other two are constants, so there is no
  longer a way to configure a crew out of its night.
- **A config reload not re-applying schedules — mostly settled by the same deletion, and what is left
  is smaller than it was.** Only `workingHours` and `clockOn` still feed a schedule, so toggling
  either at runtime leaves already-loaded workers on the old one until their chunk reloads. Worth a
  line in the config comments rather than a sweep, unless somebody actually retunes hours on a live
  server.
- **Panic and the stall clocks — guarded, and the guard is untestable on purpose.** See CLAUDE.md:
  `Progress.stalled` resets when the mob gets closer and a panicking villager thrashes, so the leash
  is already protected by accident; two tests were written to catch it and both passed with the guard
  removed, so neither shipped.
- **The bed-absence assertions — fixed.** They name the bed that must not be chosen now, which is the
  shape CLAUDE.md asks for.
