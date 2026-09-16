package com.createworkers.worker;

import com.createworkers.CWConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * How a particular kind of worker gets from one target to the next.
 *
 * <p>Instances belong to a single worker rather than being shared, because some carry pacing state
 * — see {@link TeleportLocomotion}'s cooldown.
 */
public interface WorkerLocomotion {

	/**
	 * Called every tick while the worker is heading for {@code destination} at working pace.
	 *
	 * <p>A position rather than a target because not everywhere a worker is sent is an inventory: the
	 * commute to its bed is the same trip, made the same way, and is the one journey in a worker's
	 * day that has nothing to extract from or insert into at the end of it.
	 */
	void approach(Mob mob, BlockPos destination);

	/**
	 * Called every tick while the worker is on its way to bed.
	 *
	 * <p>Walking home is not walking to work: what counts as arriving at an inventory is being within
	 * working reach of it, which is configurable up to six blocks, while what counts as arriving at a
	 * bed is being close enough to get into it. So the commute asks to be taken right up to the
	 * bedside rather than to within arm's length of it.
	 */
	default void commuteTo(Mob mob, BlockPos bed) {
		approach(mob, bed);
	}

	/**
	 * Whether the worker is close enough to use {@code pos} right now.
	 *
	 * <p>The same answer however the worker travels, which is why it lives here rather than in each
	 * implementation: reach is a property of the arm's-length a worker works at, not of whether it
	 * walked or blinked into position.
	 */
	default boolean canReach(Mob mob, BlockPos pos) {
		double reach = CWConfig.REACH_DISTANCE.get();
		return mob.distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
	}

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

	/**
	 * Whether this kind of worker walks an idle beat at all.
	 *
	 * <p>An enderman does not: it blinks where the job sends it and stands still the rest of the time.
	 * Sending one on the rounds is not merely pointless, it is harmful — the rounds give up on a stop
	 * they cannot reach, and a worker that never walks anywhere would work its way through its own
	 * programme setting every target aside.
	 */
	default boolean makesRounds() {
		return false;
	}

	/**
	 * Whether this kind of worker can drift off its patch under its own steam, and so has to be
	 * walked back.
	 *
	 * <p>A separate question from {@link #makesRounds} even though the answer is the same today. That
	 * one asks whether a worker fills its idle time by walking a beat; this asks whether it can end up
	 * somewhere nobody sent it. An enderman answers no to both for the same underlying reason — it only
	 * ever moves where the job sends it — but they are not the same reason stated twice, and a worker
	 * that drifted without patrolling would need them apart.
	 */
	default boolean needsLeash() {
		return false;
	}

	/**
	 * Whether this kind of worker keeps hours at all — knocks off at the end of the day, walks to a
	 * bed and sleeps until morning.
	 *
	 * <p>An enderman does not, and the exemption is the species rather than a shortcut around the
	 * machinery: it has no bed to walk to, no schedule to keep, and it is a creature of the night in
	 * every other context the game puts it in. A base staffed by endermen runs around the clock, and
	 * that is the point of staffing it with them.
	 */
	default boolean keepsWorkingHours() {
		return false;
	}

	/** Per-tick upkeep while employed, whether or not there is work to do. */
	default void tickEmployed(Mob mob) {
	}
}
