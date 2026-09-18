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
