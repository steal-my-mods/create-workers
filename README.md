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

Sneak + right-click a **bed** to say where that worker sleeps. The bed is outlined in pale blue
alongside the inventories, and the same click on it again takes the assignment off. Optional — a
worker with no bed on its hat finds one for itself — but it is how you put a particular worker in a
particular room.

Crafting a programmed hat on its own blanks it, the way a Create filter clears. The same hat comes
back, keeping its damage and its enchantments, rather than a factory-fresh one.

One worker walks between everything on its hat, so a hat only covers so much ground: no two assigned
blocks may be more than `maxTargetSpread` apart, and there are at most `maxTargets` of them. The
spread is a *diameter*, not a chain of short links — a block sixty from its nearest neighbour but a
hundred from the far end of the run is refused, because one worker would have to walk that hundred.
Both limits tell you as you click, rather than letting you discover later that a target quietly went
missing.

**3. Let them hire themselves.** Right-click a **Worker Station** to open its rack, drop the
programmed hat into a slot, and an unemployed villager nearby will be taken on for it — the way an
unclaimed lectern finds itself a librarian.

```
P H P      P = any planks
P A P      H = Hard Hat, programmed
           A = Andesite Alloy
```

A station holds up to twelve hats, and each one is a job on its own line. It fills them **one shift at
a time** — every job's day crew, then every job's evening crew, then every job's night crew — because
a factory's workers are a chain, and a shift missing one of them usually produces nothing rather than
less. When the village is short of villagers, the order of the rack is how you say which jobs matter
most.

The hat stays in the station and the worker wears a copy, so **if that worker dies the job is still
there** and the next villager along picks it up. And if one stops turning up — walled in by a build,
fallen somewhere it cannot climb out of — the station gives the job away after `absenteeTimeout` and
hires a replacement, so a line never quietly runs short over one lost villager. A factory built on
stations repairs its own workforce.

Workers wear their crew's colour: **orange** on days, **yellow** on evenings, **white** at night. The
hard hat is the same on all three, so a worker still reads as a worker. Name a job in the station and
its workers wear that name too, which is how you find the one standing in a hole.

Right-click the station to open the rack. Each job shows its hat, three shift toggles —
sunken for off, yellow for wanted, green for covered — and arrows to move it up or down the order.
Underneath is the count for each crew. **Click a job's name to rename it**, no anvil and no experience:
a hat called "Smelting feed" is a job you can find again when something goes wrong with it. Taking a hat out ends that job and puts its workers out of work,
leaving the rest of the rack running; breaking the block does the same to all of them.

A station is also an ordinary inventory, so a funnel or a Mechanical Arm can stock it with hats.

Two things a station will not do. A villager that already has a job of its own will never take one —
break its workstation first, the same way vanilla makes you — and children are never hired, because a
child will not take a workstation of any kind.

**Endermen cannot use a station** at all: they have no profession and no interest in workstations. So
they are hired by right-clicking one with the programmed hat and retired with a sneak + empty-hand
right-click, which is the only hiring anyone still does by hand.

**4. Wear it yourself.** It is a real helmet — two points of armour, the same as an iron one, and
rather more durable — and it renders as the same 3D hat the workers wear rather than as a texture
painted on your head.

**Or let the game explain it.** Hold **W** over a Hard Hat in your inventory and Create's own Ponder
screen walks through it over three pages: programming a hat, assigning one Depot as an input and
right-clicking a second twice to make it an output; then a Worker Station taking on a villager and
watching it carry an ingot across the yard; then shifts and sleep — the last delivery of the day, the
walk to bed, and the enderman that carries on through the night.

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
| Hours | Knocks off at dusk and sleeps in a bed | Works around the clock |

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

If it *cannot* get back — walled in, or at the bottom of a hole — it keeps trying, but waits longer
between attempts each time, and after a few failures it starts giving off the same unhappy particles
a villager shows when you break its workstation. That is the only way to spot the difference between
a line running slow and a line running one worker short. Turn on `recallStuckWorkers` if you would
rather have it teleported back than go looking.

Everywhere a worker is sent, it is allowed `pathTimeout` ticks of getting no nearer before it gives
up: on a target, which is set aside for half a minute before it is tried again; on a stop on its
rounds, which drops off them for the same; or on the walk home, after which it stands where it is
rather than keeping at it. That matters more than it sounds. A villager is steered by pinning a
destination in its brain every tick, and vanilla answers a destination it is not already walking to
by pathfinding afresh — so a worker sent somewhere it can never arrive, a funnel on a wall or a belt
across a gap, is not a worker standing idle. It is a pathfind every few ticks for as long as it
lives.

`idleBehaviour` picks between three:

| | Behaviour |
|---|---|
| `PATROL` | Unhurried rounds between its own assigned blocks, at `idleSpeedFactor` of walking speed. The default — lively and predictable |
| `HOLD_STATION` | Stands where it finished its last job. The most predictable |
| `WANDER` | Vanilla idling within `wanderRadius`. Liveliest, and the one that can lose a worker off a catwalk |

Workers keep their job across save/reload, and drop the hat and their cargo if they die. Employed
endermen stop being hostile — they are on the clock.

### Working hours

At the end of the day a villager worker downs tools, walks to a bed and sleeps until morning. It
clocks back on at first light and carries on where it left off. `workingHours` turns the whole thing
off, and `clockOn` moves the working day.

It sets **one** working day, and the three crews each start a third of a day later than the last: a job
set to run all three shifts is staffed round the clock by three villagers keeping the same hours at
different times.

**A crew works exactly a third of a day, and that is not adjustable.** A third of a day is 8000 ticks,
which is the longest a crew can work without running into the next one — so the length is fixed at
that rather than offered as a number to get wrong. It used to be a setting, and every value but the
default either overlapped the crews or left gaps between them.

**Endermen are exempt.** They have no bed and no schedule, and they are creatures of the night
everywhere else in the game, so a line staffed by endermen runs around the clock. That is the reason
to staff it with them — and it is not free, because an enderman still will not land anywhere the sky
is falling on. An outdoor enderman line stops in the rain the way a villager line stops at night.

Where a worker sleeps, in order of preference:

1. **The bed on its hat**, if you gave it one. Taken as given: you clicked it, so you decided the
   route, the same way you do for every inventory you assign.
2. **The bed the village has already put down as theirs** — workers acquire a home like any other
   villager, and that one came with a path and a claim on it.
3. **The nearest unclaimed bed within `bedSearchRadius` of the job site that it can prove a path
   to.** Proximity alone is never enough: a bed six blocks away across a gap is further, in the only
   sense that matters, than one forty blocks along a corridor. Set `bedSearchRadius` to 0 and workers
   sleep only where you tell them to.

A worker with nowhere to sleep — no bed given, none it can get to — just stands where it is until
morning. Nothing wanders off in the dark.

Some details you might otherwise read as bugs:

- **A worker caught mid-haul finishes the delivery first.** It will not start a new one, but the
  stack already in its hands goes where it was going before the worker turns in — so items are never
  parked in a pocket overnight for no visible reason.
- **A night crew really does sleep, in daylight.** Every crew carries working hours of its own, so
  the hours it rests in are its own rather than the village's — a night worker walks to bed at dawn
  and sleeps through the morning while the rest of the village is up.
- **A crew is awake for a while after its shift, and before it.** Between clocking off and bed there
  is time to itself, when vanilla has the worker and it behaves like any other villager; and it is up
  and walking to its post shortly before the shift starts, so the next crew is in place before the
  last one stops. A worker milling about near its work is off duty, not lost.
- **A bed names a dormitory rather than a mattress.** Every Worker on a job wears a copy of the one
  hat, so they all share the bed it names; whoever gets there first takes it and the others find a
  free bed beside it.

## Configuration

`config/createworkers-server.toml`:

| Option | Default | Meaning |
|---|---|---|
| `maxTargetSpread` | 48 | How far apart the furthest two blocks on one hat may be — the width of a worker's beat |
| `maxTargets` | 24 | How many blocks one hat may be programmed with. The cost of a worker with nothing to do grows with inputs times outputs, so this is the ceiling on what an idle one costs a server |
| `transferCooldown` | 10 | Ticks paused after moving an item |
| `walkSpeed` | 0.6 | Movement speed modifier for walking workers |
| `idleSpeedFactor` | 0.85 | Pace of a worker on its idle rounds, as a fraction of `walkSpeed` |
| `teleportCooldown` | 20 | Ticks between enderman teleports |
| `teleportRange` | 24 | Furthest one teleport may cover; longer trips take several hops |
| `reachDistance` | 2.5 | How close a worker must get to use an inventory |
| `pathTimeout` | 200 | Ticks spent failing to reach a target before skipping it |
| `wanderRadius` | 12 | How far a worker may stray from its post or targets before being sent back |
| `idleBehaviour` | `PATROL` | What a worker does between jobs: `PATROL`, `HOLD_STATION` or `WANDER` |
| `workingHours` | `true` | Whether workers knock off at the end of the day and sleep. Endermen are exempt whatever this says |
| `clockOn` | 0 | Time of day the day crew starts, which moves the whole working day. A crew works a third of a day and the three tile the clock between them; that length is fixed rather than configurable |
| `bedSearchRadius` | 16 | How far from the job site a worker may look for a bed of its own. 0 means it sleeps only in a bed assigned on its hat |
| `recallStuckWorkers` | `false` | Whether a worker that has repeatedly failed to walk back to its work is teleported there. Workers that can walk home always walk; this is only for the one at the bottom of a hole |
| `absenteeTimeout` | 6000 | How long a worker may go without being anywhere near its own work before its station gives the job to somebody else. 0 never gives up on anyone |

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

### The project page's art

The images the CurseForge page is built out of are generated too, from the same assets the game
loads:

```bash
python3 tools/generate_page_art.py                 # everything, into branding/
python3 tools/generate_page_art.py --only canteen  # one sheet, while iterating
```

It writes `branding/banner.png` (1280x640 — the name and the three things the mod adds in a row), a
`branding/card-<thing>.png` at 960x540 for each of them, and a `branding/recipe-<thing>.png` for
every shaped recipe the mod ships. They share the badge's blue graph paper, white keyline and soft
shadow, with the palette imported from `generate_logo.py` rather than restated, so the page and the
icon beside it in a mod list read as one family.

The recipe sheets are drawn from `data/createworkers/recipe/*.json`, so a rebalanced recipe redraws
instead of going stale, and a new one gets a picture without this script being touched. Most of what
is *in* them belongs to somebody else — andesite alloy is Create's, dye and planks are Mojang's — and
none of it is copied into this repo: the sprites are read out of the jars Gradle has already cached,
at the moment the image is drawn. What is committed is a finished picture, which puts these in the
same position as a screenshot of a crafting table rather than in the position of a repo that
redistributes two other projects' textures. That means recipe sheets need a build to have run once;
the banner and the cards need nothing (`--minecraft-jar` / `--create-jar` override the search).

`docs/curseforge-page.md` holds the page's actual copy, with each image slotted in where it goes —
including briefs for the screenshots that have to be taken rather than generated.

Nothing in them is typed twice. The block pictures are `render_block_model.py` drawing the shipped
models; the names come out of `lang/en_us.json`, so renaming a block renames it on the page; and the
readouts are supplied from the constants in `generate_block_textures.py`, which is what lets the page
show a staffed rack and a stocked trough — the lamps, the food and the gauges are drawn by the block
entity renderers, not by the block model, so a plain model render shows empty ones. Change a texture
and re-run this, and the page catches up.

Both blocks wear Create's andesite casing, so the block pictures now need Create's jar as well —
which the recipe sheets already did, and which a single `./gradlew build` provides.
The headings are set in `tools/pixel_font.py`, a bitmap font written out here rather than loaded from
the system, so that the output is byte-identical on any machine.

**What it cannot draw is a worker.** A villager in a hard hat and a hi-vis vest only exists once
vanilla's model, its textures and `WorkerGearModels.fitTo` have met inside a running client — the
gear is *fitted* to whatever model wears it, which is the whole point of it, and a hand-written
reconstruction of that in a drawing tool would be a picture of what we hope happens rather than of
what does. That is the mod's best picture and it has to be a screenshot; the gallery wants those.
This covers the rest of the page, repeatably.

Adding a thing to the page is one entry in `SUBJECTS`: its card comes for free, the banner grows a
column, and its recipe draws itself. Neither CI workflow re-runs this one — unlike the logo and the
Ponder plates, these files never enter the jar, so a stale one costs a wrong picture on a web page
rather than a wrong mod, and the recipe sheets need dependency jars a CI runner would have to fetch
to draw at all.

### The Ponder scene's plate

The little diorama the Ponder scenes play out on is generated as well, rather than built in a
creative world and saved — and so are the lines of text, which are read straight out of the
storyboards:

```bash
python3 tools/generate_ponder_structure.py   # assets/createworkers/ponder/*.nbt
python3 tools/generate_ponder_lang.py        # the scenes' lang entries
```

The second one is not tidiness. Ponder numbers a scene's lines by the order the storyboard writes
them, and looks each up by that number — so inserting a beat in the middle moves every line after it
onto the wrong step, silently, in a way neither file shows on its own.

It writes the NBT directly, gzipped with `mtime=0` so an unchanged scene produces a byte-identical
file. Both scenes' plates come out of one shared `yard()`, so they are visibly the same place — the
working-hours one is that yard with a bed in the corner the Depots leave free. Every position lives
twice, in the script and in the scene class, with nothing tying them together: move one and move the
other.

Both CI workflows re-run this and the logo script and fail on any diff. A generated file that has
gone stale would otherwise ship in the jar with nothing to notice it, so regenerating has to be a
no-op.

## Testing

### Automated

```bash
./gradlew runGameTestServer
```

Fifty-six in-world GameTests, headless, under a minute, non-zero exit on failure. They cover
target parity with the Mechanical Arm (a depot is accepted, a chest is not), the transfer algorithm
on its own, program serialization round-tripping, the clearing recipe, round-robin wrap-around, the
job site and the spread rule being derived from the programme (and an over-spread programme refused),
the enderman teleport cooldown, its refusal to land in water, its long hops only landing closer to
the target, and the vetoes that stop vanilla teleporting it off the job or digging up the blocks
under it, the wander limit and its panic exemption (mid-haul included), holding station and the
patrol stops along with the pace a worker ambles and then walks at, address-based package routing
(including that an undeliverable package is left alone), and both a villager and an enderman moving a
stack between two depots end to end.

Working hours get a set of their own: the shift clock wrapping midnight, a worker walking to the bed
on its hat and sleeping in it, nothing being hauled while it does, the delivery already in its hands
being finished first, a worker with nowhere to sleep standing its ground, endermen working straight
through, waking and going back to work at dawn, and `workingHours = false` keeping everyone on the
job. The bed hunt is pinned branch by branch — the bed on the hat, the bed the village already gave
them, and a discovered one, which must come with a path that reaches it and is refused without.
Anything that needs the world to be dark runs in its own batch, because the time of day is one clock
for the whole server and game test batches are the only isolation there is.

The rest are the guards a shared server depends on: that a programme past `maxTargets` is not
honoured in full and that one must arrive from inside the beat it describes, that resolving a
programme never loads a chunk to read a block in it and retries the targets it could not read, that a
target the worker failed to reach is set aside rather than walked at again immediately, that an
idle enderman is not sent on rounds it has no way to walk, and that a worker with nowhere to sleep
does not hunt for a bed every tick — a hunt is a point-of-interest query and a pathfind, and unpaced
it would be one per worker per tick for the length of a night.

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

  WorkerShift  ──  when the shift ends, and which bed to spend it in
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
- **Bots.** A third worker type, hired by right-clicking a block with the hat the way Steam 'n' Rails
  does with conductors. They would run on backtanks: when empty, go to an inventory, drop the spent
  backtank and pick up the fullest one available.
- **Nicer models.** The current hat and vest are built from code with generated textures — functional
  placeholders rather than proper art. The Worker Station is worse than a placeholder: it is a plain
  cube with a procedurally-drawn face, and it looks it. A block that a player walks up to and
  right-clicks wants a silhouette, the way every Create block has one.

## License

MIT — see [LICENSE](LICENSE).

`WorkerData`'s transfer algorithm is a port of Create's `ArmBlockEntity`, and Create's code is
MIT as well, so its notice travels along in [NOTICE.md](NOTICE.md) and inside the jar under
`META-INF/`.

**No Create asset is redistributed.** Create's `assets/` are All Rights Reserved, and every
texture, model and icon *in this jar* is original. The Worker Station and the Canteen do
**reference** one of Create's sprites — their models name `create:block/andesite_casing`, which
Minecraft resolves out of Create's own jar at runtime, and `CWConnectedTextures` shifts it to
Create's connected sheet so the blocks lose their seams against each other and against a real
Andesite Casing. Nothing of it is copied here; displaying a file that ships with a hard
dependency is use rather than redistribution, and the machinery doing the shifting is Create's
code, which is MIT.
