# The CurseForge project page

The copy that goes on the project page, and the map of which image goes where.

This file is the source; CurseForge's description box is a copy of it. Keeping it here means the page
can be reviewed in a pull request like anything else, and the next person to change it does not have
to reconstruct it out of the live page.

Three rules for editing it:

- **Short.** The page describes mechanics, not reasons. The reasons are in `README.md` and `docs/`.
  If a sentence explains *why* a mechanic is the way it is, cut it.
- **US English.** "Program", not "programme"; "color", not "colour". The in-game text is US, and the
  same people read both. (Code comments in this repo are not, and stay as they are.)
- **Two kinds of image.** *Generated* ones come from `python3 tools/generate_page_art.py`, which
  draws them out of this repo's own assets — edit the script, never the PNG. *Screenshot wanted*
  blocks are briefs for shots only a running client can take. The generator cannot draw a worker: a
  villager in hard hat and hi-vis vest only exists once vanilla's model, its textures and
  `WorkerGearModels.fitTo` have met in a client. That is the best picture the page has, and it has to
  be taken.

On upload, replace each `branding/…` path with the URL CurseForge gives the image.

---

![Create: Workers](branding/banner.png)
<!-- generated -->

**Create: Workers** puts villagers and endermen to work hauling items around your factory, the way a
Mechanical Arm does, except they walk (or teleport) between the inventories instead of sitting bolted
to one spot. Program a hard hat exactly as you would program an arm, drop it into a Worker Station,
and a villager clocks in: hi-vis vest, visible cargo, and an unhurried patrol of their own machines
when there is nothing to move.

> **📷 Screenshot wanted — `hero-worker.png`**
> First in the gallery. A villager in hard hat and orange hi-vis vest mid-stride between two Depots
> on a working line, at player height, daylight, belts running behind. If one shot has to carry the
> page, it is this one.

---

## What it adds

| | |
|---|---|
| ![Hard Hat](branding/card-hard-hat.png) | ![Worker Station](branding/card-worker-station.png) |
| ![Canteen](branding/card-canteen.png) | |
<!-- generated -->

---

## 1. Program a hat

![Hard Hat recipe](branding/recipe-hard-hat.png)
<!-- generated -->

Hold the hat and right-click inventories, exactly like setting up a Mechanical Arm. Each click cycles
that block between **take from** (blue) and **deposit to** (yellow); left-click removes it.

- A programmed hat can be picked back up and edited — unlike an arm, the selection comes back.
- Crafting it on its own blanks it, the way a Create filter clears. Same hat, same damage, same
  enchantments.
- Sneak + right-click a **bed** to say where that worker sleeps. Optional; a worker with no bed finds
  one.
- One worker walks the whole hat, so the targets are capped: no two further apart than
  `maxTargetSpread`, and at most `maxTargets` of them. Both refuse you as you click rather than
  dropping a target later.

> **📷 Screenshot wanted — `programming.png`**
> Three or four inventories outlined at once, at least one blue input and one yellow output, so the
> two colors obviously mean different things. A bed outlined pale blue earns its place.

## 2. Let them hire themselves

![Worker Station recipe](branding/recipe-worker-station.png)
<!-- generated -->

Right-click a **Worker Station**, drop a programmed hat into a slot, and an unemployed villager
nearby takes the job — the way an unclaimed lectern finds itself a librarian.

- Twelve jobs per station, each on its own line.
- **The hat stays in the station** and the worker wears a copy, so a worker that dies does not take
  the job with it. The next villager picks it up.
- A worker that stops turning up — walled in, fallen somewhere it cannot climb out of — loses the job
  after `absenteeTimeout` and the station hires a replacement.
- Click a job's name to rename it. No anvil, no experience.
- It is an ordinary inventory, so a funnel or an arm can stock it with hats.

Two refusals: a villager that already has a job of its own won't take one (break its workstation
first), and children are never hired.

**Endermen can't use a station** — no profession, no interest in workstations. Hire one by
right-clicking it with the programmed hat; retire it with sneak + empty-hand right-click.

> **📷 Screenshot wanted — `station-screen.png`**
> The rack open with four or five named jobs, shift toggles showing a mix of states (sunken off,
> yellow wanted, green covered) and the crew counts underneath. This screen sells the block, so it
> wants to look busy.

> **📷 Screenshot wanted — `station-in-world.png`**
> A placed Station with workers at it, some rack lamps lit and some dim — the lamps are the readout.

## 3. Three shifts

Workers wear their crew's color: **orange** on days, **yellow** on evenings, **white** at night. The
hat is the same on all three, so a worker still reads as a worker.

A station fills its rack **one shift at a time** — every job's day crew, then every job's evening
crew, then every job's night crew. When the village is short of villagers, the order of the rack is
how you say which jobs matter most.

> **📷 Screenshot wanted — `night-shift.png`**
> Night. A white-vested worker still hauling, ideally an enderman worker alongside, beds or a lit
> base behind. The shot that says the factory doesn't stop.

## 4. Feed them

![Canteen recipe](branding/recipe-canteen.png)
<!-- generated -->

Workers eat on the clock and slow to a crawl when they run out. A **Canteen** feeds every worker in
range, through walls, out of its own stock — so a running line is a matter of keeping one block
stocked, not chasing villagers with bread.

## 5. Wear it yourself

Two points of armor, same as iron, and rather more durable. It renders as the same 3D hat the workers
wear, not a texture painted on your head.

> **📷 Screenshot wanted — `player-wearing.png`**
> Third-person player in the hat. "It's actual armor" is a thing people ask; a picture answers it.

---

## What they can haul from and to

Exactly what a Mechanical Arm can reach: belts, depots, funnels, basins, mechanical crafters,
deployers, saws, millstones, crushing wheels, blaze burners, chutes, packagers, plus campfires,
composters, jukeboxes and respawn anchors — and anything another addon registers as an interaction
point type.

A worker is an arm with legs, not a bigger arm, so **a plain chest is not a valid target**, exactly as
it isn't for an arm. Put a funnel on it, same as you always would.

---

## Or let the game explain it

Hold **W** over a Hard Hat and Create's own Ponder screen walks through it: programming a hat; a
Worker Station taking on a villager and carrying an ingot across the yard; then shifts and sleep.

> **📷 Screenshot wanted — `ponder.png`**
> A frame of the scene mid-play with its caption showing. Ponder signals an addon built to Create's
> standards, so it is worth a gallery slot.

---

## Requirements

- **Minecraft** 1.21.1
- **NeoForge** 21.1.219+
- **[Create](https://www.curseforge.com/minecraft/mc-mods/create) 6.0+**

Source and issues: https://github.com/Steal-My-Mods/create-workers
