# Phase 4 — leisure, food, and trades

**Status: designed, not built.** This is the plan agreed before any code, so that the shape is
argued once rather than discovered three times. [shift-rotation.md](shift-rotation.md) holds the
original thinking; where this document disagrees with it, this one is right and says why.

## In one sentence

**A worker's day becomes work → leisure → sleep, the way a villager's already is, and leisure is what
makes food, socialising and trades possible.**

## What changed since the last design

Three things were checked rather than assumed, and each one moved the design.

### 1. The food currency in `shift-rotation.md` does not exist

That document plans to "spend food points as they haul". On `Villager`:

```
private int foodLevel;
private boolean hungry();
private void eatUntilFull();
private void digestFood(int);
public void eatAndDigestFood();
public boolean hasExcessFood();
public boolean wantsMoreFood();
public static final Map<Item, Integer> FOOD_POINTS;
public SimpleContainer getInventory();     // on AbstractVillager
```

`foodLevel` is private with no accessor, and so are the three methods that move it. Spending it needs
reflection or a mixin, and this project uses neither — it shuts off an enderman's own goals through
events rather than patching them.

What is public is better anyway. **The currency is food in the worker's inventory**, priced by
vanilla's own `FOOD_POINTS` table, with the running total on `WorkerData` beside everything else we
persist. That is visible — a worker is carrying bread or it is not — it is an item, so stocking a
workforce is an ordinary Create automation problem, and `wantsMoreFood()` and `hasExcessFood()` both
read the inventory, so farmer sharing and worker-to-worker sharing keep working for free.

### 2. Leisure is nearly free, because the shift window is already `IDLE`

`WorkerShift.workerSchedule` builds `clockOn → IDLE`, `clockOff → REST`. The *working* window is
`Activity.IDLE`, not `WORK` — vanilla's idle package has been running throughout every shift all
along, and the job goal simply overwrites `WALK_TARGET` each tick so its strolling never lands.

So leisure is not a new activity. It is **a stretch where the goal stops pinning**. The schedule keeps
two keyframes; `REST` just starts later than `clockOff`, and the gap between them is leisure.

### 3. `IDLE` carries everything, and `MEET` is a trap

| package | contains |
|---|---|
| `getIdlePackage` | `VillagerMakeLove`, `TradeWithVillager`, `VillageBoundRandomStroll`, `InteractWith`, `JumpOnBed`, `GiveGiftToHero`, `ShowTradesToPlayer` |
| `getMeetPackage` | `GiveGiftToHero`, `ShowTradesToPlayer`, `TradeWithVillager` |

`IDLE` has breeding, food sharing, socialising and trade display. `MEET` is a strict subset **plus a
pull toward a village meeting point**, which would drag workers off site toward a distant bell. We
want `IDLE` and specifically not `MEET`.

## The schedule

Vanilla, exactly, from `Schedule.VILLAGER_DEFAULT`:

| from | activity | length |
|---|---|---|
| 10 | `IDLE` | 1990 |
| 2000 | `WORK` | 7000 |
| 9000 | `MEET` | 2000 |
| 11000 | `IDLE` | 1000 |
| 12000 | `REST` | 12010 |

7000 working, ~4990 idling, 12010 asleep.

**Our one hard constraint is coverage.** `Shift.OFFSET` is a third of a day, so three crews offset by
8000 ticks cover the day seamlessly only if each works exactly 8000 — which is why `clockOff` defaults
to 8000, and why an earlier default of 12000 had two crews on at once. Work is fixed at 8000; leisure
and rest divide the remaining 16000.

**Proposed: 8000 work / 600 muster / 5000 leisure / 10400 rest.**

**Leisure is kept at vanilla's length and the extra comes out of sleep**, which is both how a real
shift worker's day differs from a nine-to-five and — checked below — free.

| | vanilla | worker | why |
|---|---|---|---|
| work | 7000 | **8000** | forced: three crews of 8000 cover the day |
| leisure | 4990 | **5000** | kept, near enough exactly |
| muster | — | **600** | new; see below |
| rest | 12010 | **10400** | the remainder |

For the day crew: **muster 23400–24000, work 0–8000, leisure 8000–13000, sleep 13000–23400.** The
other two crews are the same thing offset by 8000 and 16000.

### Nothing punishes a short night

Checked rather than assumed, because trimming sleep is only free if nothing depends on its length. The
one sleep-dependent mechanic on `Villager` is iron-golem spawning:

```java
private boolean golemSpawnConditionsMet(long gameTime) {
    Optional<Long> lastSlept = brain.getMemory(MemoryModuleType.LAST_SLEPT);
    return lastSlept.isPresent() && (gameTime - lastSlept.get()) < 24000L;
}
```

It asks whether the villager slept *at some point in the last day*, not for how long — `LAST_SLEPT` is
stamped when sleep begins. A single tick satisfies it. There is no well-rested mechanic for villagers,
nothing scales with sleep duration, and restocking is tied to working rather than resting.

So 10400 costs nothing. What it does mean is that a crew with **no bed** never stamps `LAST_SLEPT` and
so never contributes to a golem — which is already true today and is a reason to give a crew beds
beyond the obvious one.

### Muster: the commute belongs off the clock

Three crews of exactly 8000 look seamless and are not. At a changeover the outgoing crew stops and the
incoming crew is **in bed** — up to `bedSearchRadius` (16) away, further if the hat designates one —
so there is a walk of a few hundred ticks before anything is hauled. Coverage was already imperfect;
the 8000 constraint was buying a seamlessness the commute took straight back.

The fix is not to relax coverage but to move the commute off the clock, which is exactly what vanilla's
pre-work window is for. **Muster** is a short window before the shift in which the worker is awake and
the goal walks it to its post without hauling. The incoming crew arrives while the outgoing crew is
still working — a handover — and starts the moment the other stops.

It keeps the invariant that no two crews ever *work* at once, which `theShippedCrewsDoNotOverlap`
pins, and it reuses machinery that exists: walking a worker to its job site is what `walkHome` already
does.

**Vanilla's own pre-work window is 1990 ticks, not 10.** Its schedule starts `10 → IDLE`, and the 10 is
where that window begins rather than how long it lasts; `Timeline.getValueAt` wraps to the final
keyframe before the first, so ticks 0–9 fall back to `REST`. The 10 buys nothing we want and copying it
would leave a crew whose `clockOn` is 0 asleep for the first ten ticks of its own shift. The useful
half of that window is the idea, and muster is it — shorter, because a worker walking to a post it can
see does not need two thousand ticks.

### A consequence worth knowing: crews never share leisure

The three phases partition the day and the crews are offset by exactly one third, so **crew A's
leisure always aligns with crew B's work and crew C's sleep.** No two crews are ever idle together.

Within a crew this does not matter at all — every day-shift worker leisures at the same time, so they
socialise, share food and breed with each other. What it means is that there is no *cross-crew*
socialising, and no cross-crew food sharing. Which is thematically right: you do not meet the night
shift. It is also exactly the gap [the canteen](#the-canteen) exists to close.

## Food

**Drain is per item hauled, not per tick.** A worker that moved two hundred items should cost more
than one that stood at a dry depot all shift. That is the right economics — a busy line is the
expensive one — and it means an idle worker can never starve for having had nothing to do.

**The meal is an item out of the worker's own inventory**, worth whatever `FOOD_POINTS` says (bread 4,
potato 1, carrot 1, beetroot 1). `WorkerData` carries the remaining points; when they run out the
worker eats one food item from its inventory and adds its value. A worker with no food is hungry.

### A hungry worker slows down, with a floor, and says so

Not a stop. A line that halts is a line whose owner has to find out why, and food should not be the
one mechanic in this mod that fails invisibly — but a hard stop is over-correcting, because it turns a
supply hiccup into an outage.

So: **hungry work is slow work.** Longer transfer cooldowns and a reduced walk speed, both of which
are already config levers, down to a **floor** — never worse than some fraction of normal pace — so a
starving base limps rather than dies, and a brief gap in supply barely registers.

And it is never silent:

- the unhappy-villager particles already used for a worker that cannot find its way home,
- the job's row on the station screen flagged hungry, the way an out-of-range job is flagged now.

The floor matters for a reason worth naming: without one, "my base has been at 20% for three days" is
the same invisible failure in slow motion.

### The canteen

Checked rather than assumed: **there is no container a villager will take food out of.** Food reaches
a villager by being picked off the ground, by being thrown by another villager, or — for farmers — by
harvesting. So feeding a workforce needs a block of our own, and it solves several things at once.

- **It is an inventory**, so a funnel, chute, belt or arm fills it, and feeding a crew becomes an
  ordinary Create automation problem. That is exactly this mod's genre.
- **It is a worker target.** Registering an `ArmInteractionPointType` for it makes it a valid
  destination on a hat, so a worker hauling bread to the canteen that feeds the workers is a loop that
  costs nothing to build.
- **It is found the way a bed is found** — designated, or the nearest one with a path that reaches —
  reusing `findBed`'s paced, path-verified shape. A canteen hunt is a point-of-interest query and an
  A\*, and the worker that wants one most is the one that cannot reach any, so it wants the same
  pacing that stops a bedless worker pathfinding all night.
- **It answers the mid-shift case**, which is the important one: a worker that runs out breaks off,
  eats, and goes back to work.
- **It feeds any hungry villager, not only workers.** It is a food trough; making it worker-only would
  be arbitrary, and a factory that feeds its own farmers is a better toy. The cost is that a canteen
  near a village will be eaten from, which is a fill-rate problem and a fair one.

**The canteen is what feeds the night crew.** `TradeWithVillager` is in the idle package, so a sleeping
farmer shares nothing and a crew whose leisure falls at 17000 would never be fed by one. Carried food
and same-crew sharing bootstrap the first day; the canteen is the answer, and it does not sleep.

## Trades

Yes, with a constraint on the list. [shift-rotation.md](shift-rotation.md#trades-for-workers) argues
it out; the short version is that the surviving objection is about *what* a worker sells, not whether
it may sell.

The rule is **nothing that skips a gate**. A shaft is andesite alloy and a plank; a cog is the same.
They are available in the first hour, gated behind nothing, and mass-producing them by hand is the
tedium Create wants you to automate past. What a labourer has to sell is the product of labour.

- **Sell:** shafts, cogs, andesite alloy.
- **Buy:** raw materials, food.
- **Not:** precision mechanisms, brass casings, sturdy sheets — what a factory is the *answer* to.
- **Not hard hats.** The hat is this mod's own gate, and the same rule applies to us: a player who can
  buy one has bought past the item the mod is about.

Mechanically it is one event — `VillagerTrades.TRADES` is a plain mutable map keyed by profession, and
NeoForge's `VillagerTradesEvent` is the supported way in.

### A worker would never restock, and that is ours to fix

`WorkAtPoi` is what calls `shouldRestock()` and `restock()`, and it requires a `JOB_SITE` memory. **A
worker has none** — that is what `YieldJobSite` and `PoiCompetitorScan` forced, and it is why the
station recruits rather than advertising. So a worker given trades would sell out once and stay sold
out for the rest of the world's life, which would read as a bug the first time a player traded with
one.

**Restock on clocking on.** It is the villager equivalent of the shop opening, it needs no new clock,
and it inherits vanilla's own limits for free: `shouldRestock()` is public and already enforces the
twice-a-day cap through `numberOfRestocksToday` and `lastRestockCheckDayTime`. One guarded call at the
start of a shift, and a worker's trades behave like any other villager's.

**Trades want to ship with leisure, not after it.** `ShowTradesToPlayer` and `GiveGiftToHero` only
ever fire while idling, so before leisure exists a worker's trades would be unreachable. Leisure is
what makes them visible; trades are what make leisure legible to a player walking past.

## Breeding

It comes free with `IDLE` — `VillagerMakeLove` is in that package — so a fed crew with spare beds will
breed, and a factory can grow its own workforce. That is a real answer to "where do workers come
from", and it is left exactly as vanilla does it: no config, no gate. If a factory filling with
children turns out to be a nuisance in play, that is the point to add a switch, not before.

## What is deliberately not in this phase

- **Endermen do not eat.** `foodLevel`, the inventory and `wantsToPickUp` are all `Villager`; an
  enderman has none of them, and no leisure window either. The known imbalance — an enderman exempt
  from hours *and* hunger is close to free labour — is written down in `shift-rotation.md` rather than
  solved here.
- **A leisure window for endermen.** They do not need one: an enderman can blink to a canteen and back
  in seconds rather than needing a third of a day.
- **Hot-bunking.** Two crews sharing a bed means it is `OCCUPIED` nearly continuously, which changes
  what the `HAS_SPACE` filter sees for everyone else nearby. Worth checking before promising.

## Open questions

1. **How much food does a shift cost?** Wants tuning against a real line rather than choosing. The
   bound worth holding: a full inventory should carry a worker through more than one shift, or feeding
   becomes the whole game.
2. **What is the slowdown floor?** A third of normal pace is a guess. It wants to be slow enough to
   notice and fast enough that a base survives a bad night.
3. **Does a canteen need to be powered?** Probably not — it is a trough, not a machine — but it is the
   obvious place to spend a stress connection if the station never gets one.
4. **Is leisure configurable?** `workingHours` already turns the whole clock off. A separate
   `leisureLength` of 0 would mean straight from work to bed, which is the current behaviour and a
   reasonable thing for a server to want. Muster wants the same treatment and a different default.
5. **How long is muster really?** 600 ticks is sized for a bed within `bedSearchRadius` at
   `walkSpeed` 0.6, with room for pathing. It wants measuring against a real commute rather than
   guessing, and it is the one number here that a player would notice being wrong — too short and the
   crew is still walking when the shift starts, which is the stall it exists to remove.

## Ordering

1. **The leisure window and muster**, together — they are the same change to the same method, one
   releasing the pin and one holding it, and muster is what keeps the shift boundary honest once
   leisure exists. Independently visible: workers socialise where they previously stood still, and a
   crew is at its post when its shift starts instead of setting off then.
2. **The canteen block** — inventory, point of interest, arm interaction point. Useful before food
   exists, because it is a place to put bread.
3. **Food** — the drain, the hungry slowdown, the signal and the screen readout. Only fair once 1 and
   2 exist.
4. **Trades**, which are decoration on top and want 1 to be visible at all.
