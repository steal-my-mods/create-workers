package com.createworkers.worker;

import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.world.entity.Mob;

/**
 * How a particular kind of worker gets from one target to the next.
 *
 * <p>Instances belong to a single worker rather than being shared, because some carry pacing state
 * — see {@link TeleportLocomotion}'s cooldown.
 */
public interface WorkerLocomotion {

	/** Called every tick while the worker is heading for {@code point}. */
	void approach(Mob mob, WorkerTarget target);

	/** Whether the worker is close enough to use {@code point} right now. */
	boolean canReach(Mob mob, WorkerTarget target);

	/** Called once the worker stops heading anywhere. */
	default void stop(Mob mob) {
	}

	/** Per-tick upkeep while employed, whether or not there is work to do. */
	default void tickEmployed(Mob mob) {
	}
}
