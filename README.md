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

Crafting a programmed hat on its own blanks it, the way a Create filter clears. The same hat comes
back, keeping its damage and its enchantments, rather than a factory-fresh one.

One worker walks between everything on its hat, so a hat only covers so much ground: no two assigned
blocks may be more than `maxTargetSpread` apart. That is a *diameter*, not a chain of short links — a
block sixty from its nearest neighbour but a hundred from the far end of the run is refused, because
one worker would have to walk that hundred. You are told as you click, rather than discovering later
that a target quietly went missing.

**3. Hire someone.** Right-click a villager or an enderman with the programmed hat. They put it on
and get to work. Sneak + empty-hand right-click to retire them and get the hat (and any cargo) back.

**4. Wear it yourself.** It is a real helmet — two points of armour, the same as an iron one, and
rather more durable — and it renders as the same 3D hat the workers wear rather than as a texture
painted on your head.

**Or let the game explain it.** Hold **W** over a Hard Hat in your inventory and Create's own Ponder
screen walks through the whole job: assigning one Depot as an input, right-clicking a second twice to
make it an output, hiring a villager, and watching them carry an ingot across the yard and clock off
again.

### The job site

A worker's **job site** is the centre of the blocks on its hat — derived from the programme, not from
wherever you happened to be standing when you handed it over. It is what the wander leash anchors on,
so a worker hired at the edge of its run gets drawn into the middle of the work rather than loitering
where you left it. Because the spread rule bounds how far apart the targets can be, it always lands
in among them rather than at one end of the run — with two targets, exactly halfway between them.

Hiring further than `maxTargetSpread` from the job site is refused outright, with the coordinates in
the message. Nothing is ever silently dropped from a hat.

### What they can carry from and to

Exactly what a Mechanical Arm can reach, no more: belts, depots, funnels, basins, mechanical
crafters, deployers, saws, millstones, crushing wheels, blaze burners, chutes, packagers, plus
campfires, composters, jukeboxes and respawn anchors — and anything another addon registers as an
interaction point type.

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
| Cargo shown | Held in front of the chest | The vanilla carrying pose for blocks, in front of the chest for anything else |
| Idling | Unhurried rounds between its assigned blocks | Stands by; does not teleport idly |

Endermen are fast but not free: each teleport is followed by a cooldown, and one hop only covers
`teleportRange`, so moving goods across a base takes several hops and visibly longer than working a
tight cluster.

**Workers do their rounds.** With nothing to haul, a worker ambles between the blocks on its
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
| `PATROL` | Unhurried rounds between its own assigned blocks, at `idleSpeedFactor` of walking speed. The default — lively and predictable |
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
| `idleSpeedFactor` | 0.85 | Pace of a worker on its idle rounds, as a fraction of `walkSpeed` |
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
./gradlew runServer          # dev dedicated server (needs run/eula.txt)
./gradlew runGameTestServer  # run the automated tests (needs run-gametest/eula.txt)
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
python3 tools/generate_logo.py                                   # 256px, the in-jar logo
python3 tools/generate_logo.py --size 512 branding/icon-512.png  # 512px, for the project pages
```

It builds the badge Create and its addons all use — a white-ringed circle of blue graph paper with
the mod's item in front — with the palette and proportions sampled from Create's own `icon.png`. The
subject is the hard hat's item sprite scaled up by a whole number, so it stays crisp and matches what
the player sees in their inventory. The script needs nothing but the standard library; it reads and
writes the PNGs itself.

Every measurement in it is tuned at 256 and scaled by a single factor, so `--size` must be a
multiple of 256: any other size leaves the sprite's scale fractional and its pixels no longer
square, which is the one thing the whole approach exists to avoid. The jar keeps the 256 version
because the mods list draws it small. CurseForge wants 512 for the project page, and it downscales
well but never upscales.

### The Ponder scene's plate

The little diorama the Ponder scene plays out on is generated as well, rather than built in a
creative world and saved:

```bash
python3 tools/generate_ponder_structure.py   # assets/createworkers/ponder/hard_hat.nbt
```

It writes the NBT directly, gzipped with `mtime=0` so an unchanged scene produces a byte-identical
file. The two Depot positions live in both that script and `HardHatScene` with nothing tying them
together — move one and move the other.

Both CI workflows re-run this and the logo script and fail on any diff. A generated file that has
gone stale would otherwise ship in the jar with nothing to notice it, so regenerating has to be a
no-op.

## Testing

### Automated

```bash
./gradlew runGameTestServer
```

Twenty-three in-world GameTests, headless, under a minute, non-zero exit on failure. They cover
target parity with the Mechanical Arm (a depot is accepted, a chest is not), the transfer algorithm
on its own, program serialization round-tripping, the clearing recipe, round-robin wrap-around, the
job site and the spread rule being derived from the programme (and an over-spread programme refused),
the enderman teleport cooldown, its refusal to land in water, its long hops only landing closer to
the target, and the vetoes that stop vanilla teleporting it off the job or digging up the blocks
under it, the wander limit and its panic exemption (mid-haul included), holding station and the
patrol stops along with the pace a worker ambles and then walks at, address-based package routing
(including that an undeliverable package is left alone), and both a villager and an enderman moving a
stack between two depots end to end.

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

Two behaviours that are deliberate and might otherwise read as bugs: an employed villager keeps to
its patch instead of strolling off, and an employed enderman stops being hostile. Trading is not
blocked, though — right-click a worker with a profession and its trade screen opens as usual.

**Tips.** For a continuous loop rather than a single trip, feed the source from a chest → funnel →
**belt** and set the belt as the input, so items keep arriving and the worker keeps ferrying. To
watch it move faster, edit `run/config/createworkers-server.toml` — drop `transferCooldown` to `0`
and raise `walkSpeed`. `maxTargetSpread` caps how far apart the assigned blocks can be.

## Releasing

Uploads are driven from the repo rather than typed into web forms:

```bash
./gradlew publishMods
```

That pushes the jar to CurseForge and GitHub Releases, taking the release notes from the
`CHANGELOG.md` section that names the current `mod_version`. It needs `CURSEFORGE_TOKEN` and
`GITHUB_TOKEN` in the environment and `curseforge_project_id` in `gradle.properties`. Add
`-PdryRun=true` and it rehearses the lot — resolving the jar, pulling the changelog section, checking
every destination is configured — writing what it would have uploaded to `build/` instead of
uploading it.

Modrinth is not a destination for now, while its new rules on generative AI in project images are
still an open question for the badge icon — see the Distribution notes in
[`CLAUDE.md`](CLAUDE.md). The project there is reserved, not abandoned.

You should not need to run it by hand, though. Pushing a `v*` tag runs
[`.github/workflows/release.yml`](.github/workflows/release.yml), which builds, runs the
GameTests, and publishes only if they pass:

```bash
git tag v0.3.0 && git push origin v0.3.0
```

Three things in there are guards rather than steps, and each one fails the release outright rather
than let it half-publish — neither site lets you rename or replace a file after upload:

- **The CurseForge token is checked with curl before anything is built**, so an expired secret fails
  before GitHub has accepted a release that CurseForge then never gets.
- **The tag has to agree with `mod_version`**, or the jar goes up under the wrong number on both
  sites at once.
- **The generators are re-run and any diff fails the build**, so a stale badge or Ponder plate
  cannot ship inside the jar.

Running the workflow by hand rehearses by default: its *Rehearse without uploading anything*
checkbox starts ticked, so a curious click walks the whole path — token check, build, GameTests,
generator diff, changelog lookup — without uploading. A tag push always publishes for real.

`CURSEFORGE_TOKEN` is a repository secret you add yourself; `GITHUB_TOKEN` comes from Actions.

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

**The worker in the Ponder scene is a puppet.** A ponder level reports itself as client-side, so
nothing in the chain above runs in there — no brain, no `serverAiStep`, nothing that would move the
villager. `WalkInstruction` sets its position every tick instead, which is also why Ponder needed a
new instruction: it has none for walking an entity across a scene.

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

MIT — see [LICENSE](LICENSE).

`WorkerData`'s transfer algorithm is a port of Create's `ArmBlockEntity`, and Create's code is
MIT as well, so its notice travels along in [NOTICE.md](NOTICE.md) and inside the jar under
`META-INF/`. No Create asset is used or redistributed — Create's `assets/` are All Rights
Reserved, and every texture, model and icon here is original.
