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

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

		builder.comment("Create: Workers").push("workers");

		MAX_TARGET_SPREAD = builder
			.comment("How far apart the furthest two inventories on one hard hat may be — the width of",
				"a single worker's beat, checked as you assign them. Half of it is how far any target",
				"can sit from the job site, which is what the hiring and wander checks measure against.")
			.defineInRange("maxTargetSpread", 48, 8, 256);

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

		builder.pop();
		SPEC = builder.build();
	}
}
