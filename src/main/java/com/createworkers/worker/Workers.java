package com.createworkers.worker;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.net.WorkerStatePacket;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWAttachments;

import java.util.ArrayList;
import java.util.List;

import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** Helpers for reading and classifying worker state. */
public class Workers {

	/** @return the worker state already on this entity, or null if it has never had one. */
	@Nullable
	public static WorkerData get(Entity entity) {
		if (!entity.hasData(CWAttachments.WORKER.get()))
			return null;
		return entity.getData(CWAttachments.WORKER.get());
	}

	/** @return the worker state, creating it if needed. Only call when about to employ. */
	public static WorkerData getOrCreate(Entity entity) {
		return entity.getData(CWAttachments.WORKER.get());
	}

	public static boolean isEmployed(Entity entity) {
		WorkerData data = get(entity);
		return data != null && data.isEmployed();
	}

	/** @return whether this kind of mob can be given a hard hat at all. */
	public static boolean canBeEmployed(Entity entity) {
		return entity instanceof Villager || entity instanceof EnderMan;
	}

	/**
	 * @return a fresh locomotion for this worker, or null if it cannot be employed. Instances are
	 *         per-worker rather than shared because some hold pacing state.
	 */
	@Nullable
	public static WorkerLocomotion locomotionFor(Mob mob) {
		if (mob instanceof EnderMan)
			return new TeleportLocomotion();
		if (mob instanceof Villager)
			return new WalkLocomotion();
		return null;
	}

	/**
	 * Whether a worker has strayed off its patch: further than {@code radius} from both its job
	 * site and every inventory it was programmed with.
	 *
	 * <p>Targets count as posts in their own right, so a worker standing at the far end of a long
	 * run is at work rather than wandering, however far that is from the middle of its beat.
	 */
	public static boolean isOffStation(BlockPos pos, WorkerData data, int radius) {
		if (pos.closerThan(data.getJobSite(), radius))
			return false;
		for (WorkerTarget target : data.getInputs())
			if (pos.closerThan(target.getPos(), radius))
				return false;
		for (WorkerTarget target : data.getOutputs())
			if (pos.closerThan(target.getPos(), radius))
				return false;
		return true;
	}

	/**
	 * Everywhere a worker may amble to on its idle rounds: exactly the blocks it was programmed with.
	 *
	 * <p>Keeping the rounds to the programme is what makes idling safe. These are the same positions
	 * the worker already walks to in order to do its job, so a worker that can work its beat can
	 * always walk its beat, and it can never idle its way somewhere it cannot get back from.
	 */
	public static List<BlockPos> patrolStops(WorkerData data, long gameTime) {
		List<BlockPos> stops = new ArrayList<>(data.getInputs()
			.size()
			+ data.getOutputs()
				.size());
		addStops(stops, data.getInputs(), gameTime);
		addStops(stops, data.getOutputs(), gameTime);
		return stops;
	}

	/**
	 * Targets the worker has lately failed to reach are left off the rounds. Without that, a stop it
	 * can never arrive at is picked again every few rounds and walked at until the worker is
	 * interrupted — and each of those attempts is the villager brain pathfinding once every few ticks
	 * for as long as it lasts.
	 */
	private static void addStops(List<BlockPos> stops, List<WorkerTarget> targets, long gameTime) {
		for (WorkerTarget target : targets)
			if (!target.isUnreachable(gameTime))
				stops.add(target.getPos());
	}

	/**
	 * Puts a mob to work. The one way anything becomes a worker.
	 *
	 * <p>Villagers arrive here from a {@code WorkerStation}, having already been made Workers by
	 * vanilla's {@code AssignProfessionFromJobSite}; endermen arrive from a player's right-click. Both
	 * need the same four things done, and the schedule is the one that is easy to forget — a worker
	 * without it keeps the village's hours rather than its own.
	 *
	 * <p>Nothing here touches the villager's profession. It does not have to: a station only ever
	 * hires a villager that had none, because {@code AssignProfessionFromJobSite} refuses to convert
	 * anything else. That is what let the whole business of stashing a village job, restoring its
	 * trades, refreshing the brain and holding the trade level above {@code ResetProfession}'s reach
	 * be deleted rather than maintained — see {@code docs/professions.md} for what used to be here and
	 * why none of it is needed once hiring goes through a workstation like every other job.
	 */
	public static void employ(Mob mob, ItemStack hat, WorkerProgram programme, @Nullable GlobalPos station) {
		WorkerData data = getOrCreate(mob);
		data.employ(hat, programme);
		if (station != null)
			data.rememberStation(station);

		WorkerShift.applySchedule(mob);
		data.markAtWork(mob.level()
			.getGameTime());
		updateCargoAppearance(mob, data.getHeld());
		WorkerStatePacket.sync(mob, data);
	}

	/**
	 * Takes a mob off the job, and returns whatever it should drop.
	 *
	 * <p>A villager's profession is left alone here too, and vanilla tidies it: a worker with no job
	 * site that has never traded and is still on trade level one is exactly what {@code
	 * ResetProfession} clears, so within a tick or two of its station going away it is an ordinary
	 * unemployed villager that can take any job again. That only works because nothing raises its
	 * trade level any more.
	 */
	public static List<ItemStack> dismiss(Mob mob) {
		WorkerData data = get(mob);
		if (data == null || !data.isEmployed())
			return List.of();

		wake(mob);
		List<ItemStack> drops = data.dismiss();
		updateCargoAppearance(mob, ItemStack.EMPTY);

		WorkerLocomotion locomotion = locomotionFor(mob);
		if (locomotion != null)
			locomotion.stop(mob);

		WorkerStatePacket.sync(mob, data);
		return drops;
	}

	/**
	 * Gets a worker out of bed.
	 *
	 * <p>A bed keeps the {@code OCCUPIED} flag it was given when somebody lay down in it, and clearing
	 * it is the sleeper's job on the way out. Dying does that for itself — {@code LivingEntity.die}
	 * wakes the entity first — but being <em>replaced</em> does not, so a worker bitten in its sleep
	 * would leave a bed nobody could ever use again. Cheap to call when the worker is already up.
	 */
	public static void wake(Entity entity) {
		if (entity instanceof LivingEntity living && living.isSleeping())
			living.stopSleeping();
	}

	/**
	 * Updates anything the mob itself draws for its cargo. Endermen already have a vanilla layer for
	 * a carried block, so a block cargo is handed to that rather than drawn twice.
	 *
	 * <p>That only tells the truth because an employed enderman is barred from picking blocks up and
	 * putting them down of its own accord — see {@code WorkerEvents.onMobGriefing}. Otherwise the
	 * block in its hands is whatever it last dug out of the floor.
	 */
	public static void updateCargoAppearance(Mob mob, ItemStack cargo) {
		if (!(mob instanceof EnderMan enderman))
			return;
		if (cargo.getItem() instanceof BlockItem blockItem)
			enderman.setCarriedBlock(blockItem.getBlock()
				.defaultBlockState());
		else
			enderman.setCarriedBlock(null);
	}
}
