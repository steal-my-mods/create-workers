package com.createworkers;



import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side tunables for how workers behave. */
public class CWConfig {

	/** What a worker does between jobs. */
	public enum IdleBehaviour {
		/** Slow rounds between the worker's own assigned blocks. */
		PATROL,
		/** Stand where the last job finished. */
		HOLD_STATION,
		/** Leave it to the mob's own idle behaviour. */
		WANDER
	}

	public static final ModConfigSpec SPEC;

	/** How far apart two of a hat's programmed targets may be. */
	public static final ModConfigSpec.IntValue MAX_TARGET_SPREAD;
	/** How many inventories one hat may be programmed with. */
	public static final ModConfigSpec.IntValue MAX_TARGETS;
	public static final ModConfigSpec.IntValue STATION_RANGE;
	/** Ticks a worker waits after completing a transfer before looking for more work. */
	public static final ModConfigSpec.IntValue TRANSFER_COOLDOWN;
	/** Movement speed modifier applied to walking workers. */
	public static final ModConfigSpec.DoubleValue WALK_SPEED;
	/** Fraction of that speed used for the idle rounds. */
	public static final ModConfigSpec.DoubleValue IDLE_SPEED_FACTOR;
	/** Ticks an enderman waits between teleports. */
	public static final ModConfigSpec.IntValue TELEPORT_COOLDOWN;
	/** Furthest an enderman may cover in a single teleport; longer trips take several hops. */
	public static final ModConfigSpec.IntValue TELEPORT_RANGE;
	/** How close a walking worker must get to an inventory before it can reach it. */
	public static final ModConfigSpec.DoubleValue REACH_DISTANCE;
	/** Ticks a walking worker may spend failing to reach a target before giving up on it. */
	public static final ModConfigSpec.IntValue PATH_TIMEOUT;
	/** How far a worker may stray from its post and its targets before being sent back. */
	public static final ModConfigSpec.IntValue WANDER_RADIUS;
	/** What a worker does when it has nothing to haul. */
	public static final ModConfigSpec.EnumValue<IdleBehaviour> IDLE_BEHAVIOUR;
	/** Whether workers knock off at the end of the day and go to bed. */
	public static final ModConfigSpec.BooleanValue WORKING_HOURS;
	/** Whether villager workers eat, and slow down when they cannot. */
	public static final ModConfigSpec.BooleanValue REQUIRE_FOOD;
	/** How many ticks on the clock one point of villager food is worth. */
	public static final ModConfigSpec.IntValue TICKS_PER_FOOD_POINT;
	/** The fraction of normal pace a hungry worker manages. Also the floor: it never gets worse. */
	public static final ModConfigSpec.DoubleValue HUNGRY_PACE;
	/** How far a canteen hands food out. */
	public static final ModConfigSpec.IntValue CANTEEN_RANGE;
	/** The time of day the day crew starts. Every other crew is one Shift.OFFSET along from it. */
	public static final ModConfigSpec.IntValue CLOCK_ON;
	/** How far from the job site a worker may look for a bed nobody gave it. */
	public static final ModConfigSpec.IntValue BED_SEARCH_RADIUS;
	/** Whether a worker that cannot walk home is put back by hand. */
	public static final ModConfigSpec.BooleanValue RECALL_STUCK_WORKERS;
	/** How long a worker may be away from its work before its station gives the job to somebody else. */
	public static final ModConfigSpec.IntValue ABSENTEE_TIMEOUT;
	/** How many jobs one station may hold. */
	public static final ModConfigSpec.IntValue STATION_SLOTS;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

		builder.comment("Create: Workers").push("workers");

		MAX_TARGET_SPREAD = builder
			.comment("How far apart the furthest two inventories on one hard hat may be — the width of",
				"a single worker's beat, checked as you assign them. The job site is the centre of the",
				"box the targets span; hiring measures this full distance from it.")
			.defineInRange("maxTargetSpread", 48, 8, 256);

		STATION_RANGE = builder
			.comment("How far a Worker Station's work may be from the Station itself, measured to the",
				"middle of the hat's targets. This is a radius from the block, and a different thing",
				"from maxTargetSpread, which is how wide one hat's own beat may be.",
				"A Station hires Villagers standing next to it and sends them to the work, so the gap",
				"between the two is a walk somebody has to make: keep it inside a Villager's pathing",
				"range or they arrive slowly, or not at all. A job programmed further off than this is",
				"held in the rack and marked out of range rather than staffed.")
			.defineInRange("stationRange", 32, 8, 256);

		MAX_TARGETS = builder
			.comment("How many inventories one hard hat may be programmed with.",
				"Not a taste setting -- it is the bound on what a worker costs a server. Every scan",
				"that finds nothing to do walks the whole programme and prices each input slot",
				"against every output, so the work an idle worker does grows with inputs times",
				"outputs; the wander check walks the list every tick; and the programme itself is",
				"stored on the item, saved, and sent to every client that can see the hat.")
			.defineInRange("maxTargets", 24, 1, 256);

		TRANSFER_COOLDOWN = builder
			.comment("Ticks a worker pauses after moving an item.")
			.defineInRange("transferCooldown", 10, 0, 200);

		WALK_SPEED = builder
			.comment("Movement speed modifier for walking workers (villagers).")
			.defineInRange("walkSpeed", 0.6D, 0.1D, 2.0D);

		IDLE_SPEED_FACTOR = builder
			.comment("How fast a worker ambles on its idle rounds, as a fraction of walkSpeed.",
				"Below 1.0 an idle worker is visibly off the clock; at 1.0 it moves between its own",
				"blocks at working pace.")
			.defineInRange("idleSpeedFactor", 0.85D, 0.25D, 1.0D);

		TELEPORT_COOLDOWN = builder
			.comment("Ticks an enderman waits between teleports.")
			.defineInRange("teleportCooldown", 20, 1, 200);

		TELEPORT_RANGE = builder
			.comment("Furthest an enderman may cover in one teleport. Longer trips are made in",
				"several hops, each costing another teleportCooldown.")
			.defineInRange("teleportRange", 24, 4, 128);

		REACH_DISTANCE = builder
			.comment("How close a walking worker must be to an inventory to use it.")
			.defineInRange("reachDistance", 2.5D, 1.0D, 6.0D);

		PATH_TIMEOUT = builder
			.comment("Ticks a walking worker may spend failing to reach a target before skipping it.")
			.defineInRange("pathTimeout", 200, 40, 2000);

		WANDER_RADIUS = builder
			.comment("How far a worker may stray from its work site, or from any of its programmed",
				"targets, before it is sent back. Workers are free to mill about inside this.")
			.defineInRange("wanderRadius", 12, 4, 64);

		IDLE_BEHAVIOUR = builder
			.comment("What a worker does when there is nothing to haul.",
				"PATROL: strolls slowly between its own assigned blocks, as though checking on them.",
				"  Safe by construction -- the only places it goes are ones it already walks to for",
				"  work, so it cannot wander anywhere it could not already get back from.",
				"HOLD_STATION: stands where it finished its last job. The most predictable option.",
				"WANDER: leaves idling to vanilla, which strolls up to ten blocks at a time, repeatedly.",
				"  Livelier, but an idle villager can stroll off a catwalk and have to find its way back.")
			.defineEnum("idleBehaviour", IdleBehaviour.PATROL);

		WORKING_HOURS = builder
			.comment("Whether workers keep hours: down tools at the end of the day, walk to a bed and",
				"sleep until morning. A sleeping worker moves nothing, so a line fed only by workers",
				"stops overnight -- turn this off and they work around the clock.",
				"Endermen are exempt whatever this says. They have no beds and no schedule.")
			.define("workingHours", true);

		REQUIRE_FOOD = builder
			.comment("Whether Villager Workers eat. They spend food for every tick they are on the",
				"clock -- see ticksPerFoodPoint -- taken from their own inventory, where a loaf of",
				"bread is four points and a potato, carrot or beetroot is one. A Worker with none left",
				"keeps working at a reduced pace rather than stopping. Feeding a crew is what a Canteen",
				"is for. Turn this off and Workers never eat and never slow down.",
				"Endermen are exempt whatever this says. They eat nothing.")
			.define("requireFood", true);

		TICKS_PER_FOOD_POINT = builder
			.comment("How far one point of food goes, counted in ticks on the clock. Vanilla values a loaf",
				"of bread at four points and a carrot, potato or beetroot at one, so at the default a loaf",
				"carries a Worker through 7200 ticks -- near enough a whole shift.",
				"",
				"Charged by time rather than by items moved, so the bill is a matter of how many Workers",
				"you employ and not of how you laid your factory out. A Worker off the clock -- at",
				"leisure or asleep -- eats nothing.",
				"",
				"It also decides how far a Worker can stray from a Canteen, because a villager will only",
				"ever hold twelve points of food: at the default that is about three shifts away from a",
				"trough before it starts to limp. Lower numbers make feeding a crew the thing your",
				"factory is mostly doing; higher ones make it something you set up once and forget.")
			.defineInRange("ticksPerFoodPoint", 1800, 1, 100000);

		HUNGRY_PACE = builder
			.comment("How fast a hungry Worker works, as a fraction of its normal pace: it walks this",
				"much slower and pauses this much longer between items. This is a floor rather than a",
				"slide -- a Worker with nothing to eat is slow, and never becomes slower than this, and",
				"never stops. A line that halts is a line whose owner has to go and find out why; a",
				"line running at a third is one that is plainly limping.")
			.defineInRange("hungryPace", 0.35D, 0.05D, 1.0D);

		CANTEEN_RANGE = builder
			.comment("How far a Canteen hands food out, in blocks. It serves anybody it can reach who is",
				"short of food -- Workers, farmers, anyone -- so a Canteen is something you place where",
				"the people are rather than something they queue at. A line whose Workers are outside",
				"every Canteen's reach goes hungry and slows down, which is the shape of the problem:",
				"feeding a factory is a question of where you put the troughs.")
			.defineInRange("canteenRange", 16, 1, 64);



		CLOCK_ON = builder
			.comment("The time of day the day crew starts, in ticks. The default is first light.",
				"Setting this later than clockOff inverts the two, which is how a night shift is made --",
				"and it moves every worker on the server, not one of them. Each crew gets working hours",
				"of its own, so a crew whose off hours fall in daylight sleeps through them.")
			.defineInRange("clockOn", 0, 0, 23999);



		BED_SEARCH_RADIUS = builder
			.comment("How far from the job site a worker may look for a bed of its own, for workers whose",
				"hat names no bed. Only beds it can prove a path to are taken, so this is a bound on the",
				"search rather than a promise about the walk. 0 turns the search off entirely: a worker",
				"then sleeps only in a bed assigned on its hat, and stands its ground all night without.")
			.defineInRange("bedSearchRadius", 16, 0, 64);

		RECALL_STUCK_WORKERS = builder
			.comment("Whether a worker that has repeatedly failed to walk back to its work is teleported",
				"there. Off by default, because a worker appearing out of thin air is not something this",
				"mod does anywhere else -- but a worker at the bottom of a hole is a line quietly running",
				"short with nothing to say which villager to go and find, and some servers would rather",
				"have the teleport than the puzzle. Workers that can walk home always walk.")
			.define("recallStuckWorkers", false);

		ABSENTEE_TIMEOUT = builder
			.comment("How long a worker may go without being anywhere near its own work before the",
				"station that hired it gives the job to somebody else, in ticks. 6000 is five minutes.",
				"This is what stops a line quietly running short because one villager fell in a hole:",
				"the absentee is let go, an ordinary villager again, and the next one along takes the",
				"job. Measured by where the worker is, not by how much it has moved, because a worker",
				"with nothing to haul is idle rather than absent. 0 never gives up on anyone.")
			.defineInRange("absenteeTimeout", 6000, 0, 72000);

		STATION_SLOTS = builder
			.comment("How many jobs one worker station may hold. Each job may run on up to three shifts,",
				"so a station of this many slots is up to three times this many villagers -- which is",
				"what this setting is really metering, since every worker costs a server something every",
				"tick. 12 is the ceiling the mod is built for and cannot be raised: the number of",
				"villagers one station can hire is fixed when its point of interest is registered.")
			.defineInRange("stationSlots", 12, 1, 12);

		builder.pop();
		SPEC = builder.build();
	}
}
