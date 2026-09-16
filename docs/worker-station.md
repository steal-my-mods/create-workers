# The Worker Station

**Status: not built.** The block that hires workers, and the thing
[shift rotation](shift-rotation.md) should probably wait for, because it is where a crew gets
defined.

## In one sentence

**A rack of programmed hard hats that hires villagers to wear them.**

## Why it should exist

1. **A factory that heals itself.** This is the argument that carries the rest. Today a worker that
   dies — or falls in a hole and never gets out — takes its job with it, and nothing says so: the
   line quietly runs at less than it used to and the player has no way to tell which villager to go
   looking for. A station holds the job rather than the villager, so a lost worker leaves an empty
   slot that refills itself.
2. **A crew belongs to the job, not to N loose hats.** "This job runs three shifts" is a property of
   the work. Expressing it as three separately-configured hats is worse in every way than a block
   with three slots.
3. **It is the idiom every player already knows.** Unemployed villager plus unclaimed workstation
   equals a new profession. Nothing to teach.
4. **It pays for itself twice in simplification** — see [What it lets us delete](#what-it-lets-us-delete).

## It does not replace the hard hat

The hat is the **job description**; the station is the **employer**. Keep both.

Create already has this exact shape: a Train Schedule is a programmed item you hand to a conductor.
The schedule is the job, the conductor is the worker, and the station — in our case — is where the
unclaimed schedules hang.

Dropping the hat in favour of the block would cost: the Mechanical Arm parallel the whole mod is
built on, the click-to-select programming UX, the clearing recipe, both Ponder scenes, and the item
that *is* the mod's identity on a project page. Against that, the station gains nothing it does not
already get from holding hats — a station with three hats in it **is** a crew of three, with no new
concepts and no new state.

So: **manual hiring stays.** Right-clicking a villager with a programmed hat is still the small-build
path and still the tutorial path. The station is the automated path, for when a base has outgrown
doing it by hand.

## How hiring works

Almost all of it is vanilla, and the part that is not is small.

**Registration.** A `createworkers:worker_station` point-of-interest type over the block's states,
and `CWProfessions.WORKER`'s `heldJobSite()` and `acquirableJobSite()` pointed at that POI **and
nothing else**. The property [`professions.md`](professions.md) calls load-bearing — a worker
profession that matches nothing can never squat a composter — survives exactly as it is. It gains the
ability to claim precisely one block: ours.

**Then vanilla does the work**, in two behaviours that are already in every villager's CORE package:

| | |
|---|---|
| `AcquirePoi` (CORE, priority 6) | Finds the station, **path-verifies it**, takes a ticket, and writes `POTENTIAL_JOB_SITE`. The same code that gets a villager to a lectern |
| `AssignProfessionFromJobSite` (CORE, priority 10) | Once the villager is **within 2.0 blocks**, moves `POTENTIAL_JOB_SITE` to `JOB_SITE` and assigns the profession whose `heldJobSite` matches the POI it found |

**Our part** is one event: when a villager acquires a station as its `JOB_SITE`, hand it the hat from
the first unfilled slot and remember the station on its `WorkerData`. That is `data.employ(hat,
programme)` — the method that already exists — plus a `GlobalPos`.

Two details worth knowing before building it:

- **Only unemployed villagers take the job.** `AssignProfessionFromJobSite` returns early unless the
  profession is `NONE`. A librarian will never spontaneously become a worker; you hire it by hand, or
  you break its lectern first. That is correct behaviour and it matches every other profession.
- **`maxTickets` is per POI *type*, not per block.** Register it at the largest crew the mod supports
  and let a station refuse a villager it has no free hat for — that villager releases the POI and
  goes back to being unemployed. Self-correcting, and mildly wasteful in that a villager may walk
  over for nothing.

## Filling shifts: whole shifts, never thin ones

Fill a shift completely before opening the next. The rule is easy; the reason it is right is the part
worth writing down.

**Degrade by dropping whole shifts, never by thinning every shift.** A half-staffed shift is a window
that runs at half rate while still *looking* like it is working — the machines turn, items move,
nothing is obviously wrong, and the player has no way to see that they are down a villager. An
unstaffed shift is visibly off. So when the village is short of people, the player should lose *hours
of the day*, not *rate across the whole day*, because hours are legible and rate is not.

That makes the station's screen the diagnostic:

```
Shift 1 (day)      ██  2/2
Shift 2 (evening)  █·  1/2
Shift 3 (night)    ··  0/2
```

and "I am two villagers short" is readable at a glance.

**Fill the daytime shift first**, so an understaffed factory runs during the hours the player is most
likely to be standing in it. A factory that only works while you are asleep is a factory you cannot
debug.

## Coming back: the self-healing part, and its limit

`WorkerData` remembers the station it was hired from as a `GlobalPos`. Then:

- **On death, conversion or retirement**, if that station still exists and is loaded, the hat returns
  to its slot instead of dropping. If the station is gone, it drops exactly as it does today.
- The slot is now empty, so the station advertises the job again, and the next unemployed villager
  takes it.

**But this fixes dead, not stuck**, and it is worth being honest about the difference. A worker at
the bottom of a hole is still alive and still employed, so its slot is not free and the station will
not replace it. The job is occupied by someone who is never coming to work.

The answer is an **absentee rule**: a station reclaims a job from a worker that has not been near any
of its own targets — or the station — for some long while. The hat comes back, someone else is hired,
and the absentee is retired properly, which restores its old profession and leaves an ordinary
villager standing in a hole. Harmless, and the factory carries on.

Note the signal has to be *proximity to the work*, not *items moved*. A worker with nothing to haul
is idle, not absent, and firing it for a quiet shift would be wrong.

## Stress

**Yes, it should cost SU**, and it should be the primary cost. Two arguments:

- The fiction already works. "A worker is an arm with legs" is the design rule the whole mod is built
  on, and an arm costs stress for its motion. Paying stress for a worker is paying for the motion you
  did not have to build.
- A block in a Create addon that never touches a shaft is an oddity. This is the one place the mod
  can join the kinetic network without inventing anything.

**Scale it with active workers, not with slots**, so an unstaffed shift costs nothing and the bill
grows exactly as the player staffs up. That is the Create loop: more output, more stress, go and
build more power.

**Cut the power and the crew clocks off.** An unpowered or overstressed station stops advertising
jobs and sends its workers home — which is a legible Create failure and, incidentally, a factory
off-switch that costs nothing to implement.

### This is not double-taxing with food

SU and food do different jobs, and the design only works if they stay separate:

| | |
|---|---|
| **Stress** | *Capacity.* The right to keep N slots staffed at all. Global, continuous, paid in power |
| **Food** | *Availability.* Whether one worker actually turns up. Local, recoverable, paid in bread |

Stress answers "how big can my workforce be". Food answers "is Bob at his post". Making either one
do both jobs is what would feel like being taxed twice.

## Should stations be networked?

**No.** Create's kinetic network is a force network, not a data bus, and using it to share a labour
pool would be inventing semantics Create does not have — two stations on one shaft are no more
related than two Mechanical Arms are.

The shared resource is **villagers**, and the village is already that pool. Stations compete for
unemployed villagers exactly as vanilla workstations do, and the player's lever when they are short
is the one they already know: breed more villagers, or build another bunkhouse.

The real need behind the question is different and worth answering separately: *what is the state of
all my stations?* That is a read-only overview, and Create's Clipboard is the precedent for how an
addon does one. Later, and not as part of this.

## Where the station sits in the geometry

- **It counts toward the spread rule.** Like the bed, it is one more place the worker walks, so it
  belongs in `WorkerProgram.allPositions()` — or at minimum must be within `maxTargetSpread` of the
  job site. Leaving it out would make "how far is the office from the factory floor" the one
  unbounded distance in a design built to bound them.
- **The job site stays derived.** `centre()` is the middle of the *work*, and moving it to the
  station would drag an on-shift worker's anchor — and its wander leash — towards the office. The
  station instead becomes another **post** for the leash, which already counts every programmed
  target as one.

## What it lets us delete

Two pieces of existing complexity fall out, both documented as traps today:

1. **`RESET_PROOF_LEVEL` can go.** `ResetProfession` requires `absent(JOB_SITE)`. A worker hired from
   a station *holds* one, so the behaviour never fires on it — and the trick of holding workers at
   trade level 2 to dodge it, along with stashing and restoring the real level, stops being needed
   for station-hired workers. (A hand-hired worker still has no job site, so the hack stays for that
   path until hand-hiring also assigns one.)
2. **It unblocks trades.** With a real job site and a real trade level, a worker is an ordinary
   villager in every respect that matters, which removes the mechanical objection raised in
   [shift-rotation.md](shift-rotation.md).

## Endermen cannot use any of this

No profession, no POI, no job board. An enderman stays a hand-hire, and that is a clean split rather
than a gap:

| | Villager | Enderman |
|---|---|---|
| Hired by | A station, automatically, from the village | By hand, with a hat |
| Keeps hours | Yes — shifts, sleep, food | No, works around the clock |
| Replaced when lost | Automatically | Not at all; go and fetch another |
| Costs | Stress, bread, and villagers | Getting one there in the first place |

Which is a genuinely interesting choice rather than a strictly-better option on either side.

## Open questions

1. **How does a station get programmed?** Two shapes: you put already-programmed hats into it (no new
   UX at all, and the existing click-to-select flow is untouched), or you programme the station
   itself and it stamps blank hats. The first is far cheaper and probably right.
2. **May one station run two different programmes?** With hats in slots this is free — different hats
   in different slots — and it is genuinely useful: a day crew on one beat and a night crew on
   another. Worth not accidentally forbidding.
3. **What happens when a station is broken with workers out?** Probably: every worker it hired retires
   on the spot, dropping its hat where it stands. The alternative — orphaned workers that carry on
   forever with no employer — is the invisible-failure problem all over again.
4. **Should an empty station advertise a job?** No. A station with no hats in it should not hold a POI
   ticket, or villagers will walk to it for nothing.
5. **Where do beds fit?** A station could hold a dormitory assignment for its whole crew rather than
   each hat naming a bed. Tempting, but the hat's bed already works and already degrades well. Leave
   it until there is a reason.

## Suggested ordering

The full design is large — a block, a block entity, a model, a recipe, a screen, POI registration and
a stress connection. The **minimum viable version is much smaller and is worth having on its own**:

1. **One slot, no shifts, no stress.** A block that holds one programmed hat, hires one villager
   through the vanilla POI route, and takes the hat back when that villager dies. That is the
   self-healing property — the single best reason to build any of this — and it needs none of the
   rest.
2. **The absentee rule**, which turns self-healing from "handles dead" into "handles lost".
3. **Stress**, once there is something worth metering.
4. **Slots and shifts**, at which point [shift rotation](shift-rotation.md) has somewhere to live.
