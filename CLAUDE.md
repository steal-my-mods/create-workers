# Create: Workers — repo guide

Create addon for **Minecraft 1.21.1 / NeoForge 21.1.219+ / Create 6.0+**. Hard-hatted villagers and
endermen haul items between inventories the way a Mechanical Arm does.

## Commands

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client
./gradlew runServer          # dev dedicated server (needs run/eula.txt)
./gradlew runGameTestServer  # automated in-world tests -- the real check
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
- **Villagers use the brain, not goals.** Never steer their navigator directly. `WalkLocomotion`
  pins the `WALK_TARGET` memory every tick and lets `MoveToTargetSink` (villager CORE package,
  priority 1) path. Keeping that memory occupied is also what stops them wandering off — the idle
  and job-site behaviours require it to be *absent* to start. `Mob.serverAiStep()` is `final` and
  runs goals *before* the brain, so a goal setting the memory is seen the same tick.
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
- **Endermen don't need a wander leash, villagers do.** The job goal holds `Goal.Flag.MOVE`, which
  stops other *goals* (an enderman's random stroll) from moving the mob — but the villager brain is
  not a goal and ignores flags entirely, so villagers drift during cooldowns. `Workers.isOffStation`
  counts programmed targets as posts, not just the hire spot, or a worker at the far end of a long
  run reads as wandering. Never leash a panicking villager; reuse `VillagerPanicTrigger.isHurt` /
  `hasHostile` so the check agrees exactly with when the brain takes over.
- **Don't assert behaviour with wall-clock thresholds.** A "not delivered within 25 ticks" check for
  the teleport cooldown passed happily with the cooldown set to 1. `teleportsRespectTheirCooldown`
  asserts the mechanism instead, and was mutation-checked by deleting the gate.

## Conventions

Tabs for indentation, matching Create's own style. Registry classes are `CW*` under `registry/`.
Nothing is committed without explicit instruction.
