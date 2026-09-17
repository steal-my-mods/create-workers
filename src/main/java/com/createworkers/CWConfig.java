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
	/** The time of day a worker downs tools. */
	public static final ModConfigSpec.IntValue CLOCK_OFF;
	/** The time of day it picks them up again. */
	public static final ModConfigSpec.IntValue CLOCK_ON;
	/** How far from the job site a worker may look for a bed nobody gave it. */
	public static final ModConfigSpec.IntValue LEISURE_LENGTH;
	public static final ModConfigSpec.IntValue MUSTER_LENGTH;
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

		CLOCK_OFF = builder
			.comment("The time of day a worker downs tools, in ticks: 0 is dawn, 6000 noon, 12000 dusk,",
				"18000 midnight.",
				"",
				"This is one crew's working day, and the three crews keep the same one -- each started",
				"8000 ticks after the last. So a day longer than 8000 ticks means crews OVERLAP: at",
				"12000 the evening crew is still working when the night crew clocks on, and you pay for",
				"three villagers to get about one and a half crews of cover. 8000 tiles the clock",
				"exactly, which is what a rota is for, and is still a longer working day than a vanilla",
				"villager's own 7000.",
				"",
				"Raise it if you only ever run one shift -- overlap costs nothing when there is nobody to",
				"overlap with, and a worker that downs tools at dusk goes straight to bed rather than",
				"standing beside it waiting for the village's own bedtime.")
			.defineInRange("clockOff", 8000, 0, 23999);

		CLOCK_ON = builder
			.comment("The time of day the day crew starts, in ticks. The default is first light.",
				"Setting this later than clockOff inverts the two, which is how a night shift is made --",
				"and it moves every worker on the server, not one of them. Each crew gets working hours",
				"of its own, so a crew whose off hours fall in daylight sleeps through them.")
			.defineInRange("clockOn", 0, 0, 23999);

		LEISURE_LENGTH = builder
			.comment("How long a crew has to itself after its shift, in ticks, before it goes to bed.",
				"This is the window in which Workers do what any other Villager does off the clock --",
				"stroll, talk to each other, hand food about, and breed. It is also the only time a",
				"Worker's trades can be looked at, because vanilla only offers them while idling.",
				"The default is vanilla's own allowance: a Villager gets 4990 ticks awake and not",
				"working, and 590 of those go to muster below.",
				"0 sends a crew straight from work to bed, which is what it did before this existed.")
			.defineInRange("leisureLength", 4390, 0, 12000);

		MUSTER_LENGTH = builder
			.comment("How long before its shift a crew is woken to walk to its post, in ticks.",
				"Three crews covering the day exactly still leave a gap at every changeover, because",
				"the crew coming on is in bed when the crew going off stops. Waking them early moves",
				"that walk off the clock: they arrive while the last crew is still working and start",
				"the moment it stops.",
				"590 rather than 600 because vanilla's own waking keyframe sits ten ticks late -- a",
				"Villager lies in for ten ticks past the nominal end of its night, and so does a Worker.",
				"0 turns muster off, and the factory stalls for the length of a walk at each handover.")
			.defineInRange("musterLength", 590, 0, 6000);

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
