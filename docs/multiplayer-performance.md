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
| Employed, idle, scanning | Once every `IDLE_RESCAN_TICKS`, and only when the inputs hold something the outputs will not take: inputs × slots × outputs simulated insertions, plus one block read per *output*. This is the term that grows, and it is why `maxTargets` exists. An empty input costs nothing past the extract that comes back empty, and a deliverable one returns on the first slot. |
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

## The second pass

A later audit went over the same ground looking for constant factors rather than structure. The
shape above held up — no global state, no entity sweeps, every block read chunk-guarded — but three
things inside the dominant term were doing the same work repeatedly, and one packet was much larger
than the thing it was telling clients.

**The scan re-validated every output once per input slot.** `simulateInsertion` walked the outputs
asking `isUnreachable` and `isValid` of each, and it was called once per slot of every input. That
put a block read — `ArmInteractionPoint.isValid` refreshes its cached state with a plain
`Level.getBlockState`, and a belt point reads a second one above itself — on inputs × slots ×
outputs, to learn an answer that only varies per output. Twelve basins in and twelve out is a few
thousand block reads per scan where two dozen do. The usable outputs are now gathered once per scan
(`WorkerData.usableOutputs`), which is safe because nothing moves during a scan: every insertion
priced against that list is simulated.

Worth being exact about when this was being paid, because the table above used to overstate it. An
input whose slots are empty costs almost nothing — the simulated extract comes back empty and the
insertion is never priced. An input with something deliverable returns on the first slot it finds.
The full inputs × slots × outputs walk happens when the inputs hold items and the outputs will not
take them, which is not a rare worst case: it is the steady state of a backed-up line, repeated
every rescan for as long as it stays backed up. It also runs on the *transfer* cooldown rather than
the idle one whenever a pickup is attempted and fails, so it can come round twice as often as
`IDLE_RESCAN_TICKS` suggests.

**The winning slot was found twice per pickup.** `searchForItem` located an input *and a slot*, then
returned only the input index; `collectFrom` re-walked from slot zero, re-pricing every slot it
passed over against every output. It now carries the slot across as a hint and tries it first. A
hint is never a precondition — the worker travels between the two calls, so the amount is re-checked
and a slot that has emptied falls through to the full walk. `collectingWorksWithoutAScanToHintAt`
covers that, and was mutation-checked by deleting the fallback.

**`getSlotCount()` was the loop condition** in both walks, so a capability lookup ran once per
iteration instead of once per inventory. Hoisted.

**Every item moved re-broadcast the whole programme.** `WorkerStatePacket` carried the hat as a full
`ItemStack`, and the programme is a `networkSynchronized` data component on it, so each packet was
the entire point list — a couple of kilobytes of type names and coordinates for a full hat. It goes
to every client tracking the worker on every transfer, which is twice a second apiece, and no
receiver ever read it: the gear layer asks only whether the worker is employed and the cargo layer
only what it is holding. Twenty workers with four players in range was a few hundred kilobytes a
second of NBT the client already had on the item. The synced hat is now stripped of that one
component, which takes the packet from kilobytes to about ten bytes and makes splitting hat state
out of the per-transfer packet unnecessary.

Three findings from the same pass were left, being small and each a trade rather than a plain win:
identical stacks in adjacent slots are still re-simulated from scratch (a per-scan memo by item
would collapse a full inventory of one thing to a single probe); `isSafeStandingSpot` allocates a
`BlockPos` per floor and headroom check from an argument that is already mutable; and
`findLandingSpot` scores candidates against its centre but iterates from the far corner, so it can
never return on the first safe spot the way a distance-ordered offset table would let it.

## How the cost is tested

`WorkerCostGameTests` holds the performance tests. None of them measures a duration, and that is the
whole design: a threshold loose enough to pass on a loaded CI runner is loose enough to sleep through
a tenfold regression, and one tight enough to catch that regression fails on somebody's laptop. What
a worker costs a server is a *count* — block reads, slots looked into, deliveries priced, bytes on
the wire — so the tests count those and assert bounds **derived from the size of the programme**
rather than numbers somebody once measured. The bounds then say the same thing at any size and on any
hardware, and they are assertions about the shape of the work rather than about speed: "each output
is checked once per search" fails the moment a check drifts back inside a loop, which is the
regression that is easy to write and impossible to notice in play.

`WorkerData` keeps the tally itself — `validityChecks()`, `slotProbes()`, `deliveryProbes()`, reset
at the top of each search. Per instance rather than static, so there is no flag to turn on and
nothing to synchronise: a worker is owned by one entity on one thread. The cost is an int increment
beside operations that each already do a block read or a capability lookup. Only simulated probes are
counted; the one real extract or insert that ends a search is the work, not the looking.

Each test also logs what it measured, pass or fail, so a run reads as a report:

```
[cost] hopeless search over 3 inputs / 3 outputs (27 slots, 3 stocked): 6 validity checks,
       27 slot probes, 9 delivery probes -- checking validity per slot instead would be 12
[cost] setting one of 3 outputs aside: validity checks 6 -> 5, delivery probes 9 -> 6
[cost] collecting from slot 3 of a 18-slot basin: 1 slot probe -- walking the inventory again would be 4
[cost] render packet: 2 targets -> 10 bytes, 6 targets -> 10 bytes
```

All four were mutation-checked, which for a cost test is not optional — one that passes against the
code it was written to condemn is worse than none, because it reads like cover. Restoring the
per-slot validity check takes the first from 6 to 15; dropping the set-aside clock out of the
gathering takes the second from 5 to 6; deleting the slot hint takes the third from 1 probe to 4; and
sending the hat unstripped takes the packet from a flat 10 bytes to 144 at two targets and 378 at
six, which is the ~65 bytes a target the audit predicted from the NBT shape.

Two things the counters turned up that nobody had noticed. **A Create depot exposes nine slots** —
one for the item on it, eight for processing results — so a programme of depots pays nine slot probes
per input per search to find the one that matters, not one. It is cheap (an empty slot costs a probe
and no pricing) but it means "slots" in the cost model is not "targets" and never was. And **an empty
input is nearly free**: the simulated extract comes back empty before anything is priced against the
outputs, which is why the expensive search is specifically the one over inputs that *do* hold
something.

The obvious test still missing is a **pathfind rate**, which the section above calls the mod's most
expensive failure mode and which nothing currently bounds by cost — only `unreachableTargetsAreSetAside`
bounds it by mechanism. `MoveToTargetSink` writes each fresh `Path` into the `PATH` memory, so
counting how many distinct paths appear there over a fixed window of ticks is an observable proxy for
`createPath` calls, needing no instrumentation at all. A worker walled off from its target should
show a handful over 600 ticks where an unclocked one shows one every few ticks — an order of
magnitude apart, so a generous bound would be robust, and per-tick rather than per-second so it stays
hardware-independent.

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
