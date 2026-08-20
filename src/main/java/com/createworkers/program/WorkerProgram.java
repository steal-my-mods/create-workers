package com.createworkers.program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.createworkers.worker.target.WorkerTarget;
import com.mojang.serialization.Codec;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The set of inventories a hard hat has been programmed with, stored on the item as a data
 * component and copied onto a worker when the hat is handed over.
 *
 * <p>Positions are stored <em>absolute</em> — anchored at the origin — because a worker walks
 * around rather than sitting on one block the way a Mechanical Arm does.
 */
public record WorkerProgram(CompoundTag tag) {

	public static final String POINTS_KEY = "Points";

	public static final WorkerProgram EMPTY = new WorkerProgram(new CompoundTag());

	public static final Codec<WorkerProgram> CODEC =
		CompoundTag.CODEC.xmap(WorkerProgram::new, WorkerProgram::tag);

	public static final StreamCodec<ByteBuf, WorkerProgram> STREAM_CODEC =
		ByteBufCodecs.COMPOUND_TAG.map(WorkerProgram::new, WorkerProgram::tag);

	/** Anchor used for (de)serialization. The origin keeps stored positions absolute. */
	public static final BlockPos ANCHOR = BlockPos.ZERO;

	public static WorkerProgram of(Collection<? extends WorkerTarget> targets) {
		ListTag list = new ListTag();
		for (WorkerTarget target : targets)
			list.add(target.serialize());
		CompoundTag tag = new CompoundTag();
		tag.put(POINTS_KEY, list);
		return new WorkerProgram(tag);
	}

	public ListTag points() {
		return tag.getList(POINTS_KEY, Tag.TAG_COMPOUND);
	}

	public int size() {
		return points().size();
	}

	public boolean isEmpty() {
		return size() == 0;
	}

	public WorkerProgram copy() {
		return new WorkerProgram(tag.copy());
	}

	/**
	 * The stored positions, read straight out of NBT. No level needed, so this works before a worker
	 * exists and on either side.
	 */
	public List<BlockPos> positions() {
		List<BlockPos> positions = new ArrayList<>();
		for (Tag entry : points())
			if (entry instanceof CompoundTag compound)
				NbtUtils.readBlockPos(compound, "Pos")
					.ifPresent(positions::add);
		return positions;
	}

	/** @return the worker's job site: the centre of the box its targets sit in. */
	public BlockPos centre() {
		return centre(positions());
	}

	/**
	 * The centre of the box the given positions sit in.
	 *
	 * <p>This is what a worker's job site is derived from, rather than wherever the player happened
	 * to be standing when they handed the hat over. Because the spread rule keeps every pair of
	 * targets within {@code maxTargetSpread}, no target is ever further than half of that from here.
	 */
	public static BlockPos centre(Collection<BlockPos> positions) {
		if (positions.isEmpty())
			return BlockPos.ZERO;

		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : positions) {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		return new BlockPos(Math.floorDiv(minX + maxX, 2), Math.floorDiv(minY + maxY, 2),
			Math.floorDiv(minZ + maxZ, 2));
	}

	/**
	 * Checks a candidate target against everything already selected.
	 *
	 * <p>Every pair has to be within {@code maxSpread} — the diameter of the whole set, not a chain
	 * of short links. A candidate 60 from its nearest neighbour but 100 from the far end of the run
	 * is refused, because one worker would have to walk that 100.
	 *
	 * @return the already-selected position that is too far away, or null if the candidate fits.
	 */
	@Nullable
	public static BlockPos firstTooFar(Collection<BlockPos> selected, BlockPos candidate, int maxSpread) {
		for (BlockPos pos : selected)
			if (!pos.closerThan(candidate, maxSpread))
				return pos;
		return null;
	}

	/** @return true if any two targets are further apart than {@code maxSpread}. */
	public boolean exceedsSpread(int maxSpread) {
		List<BlockPos> positions = positions();
		for (int i = 0; i < positions.size(); i++)
			for (int j = i + 1; j < positions.size(); j++)
				if (!positions.get(i)
					.closerThan(positions.get(j), maxSpread))
					return true;
		return false;
	}

	/** Counts stored targets by mode without needing a level to resolve them against. */
	public int countWithMode(Mode mode) {
		int count = 0;
		for (Tag entry : points()) {
			if (entry instanceof CompoundTag compound && WorkerTarget.peekMode(compound) == mode)
				count++;
		}
		return count;
	}
}
