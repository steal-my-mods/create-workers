package com.createworkers.program;

import java.util.Collection;

import com.createworkers.worker.target.WorkerTarget;
import com.mojang.serialization.Codec;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
