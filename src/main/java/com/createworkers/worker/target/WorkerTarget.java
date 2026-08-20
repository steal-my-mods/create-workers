package com.createworkers.worker.target;

import org.jetbrains.annotations.Nullable;

import com.createworkers.program.WorkerProgram;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Somewhere a worker can take items from or put items into.
 *
 * <p>Deliberately exactly what a Mechanical Arm can reach, no more: belts, depots, funnels,
 * mechanical crafters, basins, deployers, saws, millstones, blaze burners, chutes, packagers,
 * campfires, composters, jukeboxes, respawn anchors, and whatever other addons register as an
 * {@code ArmInteractionPointType}. Workers are meant to do an arm's job with more range and more
 * life about it, not a bigger job, so a plain chest is no more a valid target here than it is for
 * an arm — put a funnel on it, same as you would for an arm.
 *
 * <p>This is a thin wrapper rather than a straight use of {@link ArmInteractionPoint} because
 * Create's points route every insert and extract through an {@code ArmBlockEntity}. Holding that
 * host here keeps it out of the signature of everything upstream.
 */
public final class WorkerTarget {

	private final ArmInteractionPoint point;
	@Nullable
	private ArmBlockEntity host;

	private WorkerTarget(ArmInteractionPoint point) {
		this.point = point;
	}

	/** @return whether the hard hat should offer to select this block at all. */
	public static boolean isTargetable(Level level, BlockPos pos, BlockState state) {
		return ArmInteractionPoint.isInteractable(level, pos, state);
	}

	@Nullable
	public static WorkerTarget create(Level level, BlockPos pos, BlockState state) {
		ArmInteractionPoint point = ArmInteractionPoint.create(level, pos, state);
		return point == null ? null : new WorkerTarget(point);
	}

	@Nullable
	public static WorkerTarget deserialize(CompoundTag tag, Level level) {
		ArmInteractionPoint point = ArmInteractionPoint.deserialize(tag, level, WorkerProgram.ANCHOR);
		return point == null ? null : new WorkerTarget(point);
	}

	/** Reads the mode straight out of stored NBT, for tooltips that have no level to resolve against. */
	public static Mode peekMode(CompoundTag tag) {
		return Mode.DEPOSIT.name()
			.equals(tag.getString("Mode")) ? Mode.DEPOSIT : Mode.TAKE;
	}

	public CompoundTag serialize() {
		return point.serialize(WorkerProgram.ANCHOR);
	}

	/** Hands over the worker's interaction host, which Create's arm points need in order to work. */
	public void bind(@Nullable ArmBlockEntity host) {
		this.host = host;
	}

	public ArmInteractionPoint getPoint() {
		return point;
	}

	public BlockPos getPos() {
		return point.getPos();
	}

	public Level getLevel() {
		return point.getLevel();
	}

	public Mode getMode() {
		return point.getMode();
	}

	public void cycleMode() {
		point.cycleMode();
	}

	public boolean isValid() {
		return point.isValid();
	}

	public int getSlotCount() {
		return host == null ? 0 : point.getSlotCount(host);
	}

	public ItemStack insert(ItemStack stack, boolean simulate) {
		return host == null ? stack : point.insert(host, stack, simulate);
	}

	public ItemStack extract(int slot, int amount, boolean simulate) {
		return host == null ? ItemStack.EMPTY : point.extract(host, slot, amount, simulate);
	}

	public ItemStack extract(int slot, boolean simulate) {
		return extract(slot, 64, simulate);
	}
}
