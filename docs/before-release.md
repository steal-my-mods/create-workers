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
- **`deliveriesPerFoodPoint` is 10, derived rather than measured.** It was picked as the value where
  a full canteen feeds a full station for a fortnight and a topped-up worker keeps going for about
  three shifts away from one — but a villager will only ever hold twelve points of food, so this
  number also silently sets **how far a worker can stray from a canteen**, and that half has never
  been seen in play. Watch for workers limping in a base that has plenty of bread in the wrong place.
- **The hungry slowdown floor** (`hungryPace` 0.35) — see
  [phase-4.md](phase-4.md#open-questions). Neither it nor the particles have been seen in ordinary
  play, because until the drain moved, hunger essentially never happened.
- **The trade prices are a first pass.** Nothing in the list skips a gate, which was the rule, but the
  quantities and emerald costs were chosen rather than weighed against what a villager hall pays.

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
