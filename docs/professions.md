# What hiring does to a villager's job

A hired villager holds `createworkers:worker` for as long as it is employed, and gets its old
profession and trades back when it is retired. This is a record of why, because most of the
alternatives look equivalent from the outside and are not.

## The problem it solves

Before this, hiring left the profession alone: a farmer in a hard hat was still a farmer. That was
already *mechanically* inert — `HarvestFarmland` needs `WALK_TARGET` to be absent and the job goal
pins that memory every tick, including while idling and while resting, so a hired farmer never
plants, harvests or walks to a composter. Three things leaked anyway:

- **The workstation stayed claimed** for the whole employment, so no other villager could take that
  composter. `YieldJobSite` only hands a site to a *jobless* villager.
- **Trades never restocked.** `WorkAtPoi` is what calls `restock()`, and it needs the villager within
  1.73 blocks of its job site — reachable only by walking, which is suppressed. (It has no
  `WALK_TARGET` condition of its own, so a worker whose depot happens to sit within 1.73 blocks of
  its old workstation restocks perfectly well. That is a curiosity, not a design.)
- **It read wrong.** A worker is part of a factory, not a farmer on a shift, and it looked like a
  farmer.

## Why a profession of our own, and not vanilla's unemployed

Clearing the profession to `VillagerProfession.NONE` is the obvious move and it is a trap.
`NONE` is registered with `ALL_ACQUIRABLE_JOBS`, so an unemployed villager's acquirable predicate
matches every job site in the game — and `AcquirePoi` **takes a workstation's ticket the moment a
path to it merely exists**, without ever arriving. A worker's walk target is pinned every tick, so
arriving is exactly what it would never do. The result is a worker squatting a composter it can
never use, for the rest of the villager's life, with nothing in the world to show why. Strictly
worse than leaving the profession alone.

Releasing the job site while *keeping* the profession is equally futile: a farmer's acquirable
predicate is its own job site, so it re-claims the composter as soon as a path exists. Changing the
profession is the load-bearing part; releasing the POI is the cleanup.

## Why not `NITWIT`

Vanilla's own never-works profession is registered exactly as ours is —
`register("nitwit", PoiType.NONE, PoiType.NONE, null)`, both predicates matching nothing — and would
have done the mechanical job for free, with no texture and no registry entry. Three things decided
against it:

1. **It fails badly if this mod is removed.** `Villager.readAdditionalSaveData` parses `VillagerData`
   with `resultOrPartial(...).ifPresent(...)`, so an unknown profession id logs and leaves the
   villager at its default data: a `createworkers:worker` becomes an ordinary unemployed villager who
   can take a job again. A nitwit, by contrast, is a perfectly valid vanilla id that loads fine — and
   the stash holding its real profession went with the attachment, so it would stay unemployable
   forever. The "safe" option is the one that bricks a workforce.
2. **Nitwit means something to other people.** Culling nitwits is routine village management, and
   mods and datapacks exist that reroll or kill them wholesale. A workforce should not be labelled
   with something else's idea of "useless".
3. It reads as "Nitwit" wherever professions surface — Jade, name tags, Villagers Reborn's own
   profession UI.

## Why `worker` and not `hauler`

A profession id is permanent save state: renaming it later would strand every worker in every world
on the fallback above. So the name has to survive the mod growing, and the mod's own model is that
the *hat's programme* is the job while the villager is the worker. When workers learn to do more than
haul, the programme changes and the role does not. `hauler` would describe the programme at the one
place that cannot be re-described, and it would spend the specific name on the generic meaning —
`hauler` and `builder` can still be added beside `worker` if a role ever genuinely behaves
differently rather than merely carrying a different programme.

## The two orderings that are load-bearing

Both are covered by GameTests, and both were mutation-checked by doing them the wrong way round.

- **Release the workstation before changing the profession.** `Villager.releasePoi` gates the release
  on `POI_MEMORIES`, and the predicate for `JOB_SITE` is the villager's *current* profession's
  `heldJobSite`. Change the profession first and that predicate matches nothing, the release silently
  does nothing, and the composter stays ticketed forever. `releasePoi` also does not erase the
  memory, so that is done by hand — a stale job site is one this worker would still work from close
  enough.
- **Restore the trades after the profession.** `Villager.setVillagerData` nulls the offers whenever
  the profession changes, so offers put back first are thrown away on the way in.

## Two things vanilla does behind a profession

Neither is optional, and both were found by reading vanilla rather than by watching the game — the
first version of this feature shipped without them and was quietly broken.

**The brain bakes the profession in.** `Villager.registerBrainGoals` builds
`AcquirePoi(profession.acquirableJobSite(), JOB_SITE, POTENTIAL_JOB_SITE, …)` into the CORE package
once, at construction. Change the profession without `refreshBrain` and the villager goes on hunting
for the workstations of the job it no longer has: it re-tickets the composter that was just handed
back, a few tens of ticks after the hire, and runs a follow-range pathfind looking for more. Vanilla
pairs every profession change with `refreshBrain`. Retiring needs it just as much, in reverse — a
villager left holding the worker's match-nothing predicate could never find a workstation again.

**`ResetProfession` is hunting for exactly what a worker looks like.** CORE, priority 10: it wipes
the profession of any villager that has no job site, has never traded and is still level 1 — and it
exempts `NONE` and `NITWIT` by name. A worker is all three of those things by design, so ours was
being reset to `NONE` within a tick or two of hiring, landing in precisely the everything-acquiring
state this profession exists to avoid.

Occupying `JOB_SITE` to dodge it does not work: `ValidateNearbyPoi` runs at priority 0 with the
profession's own `heldJobSite` predicate, so it erases a job site the worker does not claim before
`ResetProfession` reads it in the same tick. Pinning the memory every tick from the goal — the trick
that works for `WALK_TARGET` — fails for the same reason, since both behaviours are in one activity
and priority 0 runs before priority 10.

What is left is the level. `ResetProfession` only touches a villager still on its first trade level,
so a hired villager is held at level 2 and given its real level back from the stash when it retires.
That is also a quiet vindication of `NITWIT`: vanilla exempts its own never-works profession from
this rule precisely because a profession with no job site is otherwise unstable, and a custom one
does not get the exemption.

## Being converted counts as clocking off

A villager bitten by a zombie or struck by lightning is *replaced*, not killed: `Mob.convertTo`
spawns the new mob and discards the old one, so no death event and no drops event ever fire. Left
alone that loses the hat outright — and the profession outlives the attachment, because
`Zombie.killedEntity` copies the villager's `VillagerData` (and gossips, offers and xp) onto the
zombie villager, and curing copies it back. A worker that was bitten and cured would return holding
a profession with nothing behind it, unable to take a village job ever again, since a worker's
job-site predicates match nothing.

`WorkerEvents.onLivingConversion` retires it first, on `LivingConversionEvent.Pre` — the one hook
that runs while the villager is still whole, fired from the conversion's own check in
`Zombie.killedEntity` and `Villager.thunderHit` before the replacement is built. The hat and cargo
drop where the villager stood and the village job goes back on, so what the zombie inherits is the
farmer it was before it was hired. The event is deliberately not cancelled: becoming a zombie is the
villager's business, and a worker is not owed protection from it.

## Why retiring gives the job back at all

Employment is meant to be permanent in practice — a worker is factory equipment, not a temp. But
retiring is an explicit "I am taking the hat back", and at that moment the villager is not staff any
more, so handing back what was taken is just symmetry with the hat and the cargo. The alternative
destroys player investment on a single right-click, invisibly: you would find out months later that
your Mending librarian's trades had been rerolled. `setOffers` is public and `MerchantOffers` has a
codec, so lossless costs two fields on an attachment that already serializes.

There is no config toggle. The clear happens at hire time, so villagers already employed in existing
worlds keep their professions until they are re-hired, and nothing changes underfoot.

## The clothing

`tools/generate_worker_profession.py` writes the profession's overlay: hi-vis trim on the sleeve
cuffs and the robe hem, sampled from `worker_gear.png` so the two cannot drift apart, and everything
else transparent so the villager's own biome robe shows through. It writes **two** files, because the
overlay is looked up per renderer — `villager/profession/worker.png` and
`zombie_villager/profession/worker.png`. Vanilla ships a full set under both, and a converted villager
keeps its `VillagerData`, so a profession that skipped the zombie one would render as missing texture
the first time a worker was bitten. The two layouts put the robe in the same texels and differ only
in the sleeve, which is 8 units tall on a villager and 12 on a zombie villager. The hard hat and the vest are
geometry and already cover the head and torso, so a full outfit would be pixels nobody sees. The hat
region is left empty deliberately: a profession texture is what draws a farmer's straw hat, and a
worker wears a hard hat instead.

Vanilla's profession-level trim still renders over it — `VillagerProfessionLayer` skips only `NONE` —
so a worker shows the stone/iron badge for whatever trade level it happens to carry.
