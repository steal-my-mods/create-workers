# The Worker Station

**Status: built.** The block hires, holds a rack of up to twelve jobs, runs each of them on any of the
three shifts, promotes workers up the order when one is lost, replaces workers that die and reclaims
jobs from workers that stop turning up. The screen is built too — slots, shift toggles, order arrows
and the staffing readout — though nobody has looked at it in a running game yet, and its shape (a
fixed twelve rows) is the first thing to revisit when somebody does.

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

### Direct assignment stays, but it produces the same kind of worker

Nothing here removes a hiring route. Right-clicking a villager with a programmed hat keeps working,
and both Ponder scenes keep teaching it. The station is the path a base takes when it has outgrown
doing that by hand.

**Shifts and food are universal.** Every villager worker keeps a shift and eats, however it was
hired — there is no second class of worker exempt from the mechanics, and no compatibility path
preserving the behaviour of a pre-1.0 build. `workingHours` and a matching `requireFood` are the
escape hatches for a server that wants the old shape; carrying it in the code would be debt paid
forever for a design nobody is running.

So the shift lives **on the hat**, always. The station sets it when it fills a slot; a hand-hired hat
carries whatever it was set to, defaulting to the day shift. One mechanism, one place to look, and
the station becomes a convenience for setting it rather than the only thing that can.

What actually differs between the routes is narrower than an earlier draft claimed — only crew
membership and what happens when a worker is lost:

| | Station-hired villager | Hand-hired villager | Enderman |
|---|---|---|---|
| Keeps a shift | Yes, its crew's | Yes, the hat's | No — exempt |
| Sleeps | Yes | Yes | No |
| Eats | Yes | Yes | No — [out of scope for now](shift-rotation.md#endermen-do-not-eat-for-now) |
| Belongs to a crew | Yes | No | No |
| Replaced when lost | Yes, automatically | No | No |

The enderman column differs for a reason unrelated to this block: no profession and no point of
interest means no job board could ever hire one. That is why it stays a hand-hire, and it is the same
reason it is exempt from hours.

Keeping the route also keeps the small build small. One worker between two depots should not need a
block, a shaft and a power source.

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
- **`maxTickets` is per POI *type*, not per block.** Registered at the largest roster the mod
  supports, which is why the slot cap is a constant the config can only lower. See
  [Advertising openings](#advertising-openings) for what the block does about the difference.

### Advertising openings

The first draft said to register the type at the largest crew and let a station turn away villagers it
had no hat for. That works and is self-correcting, but it is much worse than it sounds: a station with
one job would have three dozen villagers walk across the village, each made a Worker on arrival by
`AssignProfessionFromJobSite` and un-made by `ResetProfession` a tick later.

So the block **holds back the tickets it has no opening for**, taking them in its own name and
releasing them as vacancies appear. Free tickets then mean openings, and vanilla's own `AcquirePoi`
enforces the count exactly as it does for one librarian per lectern. It is written as an invariant —
*free tickets equal vacancies* — approached from whichever side it is currently on, so it repairs
itself after a load, a config change or a death the station was not loaded to see, with none of those
needing a case of its own.

One thing this must not be built on: `HAS_JOB` means *has a hat*, never *has a vacancy*. It is the
property the point of interest is registered over, so a fully staffed station dropping out of the POI
set would destroy the record, release every ticket its living workers hold, and end with
`ResetProfession` clearing the profession off the whole crew.

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

### But the station must not decide that for the player

A first pass concluded that a shift which cannot be fully staffed should not open at all. **That is
overreach**, because the chain above is only one of the shapes a line can have, and the station cannot
tell which it is looking at:

| The player built | A part-staffed shift is |
|---|---|
| A chain, one worker per hop | Broken — nothing comes out |
| Two workers per role, for throughput or redundancy | Fine, at reduced rate |
| Several unrelated jobs in one station | Fine; the unstaffed jobs stop and the rest carry on |

Nothing in a programme declares which of those it is. A hat is a list of inventories; it does not say
"my output is worker 2's input", and it does not say "I am a spare copy of worker 1". The mod could
*infer* some of it — identical programmes are redundant copies, and a `DEPOSIT` target that is
another hat's `TAKE` target is a chain link — but that is a pile of fragile inference in service of
overriding a decision the player is better placed to make.

**So the station fills in order and does not second-guess.** Two policies, both defensible without
knowing the topology, and both now implemented as one loop in `nextVacancy()` — shift-major,
slot-minor:

- **Fill slots in list order.** Which makes *slot order the player's way of saying what matters most*
  — put one of each role first and the spares after, and a short-handed station gives you complete
  coverage before it gives you redundancy. That is a lever the player already understands, needs no
  new UI concept, and beats any rule we could invent.
- **Fill one shift before starting the next.** This one survives every topology above: concentrating
  a short crew on one window is never worse than scattering it across three, and in the chain case it
  is the difference between a working factory and nothing at all.

And **fill the daytime shift first**, so an understaffed factory runs during the hours the player is
most likely to be standing in it. A factory that only works while you are asleep is one you cannot
debug.

### Single-slot stations were considered, and they break exactly this

Worth recording, because the argument looks reasonable from the outside. Deferring the screen is
tempting — it is the one piece with no game-test coverage, client classes not loading on a dedicated
server — and a station with one slot needs no list, no ordering and no drag.

**But the fill order is a property of a line, and a single-slot station cannot see one.** N stations
competing for the village's unemployed villagers have nothing sequencing them, so the day shift of
one job fills while another job's day shift stays empty — which, by the chain argument above, is the
case that produces nothing. The coordination could be approximated (throttle each station to one
opening so the natural spread is roughly even) but not guaranteed, and the approximation is a pile of
heuristics scattered across independent blocks: the distributed problem the roster framing exists to
avoid, reintroduced to save a GUI. It also makes "take the hat out to fire the workers" ambiguous,
since the block no longer knows which crew a hat belongs to.

The ticket throttle survived on its own merits — see [Advertising openings](#advertising-openings) —
but as the mechanism that makes a *variable-size* roster work, not as a substitute for one.

What the station owes the player is not a judgement but a **clear readout** — which slots are filled,
which are not, and on which shift:

```
Shift 1 (day)      ███  3/3
Shift 2 (evening)  ██·  2/3
Shift 3 (night)    ···  0/3
```

If a part-staffed evening shift is useless on their line, they can see that and know to breed more
villagers. If it is fine, nothing has been taken away from them.

### The order, written out once

The fill order is **one order over every place on the rack**, read shift first and then down the rack.
With three jobs and two shifts it is:

| # | Place | | # | Place |
|---|---|---|---|---|
| 1 | job 1, day | | 4 | job 1, evening |
| 2 | job 2, day | | 5 | job 2, evening |
| 3 | job 3, day | | 6 | job 3, evening |

Villagers fill it from the top. So four villagers staff the whole day shift and then start the evening;
they never leave a day job empty to begin one.

**Rebalancing restores that order wherever it breaks, and it does not care why.** A vacancy is filled
from the *last* occupied place in the order, so:

- Job 1's day worker dies while job 3's day worker lives → job 3's worker moves up to job 1. **Within
  a shift**, and the promoted worker's route changes, because the hat changes with the job.
- Job 1's day worker dies while only an evening worker is spare → the evening worker moves to days.
  **Across shifts.**

Those are the same rule, not two. Which is the answer to "does it rebalance within the first shift as
well": yes, and for the same reason — the rack is a priority order, and a short crew should be doing
the work that matters most. `losingAnEarlyJobPromotesFromALaterOneOnTheSameShift` and
`losingADayWorkerPromotesSomebodyUpToIt` pin one case each.

### And it has to rebalance, or the rule only holds while the roster grows

Filling in order places new workers correctly and does nothing about the ones already placed, which
means every death degrades the arrangement permanently. Two jobs on two shifts with three villagers
is a complete day crew and one evening worker; lose one of the day crew and both lines are broken and
three survivors produce nothing — for good, if the village has nobody spare.

So a station **moves workers up the fill order until its roster is a prefix of it again**, and the one
that moves is the last in that order, which the rack already expresses and a player can predict. It is
re-employed rather than edited, because a promotion is usually a different hat as well as different
hours.

Eagerly, rather than waiting to see whether a replacement villager turns up. That costs nothing: a
replacement fills the *last* place in the order either way, so promoting first and hiring into the
hole behind reaches the same roster — and if no villager is spare it is the difference between a
running factory and a stopped one.

One related restraint, from the same principle: **do not fire a crew that becomes incomplete
mid-shift.** If a worker dies at noon, the remaining crew either carries on usefully or backs its own
line up and stops within minutes — and the station will usually have refilled the slot before that
matters. Tearing down a running shift the instant somebody dies would be a far more violent failure
than the one it prevents.

## How hats get in and out

A station needs slots managed and shifts assigned, so **yes, it needs a screen** — but it should not
*only* be a screen.

- **The block is an inventory.** Right-click with a hat to drop one in, right-click empty-handed to
  take the last one back. That covers a one-slot or two-slot station with no interface at all, the
  way a lectern or a jukebox does.
- **The screen is for arrangement**: which slot holds which hat, which shift each slot belongs to,
  and the readout above. That is the part a right-click cannot express, and it is the part that
  matters once a station holds more than about three jobs.
- **Because it is an inventory, Create can fill it** — a funnel or an arm feeding hats into a station.
  Almost certainly a curiosity rather than a real workflow, but it costs nothing to allow and refusing
  it would be the odd choice in a Create addon.

Physical hats rather than stored programmes, deliberately. The hat is a real item that the worker
wears, comes back when they die, and had to be crafted — so a station's capacity is bounded by
something the player actually built, and every existing behaviour (the drop, the clearing recipe, the
tooltip) keeps working with no special case for "a job that has no hat".

## Naming a job, and reading a factory floor

Both notes keep running into the same problem from different directions: **when something goes wrong,
which villager is it?** A stuck worker, a slot that will not fill, a crew that is one short. Two cheap
answers, and they compose.

### Name the hat, name the worker

A hard hat can already be renamed — `CUSTOM_NAME` is a data component, it survives the drop and the
return to a slot, and it shows in the tooltip — so an anvil already half-solves this. Two small
additions make it real:

- **A rename field in the station screen** *(built)*. No anvil, no experience cost, and it is right
  there while you are arranging slots. Naming a job "Smelting feed" is a label, not an enchantment, and should not
  cost a level. Create names its Frogports and Train Stations in-block for the same reason.
- **A named hat names its wearer.** Copy the hat's custom name onto the villager when it is employed,
  and clear it on retirement. Villagers never despawn, so there is no persistence side effect, and the
  payoff is large: "Smelting feed" floats over the villager standing in a hole, which is the whole
  diagnostic problem solved with a component copy.

### A uniform that can be read across a room

The gear is already generated from `worker_gear.png`, so putting information on it is cheap — and one
thing is worth showing: **the shift, as the colour of the vest.** A different vest per crew costs a
change to the generator script rather than to any render code, and it is what
[shift rotation](shift-rotation.md) already wanted.

**The vest, and not trim on the sleeves and hem.** That was tried: the profession overlay is the one
part of a worker that is a texture rather than geometry, so it looked like the cheap place to put
colour. Orange at the hands and feet reads as a costume rather than as safety gear, and it is now
blank — the vest is what a hi-vis wearer is recognised by, so it is what should carry the crew.

That, plus the name floating over the worker, is enough. *The evening crew is one short, and it is
"Smelting feed" who is missing* is a sentence a player can form by looking, without opening anything.

**A slot number on the back of the vest was considered and cut** — a quad with UVs from a digit strip
is perfectly buildable, and real vests do carry markings, but it is a third signal for a question the
first two already answer, and the number is the weakest of the three: it belongs to the station rather
than to the hat, so it changes when a hat moves slots and has to be pushed to the client, and unlike
a name it tells the player nothing about what the worker *does*.

## How many jobs one station holds

Capped, for three reasons that all point at a similar number:

1. **Every slot is a villager that will exist.** This mod counts what one worker costs a server tick
   by tick; a station is a multiplier on that count, and it deserves the same treatment `maxTargets`
   gets — a configurable ceiling, documented as a server cost rather than a taste.
2. **The POI ticket count is fixed at registration.** `maxTickets` belongs to the point-of-interest
   *type*, not to the block, so whatever cap is chosen has to be baked in when the type is registered.
   That makes it a real ceiling rather than a soft one.
3. **A line with more roles than that is two lines.** The roster framing suggests a modest number by
   construction.

**One flat list of slots, each holding a hat and a set of shifts it runs on**, rather than a grid of
roles times shifts. Simpler to cap, simpler to order — which matters, because slot order is now the
player's priority lever — and more flexible: a role can run three shifts while another runs one,
which a grid would forbid for no reason.

Twelve is the cap, as `WorkerStationBlockEntity.MAX_SLOTS`, with `stationSlots` able to lower it but
never raise it — the ceiling is baked into the point of interest at registration. Twelve jobs on three
shifts is thirty-six villagers, which is a serious village; four roles on three shifts is already a
serious line.

## Coming back: the self-healing part, and its limit

`WorkerData` remembers the station it was hired from as a `GlobalPos`. Then:

- **On death, conversion or retirement**, if that station still exists and is loaded, the hat returns
  to its slot instead of dropping. If the station is gone, it drops exactly as it does today.
- The slot is now empty, so the station advertises the job again, and the next unemployed villager
  takes it.

**But this fixes dead, not stuck**, and it is worth being honest about the difference. A worker at
the bottom of a hole is still alive and still employed, so its slot is not free and the station will
not replace it. The job is occupied by someone who is never coming to work.

There is a second gap of the same shape, and it needs the worker's help rather than the station's.
A station cannot tell an unloaded worker from a dead one: `getEntity` finds only loaded entities, and
the ticket count is a counter, so it can say *how many* of the ones it cannot see have gone but never
*which*. It therefore strikes off the unaccounted-for from the bottom of its fill order and may
occasionally be wrong — so **every worker asks its station, on every load, whether it is still on the
books**, and sacks itself if not. Without that, a worker that was only unloaded comes back doing a job
the station has given to somebody else, invisible to the one block supposed to know who works there.

The answer to the other half is an **absentee rule**: a station reclaims a job from a worker that has
not been near any of its own targets — or the station — for some long while. The hat comes back, someone else is hired,
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
   path — which is the price of keeping direct assignment, and it is worth paying.)
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

1. **The screen's shape.** Built, and rebuilt once after the first version turned out not to fit a
   screen: twelve jobs go in two columns of six rather than one column of twelve, because a `Slot`'s
   position is final once the menu is built and a window that grew with the rack would mean rebuilding
   the menu on every change. It is drawn in vanilla's own palette and bevels rather than in a texture,
   which keeps it out of anybody's art. Renaming is in, on the hat's `CUSTOM_NAME`. What is still not
   is the other half of that idea — **a named hat naming its wearer**, which is where the diagnostic
   value actually is.
2. **How does a station cope with a line too big for its slots?** Raise the cap, or let a station name
   another it depends on — an explicit link, never an inferred one. Not worth building before someone
   hits it.
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
