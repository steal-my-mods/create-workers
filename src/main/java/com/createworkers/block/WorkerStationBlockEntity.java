package com.createworkers.block;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWBlockEntities;
import com.createworkers.worker.Workers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
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

	/**
	 * Sacks whoever is wearing the hat, and hands back whatever they were carrying.
	 *
	 * <p>This is how a villager worker is fired, there being no other way now: take the hat out of the
	 * station, or break it. The hat itself is not among the drops — it is the one in this block, which
	 * goes back to the player who asked for it — but a half-finished delivery is the worker's and is
	 * dropped where it stands rather than deleted.
	 */
	public void dismissWorker() {
		if (!(level instanceof ServerLevel server) || workerId == null)
			return;

		Entity entity = server.getEntity(workerId);
		if (entity instanceof Mob mob)
			for (ItemStack drop : Workers.dismiss(mob))
				mob.spawnAtLocation(drop);
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
			if (Workers.isEmployed(villager))
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
	 * <p>Nothing is done to the villager's profession, because vanilla has already done it: only a
	 * villager with no profession is ever converted, and {@code AssignProfessionFromJobSite} made this
	 * one a Worker on arrival. When the job goes away so does its job site, and a Worker with no job
	 * site that has never traded is exactly what {@code ResetProfession} clears.
	 */
	private void hire(ServerLevel server, Villager villager, WorkerProgram programme) {
		Workers.employ(villager, hat, programme, GlobalPos.of(server.dimension(), worldPosition));
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
