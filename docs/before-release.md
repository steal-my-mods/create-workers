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

- **The Canteen holds nine slots, and nine is a guess.** For comparison: a Create Item Vault is 20
  slots per block (config `vaultCapacity`, default 20) and combines up to 3×3×9 blocks; a vanilla
  chest is 27; a dispenser is 9; a hopper is 5. So the Canteen is the smallest real container in the
  family, at 576 items if filled with one food.
  The two reasons in the code are that it should read as a trough rather than as storage, and that it
  keeps the comparator from jumping to full on the first item — the second is true of almost any slot
  count, so really there is one reason and it is a feeling.
  **What would settle it:** the food drain rate. Capacity is answering "does a canteen outlast a
  shift", and that question has no arithmetic behind it until a haul costs something. Revisit once
  food is tuned; it is a single constant (`CanteenBlockEntity.SLOTS`) and the comparator scales off it
  automatically. If it still cannot be measured, 20 is the defensible arbitrary number, because it is
  the one Create already uses for a block of this kind.
- **How much food a shift costs**, and **the slowdown floor for a hungry worker** — see
  [phase-4.md](phase-4.md#open-questions), which holds the bounds each wants to satisfy.

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
