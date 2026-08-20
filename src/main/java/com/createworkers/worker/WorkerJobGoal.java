package com.createworkers.worker;

import java.util.EnumSet;

import com.createworkers.CWConfig;
import com.createworkers.net.WorkerStatePacket;
import com.createworkers.worker.WorkerData.Phase;
import com.createworkers.worker.target.WorkerTarget;

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
	/** How long to idle after abandoning an unreachable target. */
	private static final int UNREACHABLE_TICKS = 60;

	private final Mob mob;
	private final WorkerLocomotion locomotion;
	private int travelTicks;

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
		if (data.getTargetPoint() != null)
			return; // already headed somewhere, and that takes priority
		if (!Workers.isOffStation(mob.blockPosition(), data, CWConfig.WANDER_RADIUS.get()))
			return;
		locomotion.returnTo(mob, data.getJobSite());
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
