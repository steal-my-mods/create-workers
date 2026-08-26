package com.createworkers.worker;

import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.net.WorkerStatePacket;
import com.createworkers.worker.WorkerData.Phase;
import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * Drives a hard-hatted mob through the same phases a Mechanical Arm uses — find an input,
 * go to it, take something, find an output, go to it, hand it over — with the going-there
 * part delegated to a {@link WorkerLocomotion}.
 *
 * <p>Every path through here that hands a villager somewhere to walk is on a clock. A walk target
 * that is pinned every tick and never arrived at is not a worker standing still: {@code
 * MoveToTargetSink} asks the navigation for a fresh path each time it is not already following one,
 * and each of those is an A* over a region the width of the villager's follow range. Left unbounded
 * that is a few of them a second, for as long as the worker lives, out of one mob that looks idle.
 */
public class WorkerJobGoal extends Goal {

	/** How long to idle before re-scanning when there was nothing to do. */
	private static final int IDLE_RESCAN_TICKS = 20;
	/** How long a worker lingers at a stop on its rounds -- long enough to read as a look, not a nap. */
	private static final int DWELL_MIN_TICKS = 40;
	private static final int DWELL_MAX_TICKS = 120;
	/** How long to idle after abandoning an unreachable target. */
	private static final int UNREACHABLE_TICKS = 60;
	/** How long a target the worker could not get to is left out of the scan and off the rounds. */
	private static final int SET_ASIDE_TICKS = 600;
	/** How long a worker must have had nothing to do before it starts making its rounds. */
	private static final int IDLE_GRACE_TICKS = 60;
	/** How long the leash stands down after failing to get a worker home. */
	private static final int LEASH_REST_TICKS = 600;

	private final Mob mob;
	private final WorkerLocomotion locomotion;
	/** The trip to the block the worker is about to use. */
	private final Progress travel = new Progress();
	/** The trip to the current stop on the idle rounds. */
	private final Progress rounds = new Progress();
	/** The trip back onto the patch. */
	private final Progress homeward = new Progress();
	/** Where the worker was standing when it ran out of work; null whenever it has somewhere to be. */
	@Nullable
	private BlockPos station;
	/** The stop on the worker's idle rounds it is currently ambling towards. */
	@Nullable
	private BlockPos patrolStop;
	/** Ticks left standing at a stop, looking it over. */
	private int dwellTicks;
	/** How long the worker has had nothing to haul. */
	private int idleTicks;
	/** Ticks left before the leash tries again, after it could not get the worker home. */
	private int leashRest;

	public WorkerJobGoal(Mob mob, WorkerLocomotion locomotion) {
		this.mob = mob;
		this.locomotion = locomotion;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	private WorkerData data() {
		return Workers.getOrCreate(mob);
	}

	@Override
	public boolean canUse() {
		if (!(mob.level() instanceof ServerLevel))
			return false;
		WorkerData data = Workers.get(mob);
		if (data == null || !data.isEmployed())
			return false;
		data.resolvePoints(mob);
		return data.hasWork();
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public void stop() {
		locomotion.stop(mob);
		travel.reset();
		forgetIdling();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		WorkerData data = data();
		long now = mob.level()
			.getGameTime();
		locomotion.tickEmployed(mob);
		keepNearPost(data, now);

		if (data.tickCooldown())
			return;

		switch (data.getPhase()) {
			case SEARCH_INPUTS -> {
				int index = data.searchForItem(now);
				if (index >= 0) {
					data.selectTarget(Phase.MOVE_TO_INPUT, index);
					travel.reset();
				} else {
					data.setCooldown(IDLE_RESCAN_TICKS);
				}
			}
			case SEARCH_OUTPUTS -> {
				int index = data.searchForDestination(now);
				if (index >= 0) {
					data.selectTarget(Phase.MOVE_TO_OUTPUT, index);
					travel.reset();
				} else {
					data.setCooldown(IDLE_RESCAN_TICKS);
				}
			}
			case MOVE_TO_INPUT, MOVE_TO_OUTPUT -> travel(data, now);
		}
	}

	/**
	 * Keeps an idle worker on its patch. This matters for villagers: their brain fills the gaps
	 * between jobs with strolling, trips to a job site and trips to the village meeting point, and
	 * nothing in the job goal occupies them during a cooldown.
	 */
	private void keepNearPost(WorkerData data, long gameTime) {
		if (data.getTargetPoint() != null) {
			forgetIdling();
			forgetLeash();
			return; // already headed somewhere, and that takes priority
		}

		idleTicks++;

		// Strayed off the patch entirely: walk back to the middle of the job.
		if (Workers.isOffStation(mob.blockPosition(), data, CWConfig.WANDER_RADIUS.get())) {
			walkHome(data);
			return;
		}
		forgetLeash();

		switch (CWConfig.IDLE_BEHAVIOUR.get()) {
			case WANDER -> forgetIdling(); // vanilla's problem now; the leash above is the backstop
			case HOLD_STATION -> holdStation();
			case PATROL -> {
				if (locomotion.makesRounds())
					patrol(data, gameTime);
				else
					holdStation();
			}
		}
	}

	/**
	 * Walks a strayed worker back to the middle of its job — for a while.
	 *
	 * <p>There is nowhere else for the leash to give up to, so it has to give up on the clock. A
	 * worker that cannot get home — it fell somewhere, or somebody walled the way — would otherwise
	 * be pinned at a destination it never reaches for the rest of the world's life, at the cost of a
	 * pathfind every few ticks. Holding the ground it is standing on instead still keeps it from
	 * drifting any further, which is most of what the leash was for.
	 */
	private void walkHome(WorkerData data) {
		if (leashRest > 0) {
			leashRest--;
			holdStation(); // given up for now, but not letting it wander further
			return;
		}

		BlockPos home = data.getJobSite();
		if (!homeward.stalled(mob, home, CWConfig.PATH_TIMEOUT.get())) {
			forgetIdling();
			locomotion.returnTo(mob, home);
			return;
		}

		homeward.reset();
		leashRest = LEASH_REST_TICKS;
		holdStation();
	}

	/**
	 * Holds the spot the worker was standing on when the work ran out.
	 *
	 * <p>Anchoring to a remembered position rather than to wherever it happens to be now is what
	 * stops it creeping — an anchor that followed the worker would inch along with every nudge. It is
	 * also somewhere it can definitely stand and get back to, which the geometric job site may not be.
	 */
	private void holdStation() {
		if (station == null)
			station = mob.blockPosition()
				.immutable();
		locomotion.holdAt(mob, station);
	}

	/**
	 * Idle rounds: amble to one of the worker's own assigned blocks, stand and look at it for a
	 * while, then pick another. Gives a waiting worker something to do that looks like work, without
	 * ever sending it anywhere it does not already walk to while working.
	 */
	private void patrol(WorkerData data, long gameTime) {
		// A worker between two hauls has not run out of work, it is waiting out a transfer cooldown.
		// Setting off on the rounds in that gap would have it start a trip -- and a pathfind -- between
		// every item it moves, so the rounds only begin once there has genuinely been nothing to do.
		if (idleTicks < IDLE_GRACE_TICKS) {
			holdStation();
			return;
		}

		if (dwellTicks > 0) {
			dwellTicks--;
			holdStation(); // stood at a stop, having a look at it
			return;
		}

		if (patrolStop == null) {
			patrolStop = pickPatrolStop(data, gameTime);
			rounds.reset();
			station = null;
			if (patrolStop == null) {
				holdStation(); // nothing to walk between
				return;
			}
		}

		if (mob.blockPosition()
			.closerThan(patrolStop, arrivedDistance())) {
			restHere();
			return;
		}

		if (rounds.stalled(mob, patrolStop, CWConfig.PATH_TIMEOUT.get())) {
			// Could not get there. Nothing else bounds the rounds -- there is no job waiting on this
			// stop and no timeout above it -- so a stop that cannot be arrived at has to be written
			// off here or it is walked at forever.
			data.markUnreachable(patrolStop, gameTime + SET_ASIDE_TICKS);
			restHere();
			return;
		}

		locomotion.patrolTo(mob, patrolStop);
	}

	/**
	 * How close counts as having reached a stop on the rounds.
	 *
	 * <p>Deliberately no tighter than the reach a worker needs to <em>use</em> a block. A stop it can
	 * work but can never be said to have arrived at would be walked at until the timeout above, and
	 * the write-off would then take a perfectly good target out of the scan as well.
	 */
	private static double arrivedDistance() {
		return Math.max(WalkLocomotion.PATROL_ARRIVED, Math.ceil(CWConfig.REACH_DISTANCE.get()) + 1);
	}

	/** Stands where the worker is, looking around, before it picks another stop. */
	private void restHere() {
		patrolStop = null;
		rounds.reset();
		station = mob.blockPosition()
			.immutable();
		dwellTicks = DWELL_MIN_TICKS
			+ mob.getRandom()
				.nextInt(DWELL_MAX_TICKS - DWELL_MIN_TICKS + 1);
	}

	@Nullable
	private BlockPos pickPatrolStop(WorkerData data, long gameTime) {
		List<BlockPos> stops = Workers.patrolStops(data, gameTime);
		if (stops.isEmpty())
			return null;
		return stops.get(mob.getRandom()
			.nextInt(stops.size()));
	}

	private void forgetIdling() {
		station = null;
		patrolStop = null;
		rounds.reset();
		dwellTicks = 0;
		idleTicks = 0;
	}

	/** Gives the leash a clean slate: whatever it was doing, the worker has moved on from it. */
	private void forgetLeash() {
		homeward.reset();
		leashRest = 0;
	}

	/**
	 * A clock on one trip, which only runs while the worker is getting no nearer.
	 *
	 * <p>Timing the trip itself would not do. An amble from one end of a forty-block beat to the other
	 * takes longer than any timeout worth having, and a worker written off for being slow would have a
	 * perfectly good inventory taken out of its scan. What the timeout is really for is the trip that
	 * is not going anywhere — walled off, on the wrong side of a drop, a funnel nothing can stand
	 * beside — so it is reset by any progress at all and only expires on a run of ticks without.
	 */
	private static final class Progress {

		private double closest = Double.MAX_VALUE;
		private int stalledTicks;

		void reset() {
			closest = Double.MAX_VALUE;
			stalledTicks = 0;
		}

		/** @return whether the worker has now spent {@code timeout} ticks getting no closer. */
		boolean stalled(Mob mob, BlockPos destination, int timeout) {
			double distance = mob.distanceToSqr(destination.getX() + 0.5D, destination.getY() + 0.5D,
				destination.getZ() + 0.5D);
			if (distance < closest) {
				closest = distance;
				stalledTicks = 0;
				return false;
			}
			return ++stalledTicks > timeout;
		}
	}

	private void travel(WorkerData data, long gameTime) {
		WorkerTarget point = data.getTargetPoint();
		if (point == null || !point.isValid()) {
			data.abandonTarget();
			travel.reset();
			return;
		}

		if (locomotion.canReach(mob, point)) {
			boolean collecting = data.getPhase() == Phase.MOVE_TO_INPUT;
			ItemStack before = data.getHeld()
				.copy();

			boolean acted = collecting ? data.collectFrom(point, gameTime) : data.depositTo(point);

			locomotion.stop(mob);
			travel.reset();
			data.setCooldown(CWConfig.TRANSFER_COOLDOWN.get());

			if (acted || !ItemStack.matches(before, data.getHeld())) {
				Workers.updateCargoAppearance(mob, data.getHeld());
				WorkerStatePacket.sync(mob, data);
				mob.level()
					.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F,
						collecting ? 1.0F : 0.7F);
			}
			return;
		}

		locomotion.approach(mob, point);

		if (travel.stalled(mob, point.getPos(), CWConfig.PATH_TIMEOUT.get())) {
			// Cannot get there -- set it aside and try something else rather than standing still.
			// Setting aside, rather than just skipping this round: the round-robin comes back to it
			// almost immediately, and a worker whose only target is out of reach would spend the whole
			// timeout pathfinding at it again, over and over, for the rest of its life.
			point.markUnreachable(gameTime + SET_ASIDE_TICKS);
			data.abandonTarget();
			locomotion.stop(mob);
			travel.reset();
			data.setCooldown(UNREACHABLE_TICKS);
		}
	}
}
