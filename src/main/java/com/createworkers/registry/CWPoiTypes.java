package com.createworkers.registry;

import java.util.Set;
import java.util.stream.Collectors;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlock;
import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.worker.Shift;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The worker station as a village workstation.
 *
 * <p>Deliberately <b>not</b> in {@code minecraft:acquirable_job_site}. It was, and that made hiring
 * vanilla's problem rather than ours — until it turned out that {@code YieldJobSite} makes every
 * applicant after the first give up its claim to the worker already standing there, which is right for
 * a lectern and fatal for a block that wants a crew. The station recruits for itself now.
 *
 * <p>What the point of interest is still for is everything that kept working. A worker's
 * {@code JOB_SITE} has to point at a POI whose type its profession claims, or {@code ValidateNearbyPoi}
 * erases the memory and {@code ResetProfession} clears the profession behind it — and the tickets are
 * how a station tells a worker that died from one that is merely in an unloaded chunk.
 */
public class CWPoiTypes {

	public static final DeferredRegister<PoiType> REGISTER =
		DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, CreateWorkers.ID);

	public static final ResourceKey<PoiType> WORKER_STATION_KEY =
		ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, CreateWorkers.asResource("worker_station"));

	/**
	 * Room for the largest roster the mod allows: every slot, on every shift.
	 *
	 * <p>{@code maxTickets} belongs to the point-of-interest <b>type</b> and is fixed when the type is
	 * registered, so it cannot follow the number of hats in any particular block — which is why
	 * {@code WorkerStationBlockEntity.MAX_SLOTS} is a constant rather than a config value, and why a
	 * station holds back the tickets it has no opening for. Free tickets then mean openings, and
	 * vanilla's own {@code AcquirePoi} enforces the count for us exactly as it enforces one librarian
	 * per lectern.
	 */
	public static final int MAX_TICKETS = WorkerStationBlockEntity.MAX_SLOTS * Shift.VALUES.length;

	/**
	 * The valid range is a block, as every other workstation's is.
	 *
	 * <p>Registered only over the states that have a hat in them. A station holding none is not a job
	 * site, so nobody walks to it and nobody arrives to find there is nothing to do.
	 */
	public static final DeferredHolder<PoiType, PoiType> WORKER_STATION =
		REGISTER.register("worker_station", () -> new PoiType(staffableStates(), MAX_TICKETS, 1));

	public static final ResourceKey<PoiType> CANTEEN_KEY =
		ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, CreateWorkers.asResource("canteen"));

	/**
	 * The canteen, registered so that it can be <em>found</em> and for no other reason.
	 *
	 * <p><b>Zero tickets</b>, which is the whole design. A point of interest is the only thing in the
	 * game that answers "where is the nearest one of these, within a radius, from here" without
	 * walking every block — which is what a hungry worker needs and what the bed hunt already uses. But
	 * a ticket is a <em>claim</em>, and nothing about eating is a claim: a canteen serves everybody, a
	 * villager that is not hungry has no business holding one, and a ticket held by a worker in an
	 * unloaded chunk would take a trough out of service for everyone else. With none to take,
	 * {@code AcquirePoi} can never pick this block up and {@code ValidateNearbyPoi} has nothing to
	 * validate; it is a landmark rather than a workstation.
	 *
	 * <p>It is also deliberately outside {@code minecraft:acquirable_job_site}, for the reason the
	 * Station learned the hard way — a villager that walks to a block to take a job it cannot do is a
	 * villager stuck with a profession and nothing to point it at.
	 */
	public static final DeferredHolder<PoiType, PoiType> CANTEEN =
		REGISTER.register("canteen", () -> new PoiType(canteenStates(), 0, 1));

	private static Set<BlockState> canteenStates() {
		return Set.copyOf(CWBlocks.CANTEEN.get()
			.getStateDefinition()
			.getPossibleStates());
	}

	private static Set<BlockState> staffableStates() {
		return CWBlocks.WORKER_STATION.get()
			.getStateDefinition()
			.getPossibleStates()
			.stream()
			.filter(state -> state.getValue(WorkerStationBlock.HAS_JOB))
			.collect(Collectors.toUnmodifiableSet());
	}
}
