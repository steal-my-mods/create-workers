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
python3 tools/generate_ponder_structure.py   # both Ponder scenes' structure NBT
python3 tools/generate_station_textures.py   # the Worker Station's block textures
python3 tools/generate_worker_profession.py  # the worker profession's clothing, both variants
```

JDK 21 required. `gradle/gradle-daemon-jvm.properties` pins the daemon to it, so the commands work
without setting `JAVA_HOME` even when the default `java` is newer — don't delete that file, or
`./gradlew build` dies with "Could not create task ':test' ... Type T not present" on a newer JVM.
There is no unit-test suite;
correctness is covered by GameTests in `com.createworkers.test.WorkerGameTests`, working hours by
`WorkerShiftGameTests`, and cost by `WorkerCostGameTests`. Run them after any change to worker
behaviour, targets or serialization — and after anything that touches what a search does per target,
which the cost tests bound. All run under `runGameTestServer`; the cost ones log what they measured,
so `grep '\[cost\]'` over a run reads as a report.

**A test that changes the time of day, or the config, belongs in a batch of its own.** Both are one
value for the whole server, and game test batches are the only isolation there is — the framework
runs them strictly one after another, while the tests *inside* one run side by side. `@BeforeBatch`
sets the world up for the batch and `@AfterBatch` puts it back for whatever runs next. The same
parallelism is why a test about the bed hunt may not assert "no bed was found": the tests running
beside it have laid out real beds in the same world, well within a worker's search radius. Name the
bed that must not be chosen.

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
| `program/WorkerProgram` | The hat's inventory list, plus the bed under `Bed`. Data component; **absolute** positions (anchor `BlockPos.ZERO`) because workers move. Also owns the geometry: `centre()` (job site, inventories only) and `firstTooFar`/`exceedsSpread` over `allPositions()` (the diameter rule, bed included) |
| `worker/target/WorkerTarget` | What a worker can use — a thin wrapper over Create's `ArmInteractionPoint`, holding the host so it stays out of upstream signatures |
| `worker/WorkerData` | Per-entity state (NeoForge attachment). Holds the port of `ArmBlockEntity`'s transfer algorithm |
| `worker/WorkerJobGoal` | Phase machine: search input → travel → collect → search output → travel → deposit. Also owns the stall clocks that stop a hopeless walk costing a pathfind a tick, and the night: `clockOff` → `goToBed` → `turnIn` → `clockOn` |
| `worker/WorkerShift` | Working hours. Both clocks (the operator's and the village's), the bed hunt, and what makes a bed usable |
| `worker/Shift` | Which crew a worker is on. **An offset into the configured working day, not a pair of times** — one third of a day per crew, so the span stays the operator's single choice |
| `worker/WalkLocomotion` | Villagers. Also owns `returnTo`, the wander leash |
| `worker/TeleportLocomotion` | Endermen. Holds the teleport cooldown, so locomotion instances are **per-worker**, not shared |
| `worker/WorkerEvents` | Hiring, retiring, drops, conversion, client sync, cleanup, and the vetoes that stop vanilla's own enderman AI from undoing the job |
| `client/HatSelectionHandler` | Client-side programming UX (mirrors `ArmInteractionPointHandler`) |
| `client/WorkerGearLayer` | Hard hat + hi-vis vest render layer |
| `client/model/WorkerGearModels` | Builds that gear **fitted to the model that will wear it** — `fitTo` measures a head and torso and is what makes the gear work on a modded villager |
| `client/model/HardHatArmorModel` | The same hat geometry as a `HumanoidModel`, for the hat worn by a player |
| `client/HardHatClientExtensions` | Feeds that model to the armour renderer; re-baked on resource reload |
| `client/WorkerCargoLayer` | Visible cargo — in the hand when the model has one, against the chest when it does not |
| `client/ponder/CWPonderPlugin` | Hands the scenes to Ponder. A scene is filed under an **item id**, which is what the "hold W" prompt keys off; both scenes are filed under the hat, so they are consecutive pages |
| `client/ponder/HardHatScene` | The hat's first scene: programme, hire, haul, clock off |
| `client/ponder/WorkingHoursScene` | The second: last delivery of the day, walk to bed, sleep, the enderman night shift, morning |
| `client/ponder/WalkInstruction` | Moves an entity across a scene, which Ponder itself has no instruction for |
| `registry/CWProfessions` | The `createworkers:worker` villager profession a hired villager holds instead of its own. Its job-site predicates match the worker station **and nothing else** |
| `block/WorkerStationBlock` | The block that hires. `HAS_JOB` is what the point of interest is registered over |
| `block/WorkerStationBlockEntity` | A line's roster: an ordered rack of hats, the shifts each runs on, who is wearing them, and the point-of-interest tickets it holds back |
| `block/WorkerStationMenu` | The rack as real slots, over Create's `MenuBase`. Its geometry constants are shared with the screen, because slots are placed before any screen exists |
| `client/WorkerStationScreen` | The rack arranged: shift toggles, order arrows, staffing readout. Reads the block entity, never its own copy |
| `net/StationRosterPacket` | The two edits that are not an item — which shifts a job runs, and where it sits |
| `net/StationRenamePacket` | Naming a job, on its hat's `CUSTOM_NAME`, with no anvil and no experience |
| `registry/CWMenuTypes` | Screens this mod opens. **Reads the open packet's buffer itself**, because `MenuBase` cannot |
| `registry/CWCapabilities` | What other machines can reach into: the station's rack, and nothing else |
| `registry/CWPoiTypes` | The station as a village workstation. `maxTickets` is the largest roster the mod allows, because it belongs to the *type*; the block holds back the difference |
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

  **The bed is inside that rule and outside the job site, and the asymmetry is deliberate.** It is one
  more place the worker walks, so `exceedsSpread` and `within` measure `allPositions()` — leave it out
  and the commute becomes the one unbounded trip in a programme built to bound them. But `centre()`
  reads `positions()`, because the job site is where the *work* is: a bed that moved it would drag an
  on-shift worker's anchor, and the leash hanging off it, towards the bedroom. It is also not a stop
  on the idle rounds and never an inventory — `size()` and `hasTargets()` count inventories, and a hat
  carrying nothing but a bed cannot be assigned.
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
  `final` and runs goals *before* the brain, so a goal setting the memory is seen the same tick. That
  ordering cuts both ways: a villager hurt earlier in the same tick's entity loop has already had
  `stopSleeping` called on it while its brain still reads as `REST`, which is why
  `WorkerShift.isBedtime` refuses a `LAST_WOKEN` of *this* tick rather than only an older one.
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
- **The ponder scenes' plates are generated, not built in a creative world.**
  `tools/generate_ponder_structure.py` writes both of them, from one shared `yard()` so the two
  scenes are visibly the same place. Every position in them lives twice — in that script and in the
  scene class — with nothing tying the two together, so move one and move the other.
  **The bed is the only thing in either plate with block-state properties**, and a palette entry
  written without them is two bed *feet*: a bed with no head is no `home` point of interest, and it
  erases itself the moment anything updates it. That is invisible until a player opens Ponder, so
  `theWorkingHoursPlateHasABedInIt` parses the file with Minecraft's own `StructureTemplate` and
  checks the halves — the only headless check there is on a scene, since Ponder itself does not load
  on a dedicated server. Mutation-checked by dropping the properties from the palette.
- **Armour is not just a texture on a head box.** A helmet normally renders as the vanilla head
  geometry with the armour sheet stretched over it, which looks like a painted scalp.
  `HardHatArmorModel` swaps in the real hat cubes via `IClientItemExtensions.getHumanoidArmorModel`.
  The trap is vanilla's `hat` part: a *second* head-sized box, a **sibling** of `head` rather than a
  child, which `HumanoidArmorLayer.setPartVisibility` turns on for anything in the head slot (and
  `ClientHooks.copyModelProperties` copies that visibility onto the replacement). Leave it populated
  and it draws a solid cube over the whole skull regardless of what `head` contains — so
  `createLayer` replaces it with an empty `CubeListBuilder`. Its texture sheet therefore uses the
  gear UV layout at 128x64, *not* the 64x32 humanoid armour layout.
- **The gear goes on by renderer, never by entity type.** A worker is any `Villager` or `EnderMan`,
  and a mod that re-skins villagers does it by registering *its own entity type* with its own
  renderer: Villagers Reborn replaces every villager in the world with a `Villager` subclass of its
  own, so hiring, hauling and retiring all work — every functional check in the mod is an
  `instanceof` — while the hat and vest silently never appear, because `AddLayers` was attaching them
  to the renderer for `EntityType.VILLAGER` and nothing was using it (issue #1). `CWClient` therefore
  offers the gear to *every* `LivingEntityRenderer` and lets `WorkerGearModels.fitTo` decide, which
  costs nothing on the rest: both layers bail out on their first line for an entity with no worker
  state, and only villagers and endermen are ever given any. What this cannot reach is a renderer
  that is not a `LivingEntityRenderer` — GeckoLib's is not, and draws bones rather than a
  `ModelPart` tree — so a mod rendering its villagers through one would need separate support.
- **Gear geometry has to clear what is already drawn underneath, and is fitted rather than
  hand-written.** Villagers wear a `jacket` overlay (body inflated 0.5), so a vest inflated by that
  same 0.5 lands exactly on it and z-fights. `fitTo` reads three things off the model that will wear
  the gear — where the top of the head is, how wide and deep the torso is, and whether anything is
  already drawn over either — and reproduces the villager's hand-written 1.0 and the enderman's 0.5
  exactly. Declare boxes at whole-number sizes and grow them with `CubeDeformation` so UVs stay on
  exact texels; that is also what makes fitting possible, since a deformation moves geometry without
  touching a UV.
- **An overlay can be detected but never measured, and it is detected by its footprint.** The
  thickness of a `jacket` or a hair layer is a `CubeDeformation`, which is baked into the cube's
  *vertices* — `ModelPart.Cube`'s own `minX..maxZ` are the nominal box and read the same whatever the
  inflation, and nothing public hands the deformation back. So `fitTo` looks for an x/z footprint
  drawn twice and then assumes vanilla's 0.5 outer layer. It must be the footprint and not the whole
  box: a villager's jacket is a 20-unit robe over a 12-unit torso, so comparing boxes misses it and
  dresses every villager in a vest half a unit too small (which is exactly what the first draft
  did). The corollary is that a model carrying its bulk in a deformation rather than in its boxes is
  fitted to the boxes.
- **The vest's declared depth is the one measurement the texture has an opinion about.** A box's UV
  footprint is a function of its size, so an arbitrary torso depth cannot be declared directly: the
  vest is declared at whichever of the sheet's two vest regions (6 deep at 0,28 and 4 at 32,28) is
  nearer the measured torso and deformed the rest of the way, which keeps both vanilla shapes on
  their own exact texels. Add a region and it has to be drawn on `worker_gear.png` first.
- **A fit change wants checking against baked vertices, not against `Cube`'s extents.** There is no
  client-side test suite — GameTests run on a dedicated server, where these classes do not load — but
  the model builders are pure data and run in a plain JVM off the mod's own runtime classpath, so a
  throwaway `main` can bake vanilla's `VillagerModel`/`EndermanModel`, run `fitTo` over them and
  compare. Compare *vertex positions* (reflect into `Cube.polygons`) or the check is blind to
  deformation and will happily pass a vest that is half a unit out.
- Vanilla renders villager professions as *texture overlays re-rendered over the same mesh*
  (`VillagerProfessionLayer` → `renderColoredCutoutModel`), not as extra geometry — worth knowing if
  the vest ever needs to hug the robe rather than sit over it.
- **Villagers are hired by a station, and only by a station.** The right-click hire and the
  sneak-click retire are the enderman's path now — it has no profession and no point of interest, so
  no job board could ever employ one. Firing a villager is taking the hat out of its station or
  breaking the block. That one restriction is what deleted `clearVillageJob`, `restoreVillageJob`,
  `refreshBrain`, the stashed `VillagerData` and `MerchantOffers`, and `RESET_PROOF_LEVEL`: a station
  only ever hires a villager that had *no* profession, because `AssignProfessionFromJobSite` refuses
  to convert anything else, so there is no village job to take, preserve or give back.
  **The corollary is that an employed villager can never become a worker** — break its workstation
  first, exactly as vanilla makes you.
- **A station tells a dead worker from an unloaded one by the POI ticket, never by an entity lookup.**
  `ServerLevel.getEntity` finds only *loaded* entities, so a worker that walked into a chunk nobody is
  standing in reads as gone — and a station that believed it would hire a second villager onto the
  same job. `Villager` releases its tickets on death and on conversion and **not on unloading**, and
  the ticket is saved with the chunk section, so `getFreeTickets(stationPos) == 0` is the honest
  answer and survives the chunk going away.
- **The absentee signal is proximity, and it cannot be anything else.** `WorkerData.lastAtWork` is
  stamped in `keepNearPost` only when the worker is *near* its own work — before the early return, so
  that having a target selected does not count. A worker cycling through targets it can never reach
  has one selected every tick, so anything keyed off "is it busy" shows it hard at work from the
  bottom of a hole; `leashFailures` is no good for the same reason, since it resets whenever a target
  is picked. The clock is re-stamped on **hire and on every load**, because it freezes while a chunk
  is away and a worker returning after an hour of game time is not an absentee.
  (`aWorkerThatStopsTurningUpLosesTheJob`, mutation-checked both ways: by never sacking, and by
  stamping the clock regardless of position.)
- **Sacking an absentee has to release the POI ticket by hand.** Unlike a death or a hat being taken
  out, the villager is alive and still holding the station as its `JOB_SITE`, so the one ticket would
  stay taken and nobody could ever replace it — which is the exact failure the block exists to end.
- **Nothing puts a fired worker's profession back, because vanilla does.** Losing the station loses
  the job site, and `ResetProfession` clears the profession of a villager with no job site that has
  never traded and is still on trade level one — which a worker now always is, nothing having raised
  it. The `refreshBrain` that comes with it is also what restores the village's schedule. If that ever
  stopped holding, a fired worker would be stuck as a Worker for good, its only workstation being a
  block it no longer has. `aSackedWorkerIsTidiedUpByVanilla` is a test on vanilla's behaviour for
  exactly that reason.
- **Children are refused by vanilla, not by us.** The job-site `AcquirePoi` in the villager CORE
  package is built with `onlyIfAdult`, so a baby never acquires a station and never arrives at one.
  The mod's own `isOldEnoughToWork` check and the `hireChildren` config that went with it are deleted:
  a dial that cannot change anything is worse than no dial. `aChildNeverTakesTheJob` pins the
  guarantee that replaced them.
- **(Historic) Hiring took the villager's village job, and the order of the two steps was
  load-bearing.** None of the following runs any more — see `docs/professions.md` — but it is what the
  station route was designed to avoid, and it comes straight back if hand-hiring ever does.
  `Workers.clearVillageJob` releases the workstation *before* changing the profession, because
  `Villager.releasePoi` gates the release on `Villager.POI_MEMORIES`, whose `JOB_SITE` predicate is
  the villager's **current** profession's `heldJobSite`. Change the profession first and that
  predicate matches nothing, the release silently does nothing, and the composter stays ticketed to a
  worker that can never use it for the rest of the world's life, with nothing in the world to show
  why (`hiringHandsTheWorkstationBack`, mutation-checked by swapping the two). `releasePoi` also does
  not erase the memory — that is done by hand, since `WorkAtPoi` asks only that the villager be
  within 1.73 blocks of the site, not that it be allowed to walk there. Restoring reverses the
  order for the mirror-image reason: `setVillagerData` nulls the trade list whenever the profession
  changes, so the offers go back *after* the profession or they are lost on the way in.
- **Where the cargo is drawn is a question about the model, not about the mob.** A vanilla villager's
  arms are one merged part in a fixed pose with no hands in it, so its cargo is held against the
  chest; a model that is an `ArmedModel` gets it in the hand through the same sequence vanilla's
  `ItemInHandLayer` uses, which is how a mod that draws villagers as humanoids gets hands without
  this mod having heard of it. `ArmedModel` alone is not the test, though: `EndermanModel` is one
  too, and its arms are thirty units long against a twelve-unit body, so its hands hang by its
  ankles and an item in one reads as dropped. `WorkerGearModels.handsOf` therefore measures — the
  hand may fall no more than half a torso below the torso — and returns the typed model or null, so
  the decision is made once rather than tested again at the call site. The size comes from the item
  model's own third-person transform, not from a figure chosen here: a block in hand is 0.375 of a
  block against the 0.5 vanilla's `CarriedBlockLayer` gives an enderman, and against the 0.1875 the
  chest carry works out at.
- **The worker profession's overlay is blank, and the file still has to exist.** It painted hi-vis
  cuffs and a hi-vis hem — the sleeves and the robe are the only parts the hat and vest do not cover,
  so they looked like free space. They are not: orange at the hands and feet reads as a costume rather
  than as safety gear, and the vest is the thing a worker should be recognised by. Per-shift colour, if
  it is ever wanted, belongs on the vest geometry. The generator still writes both sheets because a
  profession with no texture renders as missing texture, per renderer.
- **A profession needs a clothing overlay for every renderer that looks one up.** Vanilla ships a
  full set under both `villager/profession/` and `zombie_villager/profession/`, so a modded
  profession that ships only the first renders as missing texture the moment a worker is bitten — a
  converted villager keeps its `VillagerData`. `tools/generate_worker_profession.py` writes both; the
  two layouts share the robe's texels and differ only in the sleeve's height (8 against 12). The hat
  region is left transparent on purpose: a profession texture is what draws a farmer's straw hat, and
  a worker wears a hard hat. The **name** comes from a key with our namespace inside it —
  `entity.minecraft.villager.createworkers.worker` — because NeoForge patches `Villager.getTypeName`
  to insert the profession's namespace for anything outside `minecraft`. The obvious
  `entity.minecraft.villager.worker` is never looked up and renders as the raw key.
- **The hat's clearance goes on the crown and nowhere else.** The crown is the only box sunk into the
  head, so it is the only one with anything to clear — and growing the others pushes the peak into
  the rim, which share a plane at `z = -5`. Two overlapping coplanar faces of one render type
  stipple against each other, which is a worse artefact than the hairline z-fighting being fixed.
  `FitCheck` asserts no two boxes of the hat overlap while sharing a face plane, and that was
  mutation-checked by putting the clearance back on the rim and the peak.
- **Changing a villager's profession without `refreshBrain` leaves the old job's brain behind, and
  a custom profession has to dodge `ResetProfession`.** Two separate traps, both in
  `Workers.clearVillageJob`, both mutation-checked. `Villager.registerBrainGoals` bakes
  `AcquirePoi(profession.acquirableJobSite(), …)` into the CORE package once, so a villager whose
  profession changed underneath it keeps hunting the workstations of the job it no longer has — it
  re-tickets the composter that was just handed back, within a few tens of ticks, and pathfinds over
  its follow range looking for more. Vanilla pairs every profession change with `refreshBrain`
  (`ResetProfession` and `AssignProfessionFromJobSite` both do); so must we, on hiring *and* on
  retiring, or a retired villager keeps the worker's match-nothing predicate and can never find a
  workstation again. Separately, `ResetProfession` (CORE, priority 10) wipes any profession but
  `NONE` and `NITWIT` — named literally — off a villager with no job site that has never traded and
  is still level 1, which is every worker by design; it would reset ours to `NONE` within a tick or
  two. Occupying `JOB_SITE` cannot save it, because `ValidateNearbyPoi` at priority 0 erases a job
  site the profession does not claim before `ResetProfession` reads it in the same tick. What is
  left is the trade level, held at 2 while employed and restored from the stash on retirement.
  **A test that asserts on the tick of the hire sees none of this** — both hire tests idle 100 ticks
  and re-assert.
- **Conversion is not death, and a worker has to clock off for it.** A villager bitten by a zombie
  or hit by lightning is replaced by `Mob.convertTo`, which discards the original — no
  `LivingDeathEvent`, no `LivingDropsEvent`, so the hat would simply cease to exist. And the
  profession outlives the attachment where the hat does not: `Zombie.killedEntity` copies
  `VillagerData` onto the zombie villager and curing copies it back, so a bitten-and-cured worker
  would come back holding a profession with no employment behind it and no way to ever take a
  village job. `WorkerEvents.onLivingConversion` retires it on `LivingConversionEvent.Pre`, which is
  fired from the conversion's own check *before* the replacement is built — the only point where the
  villager is still whole enough to drop its hat and have its village job put back
  (`aBittenWorkerClocksOffFirst`, mutation-checked by dropping the handler and by dropping the
  restore). The event is not cancelled; becoming a zombie is the villager's business.
- **Never clear a worker's profession to `NONE`.** It looks equivalent to the worker profession and
  is strictly worse than doing nothing: `NONE` is registered with `ALL_ACQUIRABLE_JOBS`, and
  `AcquirePoi` takes a workstation's ticket the moment a path to it merely *exists* — arriving is not
  required, and a worker never arrives anywhere its programme did not send it, because the job goal
  pins `WALK_TARGET` every tick. `CWProfessions.WORKER` matches nothing with either predicate, which
  is what `workersNeverClaimAWorkstation` asserts. `docs/professions.md` has the rest, including why
  a profession of our own rather than vanilla's `NITWIT` (an unknown profession id is parsed
  leniently and degrades to an unemployed villager if the mod is removed; a nitwit stays a nitwit
  forever) and why it is named for the role rather than for hauling (a profession id is permanent
  save state, so a rename strands every worker in every world).
- **A station does its own hiring, and it has to. `YieldJobSite` makes vanilla's route impossible
  here.** The station used to sit in `minecraft:acquirable_job_site` and let an unemployed villager
  find it, claim it and walk over, with `AssignProfessionFromJobSite` doing the rest — the lectern
  route, and the thing the whole design was proud of. It cannot work for this block.
  `YieldJobSite` (villager CORE, **priority 8**) runs on any villager holding a `POTENTIAL_JOB_SITE`,
  looks for another villager nearby whose profession's `heldJobSite` matches that POI, and makes the
  applicant **give up its claim** — erasing its own potential job site, walk target and look target. A
  worker this station has already hired is exactly that other villager. So the first villager was
  hired and every one after it walked over, yielded and stood about; and the dropped claim **leaks a
  ticket**, because erasing the memory does not release one. The rule is right for vanilla, where a
  workstation holds one villager. A station holds up to thirty-six.
  So the tag membership is gone and `WorkerStationBlockEntity.recruit` finds an unemployed adult
  within `RECRUIT_RANGE`, path-verifies it exactly as `AcquirePoi` would, and does what
  `AssignProfessionFromJobSite` did — profession, then `refreshBrain`, then the `JOB_SITE` memory, in
  that order. What is still vanilla's is everything that kept working: the profession itself, the job
  site that keeps `ResetProfession` off a worker's back, and the tickets that say who is still alive.
  `aSecondVillagerTakesAJobAtAnOccupiedStation` is deliberately end to end with real villagers,
  because what it is really asserting is that nothing in the brain gets a veto over a second hire.
  **`PoiCompetitorScan` (CORE 2) is the same shape of hazard** and is avoided for the same reason:
  nothing gives a worker a `POTENTIAL_JOB_SITE` any more.
- **An empty station must not be a job site, and `HAS_JOB` is how.** The POI is registered only over
  the states with a hat in them. Register it over all of them and a villager crosses a village, is
  turned into a Worker on arrival, finds nothing to do — and can then never take another job, because
  a Worker's only workstation is the block it is standing at. There is no `ResetProfession` escape
  either: that needs `absent(JOB_SITE)`, and it is holding one.
  (`onlyAStationWithAJobInItIsAJobSite`, mutation-checked.)
- **A station worker's hat stays in the block and it must never drop one.** The worker wears a copy,
  which is the whole self-healing property — nothing is handed back when it dies, because the job
  never left. `WorkerData.dismiss` therefore withholds the hat whenever `station` is set, and
  `theJobOutlivesTheWorker` asserts no hat entity appears; without that guard every death mints a
  second hat. The station is forgotten when the block is broken or the hat taken out, after which the
  worker is indistinguishable from a hand-hired one and drops its hat as usual.
- **Sleeping and waking must agree with vanilla's `WakeUp`, and a worker's own `Schedule` is how.**
  `WakeUp` (villager CORE, priority 0) stands up any sleeping villager whose brain is not in
  `Activity.REST`, on every tick — so a worker whose hours are not the village's could never sleep.
  `WorkerShift.applySchedule` settles it by handing each worker a two-state `Schedule` of its own
  whose `REST` window *is* its off-shift hours, which makes the two agree by construction rather than
  by coincidence. `isBedtime` is unchanged by that; what changed is the schedule it reads.
  **Three things about it are load-bearing.** It must be applied *after* `refreshBrain`, which
  rebuilds the brain and sets `VILLAGER_DEFAULT` — apply it before and it is thrown away a line later
  (`hiringGivesAWorkerItsOwnScheduleAndRetiringTakesItBack`, mutation-checked by swapping the two).
  It must be re-applied on **every load**, because a brain's codec carries memories and
  `registerBrainGoals` sets the village's schedule on every construction
  (`aWorkerGetsItsScheduleBackWhenItLoads`, mutation-checked by dropping the join handler). And
  retirement needs nothing, because `refreshBrain` puts the village's own schedule back.
  `Timeline.getValueAt` is a **step** function, not an interpolation — it returns the last keyframe at
  or before the time and wraps to the final one before the first, which is how a two-transition
  schedule crosses midnight and why there are no ties to resolve.
- **A sleeping worker still has to be pinned.** `LivingEntity.isImmobile` is `isDeadOrDying()` for
  everything but a player, so goals, the brain and the navigation all keep running on a sleeping
  villager — and `Villager.startSleeping` *erases* `WALK_TARGET`, which is exactly the memory the
  brain's own bed-hunting behaviours need absent before they will walk it somewhere. `goToBed`
  therefore keeps holding the sleeping position. It costs nothing: `MoveToTargetSink` erases a walk
  target it has already arrived at without ever asking for a path, which is the same reason
  `holdStation` is free.
- **A sleeper that vanishes leaves the bed `OCCUPIED` forever.** Dying wakes the entity on its own
  (`LivingEntity.die`), but being *replaced* does not — a worker bitten in its sleep would leave a bed
  no villager could ever use again, with nothing in the world to show why. Hence `Workers.wake` on
  the conversion and retire paths.
- **A bed's point of interest is its head, and only its head — but a bed is two blocks.**
  `PoiTypes.HOME` matches bed states with `PART == HEAD`, so a foot-end position is a bed no search
  will ever match and no villager will recognise as theirs, and `WorkerShift.bedHead` normalises a
  click exactly as vanilla does when a player clicks the foot of one. Everything mechanical therefore
  stores the head alone. **Anything a player looks at has to undo that**, because to them a bed is one
  object two blocks long: the assignment outline drew a box around the stored position and so drew a
  box around half a bed. `WorkerShift.otherHalfOfBed` pairs them up — `FACING` runs foot-to-head, so
  each half steps along it in the opposite direction, which is vanilla's private
  `getNeighbourDirection` restated. `bothHalvesOfABedKnowAboutEachOther` covers it, and was
  mutation-checked by stepping both halves the same way.
- **The bed hunt is paced, and it has to be.** It is a point-of-interest query over every section in
  `bedSearchRadius` and then an A* across the candidates, and the worker that wants it most is the one
  that will never find it. Unpaced that is a pathfind per sleepless worker per tick for the length of
  a night; `aWorkerWithNowhereToSleepDoesNotHuntForABedEveryTick` bounds it as a rate and was
  mutation-checked by removing the interval, which measured exactly 400 hunts in 400 ticks.
- **The leash always retried; what it never did was say so.** `walkHome` counts `leashRest` down and
  tries again, indefinitely — a worker that *can* get home does, unaided, and nothing here ever gave
  up permanently. The two things it lacked are now in: the rest **grows** with consecutive failures
  (`restAfter`, capped, so a permanently walled-in worker settles at a few per cent of a tick rather
  than pathfinding for 200 ticks out of every 800 for the rest of the world's life), and a worker past
  `LEASH_LOST_AFTER` **broadcasts vanilla's unhappy-villager particles** on a slow clock, which is the
  only symptom a stuck worker has ever had. `recallStuckWorkers` teleports one home and is off by
  default, because a villager appearing out of thin air is not something this mod does anywhere else.
  **The count lives on `WorkerData`, not on the goal**, because it is a fact about the villager and
  the only externally visible sign that one is lost.
- **A worker with something to do never looks lost, and that is deliberate.** `keepNearPost` clears
  the leash the moment `getTargetPoint()` is non-null, so the failure count only accumulates while a
  worker is genuinely idle *and* off station. A worker sealed in a box with a stocked depot spends its
  time failing to reach the depot instead, which is the target set-aside clock's business, not the
  leash's — it only starts counting once every target has been set aside too. Worth knowing before
  building anything else on the count: **a test that stocks the site is testing the wrong clock**, and
  anything wanting "has this worker been useless lately" needs more than `leashFailures()` alone.
- **The wander leash does not run off shift.** A bed is inside the programme's spread but need not be
  inside `wanderRadius` of anything, so leaving the leash on would have it hauling the worker back off
  its own commute. Nothing is needed to undo that at dawn — the leash resumes and walks the worker
  home from the bed by itself.
- **Walking home stops closer than walking to work.** `approach` gets within `reachDistance`, which is
  configurable up to six blocks; the arrival test at the bed is vanilla's two. A commute that used
  `approach` is a worker standing in the doorway all night, never quite home, and then a stall clock
  writing the bed off — hence `WorkerLocomotion.commuteTo`.
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
- **`maxTickets` belongs to the point-of-interest *type*, so a station holds back its own surplus.**
  Every station is registered with room for the largest roster the mod allows (`MAX_SLOTS × 3`, which
  is why `MAX_SLOTS` is a constant and the config can only cap *below* it). Left alone, a station with
  one job would have three dozen villagers cross the village to be turned away — each of them made a
  Worker by `AssignProfessionFromJobSite` on arrival and un-made by `ResetProfession` a tick later.
  `reconcileTickets` therefore aims at one invariant, **free tickets equal vacancies**, taking tickets
  in the station's own name and releasing them as openings appear. That makes "free tickets" mean
  "openings", after which vanilla's `AcquirePoi` does the enforcing exactly as it does for one
  librarian per lectern. Aiming at an invariant rather than reacting to events is what makes it repair
  itself after a load, a config change or a death the station was not loaded to see. `reserved` is
  persisted because the tickets are — a `PoiRecord` saves its free count with the chunk section — and
  reset to zero the moment a rack goes from empty to occupied, because an empty station is not a job
  site at all and the record it is about to get starts full. `aStationAdvertisesOnlyTheOpeningsItHas`
  bounds it, mutation-checked by dropping the reconciliation, which advertised 36 openings for 4 jobs.
- **`HAS_JOB` must mean "has a hat", never "has a vacancy".** It is the property the point of interest
  is registered over, so a state change that leaves the set destroys the `PoiRecord` — which releases
  every ticket the station's *living* workers hold and then has `ValidateNearbyPoi` erase their
  `JOB_SITE` memories, after which `ResetProfession` clears the profession off a whole crew. A fully
  staffed station dropping out of the POI set is therefore the one thing that must not happen, and the
  vacancy count is expressed in tickets precisely so that it never has to touch the block state.
- **A station cannot tell an unloaded worker from a dead one, and the ticket count only answers in
  aggregate.** `getEntity(uuid)` finds loaded entities only, so a station that read "not found" as
  "gone" would hire a second villager onto a job somebody is already doing. Tickets are the honest
  signal — released by `Villager.die` (which zombie conversion goes through) and by `thunderHit`, never
  on unloading — but a `PoiRecord` is a counter, so it can say *how many* of the workers we cannot see
  have gone and never *which*. `auditRoster` strikes off the unaccounted-for from the bottom of the
  fill order, and the other half of the deal is `Workers.verifyEmployment`: **every worker asks its
  station, on every load, whether it is still on the books**, and sacks itself if not. Without that a
  worker that was only unloaded comes back doing a job the station has given to somebody else, with
  nothing anywhere that knows. A station whose chunk is away answers nothing and the worker is left
  alone; only a station that is there and says no counts as a no.
  (`aWorkerStruckOffTheRosterSacksItselfWhenItLoads`, mutation-checked by dropping the call.)
- **Fill order is shift-major, slot-minor, and that is the whole reason a station holds a line rather
  than a job.** Workers in a factory are a chain: take one out and the one before it fills a depot that
  never drains and then stops entirely, so a shift missing a worker usually produces *nothing* rather
  than less. Concentrating a short crew on one shift is therefore a correctness rule, not a
  preference — and a rule about several jobs at once has to live somewhere that can see several jobs at
  once. One station with N slots loops over its own roster; N single-slot stations could only
  coordinate by talking to each other, which is a distributed problem invented to avoid a list. Slot
  order breaks ties within a shift, which is what makes the order of the rack the player's priority
  lever. (`shortCrewsFillWholeShiftsBeforeDeepOnes`, mutation-checked by swapping the loops.)
- **`MenuBase` calls `createOnClient` before it has assigned a single one of its fields.** Its buffer
  constructor is `super(type, id); init(inventory, createOnClient(buf))` — so `player`,
  `playerInventory` and `contentHolder` are all still null inside it, and a `createOnClient` that reads
  `player.level()` to find its block **crashes the client on the tick the screen opens**, which is how
  the first version of the station screen shipped. Create's own menus answer this by reaching for
  `Minecraft.getInstance().level`, and **that is worse, not better**: it loads a client class from a
  class a dedicated server also loads, which NeoForge refuses outright ("Attempted to load class
  net/minecraft/client/multiplayer/ClientLevel for invalid dist DEDICATED_SERVER"). The buffer is
  therefore read in `CWMenuTypes`' factory, which is handed the `Inventory` — and an inventory has a
  player, and a player has a level. `createOnClient` returns null and is never called.
  `theStationMenuIsBuiltOverTheRack` is what found the second bug; nothing can find the first but a
  running client.
- **`MenuBase.stillValid` returns `true` unconditionally** unless its content holder implements
  Create's `IInteractionChecker`. So a menu over a block entity that does not implement it never closes
  when the player walks away, and any packet that gates on `stillValid` — `StationRosterPacket` does,
  and what it guards is hiring and firing villagers — is gating on nothing at all. Mutation-checked by
  dropping the interface, which leaves a player forty blocks off still able to rearrange the rack.
- **A menu's slots are placed once, before any screen exists, and a `Slot`'s position is final.** Both
  halves bite. The menu is built on the server *and* on the client from the payload, so anything its
  layout depends on must be true on both sides at that moment — which is why the rack's `getSlots()`
  returns the hard `MAX_SLOTS` and never the configured capacity, the config being a server setting
  that merely usually reaches a client in time. And since `Slot.x`/`y` are final in 1.21, a window that
  grew and shrank with the number of jobs would mean rebuilding the menu every time a hat moved — so
  every place is drawn whether or not it holds a job, and **twelve of them go in two columns of six**.
  One column of twelve made a panel 342 pixels tall, which fits nobody's screen at a GUI scale anybody
  chooses. A rack read down one column and then down the other is still a rack.
- **A layout is testable even though a screen is not.** Client classes do not load on a dedicated
  server, so nothing can render `WorkerStationScreen` — but a menu's slot positions are ordinary
  arithmetic in a common class, and a window that does not fit shows up there first.
  `theStationScreenLaysOutInsideItsPanel` asserts every slot is inside the panel, that no two share a
  position, that the second column starts level with the first, and that the panel is short enough to
  fit a screen. Mutation-checked by putting the rack back in one column, which it catches as a slot
  hanging out of the bottom. Write geometry into the *menu*, never into the screen, so it stays
  reachable.
- **The screen reads the block entity, not a copy threaded through the menu.** `WorkerStationBlockEntity`
  overrides `getUpdateTag`/`getUpdatePacket` and sends itself whole whenever its rack changes, so shift
  toggles, who is wearing what and the staffing readout are all live — a worker hired or lost while
  somebody has the screen open appears without a menu packet. It is only affordable because a rack
  changes a handful of times an hour rather than a handful of times a tick, so `changed()` sends and
  the bookkeeping-only `setChanged()` does not.
- **An edit from the screen is checked against the menu, not against the position it names.** A packet
  carrying a block position is a packet a client can aim anywhere; `StationRosterPacket` instead reads
  the station out of the sender's open menu and gates on `stillValid`, which is the same check that
  closes the menu when the player walks away or the block is broken. The rack's order is a priority
  lever and its shift toggles hire and fire villagers, so neither may be driven from across the world.
- **The rack is a fixed set of places with gaps allowed, not a list that closes up.** A job's place is
  the player's statement of what matters most, so it has to be theirs to choose — a hat you know is
  your least important goes at the bottom before anything is above it — and taking one out of the
  middle must not promote everything below it into a priority nobody asked for. It is also what an
  inventory already is: a dense list backing one disagrees with vanilla about what a slot is, and that
  disagreement is a crash, not an inconvenience (see the next entry). `putHat` places at an index,
  `addHat` finds the first free one for the right-click path, and `moveSlot` **swaps** rather than
  inserts, which is how a job is pushed into an empty place above it.
- **`moveItemStackTo` shrinks the stack it was handed, in place.** The stack a menu hands it is the
  live one in the inventory, so a `quickMoveStack` that does not empty its source slot afterwards
  leaves a slot holding a **zero-count** stack. Nothing notices until the chunk is written, at which
  point `ItemStack.save` throws "Cannot encode empty ItemStack" and takes the server down — which is
  exactly how shift-clicking a hat out of a station crashed. Follow vanilla's shape exactly: after the
  move, `stack.isEmpty()` means `slot.setByPlayer(ItemStack.EMPTY)`, never `setChanged()` alone.
  `shiftClickingAHatOutEndsItsJob` reproduces it end to end, saving the block entity at the finish
  because that is where the crash actually was, and is mutation-checked by dropping the clear.
- **Ticket reconciliation must be able to release more than it took.** The obvious guard —
  only release a ticket this station held back — makes a leak permanent: a claimant that wandered off
  and died, or a release vanilla refused because the villager's profession no longer matched the job
  site, leaves a ticket out on loan forever, and a station whose free count is stuck below its
  vacancies stands there with openings it never offers anybody. That is what "villagers standing around
  while shifts are available" looks like from the outside. So it drives free tickets at `vacancies()`
  from either side and clamps `reserved` at zero afterwards; reading the count low only ever makes the
  roster audit more cautious.
- **A station must reconcile its tickets *after* it hires, never before.** Reconciling first leaves it
  advertising, for the rest of that tick, the openings it is about to fill — and a villager that claims
  one, walks over and is turned away does not simply try again. `AcquirePoi.JitteredLinearRetry` puts
  that position on a backoff growing to **400 ticks**, so a station that over-advertises even
  occasionally teaches the village to stop applying, and the symptom is "I added more shifts and
  nobody came". `aJobOnThreeShiftsTakesThreeVillagers` samples free tickets against `vacancies()` every
  tick across a hiring and was mutation-checked by putting the reconcile back in front — the window is
  one tick wide, so an assertion at the end of the sequence does not see it.
- **Anything that changes who is on the rack must say so, and `setChanged` does not.** It saves the
  block and tells no client. Hiring, striking off, sacking and promoting all did exactly that, so a
  screen showed a job as unstaffed until something the player did happened to call `changed()` — and a
  shift toggled against that stale view wrote the client's fiction back over the truth. They set
  `rosterChanged` now and the tick syncs once at the end, which is also why it is a flag rather than a
  packet per worker.
- **A worker being moved or let go serves notice; it is never stopped mid-delivery.** Dropping a
  half-finished delivery on the floor is items out of the player's own machines scattered for a reason
  nothing in the world explains. So `WorkerData.noticeUntil` makes the worker stop taking new pickups
  and hand over what it already has, and the station completes the change on a later look, once its
  hands are empty. **Carrying the load across to the new job is not the answer** — that was the first
  attempt: a promotion is a *different* job, so the load was picked up for outputs the new job may have
  no business delivering to, which either strands the worker holding something nothing accepts or puts
  items somewhere that takes anything and should not have had them. **The deadline is not optional**
  either: a worker whose last output is full or unreachable would never empty its hands, and a job
  nobody can leave is a job nobody can be hired into. (`aWorkerWhoseShiftIsTurnedOffFinishesFirst` and
  `aPromotionWaitsForTheWorkerToPutItsLoadDown`, each mutation-checked.)
- **Turning a shift off must not sack anybody on the spot, and the second effect is worse than the
  first.** It dropped the cargo, yes — but it also left an *unemployed* villager standing at its own
  job site, which `staffUp` then hired straight back onto whichever shift had just been switched on.
  So a player toggling shifts saw items hit the floor and the worker apparently teleport between
  crews. `setShifts` now only changes which shifts the job runs; a worker left on one it no longer runs
  is counted by nothing — not `positions`, not `staffed`, not `nextVacancy` — and `finishHandovers`
  lets it go once it is done. Turning the shift back on first simply cancels the notice.
- **A test that waits for "somebody is on that shift" after killing a worker passes instantly.** The
  dead worker's record stays on the rack until the next audit strikes it off, so the wait has to name
  the villager it expects — `carrierId.equals(jobAt(0).worker(DAY))` — not merely check for non-null.
  Two tests have now been written the wrong way round.
- **A death has to be followed by a promotion, or the fill order only holds while a roster grows.**
  `nextVacancy` puts new workers in the right place; it cannot move the ones already there. Three jobs
  on two shifts with four villagers gives a complete day crew and one evening worker, and losing a day
  worker leaves both lines broken and three survivors producing nothing — permanently, if the village
  has nobody spare. `rebalance` therefore moves workers up the fill order until the roster is a prefix
  of it again, which is the chain argument applied to the case the fill order alone cannot reach. It
  is done eagerly rather than waiting to see whether a replacement arrives, because a replacement
  fills the *last* place in the order either way: promoting first and hiring into the hole behind
  reaches the same roster, and the state in between is the one that works.
  (`losingADayWorkerPromotesSomebodyUpToIt`, mutation-checked by dropping the call.)
- **A villager killed with `die()` rather than with damage may not actually die.** Both are used in
  these tests and only one is reliable: `die()` leaves the health where it was, so `isAlive()` stays
  true for the whole death animation, and a villager held still with `setNoAi` never got removed at
  all. Anything asserting on the *consequences* of a death — a struck-off roster entry, a refilled
  job — kills with `hurt(damageSources().genericKill(), Float.MAX_VALUE)`. And a roster entry is not
  cleared on the tick of the death: assert that a worker never comes *back* after being struck off
  (`thenExecuteFor` over the animation), never that it is absent, or the assertion fires on the stale
  entry rather than on the bug.
- **Never build a game test on two villagers finding the same station by themselves.** `AcquirePoi`
  scans 48 blocks, which on the test grid reaches several other tests' stations, and a claim it loses
  puts that position on a backoff that grows to 400 ticks — so whether the second villager is hired
  inside any particular window is a coin toss, and a test written that way fails under mutations it
  has nothing to do with. The single-villager tests cover the wiring into vanilla; anything about the
  *rack* uses `claimant()`, which sets `JOB_SITE`, takes the ticket and assigns the profession by hand,
  exactly as vanilla leaves a villager that has arrived. It also sets `setNoAi` — an unemployed
  villager strolls, and one that strolls out of `HIRING_RANGE` between two of the station's twenty-tick
  looks is a test failing on the villager's legs. A real claimant cannot do that: it is hired within a
  tick or two of arriving, because arriving is what made it a Worker.

## Design notes

`docs/` holds write-ups of the thinking behind features — including, for the ones not built, the
reasoning against building them, and for the ones since built, what the design turned out to be and
where it departed from the plan. Read the relevant one before touching such a feature, and update it
if the thinking changes — the point is that the analysis is not redone from scratch.

- `docs/working-hours.md` — working hours as built: the two clocks, the three places a bed comes from
  and why they are trusted differently, what happens to a worker caught mid-haul at dusk, and the
  standing argument that the whole feature may be an annoyance (kept, because it is what to weigh if
  it ever needs undoing)
- `docs/professions.md` — what hiring does to a villager's village job, and the several ways of
  doing it that look equivalent and are not
- `docs/shift-rotation.md` — shifts, food and leisure as one feature, because `GoToWantedItem` needs
  `WALK_TARGET` absent and so a pinned worker can never feed itself. **Shifts are built**; food and
  leisure are not. Holds what a shift turned out to be (an offset, on the worker, set by the slot) and
  the correction
  to the one thing `working-hours.md` got wrong (a worker **can** be given its own `Schedule`), the
  canteen block nothing in vanilla provides, and the revised case *for* giving workers trades
- `docs/worker-station.md` — the block that hires workers: a rack of programmed hats for one
  production *line*, filled through the vanilla point-of-interest route, so a lost worker's job
  refills itself. **Built, except the screen.** Holds the argument that a part-staffed shift produces
  nothing rather than less, why single-slot stations were rejected, and how a block advertises fewer
  openings than its point-of-interest type allows
- `docs/multiplayer-performance.md` — what a worker costs a server per tick, where that was fixed,
  and the things a shared server still wants that this mod deliberately does not do

## Conventions

Tabs for indentation, matching Create's own style. Registry classes are `CW*` under `registry/`.
Nothing is committed without explicit instruction.
