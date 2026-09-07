package com.createworkers.worker;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.registry.CWAttachments;
import com.createworkers.registry.CWProfessions;

import java.util.ArrayList;
import java.util.List;

import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;

/** Helpers for reading and classifying worker state. */
public class Workers {

	/**
	 * The trade level a hired villager is held at, because {@code ResetProfession} only wipes the
	 * profession of a villager still on its first. See {@link #clearVillageJob}.
	 */
	private static final int RESET_PROOF_LEVEL = 2;

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
	 * Whether a mob is old enough to be handed a hard hat, which is a question about hiring and only
	 * about hiring. Children grow up, so one turned away today can be hired the day it does — and
	 * because {@code WorkerEvents} gives the job goal to every villager as it spawns, employed or
	 * not, growing up needs no bookkeeping of its own. Gating the goal on age instead would leave a
	 * villager that grew up unable to work for the rest of its life.
	 *
	 * <p>Endermen have no young, so this is a villager rule in generic clothes.
	 */
	public static boolean isOldEnoughToWork(Entity entity) {
		if (CWConfig.HIRE_CHILDREN.get())
			return true;
		return !(entity instanceof LivingEntity living) || !living.isBaby();
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
	 * Takes a villager off its village job and onto the payroll.
	 *
	 * <p>The order here is load-bearing. {@code Villager.releasePoi} only lets go of a job site if
	 * the villager's <em>current</em> profession still claims that kind of site — the predicate in
	 * {@code Villager.POI_MEMORIES} for {@code JOB_SITE} is the profession's own {@code heldJobSite}
	 * — so changing the profession first would leave the composter ticketed to a worker that can
	 * never use it, for the rest of the world's life, with nothing to show why. Release first, then
	 * change the job. Releasing also does not erase the memory, so that is done by hand: a stale
	 * job site is a workstation this worker would still work if it ever stood within 1.73 blocks of
	 * it, which is the only thing {@code WorkAtPoi} asks.
	 *
	 * <p>Two things vanilla does behind the profession have to be answered, and neither is optional.
	 *
	 * <p>The brain <em>bakes the profession in</em>: {@code Villager.registerBrainGoals} builds
	 * {@code AcquirePoi(profession.acquirableJobSite(), ...)} into the CORE package once, so a
	 * villager whose profession changed without a {@code refreshBrain} keeps hunting for the
	 * workstations of the job it no longer has — it would re-ticket the very composter this method
	 * just handed back, a few tens of ticks later, and pathfind across its follow range looking for
	 * more. Vanilla pairs every profession change with {@code refreshBrain} for exactly this reason
	 * ({@code ResetProfession} and {@code AssignProfessionFromJobSite} both do).
	 *
	 * <p>And {@code ResetProfession} — CORE, priority 10 — wipes the profession of any villager that
	 * has no job site, has never traded and is still level 1, exempting only {@code NONE} and
	 * {@code NITWIT} by name. A worker is all three of those things by design, so without something
	 * done about it a hired villager's profession is reset to {@code NONE} within a tick or two:
	 * precisely the everything-acquiring state {@link CWProfessions} exists to avoid. Occupying
	 * {@code JOB_SITE} cannot save it, because {@code ValidateNearbyPoi} runs at priority 0 and
	 * erases a job site the profession does not claim before {@code ResetProfession} reads it in the
	 * same tick. What is left is the level: at 2 the reset does not apply. The real one is stashed
	 * with the rest of the job and comes back on retirement.
	 *
	 * <p>Endermen have no profession, so this is a villager rule in generic clothes.
	 */
	public static void clearVillageJob(Mob mob, WorkerData data) {
		if (!(mob instanceof Villager villager))
			return;

		VillagerData job = villager.getVillagerData();
		if (job.getProfession() == CWProfessions.WORKER.get())
			return;

		villager.releasePoi(MemoryModuleType.JOB_SITE);
		villager.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
		villager.getBrain()
			.eraseMemory(MemoryModuleType.JOB_SITE);
		villager.getBrain()
			.eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);

		data.stashVillageJob(job, villager.getOffers());
		villager.setVillagerData(job.setProfession(CWProfessions.WORKER.get())
			.setLevel(Math.max(job.getLevel(), RESET_PROOF_LEVEL)));
		refreshBrain(villager);
	}

	/**
	 * Gives back the village job a worker was hired out of, trades and all.
	 *
	 * <p>The order is load-bearing here too, the other way about: {@code setVillagerData} throws the
	 * trade list away when the profession changes, so the offers have to go back afterwards or they
	 * are lost on the way in.
	 *
	 * <p>The villager keeps its profession but not its old workstation — that went back to the
	 * village when it was hired, and may well be someone else's by now. Vanilla will send it looking
	 * for another one, exactly as it would for any villager whose job site was taken, which is what
	 * the brain refresh at the end is for.
	 */
	public static void restoreVillageJob(Mob mob, WorkerData data) {
		if (!(mob instanceof Villager villager))
			return;

		VillagerData job = data.takeStashedJob();
		if (job == null)
			return;

		villager.setVillagerData(job);
		MerchantOffers offers = data.takeStashedOffers();
		if (offers != null)
			villager.setOffers(offers);
		refreshBrain(villager);
	}

	/**
	 * Rebuilds the brain around the profession the villager now holds.
	 *
	 * <p>Not optional either way round: a retired villager whose brain still carried the worker's
	 * job-site predicate — which matches nothing — could never find a workstation again.
	 */
	private static void refreshBrain(Villager villager) {
		if (villager.level() instanceof ServerLevel level)
			villager.refreshBrain(level);
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
