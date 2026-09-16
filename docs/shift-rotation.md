# Shift rotation

**Status: not built.** Written because [working hours](working-hours.md) shipped with a standing
objection against it, and this is the thing that answers the objection rather than working around it.

The idea: a hard hat names which **shift** its worker keeps. Two crews on the same programme cover
the clock between them, so a line runs through the night if the player has built enough workers —
and a dormitory to put them in — to staff it.

## Why this is the feature working hours actually wants

The argument against working hours, kept in full in that document, is that **a factory which silently
halves its throughput overnight is a worse toy than one that runs**. Nothing in what shipped answers
that. The config turns the feature off, and endermen are exempt, but both of those are ways of
*avoiding* the mechanic rather than playing with it.

Rotation converts it. The night stops being a tax the player pays and becomes a problem the player
solves by building something: a second crew, a bunkhouse, a bigger village to recruit from. That is
the Create idiom exactly — a constraint you engineer around. It would make working hours a reason to
build more rather than a reason to edit the config, which is the difference between a mechanic and a
nerf.

It also gives the bed slot a job. Today a bed is a nicety; with two crews the bedroom is
infrastructure, and the number of beds you have built is the size of the workforce you can run.

## What is already in place

More than expected, because the two-clock design turned out to be the right shape for this:

- `WorkerShift.isOffShift(dayTime, clockOn, clockOff)` **takes its bounds as arguments** rather than
  reading the config inside. The config-reading overload is a thin wrapper over it. Making the bounds
  per-worker is a change at the call site, not a rewrite.
- The hat already carries per-worker configuration — the programme and the bed — in one data
  component, with a clearing recipe, a tooltip and a client/server round trip already built around it.
- The handover needs nothing. The knock-off grace already makes the outgoing worker finish the
  delivery in its hands before it leaves, the incoming worker starts on its own clock, and the two
  share nothing but the targets. The transfer algorithm is round-robin and re-entrant already.
- Two workers on opposite shifts can **share one bed**. They are never off duty at the same time, and
  `BedBlock.OCCUPIED` already arbitrates between two workers that pick the same one. Hot-bunking, for
  free, and it halves the bedroom a player has to build.

## The finding that changes the design: a worker can be given its own schedule

`working-hours.md` concluded that a worker whose off-hours are daylight can never sleep, because
`WakeUp` (villager CORE, priority 0) stands up any sleeping villager whose brain is not in `REST`,
every tick — and that lifting it would mean taking the villager schedule apart.

**That was wrong, and it is the most useful thing in this document.** The schedule is not something to
be fought or replaced wholesale; it is a field on the brain with a public setter:

- `Brain.setSchedule(Schedule)` is public.
- `ScheduleBuilder` is public — constructor, `changeActivityAt`, `build`.
- `Schedule` declares no constructor at all, so it has an implicit public one.

So a worker can simply be handed a two-state schedule of its own:

```java
new ScheduleBuilder(new Schedule())
    .changeActivityAt(clockOn,  Activity.IDLE)
    .changeActivityAt(clockOff, Activity.REST)
    .build();
```

Vanilla's own `Schedule.SIMPLE` is the same two-transition shape, so this is a supported form rather
than a trick. `WakeUp` then **agrees with us by construction** instead of by coincidence: `REST` is
genuinely when that worker rests, whatever hour it falls at. A night-shift worker sleeps through the
afternoon, and every clock in the mod lines up with every clock in the game.

`IDLE` rather than `WORK` for the on-shift half because both are inert for a worker and `IDLE` is the
quieter of the two — `WORK` runs `WorkAtPoi`, which needs a `JOB_SITE` memory a worker never has,
while the idle package's wanderers all need `WALK_TARGET` absent, which the job goal never allows.

Three things to know before building it:

| | |
|---|---|
| **It is not persisted** | The Brain's codec carries memories; the schedule is a plain field, and `registerBrainGoals` sets `VILLAGER_DEFAULT` on every construction — which includes every load. So the schedule has to be re-applied whenever a worker *loads*, not only when it is hired. `WorkerEvents.onEntityJoinLevel` already runs for every villager and is the obvious place |
| **Retirement is free** | `restoreVillageJob` calls `refreshBrain`, which calls `registerBrainGoals`, which sets `VILLAGER_DEFAULT` back. Nothing to undo by hand |
| **`MEET` goes away** | A two-state schedule has no meeting-point window, which is where gossip, gift-giving and breeding live. An employed worker pinned to its beat could do none of those anyway, so this is naming a consequence rather than paying a cost — but it is a real behaviour change and belongs in the changelog |

An unregistered `Schedule` object works at runtime, because `setSchedule` only stores it and
`getActivityAt` is called on it directly. Registering one per shift into `BuiltInRegistries.SCHEDULE`
is the tidier option if the set of shifts is fixed, and it is the only way another mod reading
`brain.getSchedule()` sees something it can name.

## How many shifts

**Two.** Three buys nothing, and that is worth stating plainly because "two or three" is the obvious
way to pose the question and the answer is not symmetric.

Coverage is the point, and two crews of twelve hours already cover the clock. Three crews of eight
cover exactly the same clock and cost fifty per cent more villagers for it. Three is only interesting
if you want each worker on duty for *less* than half the day — which is a balance lever, not a
coverage one, and the configurable shift boundary already lets an operator make two shifts uneven if
that is the goal.

So: two crews, one boundary, and `clockOff`/`clockOn` change meaning slightly — from "when workers
work" to "where the boundary between the shifts falls". The hat says which side of it a worker is on.
That keeps exactly one server-wide answer to "when does the day shift end", which is what stops this
becoming a guessing game about when each villager was hired.

## What the player sets, and what the player sees

**Setting it** wants Create's idiom for a mode on a held item: ctrl + scroll while holding the hat,
cycling Day / Night / Always. The bed used a world click because a bed is a block; a shift is not, and
inventing a second click gesture for it would be worse than borrowing the one Create already teaches.

**Seeing it is the harder half, and it is the part that decides whether this is pleasant.** A base
with a dozen workers needs the shift readable across a room, not by hovering each hat. The gear
textures are already generated by `tools/generate_worker_profession.py` and `worker_gear.png`, so a
different hi-vis trim colour per shift costs a script change and nothing else. That is almost
certainly the whole answer, and it should be built at the same time as the mechanic rather than after
it — a shift you cannot see is the same feature as no shift at all, plus confusion.

A tooltip line naming the shift is state rather than instruction, so it fits beside the bed line
without reopening the argument about tooltip verbosity.

## What it costs

- **Two to three times the villagers per line**, and therefore two to three times the per-worker
  server cost. This mod has a whole test file's worth of discipline about what one worker costs a
  tick; a mechanic whose whole point is multiplying the worker count should say so out loud, and
  `docs/multiplayer-performance.md` should get a paragraph when it lands.
- **It weakens the case for endermen**, which the exemption had just strengthened. Two villagers and
  a bunkhouse now cover what one enderman covers. The counterweight is unchanged — an enderman cannot
  be led, bred or traded for — so the choice becomes "spend two villagers and a room" against "go and
  fetch something from the End", which is a better trade than the current "endermen or nights off".
- **More state on the hat**, and state that wants to be visible in more places than the bed does.

## Open questions

1. **What does a hat with no shift set do?** Probably "Always", so that every hat programmed before
   this feature keeps working exactly as it did, and the mechanic is opt-in per worker rather than
   imposed on an existing base. That also makes `workingHours = false` and "every hat says Always"
   the same world, which is a pleasing property.
2. **Does an Always worker still sleep?** It cannot — there is no off-shift window to sleep in. So
   "Always" is the current enderman deal offered to villagers, and it should probably cost something,
   or it is strictly better than running two crews. This is the one balance question the design does
   not yet have an answer to.
3. **Do the two crews want separate beds after all?** They can share, but sharing means the bed is
   `OCCUPIED` almost continuously, which makes the auto-discovery hunt's `HAS_SPACE` filter behave
   oddly for anyone else nearby. Worth checking before promising hot-bunking.
4. **Should the shift be visible on the worker's name or just its gear?** A named worker is easier to
   find with a search; gear is easier to read at a glance. Probably gear only, but worth a look.

## Suggested ordering

**Build the per-worker schedule first, on its own.** It is independently useful — it fixes the
standing wart where a globally inverted `clockOn`/`clockOff` produces workers that stand beside beds
they never get into — it is testable without any of the rest (give a worker a custom schedule, set
the world to noon, assert it sleeps), and everything else here rests on it. Ship that, then add the
shift field to the hat, then the gear colours and the scroll gesture together.
