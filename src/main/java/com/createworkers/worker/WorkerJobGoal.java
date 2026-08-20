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
 */
public class WorkerJobGoal extends Goal {

	/** How long to idle before re-scanning when there was nothing to do. */
	private static final int IDLE_RESCAN_TICKS = 20;
	/** How long a worker lingers at a stop on its rounds. */
	private static final int DWELL_MIN_TICKS = 60;
	private static final int DWELL_MAX_TICKS = 200;
	/** How long to idle after abandoning an unreachable target. */
	private static final int UNREACHABLE_TICKS = 60;

	private final Mob mob;
	private final WorkerLocomotion locomotion;
	private int travelTicks;
	/** Where the worker was standing when it ran out of work; null whenever it has somewhere to be. */
	@Nullable
	private BlockPos station;
	/** The stop on the worker's idle rounds it is currently ambling towards. */
	@Nullable
	private BlockPos patrolStop;
	/** Ticks left standing at a stop, looking it over. */
	private int dwellTicks;

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
		travelTicks = 0;
		forgetIdling();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		WorkerData data = data();
		locomotion.tickEmployed(mob);
		keepNearPost(data);

		if (data.tickCooldown())
			return;

		switch (data.getPhase()) {
			case SEARCH_INPUTS -> {
				int index = data.searchForItem();
				if (index >= 0) {
					data.selectTarget(Phase.MOVE_TO_INPUT, index);
					travelTicks = 0;
				} else {
					data.setCooldown(IDLE_RESCAN_TICKS);
				}
			}
			case SEARCH_OUTPUTS -> {
				int index = data.searchForDestination();
				if (index >= 0) {
					data.selectTarget(Phase.MOVE_TO_OUTPUT, index);
					travelTicks = 0;
				} else {
					data.setCooldown(IDLE_RESCAN_TICKS);
				}
			}
			case MOVE_TO_INPUT, MOVE_TO_OUTPUT -> travel(data);
		}
	}

	/**
	 * Keeps an idle worker on its patch. This matters for villagers: their brain fills the gaps
	 * between jobs with strolling, trips to a job site and trips to the village meeting point, and
	 * nothing in the job goal occupies them during a cooldown.
	 */
	private void keepNearPost(WorkerData data) {
		if (data.getTargetPoint() != null) {
			forgetIdling();
			return; // already headed somewhere, and that takes priority
		}

		// Strayed off the patch entirely: walk back to the middle of the job.
		if (Workers.isOffStation(mob.blockPosition(), data, CWConfig.WANDER_RADIUS.get())) {
			forgetIdling();
			locomotion.returnTo(mob, data.getJobSite());
			return;
		}

		switch (CWConfig.IDLE_BEHAVIOUR.get()) {
			case WANDER -> forgetIdling(); // vanilla's problem now; the leash above is the backstop
			case HOLD_STATION -> holdStation();
			case PATROL -> patrol(data);
		}
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
	private void patrol(WorkerData data) {
		if (dwellTicks > 0) {
			dwellTicks--;
			holdStation(); // stood at a stop, having a look at it
			return;
		}

		if (patrolStop == null) {
			patrolStop = pickPatrolStop(data);
			station = null;
			if (patrolStop == null) {
				holdStation(); // nothing to walk between
				return;
			}
		}

		if (mob.blockPosition()
			.closerThan(patrolStop, WalkLocomotion.PATROL_ARRIVED)) {
			patrolStop = null;
			station = mob.blockPosition()
				.immutable();
			dwellTicks = DWELL_MIN_TICKS
				+ mob.getRandom()
					.nextInt(DWELL_MAX_TICKS - DWELL_MIN_TICKS + 1);
			return;
		}

		locomotion.patrolTo(mob, patrolStop);
	}

	@Nullable
	private BlockPos pickPatrolStop(WorkerData data) {
		List<BlockPos> stops = Workers.patrolStops(data);
		if (stops.isEmpty())
			return null;
		return stops.get(mob.getRandom()
			.nextInt(stops.size()));
	}

	private void forgetIdling() {
		station = null;
		patrolStop = null;
		dwellTicks = 0;
	}

	private void travel(WorkerData data) {
		WorkerTarget point = data.getTargetPoint();
		if (point == null || !point.isValid()) {
			data.abandonTarget();
			travelTicks = 0;
			return;
		}

		if (locomotion.canReach(mob, point)) {
			boolean collecting = data.getPhase() == Phase.MOVE_TO_INPUT;
			ItemStack before = data.getHeld()
				.copy();

			boolean acted = collecting ? data.collectFrom(point) : data.depositTo(point);

			locomotion.stop(mob);
			travelTicks = 0;
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

		if (++travelTicks > CWConfig.PATH_TIMEOUT.get()) {
			// Cannot get there -- skip it and try something else rather than standing still.
			data.abandonTarget();
			locomotion.stop(mob);
			travelTicks = 0;
			data.setCooldown(UNREACHABLE_TICKS);
		}
	}
}
