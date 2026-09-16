# Working hours and off-shift behaviour

**Status: built.** Workers knock off at the end of the day, walk to a bed and sleep until morning.
It is on by default and `workingHours` turns it off.

This document was written before any of it existed, as the case for and against building it. Most of
it survives as written, because the reasoning is what the code was built from; the sections that
recorded open questions now record the answers. The argument in
[Is it even a good idea?](#is-it-even-a-good-idea) is kept in full, including the part that says this
may be a mistake — if it ever needs undoing, that is the section that says what to weigh.

## The idea

Workers keep hours. Villagers knock off at night and go somewhere to wait it out rather than standing
at a depot in the dark.

## The constraint everything else follows from

Idle behaviour is safe today because of *provenance*, not distance. A patrolling worker only visits
blocks on its own hat, and those are proven reachable by the fact that it works at all. That is the
whole argument in `Workers.patrolStops`, and it is why an idle worker cannot strand itself.

So the useful question is not "how far may an off-shift worker roam". It is "can we prove the
destination is reachable". A forty-block commute along a verified path is safer than a six-block
unverified stroll, because it is the stroll that walks off a catwalk. Get provenance right and
distance stops mattering.

Vanilla is the cautionary example. `NearestBedSensor` fills `NEAREST_BED` by straight-line proximity,
which is how you get a villager that can see a bed across a gap it cannot cross.

## Designating the bed

A bed is a new destination, not in the programmed set, so it needs provenance of its own. There turned
out to be **three** sources of one, and `WorkerShift.findBed` asks them in this order:

**Player-designated.** Sneak + right-click a bed while holding the hat. The commute becomes part of
the programme, and the player takes responsibility for the route the same way they already do for
every depot they assign. Trusted on that basis: the only thing checked is that it is still a bed
nobody else is in.

That interaction was free, as predicted: `HardHatItem.useOn` only swallows the click for blocks that
are arm interaction points, and a bed is not one. Sneak-clicking a bed normally sets spawn, so the
handler cancels the vanilla interaction — and because the cancel happens client-side, before the use
packet is sent, the server never hears about the click at all.

**The bed the village already gave this worker.** Its `HOME` memory, which was not in the original
design and is the best answer when there is one. Every villager's CORE package runs
`AcquirePoi(HOME)` whether or not it is employed, so most workers acquire a bed within moments of one
being laid down nearby — and vanilla's acquisition already did both of the things this document asked
for: it computed a path to the bed and it took a POI ticket, which the same vanilla code releases. A
worker sleeping in its own registered home is one that is not quietly squatting in somebody else's.

**Auto-discovered, path-verified.** Last, and only if the two above come up empty: the nearest few
unclaimed `home` points of interest within `bedSearchRadius` of the job site, handed to
`AcquirePoi.findPathToPois` — vanilla's own helper — and accepted only if the path it returns
reaches. Path length is proof; proximity is not.

No ticket is taken on a discovered bed. A worker that claimed one and then died, unloaded or was
retired would leave it ticketed to nobody for the rest of the world's life, which is the trap
[hiring](professions.md) already has to step around with workstations. Asking only for beds that
still have space is what keeps a worker out of a villager's bedroom, and the bed's own `OCCUPIED`
flag settles the rest: two workers who pick the same bed do not both get into it.

The design asked for path verification *at assignment time*, so the player is told immediately rather
than discovering it at dusk. That is not what was built, for a reason that only appears once you try
it: assignment happens on the client, where there is no worker to path from, and the villager who
will one day wear the hat may not exist yet. What a designated bed gets instead is the same backstop
every other destination has — the commute is on a `Progress` clock, and a worker that cannot get
there stands down and holds its station, exactly as it does for a depot it cannot reach.

## Where the bed sits in the existing geometry

Both answers held, and the asymmetry is still the point:

- **It counts toward `maxTargetSpread`.** The commute is part of the beat the worker walks, so
  including it keeps one rule governing everything: a worker's whole world fits inside one ball.
  Exclude it and the commute becomes the unbounded thing the spread rule exists to prevent. This is
  `WorkerProgram.allPositions`, which is what `exceedsSpread` and `within` measure.
- **It does *not* move the job site.** `WorkerProgram.centre()` is where the *work* is, and it is
  built from `positions()`, which is inventories only. If the bed shifted it, an on-shift worker's
  anchor — and the wander leash hanging off it — would creep towards the bedroom.

## No bed assigned

Degrades to holding station, as designed. Off-shift with nowhere to go is idling that does not haul:
no new destinations, no new risk, and no demand that a bedroom be built before the feature stops
being irritating. Vanilla wandering is never fallen back to, least of all at night with mobs about.

## The transition is the hard part

All four failure modes are handled, and the first two differently from how this document guessed.

| | |
|---|---|
| **Mid-haul at dusk** | The worker finishes the delivery in its hands and only then clocks off. It starts no new pickup — nothing in the off-shift branch searches for an input. And the finishing is itself bounded: an output that will not take the stack would otherwise keep a worker on the clock all night, so after `KNOCK_OFF_GRACE_TICKS` it goes to bed carrying the load and delivers it in the morning. Carried cargo is drawn on the worker and drops if it dies, so this is visible rather than lost |
| **Caught out at dusk** | No margin is computed. The default `clockOff` of 12000 is the moment the village itself turns in, which is a thousand ticks before night proper — the same margin vanilla gives its own villagers, and it scales with nothing because the commute is bounded by the spread rule anyway |
| **Bed unreachable when it is time to go** | The commute's `Progress` clock gives up after `pathTimeout`, stands down for `BED_REST_TICKS`, and holds station meanwhile. It tries again after that, so a door that was shut at dusk and opened at midnight is not a night spent standing outside |
| **Woken early / bed taken** | Neither cascades. A worker pulled out of bed does not climb back in for 100 ticks — vanilla's own cooldown, read off `LAST_WOKEN` — and a bed somebody else is in is refused by `isUsableBed`, which sends the worker back to holding station rather than into a fight over it |

And the thing that came free came free: **coming back needs no new machinery.** The `wanderRadius`
leash is simply not run while a worker is off shift — a bed is inside the programme's spread but need
not be inside the leash's radius, so running it would have the leash hauling the worker off its own
commute. At dawn it resumes and walks the worker back from the bed by itself.

## The open questions, answered

1. **Do they sleep, or just stand at the bed?** They sleep, driven from this side rather than
   vanilla's — `startSleeping` and `stopSleeping` directly, with no reliance on the villager
   schedule's `REST` package. But *when* they may lie down is vanilla's call and has to be: `WakeUp`
   (CORE, priority 0) stands up any sleeping villager whose brain is not in `REST`, every tick, so a
   sleep predicate that disagreed with it would be a worker lying down and getting up again for as
   long as the disagreement lasted. Hence two clocks, and the fact that they are two is the design:
   `isOffShift` is the operator's and stops the work, `isBedtime` is the village's and permits the
   lying down. Clocking off earlier than the village does means standing at the bedside until
   bedtime, which reads as a worker waiting to turn in.
2. **Endermen.** Exempt, outright. They have no bed to walk to and no schedule to keep, and they are
   creatures of the night in every other context the game puts them in. A base staffed by endermen
   runs around the clock, and that is now a reason to staff it with them. `keepsWorkingHours` on
   `WorkerLocomotion` is the seam, so inverting them onto a night shift later is a one-line change
   plus a config option to choose between the two.

   **This does not make endermen newly overpowered, and the first draft of this section said it did.**
   They were always the faster worker by an order of magnitude — one hop covers `teleportRange` in
   `teleportCooldown` ticks, where a villager walks it — and what pays for that is not their pacing,
   it is *getting one into a factory at all*: an enderman cannot be led, bred or traded for, and has
   to be brought somewhere it does not want to be. What the exemption does is complete a symmetry
   that already existed rather than break one. An enderman will not land anywhere the sky is falling
   on, so an outdoor enderman line stops in the rain; a villager line now stops at night. Each kind
   of worker has weather it cannot work in, and neither is available all of the time.
3. **Trigger.** Configurable, and cheap as predicted: `clockOff` and `clockOn` are times of day, the
   window is measured from `clockOn` so it wraps midnight without a special case, and a `clockOn`
   later than the `clockOff` is a night shift with no further machinery. What a player will actually
   feel is the default, which is the village's own hours.

   **Built since: a night shift sleeps.** `WorkerShift.applySchedule` gives each worker a schedule of
   its own, so the paragraphs below describe the problem rather than the behaviour. Kept because the
   reasoning is what the fix was built from.

   **A night shift did not sleep.** Its off-shift window is daylight, and the two-clock rule
   above says a worker may only lie down when the village is resting — so an inverted worker walks to
   its bed each morning and stands beside it until evening. The bed still earns its place, as the spot
   the worker spends the day rather than the middle of the factory floor, and the config comment says
   what happens where someone inverting the hours will read it.

   **The first draft of this section said that could never be fixed. That was wrong**, and the
   correction is worth more than the mistake cost: the schedule is not something to be fought or
   replaced wholesale, it is a field on the brain with a public setter. `Brain.setSchedule` is public,
   `ScheduleBuilder` is public, and `Schedule` declares no constructor and so has an implicit public
   one — meaning a worker can be handed a two-state schedule of its own whose `REST` window *is* its
   off-shift window, at which point `WakeUp` agrees with us by construction rather than by
   coincidence. [shift-rotation.md](shift-rotation.md) needs the same machinery and works it through
   in full, including the part where the schedule is not persisted and has to be re-applied on load.
4. **Interaction with `idleBehaviour`.** Off-shift is not a third idle state: it is its own branch,
   and it suppresses idling entirely. The rounds do not run at night, and a worker with nowhere to
   sleep holds station whatever `idleBehaviour` says. `PATROL` at 2am would be a worker walking its
   beat in the dark for no reason, which is neither safe nor charming.

## Is it even a good idea?

The honest risk: **this may just be an annoyance.** A factory that silently halves its throughput
overnight, for reasons invisible from the machine, is a worse toy than one that runs. Create's own
machines do not keep hours. A player who wanted a day/night rhythm can already get one by not
building at night.

It is also the one roadmap item that makes workers *less* predictable, which is the opposite
direction from everywhere else this has gone.

Arguments for it: it makes workers feel like people rather than machines, which is the whole point of
the mod; it is a natural balance lever against being strictly better than an arm; and it gives beds
and lighting a reason to exist in a factory.

This document originally concluded that if it were built it should be **off by default**. It ships
**on** — the owner's call, on the grounds that a feature nobody turns on is a feature nobody sees,
and that "workers sleep" is the sort of thing a player wants to discover rather than enable. The
mitigations for the risk above are that endermen are exempt, so there is always a way to run a line
through the night, and that `workingHours = false` restores the old behaviour exactly.

## Telling the player

The failure this feature can produce is not a crash, it is a factory that stops for a reason nobody
can see from the machine. So the explaining is part of the build rather than an afterthought:

- a **second Ponder page** on the Hard Hat, `WorkingHoursScene` — the last delivery of the day, the
  walk to bed, the enderman still working, and morning. Night itself cannot be drawn (a ponder level
  has no sky), which is why the scene opens mid-haul: a worker walking to a bed only reads as
  knocking off if it was visibly working a moment before;
- the hat's tooltip names the bed when one is assigned, and offers the sneak-click hint when none is;
- the bed is outlined in the world as you assign it, in a violet a third of the colour wheel away
  from Create's cyan `TAKE` and sand `DEPOSIT`, so three outlines read as one set of three meanings.

Nothing tells a player *at the moment their line stops*, and that is a decision rather than a gap. A
tooltip line saying workers keep hours was written and then taken back out: Create and its addons
keep item tooltips to what the item currently holds and put the teaching in Ponder, and a hat that
grew another hint line every time the mod learned a trick is a hat nobody reads. So the tooltip names
the bed when there is one — state, like the input and output counts beside it — and says nothing
otherwise.

## Nowhere with a fixed sky knocks off

The Nether and the End have no day to end — but they do have a day *time*, the overworld's, shared
through the level data, which is why vanilla villagers down there go to bed at a midnight they cannot
see. For a villager that is a curiosity. For something wired into a factory it is exactly the failure
above, so `isOffShift` refuses outright for any dimension with a fixed sky.

A frozen `doDaylightCycle` is the same shape of problem and is *not* handled: a world stopped at
midnight is a world where day-shift workers sleep until it is started again. That is the honest
reading of the clock, and the two ways out — turn the feature off, or move the clock — are both one
command.

## What the shape of it ended up being

The ordering this document suggested — designated bed first, working hours on top — was not followed,
because the transition machinery turned out to be the small part. What carries the feature is:

| | |
|---|---|
| `WorkerShift` | Both clocks, the bed hunt, and what makes a bed usable |
| `WorkerJobGoal.clockOff` / `goToBed` / `turnIn` / `clockOn` | The phases of a night, sharing the goal's existing `Progress` and station machinery |
| `WorkerProgram`'s `Bed` key | The designated bed, in the same data component as the inventories so clearing a hat clears it too |
| `WorkerLocomotion.commuteTo` | Walking home, which stops closer than walking to work does — the arrival test on the other side is vanilla's two blocks |
| `WorkerLocomotion.keepsWorkingHours` | Where the enderman exemption lives |
| `WorkingHoursScene` | The Ponder page, staged on a second generated plate — the same yard with a bed in it |
