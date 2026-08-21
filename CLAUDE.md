# Create: Workers — repo guide

Create addon for **Minecraft 1.21.1 / NeoForge 21.1.219+ / Create 6.0+**. Hard-hatted villagers and
endermen haul items between inventories the way a Mechanical Arm does.

## Commands

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client
./gradlew runServer          # dev dedicated server (needs run/eula.txt)
./gradlew runGameTestServer  # automated in-world tests -- the real check
./gradlew publishMods        # upload to CurseForge, Modrinth and GitHub Releases
./gradlew publishMods -PdryRun=true   # ...or rehearse it without uploading anything
```

JDK 21 required. `gradle/gradle-daemon-jvm.properties` pins the daemon to it, so the commands work
without setting `JAVA_HOME` even when the default `java` is newer — don't delete that file, or
`./gradlew build` dies with "Could not create task ':test' ... Type T not present" on a newer JVM.
There is no unit-test suite;
correctness is covered by GameTests in `com.createworkers.test.WorkerGameTests`. Run them after any
change to worker behaviour, targets or serialization.

## Build quirk worth knowing

Create declares Registrate / Ponder / Flywheel as Maven dependencies, but **no 1.21.1 build of any of
them is published to a public Maven** — Create ships them jar-in-jar. So `build.gradle`:

1. resolves Create with `transitive = false`,
2. unpacks `META-INF/jarjar/*.jar` out of Create's jar (`unpackCreateJij` task),
3. puts those on the compile classpath as **`compileOnly`**.

`compileOnly` is deliberate: at runtime FML loads them from Create's own jar, and a second copy on
the runtime classpath makes each mod load twice. Catnip is not a separate artifact — it lives inside
the Ponder jar.

## Distribution

Releases go out through `publishMods` (`me.modmuss50.mod-publish-plugin`), driven by
`.github/workflows/release.yml` on a `v*` tag. Things in there that are decisions, not accidents:

- **`minecraft_version_range` is `[1.21.1,1.21.2)`,** not the MDK's default `[1.21.1,1.22)`. This
  mod reaches into the villager brain and needs Create 6 for 1.21.1; the wider range would let it
  install on 1.21.4 and break there instead of refusing.
- **The changelog drives the release notes.** `publishMods` reads the `CHANGELOG.md` section whose
  heading names the current `mod_version` and fails if there isn't one — a missing entry should
  stop a release rather than ship the previous version's notes under a new number. It is wired as
  a lazy provider so an ordinary `./gradlew build` never trips over it.
- **`archivesName` carries the Minecraft version** (`createworkers-1.21.1-0.1.0.jar`). If you
  change it, remember the three sites will not let you rename a file after upload.
- **`LICENSE` and `NOTICE.md` ship in the jar under `META-INF/`.** `WorkerData`'s transfer
  algorithm is a port of Create's `ArmBlockEntity`, Create's code is MIT, and MIT wants its notice
  carried with "copies or substantial portions" — a jar handed to a player is a copy. Create's
  `assets/` are separately All Rights Reserved, which is why no Create art is used and the badge
  icon is generated from this mod's own sprite instead.
- **The logo script is size-parameterised** (`--size`, a multiple of 256): 256 for the in-jar
  `logoFile`, 512 in `branding/` for the project pages. Multiples only, or `SPRITE_SCALE` goes
  fractional and the sprite's pixels stop being square.
- **Commits use a repo-local identity** (`Steal-My-Mods`, the account noreply address) set in
  `.git/config`, deliberately not the global one. Don't "fix" it back.
- Both project ids in `gradle.properties` are blank until the CurseForge and Modrinth projects
  exist; `publishMods` is the only thing that needs them.

## Architecture landmarks

| Path | Role |
|---|---|
| `program/WorkerProgram` | The hat's inventory list. Data component; **absolute** positions (anchor `BlockPos.ZERO`) because workers move. Also owns the geometry: `centre()` (job site) and `firstTooFar`/`exceedsSpread` (the diameter rule) |
| `worker/target/WorkerTarget` | What a worker can use — a thin wrapper over Create's `ArmInteractionPoint`, holding the host so it stays out of upstream signatures |
| `worker/WorkerData` | Per-entity state (NeoForge attachment). Holds the port of `ArmBlockEntity`'s transfer algorithm |
| `worker/WorkerJobGoal` | Phase machine: search input → travel → collect → search output → travel → deposit |
| `worker/WalkLocomotion` | Villagers. Also owns `returnTo`, the wander leash |
| `worker/TeleportLocomotion` | Endermen. Holds the teleport cooldown, so locomotion instances are **per-worker**, not shared |
| `worker/WorkerEvents` | Hiring, retiring, drops, client sync, cleanup |
| `client/HatSelectionHandler` | Client-side programming UX (mirrors `ArmInteractionPointHandler`) |
| `client/WorkerGearLayer` | Hard hat + hi-vis vest render layer |
| `client/model/HardHatArmorModel` | The same hat geometry as a `HumanoidModel`, for the hat worn by a player |
| `client/HardHatClientExtensions` | Feeds that model to the armour renderer; re-baked on resource reload |
| `client/WorkerCargoLayer` | Visible cargo |
| `recipe/ClearProgramRecipe` | Crafting a hat by itself blanks its program, the way a Create filter clears |

## Things that will bite you

- **Range is a property of the programme, not of a position.** `maxTargetSpread` is a *diameter*:
  every pair of a hat's targets must be within it, checked in `HatSelectionHandler` as you click and
  re-checked server-side in `ConfigureHatPacket` (never trust the client). The **job site** is
  `WorkerProgram.centre()`, the middle of the target box — not where the player stood at hire time.
  It is derived, never persisted, and recomputed on deserialize. `resolvePoints` therefore filters
  nothing: a target on the hat is a target. Don't reintroduce a silent range filter — the point of
  this shape is that a target is either refused as you assign it or honoured.
- **Villagers use the brain, not goals.** Never choose a destination for their navigator directly —
  the only thing ever set on it by hand is the speed of a path the sink already started.
  `WalkLocomotion` pins the `WALK_TARGET` memory every tick and lets `MoveToTargetSink` (villager
  CORE package, priority 1) path. Keeping that memory occupied is also what stops them wandering
  off — the idle and job-site behaviours require it to be *absent* to start. `Mob.serverAiStep()` is
  `final` and runs goals *before* the brain, so a goal setting the memory is seen the same tick.
- **`WorkerData` owns a detached `ArmBlockEntity`.** Create's interaction points take one only to
  ask `isRemoved()` — it is the liveness token for their `BlockCapabilityCache`. Always
  `releasePoints()` when a worker unloads or the caches outlive the entity.
- **Attachments are not synced.** Anything the client must render goes through `WorkerStatePacket`
  (on change, and on `PlayerEvent.StartTracking`).
- **Workers must accept exactly what a Mechanical Arm accepts** — only Create's registered
  interaction point types, so plain chests, barrels and hoppers are *not* valid targets. This is a
  deliberate design rule ("a worker is an arm with legs"), not an oversight; do not add a generic
  item-handler fallback. `targetsMatchTheMechanicalArm` guards it. Tests therefore build Depots
  rather than chests.
- **Create's lang keys are namespaced.** `Mode.getTranslationKey()` returns `mechanical_arm.*`; the
  real key is `create.mechanical_arm.*`.
- **The clearing recipe is a class, not four lines of JSON.** Create blanks a filter with a plain
  `crafting_shapeless` of the item on itself, because a vanilla crafting result is a factory-fresh
  stack — which for armour also means a free repair and a stripped set of enchantments. So
  `ClearProgramRecipe` subclasses `ShapelessRecipe` and copies the input hat over, removing only the
  program component. 1.21.1 has no `crafting_transmute` (that arrived in 1.21.2) to do it in data.
- GameTest templates: `data/createworkers/structure/*.nbt` (singular `structure` in 1.21). The
  template is intentionally empty — tests lay their own floor with `layFloor`.
- **Armour is not just a texture on a head box.** A helmet normally renders as the vanilla head
  geometry with the armour sheet stretched over it, which looks like a painted scalp.
  `HardHatArmorModel` swaps in the real hat cubes via `IClientItemExtensions.getHumanoidArmorModel`.
  The trap is vanilla's `hat` part: a *second* head-sized box, a **sibling** of `head` rather than a
  child, which `HumanoidArmorLayer.setPartVisibility` turns on for anything in the head slot (and
  `ClientHooks.copyModelProperties` copies that visibility onto the replacement). Leave it populated
  and it draws a solid cube over the whole skull regardless of what `head` contains — so
  `createLayer` replaces it with an empty `CubeListBuilder`. Its texture sheet therefore uses the
  gear UV layout at 128x64, *not* the 64x32 humanoid armour layout.
- **Gear geometry has to clear what is already drawn underneath.** Villagers wear a `jacket` overlay
  (body inflated 0.5), so a vest inflated by that same 0.5 lands exactly on it and z-fights. The
  villager vest uses 1.0; the enderman, which has no overlay, uses 0.5. Declare boxes at whole-number
  sizes and grow them with `CubeDeformation` so UVs stay on exact texels.
- Vanilla renders villager professions as *texture overlays re-rendered over the same mesh*
  (`VillagerProfessionLayer` → `renderColoredCutoutModel`), not as extra geometry — worth knowing if
  the vest ever needs to hug the robe rather than sit over it.
- **Idle rounds must only visit programmed targets** (`Workers.patrolStops`). That is the entire
  safety argument for `PATROL`: those positions are ones the worker already paths to while working,
  so idling cannot strand it anywhere it could not already get back from. Never widen the stop list
  to arbitrary nearby positions.
- **Idle villagers are pinned by occupying `WALK_TARGET`, not by fighting the brain.** The idle
  package's wanderers (`VillageBoundRandomStroll`, `JumpOnBed`, `InteractWith`) are *one-shots* that
  do nothing but write that memory, while `MoveToTargetSink` — the behaviour that actually walks the
  mob — reads it from CORE at priority 1, ahead of the idle package at priority 2. Rewriting the
  memory every tick from the goal (which runs before the brain) means a stroll's destination is
  overwritten before anything acts on it. Anchor to a *remembered* position, never to
  `mob.blockPosition()`: an anchor that follows the worker inches along with every nudge, which is
  the drift this exists to stop.
- **Writing `WALK_TARGET` does not change a villager's speed.** `MoveToTargetSink` hands the speed
  to the navigation only in its `start`, and once running the only thing that calls `start` again is
  a re-path — which it does only when the destination has moved more than *two blocks*. Stops on the
  idle rounds are the worker's own targets, so a worker ambling to one when work appears is usually
  already walking to the very block the job is at: the new walk target is the same position, nothing
  re-paths, and it strolls to work at idle pace. All of `WalkLocomotion` therefore goes through
  `walkTo`, which also sets the speed on the navigation directly — but only when the walk target it
  is overwriting was already the same position, which is what makes the running path the *same trip*
  rather than some other one. (Not `navigation.getTargetPos()`, the obvious comparison and always
  unequal: `GroundPathNavigation.createPath` retargets a solid block to the first non-solid one
  above it, so a path to a depot is a path to the air over the depot. Comparing against it disables
  the nudge outright, which is what `workFoundOnTheRoundsIsWalkedAtWorkingPace` failed on.) Without
  the gate the nudge lands on whatever path happens to be running:
  a panicking villager's flight (pinned to working pace instead of vanilla's faster one, since a
  goal writes it before `navigation.tick()` and the brain cannot get it back), or the last stride of
  an amble the worker has already arrived at, which `holdAt` would bump to working pace and end
  every idle round with a sprint — which `arrivingOnTheRoundsKeepsTheAmblePace` covers.
  `workFoundOnTheRoundsIsWalkedAtWorkingPace` asserts the speed the move control is actually driven
  at, not the memory, and was mutation-checked by deleting the nudge; it starts the trip from a
  third speed that is neither pace, so no leg can pass on a speed the test itself supplied.
- **Endermen don't need a wander leash, villagers do.** The job goal holds `Goal.Flag.MOVE`, which
  stops other *goals* (an enderman's random stroll) from moving the mob — but the villager brain is
  not a goal and ignores flags entirely, so villagers drift during cooldowns. `Workers.isOffStation`
  counts programmed targets as posts, not just the hire spot, or a worker at the far end of a long
  run reads as wandering. Never leash a panicking villager; reuse `VillagerPanicTrigger.isHurt` /
  `hasHostile` so the check agrees exactly with when the brain takes over — every path in
  `WalkLocomotion` checks it, `approach` included (`workersOnTheirWayToAJobStillPanic`), so a worker
  mid-haul flees like any other villager. `SetWalkTargetAwayFrom` sits at the same brain priority as
  the sink that reads the memory, so rewriting it every tick during a panic competes with the flight
  rather than losing to it.
- **Don't assert behaviour with wall-clock thresholds.** A "not delivered within 25 ticks" check for
  the teleport cooldown passed happily with the cooldown set to 1. `teleportsRespectTheirCooldown`
  asserts the mechanism instead, and was mutation-checked by deleting the gate.

## Design notes

`docs/` holds write-ups of features that were thought through but not built, including the reasoning
against building them. Read the relevant one before starting such a feature, and update it if the
thinking changes — the point is that the analysis is not redone from scratch.

- `docs/working-hours.md` — night shifts, designating a bed on the hat, and why the whole idea may be
  an annoyance

## Conventions

Tabs for indentation, matching Create's own style. Registry classes are `CW*` under `registry/`.
Nothing is committed without explicit instruction.
