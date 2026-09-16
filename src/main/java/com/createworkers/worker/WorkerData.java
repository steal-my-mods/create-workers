package com.createworkers.worker;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.CreateWorkers;
import com.createworkers.program.WorkerProgram;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * Everything a hard-hatted entity needs to do its job, attached to the entity itself.
 *
 * <p>The transfer algorithm is a port of {@code ArmBlockEntity}: same round-robin input and
 * output selection, same "only take what we can actually put somewhere" rule. The difference
 * is that the arm swings to a target while a worker has to walk or teleport there, so the
 * movement phases are driven externally by {@link WorkerJobGoal}.
 */
public class WorkerData implements INBTSerializable<CompoundTag> {

	public enum Phase {
		SEARCH_INPUTS, MOVE_TO_INPUT, SEARCH_OUTPUTS, MOVE_TO_OUTPUT
	}

	private WorkerProgram program = WorkerProgram.EMPTY;
	private ItemStack hat = ItemStack.EMPTY;
	private ItemStack held = ItemStack.EMPTY;
	private Phase phase = Phase.SEARCH_INPUTS;
	/** Derived from the programme, not from wherever the hat was handed over. Not persisted. */
	private BlockPos jobSite = BlockPos.ZERO;
	/**
	 * The station that hired this worker, if one did.
	 *
	 * <p>Its presence is what says the hat on this worker's head is a copy of one still sitting in a
	 * block — so the worker must not drop it when it dies, or every death mints a second hat. Cleared
	 * when the station is broken or the hat taken out of it, after which the worker is indistinguishable
	 * from one hired by hand.
	 */
	@Nullable
	private GlobalPos station;
	/**
	 * Which crew this worker belongs to, and so which hours it keeps.
	 *
	 * <p>Lives on the worker rather than on the hat. A station's slot says which shifts a job
	 * <em>runs</em>; this says which of them this particular villager was hired onto, and it is what
	 * {@code WorkerShift} reads to decide when the tools go down. A hand-hired enderman keeps the day
	 * crew's hours and never consults them, having no schedule at all.
	 */
	private Shift shift = Shift.DAY;
	private int targetIndex = -1;
	private int lastInputIndex = -1;
	private int lastOutputIndex = -1;
	private int cooldown = 0;

	// --- runtime only, rebuilt on demand -------------------------------------------------

	/** How long to leave a target that would not resolve before trying it again. */
	private static final int RESOLVE_RETRY_TICKS = 100;

	private final List<WorkerTarget> inputs = new ArrayList<>();
	private final List<WorkerTarget> outputs = new ArrayList<>();
	/** Stored points that could not be turned into targets yet — see {@link #retryPending}. */
	private final List<CompoundTag> pending = new ArrayList<>();
	private long retryAfter;
	private boolean resolved;
	/** The slot {@link #searchForItem} settled on, handed to {@link #collectFrom} as a hint. */
	private int foundSlot = -1;
	private int validityChecks;
	private int slotProbes;
	private int deliveryProbes;
	private int bedSearches;
	private int leashFailures;
	/**
	 * When this worker was last standing near its own work.
	 *
	 * <p>The signal a station's absentee rule reads, and deliberately about *position* rather than
	 * about progress: a worker with nothing to haul is idle, not absent, and one cycling through
	 * targets it can never reach has a target selected every tick and would otherwise look busy
	 * forever. Set when the worker is hired and again whenever it loads, because it freezes while a
	 * chunk is away and a worker coming back after an hour of game time is not an absentee.
	 */
	private long lastAtWork;
	@Nullable
	private ArmBlockEntity host;

	// --- state ---------------------------------------------------------------------------

	public boolean isEmployed() {
		return !hat.isEmpty();
	}

	public WorkerProgram getProgram() {
		return program;
	}

	public ItemStack getHat() {
		return hat;
	}

	public ItemStack getHeld() {
		return held;
	}

	public void setHeld(ItemStack stack) {
		this.held = stack;
	}

	public Phase getPhase() {
		return phase;
	}

	public void setPhase(Phase phase) {
		this.phase = phase;
	}

	public int getTargetIndex() {
		return targetIndex;
	}

	/** The centre of this worker's programmed targets — its post, and the anchor for the leash. */
	public BlockPos getJobSite() {
		return jobSite;
	}

	public int getCooldown() {
		return cooldown;
	}

	public void setCooldown(int cooldown) {
		this.cooldown = cooldown;
	}

	public boolean tickCooldown() {
		if (cooldown <= 0)
			return false;
		cooldown--;
		return true;
	}

	/** @return where this worker was hired from, or null if a player handed it the hat. */
	@Nullable
	public GlobalPos getStation() {
		return station;
	}

	public void rememberStation(GlobalPos pos) {
		this.station = pos;
	}

	/** Un-enrols a worker whose station has gone, leaving it exactly like a hand-hired one. */
	public void forgetStation() {
		this.station = null;
	}

	/** @return the crew this worker is on, and so the hours it keeps. */
	public Shift getShift() {
		return shift;
	}

	public void setShift(Shift shift) {
		this.shift = shift;
	}

	/** Puts the entity to work with the given hat. */
	public void employ(ItemStack hatStack, WorkerProgram program) {
		this.hat = hatStack.copyWithCount(1);
		this.program = program.copy();
		this.jobSite = this.program.centre();
		this.phase = Phase.SEARCH_INPUTS;
		this.targetIndex = -1;
		this.lastInputIndex = -1;
		this.lastOutputIndex = -1;
		this.cooldown = 0;
		this.station = null;
		this.shift = Shift.DAY;
		invalidatePoints();
	}

	/**
	 * Moves the worker to a different job without taking anything off it.
	 *
	 * <p>A promotion is not a sacking, and it should not look like one. Dismissing and re-hiring drops
	 * whatever the worker was carrying on the floor — items that came out of the player's machines,
	 * scattered in the middle of a shift for a reason invisible from anywhere in the world. Keeping the
	 * cargo and starting in {@code SEARCH_OUTPUTS} means the worker delivers what is in its hands into
	 * its new job before it picks anything else up, which is what a hand-over looks like.
	 */
	public void reassign(ItemStack hatStack, WorkerProgram program) {
		ItemStack carried = held;
		employ(hatStack, program);
		held = carried;
		phase = held.isEmpty() ? Phase.SEARCH_INPUTS : Phase.SEARCH_OUTPUTS;
	}

	/**
	 * Takes the entity off the job and returns everything it should drop: the hat plus
	 * whatever it was carrying.
	 */
	public List<ItemStack> dismiss() {
		List<ItemStack> drops = new ArrayList<>();
		// A station worker's hat is a copy of one still in the block, so dropping it would mint a
		// second. The cargo is the worker's own either way.
		if (!hat.isEmpty() && station == null)
			drops.add(hat);
		if (!held.isEmpty())
			drops.add(held);
		hat = ItemStack.EMPTY;
		held = ItemStack.EMPTY;
		program = WorkerProgram.EMPTY;
		jobSite = BlockPos.ZERO;
		phase = Phase.SEARCH_INPUTS;
		targetIndex = -1;
		station = null;
		shift = Shift.DAY;
		releasePoints();
		return drops;
	}

	/**
	 * Applies the render state pushed from the server. Only the visible parts travel, so this
	 * must never touch the program or the transfer bookkeeping.
	 */
	public void applyClientState(ItemStack hat, ItemStack held) {
		this.hat = hat;
		this.held = held;
	}

	// --- point resolution ------------------------------------------------------------------

	public void invalidatePoints() {
		resolved = false;
		inputs.clear();
		outputs.clear();
		pending.clear();
		foundSlot = -1;
	}

	/**
	 * Drops the capability caches held by this worker's interaction points. The dummy arm
	 * block entity is the liveness token those caches watch, so marking it removed is what
	 * lets the level stop tracking them.
	 */
	public void releasePoints() {
		invalidatePoints();
		if (host != null) {
			host.setRemoved();
			host = null;
		}
	}

	/**
	 * Create's interaction points do their inserting and extracting through an
	 * {@code ArmBlockEntity}, but only ever to ask whether it is still alive. A worker owns a
	 * detached one purely as that liveness token, which is what buys compatibility with every
	 * registered interaction point type — belts, depots, funnels, crafters and addon blocks
	 * included — rather than plain item handlers only.
	 */
	@Nullable
	public ArmBlockEntity host(Level level, BlockPos pos) {
		if (host == null) {
			try {
				host = new ArmBlockEntity(AllBlockEntityTypes.MECHANICAL_ARM.get(), pos,
					AllBlocks.MECHANICAL_ARM.getDefaultState());
			} catch (Exception e) {
				CreateWorkers.LOGGER.error("Could not create the interaction host for a worker", e);
				return null;
			}
		}
		host.setLevel(level);
		return host;
	}

	public void resolvePoints(LivingEntity worker) {
		if (resolved) {
			retryPending(worker);
			return;
		}
		resolved = true;
		inputs.clear();
		outputs.clear();
		pending.clear();

		Level level = worker.level();
		ArmBlockEntity host = host(level, worker.blockPosition());
		int limit = CWConfig.MAX_TARGETS.get();
		int read = 0;
		for (Tag entry : program.points()) {
			if (!(entry instanceof CompoundTag compound))
				continue;
			if (++read > limit) {
				// Only reachable through a hat that was not programmed by clicking -- a command, or a
				// config that used to allow more. Refusing the surplus keeps the cost of a scan bounded
				// by something an operator chose.
				CreateWorkers.LOGGER.warn("A hard hat carries {} targets but the limit is {}; ignoring the rest",
					program.size(), limit);
				break;
			}
			if (!resolve(compound, level, host))
				pending.add(compound);
		}
		retryAfter = level.getGameTime() + RESOLVE_RETRY_TICKS;
	}

	/**
	 * Tries again on the points that would not resolve.
	 *
	 * <p>Resolution is one-shot per load, which leaves two ways for a target to go missing for the
	 * rest of a worker's life: its chunk was not loaded at the time — deliberately not waited for,
	 * because reading a block there would pull the chunk in — or its block was gone and has since been
	 * rebuilt. Neither should be permanent, and neither is worth re-resolving a whole programme over,
	 * so only the ones that failed are retried, and the ones that worked are never rebuilt.
	 */
	private void retryPending(LivingEntity worker) {
		if (pending.isEmpty())
			return;

		Level level = worker.level();
		long now = level.getGameTime();
		if (now < retryAfter)
			return;
		retryAfter = now + RESOLVE_RETRY_TICKS;

		ArmBlockEntity host = host(level, worker.blockPosition());
		pending.removeIf(compound -> resolve(compound, level, host));
	}

	/**
	 * Turns one stored point into a live target.
	 *
	 * @return whether it worked. A false here is always worth retrying later.
	 */
	private boolean resolve(CompoundTag compound, Level level, @Nullable ArmBlockEntity host) {
		BlockPos pos = NbtUtils.readBlockPos(compound, "Pos")
			.orElse(null);
		// Never read a block in a chunk nobody has loaded: that is what loads it.
		if (pos == null || !level.isLoaded(pos))
			return false;

		WorkerTarget target = WorkerTarget.deserialize(compound, level);
		if (target == null)
			return false;

		target.bind(host);
		if (target.getMode() == Mode.DEPOSIT)
			outputs.add(target);
		else
			inputs.add(target);
		return true;
	}

	/** Sets aside every resolved target at {@code pos} — for work and for the idle rounds alike. */
	public void markUnreachable(BlockPos pos, long gameTime) {
		markUnreachable(inputs, pos, gameTime);
		markUnreachable(outputs, pos, gameTime);
	}

	private static void markUnreachable(List<WorkerTarget> targets, BlockPos pos, long gameTime) {
		for (WorkerTarget target : targets)
			if (target.getPos()
				.equals(pos))
				target.markUnreachable(gameTime);
	}

	public List<WorkerTarget> getInputs() {
		return inputs;
	}

	public List<WorkerTarget> getOutputs() {
		return outputs;
	}

	@Nullable
	public WorkerTarget getTargetPoint() {
		if (targetIndex < 0)
			return null;
		if (phase == Phase.MOVE_TO_INPUT && targetIndex < inputs.size())
			return inputs.get(targetIndex);
		if (phase == Phase.MOVE_TO_OUTPUT && targetIndex < outputs.size())
			return outputs.get(targetIndex);
		return null;
	}

	public boolean hasWork() {
		return !inputs.isEmpty() || !outputs.isEmpty();
	}

	// --- what the last search cost ------------------------------------------------------------

	/*
	 * The three operations a search is priced in, tallied as it goes.
	 *
	 * These exist to be asserted on. What this algorithm costs a server is a count of block reads
	 * and handler probes, and a test that timed it would be measuring the machine it ran on -- so
	 * the cost tests assert the counts instead, against bounds derived from the size of the
	 * programme rather than against a number somebody once measured. That makes them deterministic
	 * and worth the same on any hardware, and it makes them complexity assertions: "each output is
	 * checked once per search" is a claim about the shape of the work, and it fails the moment a
	 * check drifts back inside a loop.
	 *
	 * Per instance, not static: a worker is owned by one entity on one thread, so there is nothing
	 * to synchronise and no flag to turn on. The cost is an int increment beside operations that
	 * each already do a block read or a capability lookup.
	 *
	 * Only simulated probes are counted. The one real extract or insert that ends a search is the
	 * work, not the looking.
	 */

	/** Target validity checks — each one a block read — made by the last search. */
	public int validityChecks() {
		return validityChecks;
	}

	/** Slots the last search looked into. */
	public int slotProbes() {
		return slotProbes;
	}

	/** Simulated deliveries the last search priced. */
	public int deliveryProbes() {
		return deliveryProbes;
	}

	/**
	 * Bed searches this worker has made in its life.
	 *
	 * <p>A lifetime tally rather than a per-search one, because what needs bounding here is a rate:
	 * a bed hunt is a point-of-interest query and, for a bed nobody assigned, a pathfind, and the
	 * worker that wants one most often is the one that can never find it. Counted for the whole life
	 * so a test can watch a hopeless worker through a night and assert it looked a handful of times
	 * rather than three hundred.
	 */
	public int bedSearches() {
		return bedSearches;
	}

	void countBedSearch() {
		bedSearches++;
	}

	/**
	 * How many times in a row this worker has failed to walk back to its work.
	 *
	 * <p>Worker state rather than goal state, for the same reason the cost tallies are: it is a fact
	 * about this villager, it is what the leash's back-off and its distress signal are both computed
	 * from, and it is the only externally visible sign that a worker is stuck rather than merely slow.
	 * Runtime only, never serialized — a worker that comes back from disk deserves a fresh start, and
	 * one that was genuinely walled in will re-earn the count within a couple of minutes.
	 */
	public int leashFailures() {
		return leashFailures;
	}

	public void recordLeashFailure() {
		leashFailures++;
	}

	public void clearLeashFailures() {
		leashFailures = 0;
	}

	/** @return the game time this worker was last near its own work. */
	public long lastAtWork() {
		return lastAtWork;
	}

	public void markAtWork(long gameTime) {
		lastAtWork = gameTime;
	}

	private void resetCostAccount() {
		validityChecks = 0;
		slotProbes = 0;
		deliveryProbes = 0;
	}

	/** {@link WorkerTarget#isValid}, on the account. */
	private boolean valid(WorkerTarget point) {
		validityChecks++;
		return point.isValid();
	}

	/** A simulated extract, on the account. */
	private ItemStack probe(WorkerTarget point, int slot) {
		slotProbes++;
		return point.extract(slot, true);
	}

	/** A simulated insert, on the account. */
	private ItemStack offer(WorkerTarget point, ItemStack stack) {
		deliveryProbes++;
		return point.insert(stack, true);
	}

	// --- the arm's transfer algorithm --------------------------------------------------------

	/**
	 * @return the index of an input worth walking to, or -1.
	 *
	 * Round-robins from just after the last input used, wrapping all the way around before giving
	 * up. The arm can afford to bail out at the end of the list and rescan next tick because it
	 * ticks continuously; a worker that did that would idle for a whole rescan delay every time it
	 * used the last input in its list.
	 *
	 * <p>Also records the slot it settled on, for {@link #collectFrom} to try before walking the
	 * inventory again.
	 */
	public int searchForItem(long gameTime) {
		resetCostAccount();
		foundSlot = -1;
		int count = inputs.size();
		if (count == 0) {
			lastInputIndex = -1;
			return -1;
		}

		List<WorkerTarget> usable = usableOutputs(gameTime);
		for (int offset = 0; offset < count; offset++) {
			int i = Math.floorMod(lastInputIndex + 1 + offset, count);
			WorkerTarget point = inputs.get(i);
			// Set-aside first: it is a comparison, where the validity check is a block read.
			if (point.isUnreachable(gameTime) || !valid(point))
				continue;
			// Out of the loop condition: each call is a capability lookup, not a field read.
			int slots = point.getSlotCount();
			for (int slot = 0; slot < slots; slot++) {
				if (getDistributableAmount(point, slot, usable) == 0)
					continue;
				lastInputIndex = i;
				foundSlot = slot;
				return i;
			}
		}
		lastInputIndex = -1;
		return -1;
	}

	/**
	 * The outputs worth simulating a delivery into, gathered once for a whole scan.
	 *
	 * <p>Gathering them is the point. Whether a stack fits anywhere is priced once per slot of every
	 * input, and each output's validity check is a block read — Create's
	 * {@code ArmInteractionPoint.isValid} refreshes its cached state with a plain
	 * {@code Level.getBlockState}, and a belt point reads a second one above itself. Asking inside
	 * that loop puts the read on inputs × slots × outputs to learn an answer that only varies per
	 * output: a dozen basins in and a dozen out is a few thousand block reads per scan where two
	 * dozen will do. That is not a rare worst case either — it is what a scan costs whenever the
	 * inputs hold items the outputs will not take, which is every second, for as long as the line
	 * stays backed up.
	 *
	 * <p>Safe to hold across the scan because nothing moves during one: every insertion priced
	 * against this list is simulated.
	 */
	private List<WorkerTarget> usableOutputs(long gameTime) {
		List<WorkerTarget> usable = new ArrayList<>(outputs.size());
		for (WorkerTarget point : outputs)
			if (!point.isUnreachable(gameTime) && valid(point))
				usable.add(point);
		return usable;
	}

	/** @return the index of an output that will accept the held stack, or -1. Wraps, as above. */
	public int searchForDestination(long gameTime) {
		resetCostAccount();
		int count = outputs.size();
		for (int offset = 0; offset < count; offset++) {
			int i = Math.floorMod(lastOutputIndex + 1 + offset, count);
			WorkerTarget point = outputs.get(i);
			if (point.isUnreachable(gameTime) || !valid(point))
				continue;
			ItemStack remainder = offer(point, held.copy());
			if (ItemStack.matches(remainder, held))
				continue;
			lastOutputIndex = i;
			return i;
		}
		lastOutputIndex = -1;
		return -1;
	}

	/**
	 * How much of a slot could be taken and actually placed somewhere. Mirrors the arm so a
	 * worker never picks up items it has nowhere to put.
	 */
	private int getDistributableAmount(WorkerTarget point, int slot, List<WorkerTarget> usableOutputs) {
		ItemStack stack = probe(point, slot);
		if (stack.isEmpty())
			return 0;
		ItemStack remainder = simulateInsertion(stack, usableOutputs);
		if (ItemStack.isSameItem(stack, remainder))
			return stack.getCount() - remainder.getCount();
		return stack.getCount();
	}

	/**
	 * Somewhere a worker cannot get to is not somewhere it can put things — which is already true of
	 * everything on {@code usableOutputs}, so this only has to try them in turn.
	 */
	private ItemStack simulateInsertion(ItemStack stack, List<WorkerTarget> usableOutputs) {
		for (WorkerTarget point : usableOutputs) {
			stack = offer(point, stack);
			if (stack.isEmpty())
				break;
		}
		return stack;
	}

	/** @return true if something was picked up. */
	public boolean collectFrom(WorkerTarget point, long gameTime) {
		resetCostAccount();
		if (valid(point)) {
			List<WorkerTarget> usable = usableOutputs(gameTime);
			int slots = point.getSlotCount();

			// The slot the scan settled on, tried ahead of the walk that would find it again. Every
			// slot such a walk passes over is priced against every output, so on a wide inventory
			// whose earlier slots hold nothing deliverable the scan is paid twice for one pickup.
			//
			// Only a hint. The worker has travelled since it was recorded, so the amount is checked
			// again here, and a slot emptied in the meantime simply falls through to the full walk.
			int hint = foundSlot;
			foundSlot = -1;
			if (hint >= 0 && hint < slots && take(point, hint, usable))
				return !held.isEmpty();

			for (int slot = 0; slot < slots; slot++) {
				if (slot == hint)
					continue; // just tried it
				if (take(point, slot, usable))
					return !held.isEmpty();
			}
		}
		phase = Phase.SEARCH_INPUTS;
		targetIndex = -1;
		return false;
	}

	/**
	 * Takes as much of one slot as there is somewhere to put.
	 *
	 * @return whether anything was taken, and so is now in hand.
	 */
	private boolean take(WorkerTarget point, int slot, List<WorkerTarget> usableOutputs) {
		int amount = getDistributableAmount(point, slot, usableOutputs);
		if (amount == 0)
			return false;
		held = point.extract(slot, amount, false);
		phase = Phase.SEARCH_OUTPUTS;
		targetIndex = -1;
		return true;
	}

	/** @return true if at least part of the stack was handed over. */
	public boolean depositTo(WorkerTarget point) {
		resetCostAccount();
		boolean moved = false;
		if (valid(point)) {
			ItemStack before = held.copy();
			held = point.insert(held.copy(), false);
			moved = !ItemStack.matches(before, held);
		}
		phase = held.isEmpty() ? Phase.SEARCH_INPUTS : Phase.SEARCH_OUTPUTS;
		targetIndex = -1;
		return moved;
	}

	public void selectTarget(Phase movePhase, int index) {
		this.phase = movePhase;
		this.targetIndex = index;
	}

	public void abandonTarget() {
		this.targetIndex = -1;
		this.phase = held.isEmpty() ? Phase.SEARCH_INPUTS : Phase.SEARCH_OUTPUTS;
	}

	// --- serialization -------------------------------------------------------------------

	@Override
	public CompoundTag serializeNBT(HolderLookup.Provider provider) {
		CompoundTag tag = new CompoundTag();
		if (!hat.isEmpty())
			tag.put("Hat", hat.save(provider));
		if (!held.isEmpty())
			tag.put("Held", held.save(provider));
		tag.put("Program", program.tag());
		tag.putString("Phase", phase.name());
		tag.putInt("TargetIndex", targetIndex);
		tag.putInt("LastInput", lastInputIndex);
		tag.putInt("LastOutput", lastOutputIndex);
		tag.putInt("Cooldown", cooldown);
		tag.putString("Shift", shift.getSerializedName());
		if (station != null)
			GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, station)
				.resultOrPartial(CreateWorkers.LOGGER::error)
				.ifPresent(encoded -> tag.put("Station", encoded));
		return tag;
	}

	@Override
	public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
		hat = tag.contains("Hat") ? ItemStack.parseOptional(provider, tag.getCompound("Hat")) : ItemStack.EMPTY;
		held = tag.contains("Held") ? ItemStack.parseOptional(provider, tag.getCompound("Held")) : ItemStack.EMPTY;
		program = new WorkerProgram(tag.getCompound("Program"));
		jobSite = program.centre();
		phase = readPhase(tag.getString("Phase"));
		targetIndex = tag.getInt("TargetIndex");
		lastInputIndex = tag.getInt("LastInput");
		lastOutputIndex = tag.getInt("LastOutput");
		cooldown = tag.getInt("Cooldown");
		shift = Shift.byName(tag.getString("Shift"), Shift.DAY);
		station = null;
		if (tag.contains("Station"))
			GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.get("Station"))
				.resultOrPartial(CreateWorkers.LOGGER::error)
				.ifPresent(pos -> station = pos);
		invalidatePoints();
	}

	private static Phase readPhase(String name) {
		for (Phase candidate : Phase.values())
			if (candidate.name()
				.equals(name))
				return candidate;
		return Phase.SEARCH_INPUTS;
	}
}
