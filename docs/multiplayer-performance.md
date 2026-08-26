# What a worker costs a server

**Status: audited, and the findings acted on.** Written up because the cost model is worth keeping —
the next feature will want it — and because two of the things a shared server wants were considered
and deliberately not built.

The mod is small and holds no per-tick global state: no level tick handler, no entity sweep, nothing
that scales with the size of the world. Everything it costs is per employed worker, plus a fixed cost
on every villager and enderman for carrying a goal that refuses to run. So the whole question is what
one worker does in a tick, and what the worst tick looks like.

## The per-tick model

A worker's goal ticks every tick (`requiresUpdateEveryTick`), and `canUse`/`canContinueToUse` are
re-evaluated every *other* tick — `Mob.serverAiStep` alternates between a full `GoalSelector.tick()`
and `tickRunningGoals(false)`.

| When | What it costs |
|---|---|
| Unemployed villager or enderman | One `canUse`: an attachment lookup that returns null. Measured in tens of nanoseconds; a thousand-villager trading hall is well under a tenth of a percent of a tick. |
| Employed, hauling | `locomotion.tickEmployed`, the wander check (one `closerThan` per target, short-circuiting on the job site), one `isValid` (one block state read), one reach test, one memory write. |
| Employed, idle, scanning | Once every `IDLE_RESCAN_TICKS`: inputs × slots × outputs simulated insertions, plus one block read per target. This is the term that grows, and it is why `maxTargets` exists. |
| Employed, idle, holding station | A `WalkTarget` allocation and a memory write. The sink sees "already arrived", erases it, and pathfinds nothing. |
| Employed enderman, hopping | One landing scan per hop: a box of candidates, each scored before it is inspected. |

The thing that does *not* appear in that table, and dominated everything else before it was bounded,
is villager pathfinding — see below.

## What was wrong

**A walk the worker never finishes cost a pathfind every few ticks, forever.** `MoveToTargetSink`
starts by asking the navigation for a fresh path, and `PathNavigation.createPath` snapshots a
`PathNavigationRegion` as wide as the mob's follow range — 48 blocks for a villager, so a 97-block
cube of chunk references — and runs an A* over it. Pin `WALK_TARGET` at somewhere the villager cannot
get to and the cycle is: path, walk the partial path, arrive nowhere, sink stops, memory re-pinned,
path again. Several searches a second out of one mob that looks like it is standing still.

Only the haul had a timeout. The idle rounds and the leash home did not, and the rounds are the
default idle behaviour — a stop that is a funnel on a wall, or a belt across a gap, is a stop the
worker can *work* but can never stand within two blocks of. Every walk is now on a `Progress` clock,
and the clock measures being stuck rather than the length of the trip.

**Rescanning could drag chunks in and drop them again.** Create's `ArmInteractionPoint.isValid`
refreshes its cached block state with a plain `Level.getBlockState`, which on a server loads — and
generates — whatever chunk the position is in. Targets sit up to `maxTargetSpread` from each other,
so a worker in a loaded chunk can easily have targets that are not, and it rescans once a second. A
worker kept ticking by a chunk loader with a target three chunks outside it would do this for as long
as the world existed. Now nothing reads a block for a target without checking the chunk first, and a
point that cannot be read is deferred and retried rather than dropped.

**Nothing bounded a programme.** The configure packet accepted any compound tag the protocol would
carry — two megabytes, tens of thousands of points — and the first thing it did with it was the
pairwise spread check. That is hundreds of millions of comparisons on the server thread, from one
packet, repeatable. Downstream, the same list is stored on the item, saved, sent to every client that
can see the hat, walked twice per tooltip frame, and priced as inputs × outputs on every scan. There
is now a `maxTargets` limit enforced at four points and an NBT quota enforced during decode.

**Small things.** An employed enderman cleared its attack target every tick, and `Mob.setTarget`
fires `LivingChangeTargetEvent` whether or not anything changed — that is a bus dispatch per tick per
enderman, and other mods listen on it. The landing scans inspected up to two thousand candidate
blocks per hop, reading up to four blocks apiece, when most candidates could be ruled out by
arithmetic first.

## Considered and not done

### Permission checks on programmed targets

A hat can name any inventory within the spread, and the worker then extracts from it with no player
in the loop — so no `PlayerInteractEvent`, and nothing for a claim mod to veto. On a server running
land claims, a hat programmed at a boundary is a way to pump items out of somewhere the player cannot
open. The packet now insists a programme arrives from somewhere inside its own beat, which stops a
crafted packet naming a base across the world, but it does not ask whether the player may *use* those
blocks.

Doing it properly means one permission check per point, and the honest way to get one — firing a
synthetic interact event — is both expensive and liable to have side effects on whatever is listening.
The alternatives are a soft dependency on each claim mod's API, or a config for server owners who care
(a whitelist of dimensions, or requiring line of sight at selection time). None of them is obviously
right, so this is left as a documented gap rather than a half-measure: **a worker is exactly as
trusted as the player who programmed the hat.** Servers with claims should know that.

### Backing the idle rescan off

An idle worker rescans once a second forever. An exponential back-off — a second, then two, then
five, up to some ceiling — would cut the cost of a warehouse full of waiting workers by most of it.
It was not done because the rescan interval is also the mod's responsiveness: the delay between an
item appearing on a belt and a worker noticing. A worker that takes five seconds to spot work reads
as broken, and `maxTargets` already bounds what a single scan can cost. Worth revisiting only with a
number from a real server behind it.

### Only giving the goal to employed mobs

The goal goes on every villager and enderman as it joins the level, which is what lets a worker that
was hired, saved and reloaded start again with no bookkeeping. Adding it on hire instead — plus on
load, for anything that comes back with a hat on — would save a null attachment lookup per mob per
two ticks. That is not worth a second code path that can get out of step with the first.
