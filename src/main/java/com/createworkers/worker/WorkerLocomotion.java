package com.createworkers.worker;

import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
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

	/**
	 * Called while the worker is idle and has strayed too far, to send it back to {@code post}.
	 * Only meaningful for workers that can wander off under their own steam.
	 */
	default void returnTo(Mob mob, BlockPos post) {
	}

	/**
	 * Called every tick while the worker is idle and on its patch, to keep it standing at
	 * {@code station} rather than drifting.
	 */
	default void holdAt(Mob mob, BlockPos station) {
	}

	/**
	 * Called while the worker is idly making its rounds, to amble towards {@code destination}.
	 * Slower than working travel — it has nowhere it needs to be.
	 */
	default void patrolTo(Mob mob, BlockPos destination) {
	}

	/** Per-tick upkeep while employed, whether or not there is work to do. */
	default void tickEmployed(Mob mob) {
	}
}
