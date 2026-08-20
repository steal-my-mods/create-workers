package com.createworkers.worker;

import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** How a particular kind of worker gets from one inventory to the next. */
public interface WorkerLocomotion {

	/** Called every tick while the worker is heading for {@code point}. */
	void approach(Mob mob, WorkerTarget target);

	/** Whether the worker is close enough to use {@code point} right now. */
	boolean canReach(Mob mob, WorkerTarget target);

	/** Called once the worker stops heading anywhere. */
	default void stop(Mob mob) {
	}

	/** Called whenever the carried stack changes, for anything visual the mob owns itself. */
	default void onCargoChanged(Mob mob, ItemStack cargo) {
	}

	/** Per-tick upkeep while employed, whether or not there is work to do. */
	default void tickEmployed(Mob mob) {
	}
}
