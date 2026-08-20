package com.createworkers.worker;

import com.createworkers.CWConfig;
import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.VillagerPanicTrigger;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

/**
 * Villagers walk. They are brain-driven rather than goal-driven, so instead of steering the
 * navigator directly this keeps {@code WALK_TARGET} pinned every tick and lets the villager's
 * own {@code MoveToTargetSink} do the pathfinding.
 *
 * <p>That cooperates with the brain rather than fighting it, and it doubles as the way to stop
 * a working villager wandering off: the idle behaviours that would send it strolling, to its
 * job site or to a meeting point all require {@code WALK_TARGET} to be absent before they will
 * start, so holding the memory occupied keeps them from ever running.
 */
public class WalkLocomotion implements WorkerLocomotion {

	/** How close counts as back at the post. */
	private static final int RETURN_CLOSE_ENOUGH = 2;
	/** How close counts as standing at the station. */
	private static final int STATION_CLOSE_ENOUGH = 1;
	/** Rounds are an amble, not a commute. */
	private static final float PATROL_SPEED_FACTOR = 0.6F;
	/** How close counts as having reached a stop on the rounds. */
	static final int PATROL_ARRIVED = 2;

	@Override
	public void approach(Mob mob, WorkerTarget target) {
		BlockPos pos = target.getPos();
		Brain<?> brain = mob.getBrain();
		float speed = (float) (double) CWConfig.WALK_SPEED.get();
		int closeEnough = Math.max(1, (int) Math.floor(CWConfig.REACH_DISTANCE.get()));

		brain.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(pos));
		brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, speed, closeEnough));
	}

	@Override
	public boolean canReach(Mob mob, WorkerTarget target) {
		double reach = CWConfig.REACH_DISTANCE.get();
		return mob.distanceToSqr(Vec3.atCenterOf(target.getPos())) <= reach * reach;
	}

	/**
	 * Walks an idle worker back to its post.
	 *
	 * <p>Never while it is panicking: a villager fleeing a zombie has every reason to leave its
	 * patch, and hauling it back would get it killed. Vanilla's own panic predicate is reused so
	 * this agrees exactly with when the brain takes over.
	 */
	@Override
	public void returnTo(Mob mob, BlockPos post) {
		if (VillagerPanicTrigger.isHurt(mob) || VillagerPanicTrigger.hasHostile(mob))
			return;

		Brain<?> brain = mob.getBrain();
		brain.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(post));
		brain.setMemory(MemoryModuleType.WALK_TARGET,
			new WalkTarget(post, (float) (double) CWConfig.WALK_SPEED.get(), RETURN_CLOSE_ENOUGH));
	}

	/**
	 * Keeps an idle worker standing where it is.
	 *
	 * <p>Occupying {@code WALK_TARGET} is the whole mechanism. What would otherwise wander a villager
	 * off — {@code VillageBoundRandomStroll} and the rest of the idle package — are one-shots that do
	 * nothing but write that memory, while {@code MoveToTargetSink}, the behaviour that actually
	 * walks the mob, reads it from CORE at a higher priority than the idle package. Rewriting it
	 * every tick, before the brain runs, means a stroll's destination is overwritten before anything
	 * ever acts on it, and the villager simply stays put.
	 *
	 * <p>Deliberately does not touch {@code LOOK_TARGET}: a worker waiting for something to do should
	 * still glance around.
	 */
	@Override
	public void holdAt(Mob mob, BlockPos station) {
		if (VillagerPanicTrigger.isHurt(mob) || VillagerPanicTrigger.hasHostile(mob))
			return;

		mob.getBrain()
			.setMemory(MemoryModuleType.WALK_TARGET,
				new WalkTarget(station, (float) (double) CWConfig.WALK_SPEED.get(), STATION_CLOSE_ENOUGH));
	}

	/** Ambles towards somewhere on the worker's rounds, looking where it is going. */
	@Override
	public void patrolTo(Mob mob, BlockPos destination) {
		if (VillagerPanicTrigger.isHurt(mob) || VillagerPanicTrigger.hasHostile(mob))
			return;

		Brain<?> brain = mob.getBrain();
		float speed = (float) (double) CWConfig.WALK_SPEED.get() * PATROL_SPEED_FACTOR;
		brain.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(destination));
		brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(destination, speed, PATROL_ARRIVED));
	}

	@Override
	public void stop(Mob mob) {
		Brain<?> brain = mob.getBrain();
		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		mob.getNavigation()
			.stop();
	}
}
