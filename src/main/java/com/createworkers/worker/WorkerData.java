package com.createworkers.worker;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CreateWorkers;
import com.createworkers.program.WorkerProgram;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
	private int targetIndex = -1;
	private int lastInputIndex = -1;
	private int lastOutputIndex = -1;
	private int cooldown = 0;

	// --- runtime only, rebuilt on demand -------------------------------------------------

	private final List<WorkerTarget> inputs = new ArrayList<>();
	private final List<WorkerTarget> outputs = new ArrayList<>();
	private boolean resolved;
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
		invalidatePoints();
	}

	/**
	 * Takes the entity off the job and returns everything it should drop: the hat plus
	 * whatever it was carrying.
	 */
	public List<ItemStack> dismiss() {
		List<ItemStack> drops = new ArrayList<>();
		if (!hat.isEmpty())
			drops.add(hat);
		if (!held.isEmpty())
			drops.add(held);
		hat = ItemStack.EMPTY;
		held = ItemStack.EMPTY;
		program = WorkerProgram.EMPTY;
		jobSite = BlockPos.ZERO;
		phase = Phase.SEARCH_INPUTS;
		targetIndex = -1;
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
		if (resolved)
			return;
		resolved = true;
		inputs.clear();
		outputs.clear();

		Level level = worker.level();
		ArmBlockEntity host = host(level, worker.blockPosition());
		for (Tag entry : program.points()) {
			if (!(entry instanceof CompoundTag compound))
				continue;
			WorkerTarget target = WorkerTarget.deserialize(compound, level);
			if (target == null)
				continue;
			target.bind(host);
			if (target.getMode() == Mode.DEPOSIT)
				outputs.add(target);
			else
				inputs.add(target);
		}
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

	// --- the arm's transfer algorithm --------------------------------------------------------

	/**
	 * @return the index of an input worth walking to, or -1.
	 *
	 * Round-robins from just after the last input used, wrapping all the way around before giving
	 * up. The arm can afford to bail out at the end of the list and rescan next tick because it
	 * ticks continuously; a worker that did that would idle for a whole rescan delay every time it
	 * used the last input in its list.
	 */
	public int searchForItem() {
		int count = inputs.size();
		for (int offset = 0; offset < count; offset++) {
			int i = Math.floorMod(lastInputIndex + 1 + offset, count);
			WorkerTarget point = inputs.get(i);
			if (!point.isValid())
				continue;
			for (int slot = 0; slot < point.getSlotCount(); slot++) {
				if (getDistributableAmount(point, slot) == 0)
					continue;
				lastInputIndex = i;
				return i;
			}
		}
		lastInputIndex = -1;
		return -1;
	}

	/** @return the index of an output that will accept the held stack, or -1. Wraps, as above. */
	public int searchForDestination() {
		int count = outputs.size();
		for (int offset = 0; offset < count; offset++) {
			int i = Math.floorMod(lastOutputIndex + 1 + offset, count);
			WorkerTarget point = outputs.get(i);
			if (!point.isValid())
				continue;
			ItemStack remainder = point.insert(held.copy(), true);
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
	public int getDistributableAmount(WorkerTarget point, int slot) {
		ItemStack stack = point.extract(slot, true);
		if (stack.isEmpty())
			return 0;
		ItemStack remainder = simulateInsertion(stack);
		if (ItemStack.isSameItem(stack, remainder))
			return stack.getCount() - remainder.getCount();
		return stack.getCount();
	}

	private ItemStack simulateInsertion(ItemStack stack) {
		for (WorkerTarget point : outputs) {
			if (point.isValid())
				stack = point.insert(stack, true);
			if (stack.isEmpty())
				break;
		}
		return stack;
	}

	/** @return true if something was picked up. */
	public boolean collectFrom(WorkerTarget point) {
		if (point.isValid()) {
			for (int slot = 0; slot < point.getSlotCount(); slot++) {
				int amount = getDistributableAmount(point, slot);
				if (amount == 0)
					continue;
				held = point.extract(slot, amount, false);
				phase = Phase.SEARCH_OUTPUTS;
				targetIndex = -1;
				return !held.isEmpty();
			}
		}
		phase = Phase.SEARCH_INPUTS;
		targetIndex = -1;
		return false;
	}

	/** @return true if at least part of the stack was handed over. */
	public boolean depositTo(WorkerTarget point) {
		boolean moved = false;
		if (point.isValid()) {
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
