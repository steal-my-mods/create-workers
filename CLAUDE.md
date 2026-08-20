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
| `program/WorkerProgram` | The hat's inventory list. Data component; **absolute** positions (anchor `BlockPos.ZERO`) because workers move |
| `worker/target/WorkerTarget` | What a worker can use — a thin wrapper over Create's `ArmInteractionPoint`, holding the host so it stays out of upstream signatures |
| `worker/WorkerData` | Per-entity state (NeoForge attachment). Holds the port of `ArmBlockEntity`'s transfer algorithm |
| `worker/WorkerJobGoal` | Phase machine: search input → travel → collect → search output → travel → deposit |
| `worker/WalkLocomotion` | Villagers. Also owns `returnTo`, the wander leash |
| `worker/TeleportLocomotion` | Endermen. Holds the teleport cooldown, so locomotion instances are **per-worker**, not shared |
| `worker/WorkerEvents` | Hiring, retiring, drops, client sync, cleanup |
| `client/HatSelectionHandler` | Client-side programming UX (mirrors `ArmInteractionPointHandler`) |
| `client/WorkerGearLayer` | Hard hat + hi-vis vest render layer |
| `client/WorkerCargoLayer` | Visible cargo |
| `recipe/ClearProgramRecipe` | Crafting a hat by itself blanks its program, the way a Create filter clears |

## Things that will bite you

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
