package com.createworkers;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side tunables for how workers behave. */
public class CWConfig {

	public static final ModConfigSpec SPEC;

	/** How far apart two of a hat's programmed targets may be. */
	public static final ModConfigSpec.IntValue MAX_TARGET_SPREAD;
	/** Ticks a worker waits after completing a transfer before looking for more work. */
	public static final ModConfigSpec.IntValue TRANSFER_COOLDOWN;
	/** Movement speed modifier applied to walking workers. */
	public static final ModConfigSpec.DoubleValue WALK_SPEED;
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

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

		builder.comment("Create: Workers").push("workers");

		MAX_TARGET_SPREAD = builder
			.comment("How far apart the furthest two inventories on one hard hat may be — the width of",
				"a single worker's beat, checked as you assign them. Half of it is how far any target",
				"can sit from the job site, which is what the hiring and wander checks measure against.")
			.defineInRange("maxTargetSpread", 64, 8, 256);

		TRANSFER_COOLDOWN = builder
			.comment("Ticks a worker pauses after moving an item.")
			.defineInRange("transferCooldown", 10, 0, 200);

		WALK_SPEED = builder
			.comment("Movement speed modifier for walking workers (villagers).")
			.defineInRange("walkSpeed", 0.6D, 0.1D, 2.0D);

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

		builder.pop();
		SPEC = builder.build();
	}
}
