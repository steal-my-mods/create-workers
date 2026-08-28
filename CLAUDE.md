# Create: Workers — repo guide

Create addon for **Minecraft 1.21.1 / NeoForge 21.1.219+ / Create 6.0+**. Hard-hatted villagers and
endermen haul items between inventories the way a Mechanical Arm does.

## Commands

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client
./gradlew runServer          # dev dedicated server (needs run/eula.txt)
./gradlew runGameTestServer  # automated in-world tests -- the real check
./gradlew publishMods        # upload to CurseForge and GitHub Releases
./gradlew publishMods -PdryRun=true   # ...or rehearse it without uploading anything
python3 tools/generate_logo.py         # the in-jar badge at 256
python3 tools/generate_logo.py branding/icon-512.png --size 512   # ...and the 512 CurseForge wants
python3 tools/generate_ponder_structure.py   # the Ponder scene's structure NBT
```

JDK 21 required. `gradle/gradle-daemon-jvm.properties` pins the daemon to it, so the commands work
without setting `JAVA_HOME` even when the default `java` is newer — don't delete that file, or
`./gradlew build` dies with "Could not create task ':test' ... Type T not present" on a newer JVM.
There is no unit-test suite;
correctness is covered by GameTests in `com.createworkers.test.WorkerGameTests`, and cost by
`com.createworkers.test.WorkerCostGameTests`. Run them after any change to worker behaviour, targets
or serialization — and after anything that touches what a search does per target, which the cost
tests bound. Both run under `runGameTestServer`; the cost ones log what they measured, so
`grep '\[cost\]'` over a run reads as a report.

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
- **The CurseForge token is checked with curl before anything is built.** `publishMods` uploads to two
  sites, and a missing or expired token fails at *upload* — by which point GitHub may already have
  accepted the release, leaving a version published on one site and not the other, with no way to
  rename or replace a file on either. A few seconds of curl against the upload API's cheapest
  authenticated GET turns that into a failure before anything has shipped anywhere. The status codes
  were measured against the real API rather than assumed: 200 valid, **400 malformed**, 401 absent.
  All three fail the release as a bad token; anything else fails it as "could not reach CurseForge",
  because a 502 is not a bad secret.
- **Running the release workflow by hand rehearses by default.** `workflow_dispatch` has a `dry_run`
  input defaulting to true, so a manual trigger runs the whole path — token check, build, tests,
  generator diff, changelog lookup — and writes what it *would* have uploaded instead of uploading it.
  A tag push always publishes for real. Without the default, a curious click on "Run workflow" from
  `dev` publishes whatever `mod_version` currently says, over a version already on CurseForge.
- **Both workflows re-run the generators and fail on a diff.** The badge and the Ponder structure are
  generated, so a stale checked-in file would ship in the jar with nothing to notice it. Regenerating
  has to be a no-op. The check stages first (`git add -A` then `git diff --cached`) because a bare
  `git diff` says nothing about a file a generator has newly created. Both generators are already
  byte-deterministic — the Ponder one writes with `mtime=0` for exactly this reason — so the gate was
  green the day it was added; it is there to keep it that way.
- **The `github` block sets `tagName` explicitly.** Without it the plugin invents its own tag from
  `mod_version`, so pushing `v0.2.0` produced a release filed under a second, bare `0.2.0` tag on the
  same commit. Both 0.1.0 and 0.2.0 shipped before this was noticed and still carry both tags; they
  are left alone, because deleting a tag a published release points at breaks its URL. From the next
  release there is one tag apiece.
- **`archivesName` carries the Minecraft version** (`createworkers-1.21.1-0.1.0.jar`). If you
  change it, remember neither site will let you rename a file after upload.
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
- **CurseForge and GitHub only — Modrinth is deliberately not a destination.** Modrinth's Content
  Rules gained a section 6 on generative AI in August 2026. Its disclosure requirement is no
  obstacle (tick "Contains AI-generated content" and move on), but **6.2 flatly bans project images
  "created or derived from generative AI output"** with no disclosure lane, and the badge icon is
  scaled up from `hard_hat.png`, whose pixels this mod's own tooling chose. CurseForge asks only
  that a *misleading* AI-modified showcase image carry a disclaimer, which a badge of the actual
  item is not. So the first release goes to CurseForge while that is still an open question. To
  restore Modrinth: redraw `hard_hat.png` by hand, uncomment `modrinth_project_id` (the project and
  slug are already reserved), re-add the `modrinth` block to `publishMods` **and** `MODRINTH_TOKEN`
  to `release.yml` — an empty token fails at upload, not at configuration, which half-publishes a
  release after CurseForge has already accepted the jar.

## Architecture landmarks

| Path | Role |
|---|---|
| `program/WorkerProgram` | The hat's inventory list. Data component; **absolute** positions (anchor `BlockPos.ZERO`) because workers move. Also owns the geometry: `centre()` (job site) and `firstTooFar`/`exceedsSpread` (the diameter rule) |
| `worker/target/WorkerTarget` | What a worker can use — a thin wrapper over Create's `ArmInteractionPoint`, holding the host so it stays out of upstream signatures |
| `worker/WorkerData` | Per-entity state (NeoForge attachment). Holds the port of `ArmBlockEntity`'s transfer algorithm |
| `worker/WorkerJobGoal` | Phase machine: search input → travel → collect → search output → travel → deposit. Also owns the stall clocks that stop a hopeless walk costing a pathfind a tick |
| `worker/WalkLocomotion` | Villagers. Also owns `returnTo`, the wander leash |
| `worker/TeleportLocomotion` | Endermen. Holds the teleport cooldown, so locomotion instances are **per-worker**, not shared |
| `worker/WorkerEvents` | Hiring, retiring, drops, client sync, cleanup, and the vetoes that stop vanilla's own enderman AI from undoing the job |
| `client/HatSelectionHandler` | Client-side programming UX (mirrors `ArmInteractionPointHandler`) |
| `client/WorkerGearLayer` | Hard hat + hi-vis vest render layer |
| `client/model/HardHatArmorModel` | The same hat geometry as a `HumanoidModel`, for the hat worn by a player |
| `client/HardHatClientExtensions` | Feeds that model to the armour renderer; re-baked on resource reload |
| `client/WorkerCargoLayer` | Visible cargo |
| `client/ponder/CWPonderPlugin` | Hands the scenes to Ponder. A scene is filed under an **item id**, which is what the "hold W" prompt keys off |
| `client/ponder/HardHatScene` | The hat's scene: programme, hire, haul, clock off |
| `client/ponder/WalkInstruction` | Moves an entity across a scene, which Ponder itself has no instruction for |
| `recipe/ClearProgramRecipe` | Crafting a hat by itself blanks its program, the way a Create filter clears |

## Things that will bite you

- **Never read a block for a target without checking that its chunk is loaded.** Create's
  `ArmInteractionPoint.isValid` refreshes its cached state with a plain `Level.getBlockState`, and on
  a server that *loads* — generating, if nobody has ever been there — whatever chunk the position is
  in. A worker rescans its whole programme once a second, so one target outside the loaded area is a
  chunk dragged in and dropped again for as long as the worker ticks; a worker in a force-loaded
  chunk with a target three chunks out does it forever, with nothing in the world to show why the
  server is busy. `WorkerTarget.isValid` therefore checks `isLoaded` first and reports unloaded as
  invalid, and `WorkerData.resolve` refuses to deserialize a point it cannot read. `isLoaded` is a
  separate method because the client needs the other answer: `HatSelectionHandler` must not forget a
  selection just because the chunk went out of view, or walking away and clicking a block would
  quietly shorten the programme it pushes back. `resolvingNeverLoadsAChunk` covers it, and was
  mutation-checked by dropping the guard — which generates the chunk 6000 blocks away.
- **A scan prices its outputs once, not once per input slot.** `WorkerData.usableOutputs` gathers the
  outputs a scan may deliver into, and `simulateInsertion` trusts that list rather than re-checking
  each point. Move the check back inside the loop and a block read — `ArmInteractionPoint.isValid`
  does a plain `Level.getBlockState`, and a belt point reads a second one above itself — lands on
  inputs × slots × outputs to learn an answer that only varies per output. The walk that costs this
  is not a rare one: an empty input is nearly free and a deliverable one returns on its first slot,
  so the full walk is exactly what a *backed-up* line pays, every rescan, for as long as it stays
  backed up. Gathering is safe only because nothing moves during a scan — every insertion priced
  against the list is simulated. `nothingIsCollectedWithNowhereToPutIt` guards the rule the gathering
  has to preserve, and was mutation-checked by dropping the set-aside clock from the filter.
- **`collectFrom`'s slot hint is an optimisation, never a precondition.** `searchForItem` finds an
  input *and* a slot; handing only the index back made `collectFrom` re-walk from slot zero,
  re-pricing every slot it passed against every output for one pickup. The slot now travels as a
  hint — but the worker walks between the two calls, so the amount is always re-checked and the full
  walk has to stay the thing that decides (`collectingWorksWithoutAScanToHintAt`, mutation-checked by
  deleting the fallback).
- **`WorkerStatePacket` must not carry the programme.** The hat's programme is a
  `networkSynchronized` component, so sending the hat stack whole put the entire point list — a
  couple of kilobytes on a full hat — on a packet that goes to every tracking client on every item
  moved, twice a second per worker. Nothing on that side reads it: the gear layer asks only whether
  the worker is employed, the cargo layer only what it is holding. `withoutProgram` strips it. Send
  the hat unstripped again and a base full of workers is a few hundred kilobytes a second of NBT the
  client already has on the item.
- **Resolution retries only what failed.** A point that would not resolve — chunk not loaded, block
  broken since — goes on `pending` and is tried again every `RESOLVE_RETRY_TICKS`; the ones that
  worked are never rebuilt, because rebuilding throws away a live `BlockCapabilityCache` per target.
  Without the retry a target missing at load time would be missing for the rest of the worker's life
  (`unresolvedTargetsAreRetried`).
- **Anything that pins a villager's `WALK_TARGET` has to be on a clock.** This is the mod's most
  expensive failure mode by a wide margin. `MoveToTargetSink` asks the navigation for a *fresh path*
  every time it is not already following one, and `PathNavigation.createPath` snapshots a
  `PathNavigationRegion` as wide as the mob's follow range (48 for a villager) and runs an A* over it.
  A destination that is pinned every tick and never arrived at cycles path → walk → done → path,
  which is a search every few ticks, forever, out of one mob that looks idle — a walled-off stop or a
  funnel nothing can stand beside is enough. So every walk in `WorkerJobGoal` — the haul, the idle
  rounds, the leash home — carries a `Progress` clock, and expiring sets the target aside
  (`SET_ASIDE_TICKS`) or stands the leash down (`LEASH_REST_TICKS`). `Progress` measures *being
  stuck*, not the length of the trip: it resets on any progress at all, because an amble across a
  forty-block beat outlasts any timeout worth having and writing that off would take a good
  inventory out of the scan.
- **Endermen do not make rounds** (`WorkerLocomotion.makesRounds`). Their `patrolTo` is a no-op, so a
  stop one was given is a stop it never arrives at — and the rounds give up on those by setting the
  target aside, so an idle enderman would work through its own programme writing off every inventory
  on it (`idleEndermenAreNotSentOnRounds`, mutation-checked).
- **The rounds' arrival radius must never be tighter than the working reach.** A stop a worker can
  *use* but can never be said to have *arrived* at would be walked at until the clock expired, and
  the write-off would then take a working target out of the scan. Hence `arrivedDistance()`.
- **Programme size is bounded in four places, and all four are load-bearing.** `maxTargets` in the
  config; the client refusing the click; the server refusing the packet; and `WorkerData.resolve`
  capping what it will resolve, because a creative-mode client can set an item component directly
  without going anywhere near `ConfigureHatPacket`. `WorkerProgram.MAX_BYTES` caps the NBT during
  *decode*, which is the only check that costs nothing. The order in `ConfigureHatPacket.handle`
  matters: the spread test is pairwise, so the length has to be vouched for before it runs, or a
  programme that fits inside the codec's own limit is hundreds of millions of comparisons on the
  server thread.
- **Range is a property of the programme, not of a position.** `maxTargetSpread` is a *diameter*:
  every pair of a hat's targets must be within it, checked in `HatSelectionHandler` as you click and
  re-checked server-side in `ConfigureHatPacket` (never trust the client). The **job site** is
  `WorkerProgram.centre()`, the middle of the target box — not where the player stood at hire time.
  It is derived, never persisted, and recomputed on deserialize. `resolvePoints` therefore filters
  nothing *by distance*: a target on the hat is a target. Don't reintroduce a silent range filter —
  the point of this shape is that a target is either refused as you assign it or honoured. (The two
  things resolution does hold back are not range filters and not silent: a point whose chunk it
  cannot read is deferred and retried, and a programme past `maxTargets` is truncated with a warning
  in the log.)
- **The `hireChildren` gate belongs on hiring, never on the job goal.** `WorkerEvents` gives the goal
  to every villager as it spawns, and `Workers.isOldEnoughToWork` is checked only where a hat changes
  hands. Move the check up into `onEntityJoinLevel` and a villager born as a child is one that can
  never work, because the goal is added at spawn and growing up adds nothing
  (`childVillagersAreNotHired` hires the same villager before and after).
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
  template is intentionally empty — tests lay their own floor with `layFloor`. Ponder's schematics
  are a different set of files under a different root: `assets/createworkers/ponder/<name>.nbt`,
  the path Ponder builds by hand as `ponder/%s.nbt`.
- **Ponder text does not fall back to the string in the code.** The English handed to `.text(...)`
  in a storyboard is only a default for a lang generator; with editing mode off,
  `PonderLocalization.getSpecific` goes straight to `I18n.get`, so a beat with no
  `createworkers.ponder.<scene>.text_<n>` key renders the key. The `n` is an incrementing counter
  over the `.text(` calls *in the order the storyboard makes them*, so inserting a beat in the
  middle silently shifts every line after it onto the wrong step. Nothing checks this; compare the
  calls against the lang file after touching either.
- **A ponder level reports itself as client-side, so a worker in a scene is a puppet.** No brain,
  no `serverAiStep`, nothing that would move it — hence `WalkInstruction`, which sets the position
  every tick. The facing and the leg swing then come for free out of `LivingEntity.tick`, which
  derives both from the distance between the entity and `xo/yo/zo` — but only if something
  refreshes those. `setOldPosAndRot` is the only thing that does and a ponder level never calls it
  (it snapshots `xOld/yOld/zOld`, which is a different set of fields, for the render
  interpolation), so left alone the measured step is "distance from where it spawned", growing all
  the way across the plate with the legs at a flat-out run from the second stride.
- **The ponder scene's plate is generated, not built in a creative world.**
  `tools/generate_ponder_structure.py` writes it. The two Depot positions live in both that script
  and `HardHatScene`, and nothing ties them together — move one and move the other.
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
- **An enderman fights the job on three fronts of its own, and none of them are goals.**
  `Goal.Flag.MOVE` buys nothing against any of them. `customServerAiStep` — which runs *after* the
  goals in `Mob.serverAiStep` — rolls a better-than-1-in-30 chance **every tick** that it is day and
  the sky is visible of blinking to a random point up to 32 blocks off, so it lands squarely on top
  of the hop the worker just made; the 600-tick grace period that normally holds that back keys off
  `targetChangeTime`, which `tickEmployed` resets to zero every tick by clearing the target, so a
  worker never gets it. Separately, `EndermanTakeBlockGoal` (1-in-20 per tick, whenever its hands are
  empty) digs up the floor the worker stands on and puts the stolen block exactly where
  `updateCargoAppearance` draws a block cargo, so the worker appears to be hauling dirt; and
  `EndermanLeaveBlockGoal` plants the cargo in the world while `WorkerData` still holds the item,
  minting a copy. All three are shut off in `WorkerEvents`: the teleports via
  `EntityTeleportEvent.EnderEntity` (which vanilla's teleports fire and
  `LivingEntity.randomTeleport`, the mod's own hop, does not), the two goals via the mob-griefing
  check they both gate on. Endermen only — an employed villager still farms.
- **A long hop has to be scored against the target, not against the waypoint.** `chooseLanding` aims
  at a point on the straight line one full teleport away, but that point hangs in mid-air, so the
  footing nearest *it* is as often behind the worker as ahead. `findWaypointSpot` therefore ranks
  candidates by their distance to the real target and seeds the search with the distance the worker
  has already covered, so a hop that would not close the gap is refused outright rather than taken
  and undone. `longHopsOnlyLandCloserToTheTarget` covers it, and was mutation-checked by scoring
  against the waypoint instead — which picks a landing spot back at the input depot.
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
- **Performance is asserted as a count, never as a duration.** `WorkerData` tallies what each search
  cost — `validityChecks()`, `slotProbes()`, `deliveryProbes()`, reset at the top of every search —
  and `WorkerCostGameTests` asserts bounds *derived from the size of the programme*, so they hold at
  any size and on any machine. A timing threshold cannot: loose enough for a loaded CI runner is
  loose enough to miss a tenfold regression. Keep the tally per instance, never static — a worker is
  owned by one entity on one thread, so there is nothing to synchronise and no flag to switch on —
  and count only simulated probes, since the one real extract or insert that ends a search is the
  work rather than the looking. Mutation-check every cost test: one that passes against the code it
  was written to condemn reads like cover. See `docs/multiplayer-performance.md` for the numbers.

## Design notes

`docs/` holds write-ups of features that were thought through but not built, including the reasoning
against building them. Read the relevant one before starting such a feature, and update it if the
thinking changes — the point is that the analysis is not redone from scratch.

- `docs/working-hours.md` — night shifts, designating a bed on the hat, and why the whole idea may be
  an annoyance
- `docs/multiplayer-performance.md` — what a worker costs a server per tick, where that was fixed,
  and the things a shared server still wants that this mod deliberately does not do

## Conventions

Tabs for indentation, matching Create's own style. Registry classes are `CW*` under `registry/`.
Nothing is committed without explicit instruction.
