# Before the next release

Things deliberately left for later, which later is now. Each one names what would settle it, because
"revisit this" without that is a note nobody can act on.

This is not a backlog of ideas — `docs/` holds those, one file per feature. It is the list of things
that are **wrong, unmeasured or unfinished in code that is about to ship**, and it should be empty or
explicitly accepted before a `v*` tag is pushed.

## Blocking

**Nothing, as of 0.5.0.** The project page went up with its screenshots taken by hand, which was the
only item here no generator could draw, and `docs/curseforge-page.md` was read end to end against the
mod first.

Three standing rules the page left behind, each of which cost a round to learn:

- **The card art is not simply "whatever the models say".** A model knows nothing about a readout, so
  both of the Canteen's drawn faces came out blank the way the Station's lamps once did —
  `generate_block_textures.stock()` composites them now, out of the renderer's own tables, and
  `check_canteen_grid()` reads those tables back. **Anything else that grows a block entity renderer
  needs the same treatment**, or its card quietly shows a block nobody has.
- **A screenshot is only as current as the client that took it.** Resources are baked at load, so a
  shot taken before a reload puts whatever was wrong with the assets that session into a gallery
  neither site lets you replace an image in. Reload (F3+T) before shooting, after any change to a
  model, blockstate or texture.
- **Re-run `tools/generate_page_art.py` and read the page against the mod before tagging.** The page
  is the only part of this mod most people will ever see.

- **The Ponder scenes are level with the mod, and the one gap is a decision rather than a deferral.**
  `CanteenScene` covers hunger, the Canteen and trades; `WorkingHoursScene`'s bed line says a job's
  bed rather than a worker's. **The Station's screen and lamps get no scene**: Create does not
  generally Ponder a UI, and the lamp readout is useful decoration rather than something a player
  must understand to use the block. The standing rule — when a mechanic changes, the scene is part of
  the change — lives in CLAUDE.md.

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
