package com.createworkers.registry;

import com.createworkers.CreateWorkers;
import com.google.common.collect.ImmutableSet;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The village job a hired villager holds instead of the one it had.
 *
 * <p>Hiring is a career change rather than a shift: a worker is part of the factory, so the
 * profession it arrived with comes off and this one goes on — which is also what hands the
 * workstation it was sitting on back to the village.
 *
 * <p>Both job-site predicates are {@link PoiType#NONE}, which matches nothing, so a worker never
 * claims a workstation. That is the whole reason this profession exists rather than simply clearing
 * the villager's profession to {@code NONE}: an unemployed villager's <em>acquirable</em> predicate
 * is {@code ALL_ACQUIRABLE_JOBS}, and {@code AcquirePoi} takes a workstation's ticket the moment a
 * path to it merely exists — it never has to arrive. A worker's walk target is pinned every tick by
 * its job goal, so arriving is exactly what it would never do, and the ticket would sit taken for
 * the rest of the villager's life.
 *
 * <p>Vanilla's own never-works profession, {@code NITWIT}, is registered exactly like this and
 * would have done the mechanical job. A profession of our own was chosen because it reads as
 * "Worker" wherever professions are shown, because players and other mods have opinions about
 * culling nitwits that should not fall on a workforce, and because it degrades gracefully if this
 * mod is removed: {@code Villager.readAdditionalSaveData} parses an unknown profession id leniently,
 * so a worker becomes an ordinary unemployed villager that can take a job again, where a nitwit
 * would have stayed unemployable forever with its real job lost along with the attachment.
 *
 * <p>Named for the role and not for the task deliberately. A profession id is permanent save state
 * — renaming it strands every worker in every world on a fallback — and it is the hat's programme,
 * not the villager, that says whether today's job is hauling or something a later version adds.
 */
public class CWProfessions {

	public static final DeferredRegister<VillagerProfession> REGISTER =
		DeferredRegister.create(Registries.VILLAGER_PROFESSION, CreateWorkers.ID);

	/** Claims no workstation, works no job site, and has no trades of its own. */
	public static final DeferredHolder<VillagerProfession, VillagerProfession> WORKER =
		REGISTER.register("worker", id -> new VillagerProfession(id.getPath(), PoiType.NONE, PoiType.NONE,
			ImmutableSet.of(), ImmutableSet.of(), null));
}
