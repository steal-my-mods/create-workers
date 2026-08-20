package com.createworkers.worker;

import org.jetbrains.annotations.Nullable;

import com.createworkers.registry.CWAttachments;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;

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

	@Nullable
	public static WorkerLocomotion locomotionFor(Mob mob) {
		if (mob instanceof EnderMan)
			return TeleportLocomotion.INSTANCE;
		if (mob instanceof Villager)
			return WalkLocomotion.INSTANCE;
		return null;
	}
}
