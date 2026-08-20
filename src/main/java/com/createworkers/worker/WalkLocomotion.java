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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
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
	/** How close counts as having reached a stop on the rounds. */
	static final int PATROL_ARRIVED = 2;

	@Override
	public void approach(Mob mob, WorkerTarget target) {
		BlockPos pos = target.getPos();
		int closeEnough = Math.max(1, (int) Math.floor(CWConfig.REACH_DISTANCE.get()));

		mob.getBrain()
			.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(pos));
		walkTo(mob, pos, workingSpeed(), closeEnough);
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
		if (isPanicking(mob))
			return;

		mob.getBrain()
			.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(post));
		walkTo(mob, post, workingSpeed(), RETURN_CLOSE_ENOUGH);
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
		if (isPanicking(mob))
			return;

		walkTo(mob, station, workingSpeed(), STATION_CLOSE_ENOUGH);
	}

	/** Ambles towards somewhere on the worker's rounds, looking where it is going. */
	@Override
	public void patrolTo(Mob mob, BlockPos destination) {
		if (isPanicking(mob))
			return;

		mob.getBrain()
			.setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(destination));
		walkTo(mob, destination, amblingSpeed(), PATROL_ARRIVED);
	}

	@Override
	public void stop(Mob mob) {
		Brain<?> brain = mob.getBrain();
		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		mob.getNavigation()
			.stop();
	}

	/** The pace of a worker on the clock. */
	static float workingSpeed() {
		return (float) (double) CWConfig.WALK_SPEED.get();
	}

	/** The pace of a worker on its rounds: an amble, not a commute. */
	static float amblingSpeed() {
		return workingSpeed() * (float) (double) CWConfig.IDLE_SPEED_FACTOR.get();
	}

	private static boolean isPanicking(Mob mob) {
		return VillagerPanicTrigger.isHurt(mob) || VillagerPanicTrigger.hasHostile(mob);
	}

	/**
	 * Sends the villager to {@code pos}, and makes sure it is actually travelling at {@code speed}.
	 *
	 * <p>Writing the memory is not enough by itself. {@code MoveToTargetSink} passes the speed to the
	 * navigation only in its {@code start}, and once it is running the only thing that calls
	 * {@code start} again is a re-path — which it does only when the destination has moved more than
	 * two blocks. Stops on the rounds *are* the worker's targets, so a worker that is ambling to one
	 * when work turns up is usually already walking to the very block the job is at: the new walk
	 * target is the same position, nothing re-paths, and it strolls to work at idle pace for the rest
	 * of the trip. Setting the speed on the navigation directly is what makes the change take effect
	 * the same tick, and the sink overwrites it with this same value whenever it does path again.
	 */
	private static void walkTo(Mob mob, BlockPos pos, float speed, int closeEnough) {
		mob.getBrain()
			.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(pos, speed, closeEnough));

		PathNavigation navigation = mob.getNavigation();
		if (!navigation.isDone())
			navigation.setSpeedModifier(speed);
	}
}
