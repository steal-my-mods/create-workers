# Create: Workers

A [Create](https://github.com/Creators-of-Create/Create) addon that puts villagers and endermen to
work hauling items around your factory, the way a Mechanical Arm does — except they walk (or
teleport) between the inventories instead of sitting bolted to one spot. Same job as an arm, same
valid targets, much more range and a lot more life about it.

The point is to give factories some life. Instead of a silent machine flicking items between two
depots, you get a hard-hatted villager in a hi-vis vest trudging back and forth with a crate of
cobblestone.

- **Minecraft** 1.21.1
- **Loader** NeoForge 21.1.219+
- **Requires** Create 6.0+

## Using it

**1. Craft a Hard Hat**

```
A A A      A = Andesite Alloy
A D A      D = Yellow Dye
```

**2. Program it.** Hold the hat and right-click inventories, exactly like setting up a Mechanical
Arm. Each click cycles that inventory between *take from* (blue) and *deposit to* (yellow), and
left-clicking removes it. Selections are outlined in the world while you hold the hat.

Unlike the arm, a programmed hat can be picked back up and edited — the existing selection is
restored rather than starting from scratch.

One worker walks between everything on its hat, so a hat only covers so much ground: no two assigned
blocks may be more than `maxTargetSpread` apart. That is a *diameter*, not a chain of short links — a
block sixty from its nearest neighbour but a hundred from the far end of the run is refused, because
one worker would have to walk that hundred. You are told as you click, rather than discovering later
that a target quietly went missing.

**3. Hire someone.** Right-click a villager or an enderman with the programmed hat. They put it on
and get to work. Sneak + empty-hand right-click to retire them and get the hat (and any cargo) back.

**4. Wear it yourself.** It is a real helmet, worth the same protection as a leather cap, and it
renders as the same 3D hat the workers wear rather than as a texture painted on your head.

### The job site

A worker's **job site** is the centre of the blocks on its hat — derived from the programme, not from
wherever you happened to be standing when you handed it over. It is what the wander leash anchors on,
so a worker hired at the edge of its run gets drawn into the middle of the work rather than loitering
where you left it. Because of the spread rule, no assigned block is ever more than half of
`maxTargetSpread` away from it.

Hiring further than `maxTargetSpread` from the job site is refused outright, with the coordinates in
the message. Nothing is ever silently dropped from a hat.

### What they can carry from and to

Exactly what a Mechanical Arm can reach, no more: belts, depots, funnels, basins, mechanical
crafters, deployers, saws, millstones, blaze burners, chutes, packagers, plus campfires, composters,
jukeboxes and respawn anchors — and anything another addon registers as an interaction point type.

A worker is an arm with legs, not a bigger arm. So the usual Create rule still applies — "not every
type of Inventory can be interacted with directly" — and a plain chest is no more a valid target for
a worker than it is for an arm. Put a funnel on the chest, same as you always would.

### Sorting packages by address

Workers double as postmen, and this needs no extra setup beyond what Create already gives you.

Put a **Package Filter** on a **Brass Funnel** and set an address on it. That funnel will then only
accept packages whose address matches, and a worker carrying a package will walk past the funnels
that refuse it and deliver to the one that takes it. Give each destination its own address and a
single worker will sort a mixed stream of packages between them.

Two behaviours worth knowing:

- Addresses are **glob patterns**, so a funnel filtered to `Smelting*` catches `Smelting_Iron` and
  `Smelting_Gold`, and `*` catches everything.
- A worker will **not pick up a package it cannot deliver**. If no funnel in its program accepts the
  address, the package is left where it is rather than carried around forever — the same "only take
  what you can put down" rule the Mechanical Arm follows.

None of this is special-cased for packages. Create's funnels already refuse a stack their filter
rejects, its Package Filter already tests by address, and the arm transfer algorithm already tries
each output and keeps whichever accepts. Workers inherit all three.

**Postboxes and frogports** are reached the same way belts and Mechanical Arms reach them — through a
funnel or an attached inventory, not directly. They expose an automation inventory that is itself
address-aware in both directions: it refuses a package addressed to that port (automation inserts are
outbound mail, so you cannot post to yourself) and will only give up packages that *are* addressed to
it (inbound mail). So a worker feeding a funnel on a postbox is posting mail, and a worker collecting
from an extracting funnel on one is emptying the mailbox.

### Villagers vs endermen

| | Villager | Enderman |
|---|---|---|
| Travel | Walks, pathfinding to each target | Teleports, up to `teleportRange` per hop |
| Pacing | Walking speed | A cooldown between hops, so a haul costs real time |
| Blocked by | Terrain it cannot path through | Nowhere safe to land |
| Safety | — | Refuses to land in water, rain, fire or lava |
| Cargo shown | Held in front of the chest | Held as a carried block, plus in-hand for non-blocks |
| Idling | Slow rounds between its assigned blocks | Stands by; does not teleport idly |

Endermen are fast but not free: each teleport is followed by a cooldown, and one hop only covers
`teleportRange`, so moving goods across a base takes several hops and visibly longer than working a
tight cluster.

**Workers do their rounds.** With nothing to haul, a worker ambles slowly between the blocks on its
own hat, standing at each for a while as though checking on it, then moving on. It looks like a
worker with time on their hands, and it is safe by construction: the only places it goes are ones it
already walks to in order to work, so idling can never strand it somewhere it cannot get back from.

Left to itself a villager would do something much worse. Its idle behaviour strolls up to ten blocks
at a time, repeatedly — a random walk with nothing bounding it — plus trips to any bed, to other
villagers, and to its job site and the village meeting point. That is how an idle villager ends up
off a catwalk and stuck, which is the last thing you want from something wired into your automation.

A worker that has somehow strayed further than `wanderRadius` from its job site walks back to it. Its
programmed blocks count as posts too, so one at the far end of a long run is at work rather than
wandering. A villager fleeing a mob is never pinned or dragged back.

`idleBehaviour` picks between three:

| | Behaviour |
|---|---|
| `PATROL` | Slow rounds between its own assigned blocks. The default — lively and predictable |
| `HOLD_STATION` | Stands where it finished its last job. The most predictable |
| `WANDER` | Vanilla idling within `wanderRadius`. Liveliest, and the one that can lose a worker off a catwalk |

Workers keep their job across save/reload, and drop the hat and their cargo if they die. Employed
endermen stop being hostile — they are on the clock.

## Configuration

`config/createworkers-server.toml`:

| Option | Default | Meaning |
|---|---|---|
| `maxTargetSpread` | 48 | How far apart the furthest two blocks on one hat may be — the width of a worker's beat |
| `transferCooldown` | 10 | Ticks paused after moving an item |
| `walkSpeed` | 0.6 | Movement speed modifier for walking workers |
| `teleportCooldown` | 20 | Ticks between enderman teleports |
| `teleportRange` | 24 | Furthest one teleport may cover; longer trips take several hops |
| `reachDistance` | 2.5 | How close a worker must get to use an inventory |
| `pathTimeout` | 200 | Ticks spent failing to reach a target before skipping it |
| `wanderRadius` | 12 | How far a worker may stray from its post or targets before being sent back |
| `idleBehaviour` | `PATROL` | What a worker does between jobs: `PATROL`, `HOLD_STATION` or `WANDER` |

## Development

```bash
./gradlew build              # compile and jar
./gradlew runClient          # dev client
./gradlew runServer          # dev dedicated server
./gradlew runGameTestServer  # run the automated tests
```

Requires JDK 21, but you should not have to think about it: `gradle/gradle-daemon-jvm.properties`
pins the Gradle daemon to Java 21 and the toolchain handles compilation, so the commands above work
as-is even when your default `java` is something newer. (Without that pin, a daemon on a too-new JVM
fails to construct the `test` task with a confusing "Type T not present".)

Create publishes no 1.21.1 build of Registrate, Ponder or Flywheel to any public Maven — it ships
them as jar-in-jar. `build.gradle` therefore resolves Create without transitives and unpacks its
embedded jars onto the compile classpath (`unpackCreateJij`). They stay `compileOnly` on purpose: at
runtime FML loads them out of Create's own jar, and a second copy on the runtime classpath would
load each mod twice.

### The logo

`src/main/resources/createworkers_icon.png` is generated, not hand-drawn:

```bash
python3 tools/generate_logo.py
```

It builds the badge Create and its addons all use — a white-ringed circle of blue graph paper with
the mod's item in front — with the palette and proportions sampled from Create's own `icon.png`. The
subject is the hard hat's item sprite scaled up by a whole number, so it stays crisp and matches what
the player sees in their inventory. The script needs nothing but the standard library; it reads and
writes the PNGs itself.

## Testing

### Automated

```bash
./gradlew runGameTestServer
```

Seventeen in-world GameTests, headless, under a minute, non-zero exit on failure. They cover target
parity with the Mechanical Arm (a depot is accepted, a chest is not), the transfer algorithm on its
own, program serialization round-tripping, round-robin wrap-around, the enderman teleport cooldown
and its refusal to land in water, the wander limit and its panic exemption, address-based package
routing (including that an undeliverable package is left alone), and both a villager and an enderman
moving a stack between two depots end to end.

Run these after any change to worker behaviour, targets or serialization.

### By hand

```bash
./gradlew runClient
```

First launch takes a minute. Make a **creative superflat** world.

**1. Get the gear.** Creative tab "Create: Workers", or:

```
/give @s createworkers:hard_hat
/give @s create:depot 2
```

**2. Build the rig.** Place two Depots about ten blocks apart. Hold a stack of cobblestone and
right-click Depot A to set it down. Depots rather than chests, because a worker targets exactly what
a Mechanical Arm targets.

**3. Program the hat.** Holding the hard hat:

- right-click **Depot A** once → *"Take items from Depot"*, outlined blue
- right-click **Depot B** twice → *"Deposit items to Depot"*, outlined yellow

The first click on a block makes it an input and clicking again toggles; left-click removes it. The
hat's tooltip should now read `1 input(s), 1 output(s)`.

**4. Hire someone.** Spawn a villager and right-click it with the hat. You should get *"Clocked in
with 2 assigned inventories"*, and a villager in a yellow hard hat and orange hi-vis vest heading
for Depot A. Swap in an enderman to watch the teleporting variant instead.

What to look for:

| | Where |
|---|---|
| Hat and vest | On the worker immediately after hiring |
| Visible cargo | Held in front of the chest while walking between depots |
| Enderman carried block | The vanilla carrying pose, since cobblestone is a block |
| Automatic pathing | The villager routes around obstacles; the enderman blinks |
| Wearing it yourself | Put the hat in your helmet slot |
| Retiring | Sneak + **empty hand** right-click returns the hat and any cargo |

Two behaviours that are deliberate and might otherwise read as bugs: an employed villager will not
wander off or trade while working, and an employed enderman stops being hostile.

**Tips.** For a continuous loop rather than a single trip, feed the source from a chest → funnel →
**belt** and set the belt as the input, so items keep arriving and the worker keeps ferrying. To
watch it move faster, edit `run/config/createworkers-server.toml` — drop `transferCooldown` to `0`
and raise `walkSpeed`. `maxTargetSpread` caps how far apart the assigned blocks can be.

## How it works

```
HardHatItem ──── WorkerProgram (data component, absolute positions)
                      │
                      │  right-click a villager/enderman
                      ▼
                 WorkerData (NeoForge attachment, saved with the entity)
                      │
        ┌─────────────┴─────────────┐
        ▼                           ▼
  WorkerJobGoal              WorkerTarget
  (arm's phase machine)      └── wraps Create's ArmInteractionPoint
        │                        (so every arm-compatible block just works)
        ▼
  WorkerLocomotion
  ├── WalkLocomotion      (villagers: brain WALK_TARGET memory)
  └── TeleportLocomotion  (endermen: safe-spot search + randomTeleport)
```

A few decisions worth knowing about:

**Villagers are brain-driven, not goal-driven.** Rather than fighting the brain for control of the
navigator, `WalkLocomotion` pins the `WALK_TARGET` memory every tick and lets the villager's own
`MoveToTargetSink` do the pathfinding. That also solves the wandering problem for free: the idle
behaviours that would send a villager strolling or off to its job site all require `WALK_TARGET` to
be *absent* before they will start, so holding the memory occupied keeps them from ever running.

**Each worker owns a detached `ArmBlockEntity`.** Create's interaction points insert and extract
through an `ArmBlockEntity`, but the only thing they ever ask it is whether it is still alive — it
is the liveness token for their capability caches. Giving each worker a throwaway one is what buys
compatibility with every registered interaction point type, including ones other addons add, rather
than only with plain item handlers. It is released when the worker unloads so the caches do not
outlive the entity.

**Render state travels in its own packet.** Data attachments are not synchronised, and vanilla
entities have no spare synched data slots, so `WorkerStatePacket` pushes the hat and cargo to
tracking clients.

## Roadmap

Ideas deliberately left out of the MVP. Anything worked through in detail lives in [`docs/`](docs/):

- **Energy.** Workers should not be strictly better free Mechanical Arms. Give them an inventory to
  fetch "fuel" from — chorus fruit for endermen, any food for villagers — and have them stop when
  they run out.
- **Working hours.** Villagers knock off at night, or keep to set hours, and go somewhere to wait it
  out. Designed out in [docs/working-hours.md](docs/working-hours.md), including the argument that it
  might only be an annoyance — it is the one idea here that makes workers *less* predictable, so it is
  written up rather than queued.
- **Bots.** A third worker type, hired by right-clicking a block with the hat the way Steam 'n' Rails
  does with conductors. They would run on backtanks: when empty, go to an inventory, drop the spent
  backtank and pick up the fullest one available.
- **Nicer models.** The current hat and vest are built from code with generated textures — functional
  placeholders rather than proper art.

## License

MIT
