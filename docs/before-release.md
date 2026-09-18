# Before the next release

Things deliberately left for later, which later is now. Each one names what would settle it, because
"revisit this" without that is a note nobody can act on.

This is not a backlog of ideas — `docs/` holds those, one file per feature. It is the list of things
that are **wrong, unmeasured or unfinished in code that is about to ship**, and it should be empty or
explicitly accepted before a `v*` tag is pushed.

## Blocking

- **The changelog has no section for anything since 0.4.0, and `mod_version` still says `0.4.0`.**
  Stations, the lamp readout, leisure, muster and the Canteen have no entry. `publishMods` reads the
  section whose heading names the current `mod_version` and fails when there isn't one, so this stops
  a release by itself — which is the intended behaviour, not a bug to work around. Write the section
  for a player reading a download page, not for someone reading the diff.

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
- **The hungry slowdown floor** (`hungryPace` 0.35) — see
  [phase-4.md](phase-4.md#open-questions). **Now seen in play**, by starving a worker deliberately
  (`/tick sprint 44000` with the Canteen out of range). It works, and the unhappy-villager particles
  were confirmed as the thing that makes it legible. Two findings.
  The slowdown is **not** a double penalty, which is worth knowing before anyone retunes it: the walk
  is multiplied by `hungryPace` and the transfer cooldown divided by it, so every part of a haul cycle
  stretches by the same 2.86x and **throughput lands at exactly 0.35**. One number, one meaning.
  What is unresolved is that the one number does **two jobs**: the walk is what a player *sees* (and
  0.35 of villager pace is a crawl — "very slow, maybe too slow" was the verdict), while the cooldown
  is what a factory *measures*. They are coupled only because it was simpler. If the crawl turns out
  to read as punishing during real production rather than during a deliberate starvation test, split
  them — walk at ~0.6, cooldown left alone — and the cost is unchanged while the limp is less grim.
  Left at 0.35 for now: legibility is the point, and a third of output is a long way from an outage.
- **The trade table is loop-proof and priced against the field, but its income has never been
  played.** Two things are settled and need no further thought. There is no emerald loop:
  `theTradeTableHasNoEmeraldLoop` walks the server's own recipes and is mutation-checked, and it is
  what removed the Zinc Ingot sell (a zinc ingot reaches 54 cogwheels through `create:cutting`, which
  returned 4 emeralds on every 1 spent). And the prices sit at roughly the cheapest general-play
  comparator measured — Create: Engineers, A Distant Journey, flesh-and-steel — rather than under all
  of them as they did before: Drill 3e against their 5, Fan 2e against 3, Mixer 3e against 4.
  What is **unvalidated is the size of the buy side**, which is the new thing. It is metered by
  `maxUses` rather than by price: a purchase yields at most 16 emeralds before the Worker must
  restock, which it can only do twice a day, and any one Worker shows only two of a level's listings.
  On paper a full station is a few hundred emeralds a day; nobody has played it. Watch for a factory
  that makes emeralds faster than it makes anything else, and turn `maxUses` down rather than prices
  up if so.
  Also unvalidated, and cheaper to settle: whether four Create foods on the buy side is three too
  many, since they compete with each other for the two listings a level shows.

- **The CHANGELOG has no entries for most of what is unreleased.** `## [Unreleased]` covers the trade
  rework and nothing else, while everything since 0.4.0 — the Worker Station and its screen, shifts,
  muster and leisure, the Canteen, food — has no player-facing note at all. `mod_version` is also
  still `0.4.0`, so `publishMods` would happily find that heading and ship the previous release's
  notes under a new tag. Bump the version and write the section before any `v*` tag.

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
