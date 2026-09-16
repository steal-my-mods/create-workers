package com.createworkers.block;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWBlockEntities;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.WorkerShift;
import com.createworkers.worker.Workers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Holds one programmed hard hat and sees that a villager is wearing it.
 *
 * <p>Nearly all of the hiring is vanilla's. The station is a point of interest in the
 * {@code acquirable_job_site} tag, so an unemployed villager finds it, walks to it and takes a ticket
 * through {@code AcquirePoi}, and {@code AssignProfessionFromJobSite} turns it into a Worker once it
 * is within two blocks — the same path that makes a librarian out of somebody standing at a lectern.
 * All this has to do is notice that it happened and hand the hat over.
 *
 * <p>The hat <b>stays in the station</b> and the worker wears a copy. That one decision is the whole
 * self-healing property: nothing has to be handed back when a worker dies, because the job never left
 * the block. A dying villager releases its point-of-interest tickets, so the station simply finds
 * itself unstaffed and hires the next villager to come along.
 */
public class WorkerStationBlockEntity extends BlockEntity {

	/** How often an unstaffed station looks for somebody who has claimed it. */
	private static final int STAFFING_INTERVAL = 20;
	/** How far from the block to look. Vanilla assigns the profession within 2; this is slack on that. */
	private static final double HIRING_RANGE = 4.0D;

	private ItemStack hat = ItemStack.EMPTY;
	/**
	 * Who is wearing it. Remembered so that "am I staffed?" is a lookup by id rather than a sweep of
	 * everything nearby, and so that breaking the block can find its worker to let it go.
	 */
	@Nullable
	private UUID workerId;

	private int untilNextLook;

	public WorkerStationBlockEntity(BlockPos pos, BlockState state) {
		super(CWBlockEntities.WORKER_STATION.get(), pos, state);
	}

	public ItemStack getHat() {
		return hat;
	}

	public boolean hasJob() {
		return !hat.isEmpty();
	}

	/**
	 * Takes a hat in, or gives the one it holds back.
	 *
	 * <p>The block state follows, because {@code has_job} is what the point of interest is registered
	 * over: a station with no hat is not a job site at all, so nobody walks to it and nobody is left
	 * standing beside it having become a Worker with nothing to do — and unable to take any other job,
	 * a Worker's only workstation being the block it is standing at.
	 */
	public void setHat(ItemStack stack) {
		this.hat = stack;
		setChanged();
		if (level != null)
			level.setBlock(worldPosition, getBlockState().setValue(WorkerStationBlock.HAS_JOB, hasJob()), 3);
	}

	/** Lets go of whoever is wearing the hat, so they carry on as an ordinary hand-hired worker. */
	public void releaseWorker() {
		if (!(level instanceof ServerLevel server) || workerId == null)
			return;

		Entity entity = server.getEntity(workerId);
		if (entity != null) {
			WorkerData data = Workers.get(entity);
			if (data != null)
				data.forgetStation();
		}
		workerId = null;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, WorkerStationBlockEntity station) {
		if (station.untilNextLook-- > 0)
			return;
		station.untilNextLook = STAFFING_INTERVAL;
		station.staffUp(level);
	}

	/**
	 * Finds the villager that has claimed this station and puts it to work.
	 *
	 * <p>Only ever one: the point of interest is registered with a single ticket, so vanilla itself
	 * refuses a second claimant while the first is alive.
	 */
	private void staffUp(Level level) {
		if (!hasJob() || !(level instanceof ServerLevel server))
			return;
		if (stillStaffed(server))
			return;

		WorkerProgram programme = HardHatItem.getProgram(hat);
		if (!programme.hasTargets())
			return;

		AABB nearby = new AABB(worldPosition).inflate(HIRING_RANGE);
		for (Villager villager : server.getEntitiesOfClass(Villager.class, nearby, this::hasClaimedThis)) {
			if (Workers.isEmployed(villager) || !Workers.isOldEnoughToWork(villager))
				continue;

			hire(server, villager, programme);
			return;
		}
	}

	private boolean stillStaffed(ServerLevel server) {
		if (workerId == null)
			return false;

		Entity entity = server.getEntity(workerId);
		if (entity == null || !entity.isAlive() || !Workers.isEmployed(entity)) {
			workerId = null;
			return false;
		}
		return true;
	}

	private boolean hasClaimedThis(Villager villager) {
		return villager.getBrain()
			.getMemory(MemoryModuleType.JOB_SITE)
			.map(site -> site.pos()
				.equals(worldPosition))
			.orElse(false);
	}

	/**
	 * Puts the hat on, without taking it out of the station.
	 *
	 * <p>The villager was unemployed a moment ago — {@code AssignProfessionFromJobSite} only ever
	 * converts one whose profession is {@code NONE} — so that is what it goes back to when it retires.
	 * Saying so explicitly is what stops a retired worker keeping a profession whose only workstation
	 * is a block it no longer has.
	 */
	private void hire(ServerLevel server, Villager villager, WorkerProgram programme) {
		WorkerData data = Workers.getOrCreate(villager);
		VillagerData asHired = villager.getVillagerData();
		data.stashVillageJob(asHired.setProfession(VillagerProfession.NONE)
			.setLevel(1), new MerchantOffers());

		data.employ(hat, programme);
		data.rememberStation(GlobalPos.of(server.dimension(), worldPosition));
		// Vanilla gave it the profession, so clearVillageJob would bow out -- and take the schedule
		// with it, since that is applied on the way through.
		WorkerShift.applySchedule(villager);
		Workers.updateCargoAppearance(villager, data.getHeld());

		workerId = villager.getUUID();
		setChanged();
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		hat = tag.contains("Hat") ? ItemStack.parseOptional(registries, tag.getCompound("Hat")) : ItemStack.EMPTY;
		workerId = tag.hasUUID("Worker") ? tag.getUUID("Worker") : null;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		if (!hat.isEmpty())
			tag.put("Hat", hat.save(registries));
		if (workerId != null)
			tag.putUUID("Worker", workerId);
	}
}
