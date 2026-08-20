# Working hours and off-shift behaviour

**Status: not planned for the first release.** Written up because the analysis is worth keeping, not
because it is queued. See [Is it even a good idea?](#is-it-even-a-good-idea) — there is a real risk
this only makes workers annoying.

## The idea

Workers keep hours. Villagers knock off at night, or to a configured schedule, and go somewhere to
wait it out rather than standing at a depot in the dark.

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

A bed is a new destination, not in the programmed set, so it needs provenance of its own. Two ways
to get it, and they compose rather than compete:

**Player-designated.** Sneak + right-click a bed while holding the hat. The commute becomes part of
the programme, and the player takes responsibility for the route the same way they already do for
every depot they assign.

That interaction is free: `HardHatItem.useOn` only swallows the click for blocks that are arm
interaction points, and a bed is not one, so the slot is unused. Sneak-clicking a bed normally sets
spawn, so the handler has to cancel the vanilla interaction — the same pattern
`HatSelectionHandler` already uses for the selection clicks.

**Auto-discovered, but path-verified.** A bed found by sensor is acceptable *if* we compute a real
path to it and bound its length. `PathNavigation.createPath` gives a path we can measure. Path length
is proof; proximity is not.

Either way, verify at assignment time and say so immediately — *"can't find a way there from the job
site"* — rather than letting the player discover it at dusk. The same principle applies retroactively
to the hiring check, which currently measures straight-line distance from the job site and has the
same blind spot.

## Where the bed sits in the existing geometry

Two answers, and the asymmetry between them is the point:

- **It counts toward `maxTargetSpread`.** The commute is part of the beat the worker walks, so
  including it keeps one rule governing everything: a worker's whole world fits inside one ball.
  Exclude it and the commute becomes the unbounded thing the spread rule exists to prevent.
- **It does *not* move the job site.** `WorkerProgram.centre()` is where the *work* is. If the bed
  shifted it, an on-shift worker's anchor would creep towards the bedroom.

## No bed assigned

Degrade to the current idle behaviour — hold station or slow rounds. Off-shift with nowhere to go is
just "idle, but not hauling": no new destinations, no new risk, and the feature does not demand setup
before it stops being irritating.

Never fall back to vanilla wandering, least of all at night with mobs about.

## The transition is the hard part

Four failure modes:

| | |
|---|---|
| **Mid-haul at dusk** | A worker that stops holding a stack has put that item in limbo — invisible until it dies. Rule: finish the current delivery, *then* clock off |
| **Caught out at dusk** | Leave for home *before* the shift ends, as real villagers do. The margin should scale with the commute, and a computed path tells us how long that is |
| **Bed unreachable when it is time to go** | Blocked, destroyed, chunk unloaded. Fall back to holding station |
| **Woken early / bed taken** | Should not cascade into wandering |

And one thing that comes free: **coming back needs no new machinery.** The `wanderRadius` leash
already walks a worker to its job site when it is too far from it. Off-shift merely *suspends* the
leash; resuming it at dawn walks the worker back from the bed by itself. Only the outbound commute
needs designing.

## Open questions

1. **Do they sleep, or just stand at the bed?** Lying down is most of the charm, but vanilla
   `SleepInBed` runs off the villager's `HOME` POI and bed claiming, and the villager schedule is
   deliberately suppressed to stop workers wandering (see CLAUDE.md). Reintroducing it selectively
   may fight that. Read the POI and claiming code before promising it works.
2. **Endermen.** Working hours is a villager-shaped idea. Do endermen ignore it, or invert it and work
   nights? `WorkerLocomotion` is the seam for making it per-species.
3. **Trigger.** Fixed dusk/dawn is simpler; a configurable schedule is more useful and costs little
   once the transition machinery exists.
4. **Interaction with `idleBehaviour`.** Off-shift is a third state alongside working and idling, and
   it is not obvious whether `PATROL` should apply to it, be suppressed, or slow down.

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

If it is built, it should be **off by default**, so it is something a player opts into for flavour
rather than something that surprises them.

## Suggested ordering, if it happens

Build the **designated bed slot with path verification first**, on its own. It is independently
useful as a predictable "go here when not working" destination, it is the thing the rest rests on, and
it carries none of the throughput risk. Working hours then becomes a small trigger on top of a
mechanism already proven in play, rather than a large feature bundled with a risky one.
