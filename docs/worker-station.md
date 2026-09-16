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

### So does direct assignment, and it is worth naming what it produces

Right-clicking a villager with a programmed hat stays. But rather than "the same thing, done by
hand", it is better understood as producing a different *kind* of worker — an **unmanaged** one:

| | Hired from a station | Hired by hand |
|---|---|---|
| Belongs to a crew | Yes | No |
| Keeps a shift | Yes | No — always on |
| Replaced when lost | Yes | No |
| Eats | Yes, on its own | Yes, but the player feeds it |

That is not a second-class citizen so much as the only kind of worker an **enderman** can ever be:
no profession, no point of interest, nothing for a job board to hire. So the hand-hired villager and
the enderman converge on one idea — the unmanaged worker, always on, no crew, no self-healing — which
is a coherent thing to have rather than an awkward leftover.

It also keeps the small build small. One worker between two depots should not need a block, a shaft
and a power source, and both Ponder scenes still teach the thing the player does first.

The honest cost of keeping it: the `RESET_PROOF_LEVEL` trick stays alive for that path, because a
hand-hired worker still has no job site. See [What it lets us delete](#what-it-lets-us-delete).

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

## Filling shifts: whole shifts, because a partial one is a broken factory

Fill a shift completely before opening the next. An earlier draft justified that on legibility —
a half-staffed shift "runs at half rate while looking like it works". **That is wrong, and the real
reason is much stronger: a half-staffed shift mostly does not work at all.**

Workers in a factory are a *chain*, not a pool. Worker 1 feeds machine A, worker 2 carries A's output
to machine B, worker 3 carries B's output onward. Take worker 2 away and nothing degrades gracefully:

- Worker 1 fills A's output depot, which never drains. Then it **stops entirely**, because the arm
  rule this mod is built on says take nothing you have nowhere to put.
- Worker 3 stands at an input that never fills.
- The line produces nothing, while two of its three workers are visibly walking about.

So the throughput of a shift missing one worker is not a fraction, it is usually **zero** — and a
zero that looks busy, which is the worst state a factory can be in. That makes filling whole shifts a
correctness rule rather than a preference.

### Which means a station is a line's roster, not a single job

That chain argument only works if one station knows about the whole chain. So: **a station holds the
hats for a production line**, and its slots are the roles in that line — worker 1's programme,
worker 2's, worker 3's — each of which needs filling on every shift the line runs.

This is the answer to "should stations talk to each other over the stress network". They should not
need to, because a line that depends on three workers is *one* station with three slots. Coordination
inside a station is a loop over its own slots; coordination between stations would be a distributed
problem, and the way to avoid a distributed problem is not to create one.

### What a station does with a crew it cannot complete

It should **hold the shift closed**. A shift it cannot fully staff does not run: the hats stay in
their slots, no villager is hired into it, and the screen says so.

```
Shift 1 (day)      ███  3/3   running
Shift 2 (evening)  ██·  2/3   short one worker -- not running
Shift 3 (night)    ···  0/3   no crew
```

Two refinements that fall out of the chain argument:

- **Do not fire a crew that becomes incomplete mid-shift.** If a worker dies at noon, the remaining
  two will back their own line up and stop on their own within a few minutes, and the station will
  usually have refilled the slot before that matters. Tearing down a running shift the instant
  somebody dies would be a far more violent failure than the one it prevents.
- **Fill the daytime shift first**, so an understaffed factory runs during the hours the player is
  most likely to be standing in it. A factory that only works while you are asleep is one you cannot
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

An earlier draft said the station's stress should **scale with the number of active workers**, on the
fiction that "a worker is an arm with legs, and an arm costs stress for its motion, so you are paying
for the motion you did not have to build".

**That does not survive the obvious question: why would ten villagers carrying boxes draw more
rotational force than one?** They are walking. Nothing in the kinetic network is moving them. The
scaling cost was a balance lever wearing a simulation costume — the same mistake this document made
about trades one section over, and it should be named as such rather than quietly fixed.

So, honestly:

**A flat, small stress impact, or none at all.** The station is a machine — a job board, a time clock,
whatever the model ends up being — and "this block is powered" is a claim that survives scrutiny in a
way that "ten employees are heavier than one" does not. It buys three things worth having:

- The block joins the kinetic network, which an addon block arguably should.
- It has to be *sited* near your power, which is a real build constraint and a thematic one: the
  factory office is part of the factory.
- **Cutting the power sends the crew home.** A stopped station stops advertising jobs and clocks its
  workers off, which is a factory off-switch that costs nothing to implement and needs *some* power
  dependency to exist at all.

None of those need the cost to scale, and none of them are hurt by it being small.

### Headcount costs food, because eating is the thing that actually scales

The cost that *should* grow with the size of a workforce is the one that grows with it in the world:
ten people eat ten times as much bread as one. That is simulation which happens to also be balance,
rather than balance dressed as simulation, and it is already the design in
[shift-rotation.md](shift-rotation.md).

Which gives a clean split with nothing invented:

| | |
|---|---|
| **Stress** | *This block runs.* Flat, small, and the reason a factory has an off-switch |
| **Food** | *These people work.* Scales with headcount, because that is what feeding people does |

If playtesting says workers still feel too free, the lever to reach for is the food drain, not the
stress curve.

## Should stations be networked?

**No, and the roster framing above is why.** The coordination problem is real — a line with three
roles must staff all three or produce nothing — but it is solved by scope, not by wiring: those three
roles live in one station, so completing a crew is a loop over one block's own slots.

Create's kinetic network would be the wrong tool for it even if the problem survived. It is a force
network, not a data bus, and two stations on one shaft are no more related than two Mechanical Arms
are.

The remaining shared resource is **villagers**, and the village is already that pool. Stations compete
for unemployed villagers exactly as vanilla workstations do, and the player's lever when short is the
one they already know: breed more, or build another bunkhouse.

What is genuinely unanswered is a line too big for one station's slots, which would put two stations
back in a dependency. Options when it comes up: raise the slot count, or let a station name another
it depends on — an explicit link, not an inferred one. Not worth building before someone hits it.

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
