# Shifts, food, and what a worker does off the clock

**Status: not built.** Written because [working hours](working-hours.md) shipped with a standing
objection against it, and because the three ideas below turned out to be one idea.

A hard hat names which **shift** its worker keeps. Crews cover the clock between them, each worker
gets a whole villager day — work, then eating and company, then sleep — and the eating is what makes
the middle window matter rather than decorate.

## Why this is the feature working hours actually wants

The argument against working hours, kept in full in that document, is that **a factory which silently
halves its throughput overnight is a worse toy than one that runs**. Nothing that shipped answers it.
The config turns the feature off and endermen are exempt, but both are ways of *avoiding* the
mechanic rather than playing with it.

Rotation converts it. The night stops being a tax and becomes a thing the player builds around: a
second crew, a bunkhouse, a bread supply. That is the Create idiom — a constraint you engineer
around — and it turns working hours into a reason to build more rather than a reason to edit a config
file.

## Universal, with a config to turn it off

**Every villager worker keeps a shift and eats**, however it was hired — endermen remain exempt from
both, and are [out of scope](#endermen-do-not-eat-for-now) for the time being. There is no exempt
second class among villagers, though, and no compatibility path preserving how a pre-1.0 build
behaved: that would be debt paid
forever to support a design nobody is running. The shift lives on the hat, so a hand-hired worker
carries one the same way a station-hired one does, defaulting to the day shift.

Servers that want the old shape get it from config, not from code — `workingHours` already exists and
`requireFood` joins it. Both default on.

## Three questions that turn out to be one

They were raised separately and they cannot be built separately, because of one line in the villager
brain: **`GoToWantedItem` sits in CORE at priority 5 and requires `WALK_TARGET` to be absent.** The
job goal pins that memory every tick, so a worker as it exists today can never pick anything up off
the ground, ever.

That makes the three interlock:

| | |
|---|---|
| **Shifts without leisure** | Coverage and nothing else. Workers become a rota, with none of the life the mod exists to give them |
| **Food without leisure** | Cruel. A pinned worker cannot feed itself, so the player hand-feeds every one of them or watches them starve at a depot |
| **Leisure without food** | Decorative. All of the risk described below, for flavour alone |

Together, each one justifies the others. The leisure window is what makes food obtainable; food is
what makes the leisure window load-bearing; and shifts are what stop the leisure window costing the
player their throughput.

## What is already in place

More than expected, because the two-clock design turned out to be the right shape:

- `WorkerShift.isOffShift(dayTime, clockOn, clockOff)` **takes its bounds as arguments** rather than
  reading the config inside. Making them per-worker is a change at the call site, not a rewrite.
- The hat already carries per-worker configuration — the programme and the bed — in one data
  component, with a clearing recipe, a tooltip and a client/server round trip built around it.
- The handover needs nothing. The knock-off grace already makes the outgoing worker finish the
  delivery in its hands, the incoming one starts on its own clock, and the two share nothing but the
  targets. The transfer algorithm is round-robin and re-entrant already.
- Two workers on opposite shifts can **share one bed**: they are never off duty at the same time, and
  `BedBlock.OCCUPIED` already arbitrates. Hot-bunking, for free, halving the bedroom to be built.

## The finding that makes it possible: a worker can be given its own schedule

`working-hours.md` concluded that a worker whose off-hours are daylight can never sleep, because
`WakeUp` (villager CORE, priority 0) stands up any sleeping villager whose brain is not in `REST` —
and that lifting it would mean taking the villager schedule apart.

**That was wrong, and it is the keystone of everything here.** The schedule is not something to be
fought or replaced wholesale; it is a field on the brain with a public setter:

- `Brain.setSchedule(Schedule)` is public.
- `ScheduleBuilder` is public — constructor, `changeActivityAt`, `build`.
- `Schedule` declares no constructor at all, so it has an implicit public one.

So a worker can be handed a schedule of its own, and `WakeUp` then **agrees by construction** rather
than by coincidence: `REST` is genuinely when that worker rests, whatever hour it falls at.

Three things to know before building it:

| | |
|---|---|
| **It is not persisted** | The Brain's codec carries memories; the schedule is a plain field, and `registerBrainGoals` sets `VILLAGER_DEFAULT` on every construction — which includes every load. It must be re-applied whenever a worker *loads*, not only when it is hired. `WorkerEvents.onEntityJoinLevel` already runs for every villager |
| **Retirement is free** | `restoreVillageJob` calls `refreshBrain`, which sets `VILLAGER_DEFAULT` back. Nothing to undo by hand |
| **An unregistered `Schedule` works** | `setSchedule` only stores it and `getActivityAt` is called directly. Registering one per shift into `BuiltInRegistries.SCHEDULE` is tidier if the set is fixed, and is the only way another mod reading `brain.getSchedule()` sees something it can name |

## How many shifts: three, and the earlier answer was to a different question

An earlier draft of this document said three shifts buy nothing over two, because two crews of twelve
hours already cover the clock. That arithmetic is right and **it is the wrong axis.** Coverage is not
the only reason to divide a day. A worker that only works and sleeps has no room in its day to be a
villager — no time to eat, and nothing to do that the mod did not tell it to do.

So the third window is not a third work shift. It is the *middle* of each worker's own day:

```
work  ->  eat and socialise  ->  sleep  ->  work ...
```

which is vanilla's own shape. `VILLAGER_DEFAULT` is `WORK` 2000–9000, `MEET` 9000–11000, `IDLE`
11000–12000, `REST` 12000 onward. The design falls straight out of the schedule finding above: give
each crew the **vanilla timeline phase-shifted** — crew A at +0, crew B at +8000, crew C at +16000.
Each worker keeps vanilla's proportions, and the three work windows tile the clock:

| Crew | Works | Eats and socialises | Sleeps |
|---|---|---|---|
| A | 2000–9000 | 9000–12000 | 12000–2000 |
| B | 10000–17000 | 17000–20000 | 20000–10000 |
| C | 18000–1000 | 2000–4000 (approx) | 4000–18000 |

Three crews of seven thousand ticks leave 1000-tick gaps at each changeover. **Those gaps are worth
keeping rather than closing** — see the food section: they are when two crews are awake at the same
time, which is when they can hand food to each other.

## Food is already in the game, and nothing drains it

There is no need for a fuel system, an energy bar or a new component. A villager's hunger is a
complete, persisted, vanilla-managed mechanic that workers simply never touch:

- `foodLevel`, saved to NBT as `FoodLevel`.
- `FOOD_POINTS`: bread 4, potato 1, carrot 1, beetroot 1.
- `hungry()` is `foodLevel < 12`; `wantsMoreFood()` is inventory food under 12; `hasExcessFood()` is
  24 or more.
- `eatUntilFull()` already eats from the villager's own inventory.
- `wantsToPickUp` accepts `WANTED_ITEMS` — bread, potato, carrot, wheat, beetroot and the seeds — so
  a villager picks food up off the floor without being asked.

So "workers need food" is: **spend food points as they haul, refuse to work at zero, and let vanilla
do the refilling.** Bread the player already farms, in a mechanic they already understand, with a
number that already survives a save.

Tie the drain to *work done* rather than to time. A worker that hauled two hundred items should cost
more than one that stood at an empty depot all shift — that is the right economics, it makes a busy
line the expensive one, and it means an idle worker cannot starve for having had nothing to do.

### Endermen do not eat, for now

"Every worker eats" means **every villager worker**. Endermen are out of scope for food as well as for
hours, and that is a deliberate scope line rather than an answer.

It leaves a known imbalance worth writing down so it is not rediscovered as a bug: an enderman exempt
from both hours and hunger is close to free labour, and the only thing still paying for it is how hard
one is to get into a factory in the first place. Acceptable while the mechanic is young; not a
permanent resting place.

The route to fixing it later is already half-built by this design. None of the villager food machinery
reaches an enderman — `foodLevel` is a `Villager` field, and they have no inventory, no
`wantsToPickUp` and no leisure window — so their hunger would have to be ours: a counter on
`WorkerData` and an item that suits them, which the roadmap has long guessed at as **chorus fruit**.
The [canteen](#nothing-in-vanilla-hands-food-to-a-villager-so-we-need-a-block) is the piece that makes
it tractable, and it is worth building with that in mind even though nothing will use it yet: an
enderman needs no leisure window to eat, because it can blink to a canteen, eat, and blink back in a
few seconds rather than a third of a day.

## Where food comes from, and the night crew## Where food comes from, and the night crew

`TradeWithVillager` — the behaviour that makes villagers share food — is **initiated by the villager
that has the excess**, not by the hungry one:

```java
if (owner.hasExcessFood() && (owner.getVillagerData().getProfession() == FARMER || villager.wantsMoreFood()))
    throwHalfStack(owner, Villager.FOOD_POINTS.keySet(), villager);
```

It lives in the `IDLE` package, so the giver has to be awake and idling. **A sleeping farmer shares
nothing.** Crew B above eats at 17000–20000, when every farmer in the village is in bed, so it would
never be fed by one. That is a real problem and it has three answers, in increasing order of how much
they deserve to be the design:

1. **Food is carried, and it persists.** `foodLevel` survives saves and a villager's inventory holds
   up to 24 points of food. With a modest drain, one overlap a day is plenty — only a night crew's
   *first* meal needs to coincide with a farmer, and after that it is topping up.
2. **Workers feed each other.** Read the condition again: the giver only needs excess food, and the
   receiver only needs to want more. Two *workers* satisfy it. A day-crew worker that picked up a
   stack of bread will hand half of it to a night-crew worker they meet — which is exactly what the
   1000-tick changeover gaps are for. Do not close them.
3. **The player automates it**, and this is the answer to design toward — but not by dropping items
   on the floor. See below.

Adjusting everyone's hours to overlap the farmer's is the one answer to avoid. It would compress
every shift into the waking half of the day, which defeats the point of having shifts at all.

### Nothing in vanilla hands food to a villager, so we need a block

Checked rather than assumed: **there is no container a villager will take food out of.** The three
ways food reaches a villager are picking it up off the ground, being thrown it by another villager,
and — for farmers — harvesting crops. `WorkAtComposter` turns out to be the village bakery, taking
wheat out of the farmer's *own* inventory and putting bread back into it, but it is not a place
anyone else can shop.

Loose items on the floor are a poor answer anyway: they despawn, they are picked up by hoppers and
players, and a heap of bread in a factory looks like a mistake. So this wants a block of our own — a
**canteen** — and it turns out to solve two problems at once.

- **It is an inventory**, so a funnel, chute, belt or arm fills it and feeding a workforce becomes an
  ordinary Create automation problem. That is the right genre for this mod entirely.
- **It should be a worker target**, by registering an `ArmInteractionPointType` for it — which makes
  it a valid destination on a hat. A worker hauling bread to the canteen that feeds the workers is a
  pleasing loop, and it costs nothing because that machinery already exists.
- **It is found the way a bed is found**: register it as a point of interest and reuse `findBed`'s
  shape — designated, or nearest one with a path that reaches. No new provenance rules.
- **It answers the mid-shift case.** A worker that hits zero food with a canteen in range breaks off,
  eats, and goes back to work, instead of the line stopping for reasons nobody can see. That is the
  difference between food being a mechanic and food being a trap.
- It should probably feed *any* hungry villager, not just workers. It is a food trough; making it
  worker-only would be arbitrary, and a village that feeds itself is a better toy.

Farmer sharing then becomes a pleasant bootstrap — the way your first crew eats before you have built
anything — rather than the mechanism the design leans on.

## Off the clock: what vanilla's leisure actually is

Read concretely, the idle package is mostly already local:

| Behaviour | Reach |
|---|---|
| `InteractWith` villager / cat, `TradeWithVillager`, `VillagerMakeLove`, `GiveGiftToHero`, `ShowTradesToPlayer` | 8 blocks, interaction-target driven |
| `JumpOnBed`, `SetWalkTargetFromLookTarget`, `DoNothing` | local or nil |
| **`VillageBoundRandomStroll`** | **10 blocks a hop, random, repeated** |

All the risk is in one behaviour, which is the good news: this is not a choice between "vanilla life"
and "safe", it is one behaviour to bound.

## Bounding it, and no — you do not need to build near a village

**The leash is the bound, not the village.** `Workers.isOffStation` already measures against the job
site *and every programmed target*, so a large factory is already its own patch: a worker at the far
end of a long run is at work, not wandering. Leisure should simply keep the wander leash running,
which today is suspended off-shift because the bed is a known destination. That makes leisure the
existing `IdleBehaviour.WANDER` plus a leash — a thing the mod already ships and already documents
the failure mode of.

**The food supply is a fair reason to want a village nearby, though, and that was the better version
of the question.** Farmers are where bread comes from, and farmers live in villages. Three ways out,
and none of them is "build next to a village": hire a farmer of your own by putting a composter and a
wheat field in the factory, in which case the bakery is on site; automate a canteen, as above; or
accept a daily commute's worth of sharing at the changeover. The first is the charming one — a
factory with its own farmer and its own crops is a village that happens to make andesite.

The village still matters beyond food, but as a soft good rather than a requirement, and there is a
pleasant result hiding in how vanilla defines one. `isVillageCenter` looks for POIs tagged `PoiTypeTags.VILLAGE`
with `Occupancy.IS_OCCUPIED` — *claimed* ones. Beds are village POIs, and vanilla's own
`AcquirePoi(HOME)` claims a bed for any villager, worker or not. So **a bunkhouse whose crew has taken
its beds is a village centre**, and `isVillage` is within one section of one.

Which means: build your crew somewhere to sleep — which this design already requires — and the
factory becomes a village on its own terms. `VillageBoundRandomStroll` then has its restoring force,
and outside the village it pulls *toward* the nearest village section, which is the bunkhouse. The
leash and the stroll end up pointing the same way instead of fighting.

## The leash is not broken — it is silent *(built)*

An earlier draft said the leash "tries for `pathTimeout`, stands down for `LEASH_REST_TICKS`, then
holds station **forever**", and proposed making it escalate rather than give up. **Reading the code
rather than remembering it: that is wrong.** `walkHome` counts `leashRest` down and then tries again.
It never permanently gives up, and a worker in a hole goes on attempting to walk home for as long as
it lives.

So the real picture is milder in one way and worse in another:

- **It does recover** whenever recovery is possible. A worker knocked fifteen blocks off its patch
  gets itself back with no help.
- **It costs more than it looks.** During each 200-tick attempt the goal pins `WALK_TARGET` every
  tick and `MoveToTargetSink` re-paths whenever it is not already following a path, so a permanently
  stuck worker is pathfinding hard for 200 ticks out of every 800 — a 25% duty cycle on exactly the
  failure mode CLAUDE.md calls the mod's most expensive, forever.
- **Nothing ever says so.** This is the actual gap. The line underperforms, one villager is at the
  bottom of a hole, and there is no signal of any kind.

Which makes the work smaller and differently shaped than the earlier draft claimed:

**All three are now built** — `restAfter`, the particle signal, and `recallStuckWorkers`. What follows
is the reasoning they came from.

1. **Report it.** The important one. A worker that has failed to get home for several minutes should
   stop being invisible — the angry-villager particle on a slow clock costs nothing, and the name
   floating over it (see [the station note](worker-station.md#name-the-hat-name-the-worker)) turns
   that into "go and look for Smelting feed".
2. **Back off progressively.** Lengthen the rest after repeated failures rather than holding the duty
   cycle at 25% for the rest of the world's life. A few lines, and it bounds the cost properly.
3. **An optional recall**, config-gated, for players who would rather a stuck worker were teleported
   home than left as a puzzle. The only thing that *guarantees* no worker is ever lost, and
   unpalatable enough that it should be opt-in and named honestly.

**Getting stuck is still the failure to design against; dying is not.** A dead worker drops its hat
where it fell — visible, recoverable, and the player learns something happened. A stuck worker is
invisible, which is why (1) matters more than (2) and (3) together.

## Does the designated bed survive all this?

Yes, and it is already the right shape — it just gets demoted, which it was built for.

`WorkerShift.findBed` asks the hat first, then the villager's own claimed `HOME`, then a
path-verified hunt. Vanilla already wins by default whenever it has an answer, because most workers
acquire a home within moments of one being laid down. The hat's bed is an **override** for when
vanilla's choice is wrong: the bed it picked is on the far side of the factory, or you want a
particular crew in a particular dormitory.

So: keep it, do not grow it, and let the vanilla path carry the common case. It is one line in a
tooltip and a sneak-click that costs nothing to leave in.

## A worker station block

The strongest of the ideas raised alongside this one, and it should probably be built **before or
with** shifts rather than after — because it is where a crew would naturally be defined.

Every other villager job comes from a block: an unemployed villager near an unclaimed workstation
acquires the POI and `AssignProfessionFromJobSite` gives it the profession. A `Worker Station` doing
the same would buy four things:

1. **A factory that heals itself.** A dead or lost worker's job returns to the block, and the next
   unemployed villager picks it up. That directly answers the worst failure mode in this document —
   a line that silently underperforms because one worker is at the bottom of a hole.
2. **A place to express a crew.** "This job runs three shifts" is a property of the *job*, not of
   three separate hats. A station that hands out three hats is a far better answer to "how does the
   player assign shifts" than setting a shift on each hat by hand.
3. **A real job site.** Today it is derived from the target box; with a station it is a block the
   player placed and can look at.
4. **Discoverability**, because it is the idiom every player already knows.

Mechanically it fits the existing profession work rather than fighting it. Register a
`createworkers:worker_station` POI type and have `CWProfessions.WORKER.acquirableJobSite()` match
**that and nothing else**. The property `docs/professions.md` calls load-bearing — a worker profession
that matches nothing can never squat a composter — is preserved exactly; it gains the ability to
claim precisely one block, our own.

The costs are real: a block, a block entity, a model, a recipe, a screen, and a decision about where
the programme lives (the hat, the block, or both). It also loses the immediacy of right-clicking a
villager, so both routes probably want to work.

**It deserves its own note.** The thing to settle here is only the ordering: if the station is
coming, shifts should wait for it, because the station is where a crew is configured.

## Trades for workers

An earlier draft said no. On re-examination **most of that was a balance opinion wearing a
principle's clothes**, and the honest position is yes, with one constraint on *what* they trade.

What the objection actually was, taken apart:

- *"A worker that pays for itself undercuts the food economy."* This is a balance argument, and a
  contingent one. Trades and food are separate loops, and a player funding bread with emeralds is
  just an economy — which is what villages are for.
- *"Selling Create components would put this addon in charge of Create's progression."* This one
  survives, but it is an objection to a *trade list*, not to trading. It rules out iron plates and
  andesite alloy; it says nothing about whether a worker may trade at all.
- *"`setVillagerData` nulls the offer list on every profession change, so worker trades regenerate on
  hire and vanish on retirement."* Real, and it means levelling is meaningless — but it is mild, and
  [the worker station](worker-station.md) dissolves it: a station-hired worker holds a `JOB_SITE`, so
  `ResetProfession` never fires, the `RESET_PROOF_LEVEL` hack goes away, and a worker can carry a real
  trade level like any other villager.

And there is a positive argument the first draft simply missed. The mod's whole thesis is that
workers are people rather than machines. Every other profession trades; a worker that cannot is
conspicuously *less* of a villager than a fletcher. Leisure makes `ShowTradesToPlayer` and
`GiveGiftToHero` fire for the first time, so trades are also the thing that makes the middle window
visible to a player walking past.

**The constraint is the trade list — and "stay inside our own namespace" was still too broad.** The
line that actually matters is *progression gates*, not who registered the item.

A shaft is andesite alloy and a plank. A cog is the same. They are available from the first hour, they
are not gated behind anything, and mass-producing them by hand is exactly the tedium Create wants you
to automate your way out of. A worker selling shafts and cogs skips nothing — and it is thematically
ideal, because what a labourer has to sell is the product of labour: cheap fabricated parts, made by
the person who made them.

What stays off the list is what a factory is the *answer* to: precision mechanisms, brass casings,
sturdy sheets. Selling those would let a player buy their way past the build Create set as the puzzle,
which is not this addon's call to make.

So the rule is **"nothing that skips a gate"**, and under it: shafts, cogs, andesite alloy and our own
hard hats are all fine to sell; raw materials and food are fine to buy; the tier-defining components
are not. (Worth checking whether Create has a stated position on addons adding villager trades for its
items — a courtesy question rather than a technical one.)

Mechanically it is one event: `VillagerTrades.TRADES` is a plain mutable map keyed by profession, and
NeoForge's `VillagerTradesEvent` is the supported way in.

Ordering: after food, because food is what makes a worker's leisure window mean something and trades
are the decoration on top — and after the station, because that is what makes trade levels real.

## What it costs

- **Two to three times the villagers per line**, and therefore two to three times the per-worker
  server cost. This mod has a whole test file of discipline about what one worker costs a tick, and a
  mechanic whose point is multiplying the worker count should say so out loud.
  `docs/multiplayer-performance.md` wants a paragraph when this lands.
- **Two thirds of the workforce is off-shift at any moment**, which is far more exposure than today,
  where an off-shift worker is asleep in a bed or standing on its own station.
- **It weakens the case for endermen**, which the exemption had just strengthened. The counterweight
  is unchanged — an enderman cannot be led, bred or traded for — so the choice becomes "spend three
  villagers, a bunkhouse and a bread supply" against "go and fetch something from the End", which is
  a better trade than today's "endermen, or nights off".

## Open questions

1. **What does a hat with no shift set do?** Probably "Always", so every hat programmed before this
   keeps working exactly as it did. That also makes `workingHours = false` and "every hat says
   Always" the same world, which is a pleasing property.
2. **Does an Always worker eat?** It has no leisure window, so it cannot feed itself — which makes
   "Always" a worker the player must hand-feed forever. That may be the right price for it, and it
   is the cleanest answer to the balance question the previous draft could not resolve.
3. **How much food does a shift cost?** Wants to be tuned against a real line, not chosen. The bound
   worth holding is that a full inventory (24 points, six loaves) should carry a worker through more
   than one shift, or feeding becomes the whole game.
4. **Do two crews sharing a bed break the hunt?** Hot-bunking means the bed is `OCCUPIED` nearly
   continuously, which changes what the `HAS_SPACE` filter in the discovery hunt sees for anyone
   else nearby. Worth checking before promising it.
5. **Shift visible on the gear?** A base with a dozen workers needs the crew readable across a room.
   The gear textures are generated already, so a different hi-vis trim per shift costs a script
   change — and it should ship *with* the mechanic, not after it. A shift you cannot see is the same
   feature as no shift, plus confusion. See
   [the uniform](worker-station.md#a-uniform-that-can-be-read-across-a-room), which pairs it with the
   cheaper and more useful half of the same idea: naming a hat names the villager wearing it.

## Suggested ordering

1. **Per-worker schedules, alone.** Independently useful — it fixes today's wart where globally
   inverted hours produce workers that stand beside beds they never get into — and independently
   testable: give a worker a custom schedule, set the world to noon, assert it sleeps. Everything
   else rests on it.
2. **Make a stuck worker visible**, also alone — plus the progressive back-off behind it. Worth
   having whether or not any of the rest happens, and smaller than it first looked: the leash already
   retries, it just never tells anyone.
3. **The leisure window**, which is then just "let go of the pin during `MEET`/`IDLE` and leave the
   leash on".
4. **Food**, which only becomes fair once 3 exists.
5. **Shifts on the hat** — or on the station, if the station is happening, in which case build that
   first.
6. **The gear colours**, with 5 and not after it.
