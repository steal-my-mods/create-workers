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

- **The project page and its art are behind the mod.** `docs/curseforge-page.md` and the images
  `tools/generate_page_art.py` draws are the only part of this mod most people will ever see, and both
  describe a slightly different mod from the one that would ship today. Known gaps:
  - **Trades are missing entirely.** The page mentions neither trading nor emeralds, and a Worker now
    has 86 listings across five levels, buys a line's output and sells assembled machines. It is one
    of the two or three things somebody deciding whether to install this would most want to know.
  - **The bed is described as "where that worker sleeps".** It is a *dormitory anchor* now: a job's
    shift-workers share one hat and therefore one bed, so the ones who miss out take a free bed beside
    it. The page's wording promises something the block deliberately no longer does.
  - **Canteens no longer fill villagers to the breeding threshold.** Worth a line, because feeding
    villagers the last stretch by hand is now the player's job and somebody will notice their
    factory stopped producing children.
  - **21 screenshot placeholders are unfilled** — every `> **📷 Screenshot wanted**` block, including
    the hero shot the page is built around. Those are the one thing here that cannot be generated.
  - **The card art follows whatever the block models say**, so any Canteen redesign means re-running
    `python3 tools/generate_page_art.py`. That part is self-updating; the prose is not.
  Re-run the generator and read the page end to end against the mod before tagging.

- **The Ponder scenes are behind the mod, which is the failure this project has already had once.**
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

- **The Ponder plate's Station faces south and the scene points at its north face.**
  `tools/generate_ponder_structure.py` writes `facing: south` and `WorkerStationScene` derives its
  pointer from `Direction.NORTH`, with a comment claiming nothing in the generator turns the block.
  `WorkerStationRenderer` draws the lamps on `FACING`, so the "one lamp per job" beat would indicate
  the unlit back. Nothing can catch it: `theHiringPlateHasAnEmptyStationInIt` never asserts `FACING`,
  and Ponder does not load on a dedicated server. Worth ten minutes with a client.
- **Lowering `stationSlots` does not shrink an existing rack.** It is enforced only on insertion;
  `loadAdditional` accepts any index below `MAX_SLOTS` and every roster loop runs to `MAX_SLOTS`, so
  an admin cutting it from 12 to 4 to reduce per-tick cost gets no reduction on a rack already built.
  `rack.setStackInSlot` also bypasses `capacity()` and the hard-hat check, and `CWCapabilities`
  exposes the rack to any item-handler consumer.
- **The lamp self-consistency check is one-dimensional.** The overlap and centring assertions in
  `generate_block_textures.py` both walk `xs` only, so changing `LAMP_PITCH_Y` in both files leaves
  the two-file equality intact, all three rows on the panel, and the generator reporting success while
  the rendered lamps overlap vertically.

## Decisions nobody has actually made

- **The Canteen's three readouts measure two different things, deliberately but unsigned-off.** The
  comparator scales **food points** against `PLENTY`; the heap on the top and the bar on the flanks
  count **slots**. So a full rack of beetroot is nine bright cells, a full bar and a comparator
  reading of 4. The argument for it is that the block shows what is in it while the comparator shows
  how much feeding it is worth — and the comparator measures points precisely so a restock line does
  not fire at the wrong time for three foods out of four. The argument against is that one block
  should not answer "how full" two ways. It has been raised twice and settled neither way.

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

From the `/code-review` pass over the leisure and muster work. Each was verified against the code;
these are the ones that were real but not worth stopping for at the time.

- **`parts()` protects muster and reserves nothing for rest.** `clockOn=0, clockOff=18000,
  musterLength=6000` — all inside their configured ranges — makes `RESTING` unreachable and
  `workerSchedule` return null, so no schedule is applied at all. Only reachable in a shift longer
  than `Shift.OFFSET`, which the mod already calls wrong, but a floor on rest is a few lines.
- **A config reload does not re-apply schedules.** `WorkerShift.scheduleFor` invalidates its cache
  when the hours change, but `applySchedule` only runs on hire and on entity join — so toggling
  `workingHours` at runtime leaves already-loaded workers on stale hours until their chunk reloads.
- **Panic and the stall clocks disagree.** `WalkLocomotion.returnTo`/`commuteTo` no-op while a
  villager is fleeing, but the clocks are evaluated first, so a worker that meets a zombie at dusk can
  lose its bed for 600 ticks; during leisure it accrues leash failures, and with `recallStuckWorkers`
  on it can be teleported home mid-flight.
- **Two tests assert on the absence of a bed**, which `CLAUDE.md` forbids by name because the tests
  running beside them lay real beds within a worker's search radius.
  `aDesignatedBedIsTrustedWhereADiscoveredOneIsProved` asserts `findBed(...) == null`, and
  `withNoBedAWorkerHoldsItsStation` / `aWorkerHoldsTheSpotItKnockedOffAt` share the `night` batch with
  tests that place beds 13 blocks off. **They pass, consistently** — most likely because the plates
  are not walkable between, so the path check rejects a neighbour's bed — so this is a latent
  assertion shape rather than a live failure. It would break the day anything makes the test floor
  continuous. Name the bed that must not be chosen.
