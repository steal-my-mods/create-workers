package com.createworkers.worker;

import org.jetbrains.annotations.Nullable;

import com.createworkers.registry.CWAttachments;

import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
	 * Updates anything the mob itself draws for its cargo. Endermen already have a vanilla layer for
	 * a carried block, so a block cargo is handed to that rather than drawn twice.
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
