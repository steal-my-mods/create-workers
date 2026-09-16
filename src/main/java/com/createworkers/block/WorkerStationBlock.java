package com.createworkers.block;

import org.jetbrains.annotations.Nullable;

import com.createworkers.item.HardHatItem;
import com.createworkers.registry.CWBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The block that hires workers: put a programmed hard hat in it and an unemployed villager takes the
 * job, the way one takes a lectern or a composter.
 *
 * <p>{@code HAS_JOB} is not decoration. The point of interest is registered over the states where it
 * is true, so a station with no hat in it is not a job site at all — which is what stops a villager
 * crossing a village to become a Worker with nothing to do, and then being unable to take any other
 * job, a Worker's only workstation being the block it is standing at.
 */
public class WorkerStationBlock extends BaseEntityBlock {

	public static final MapCodec<WorkerStationBlock> CODEC = simpleCodec(WorkerStationBlock::new);

	/** Whether there is a hat in it, and so whether there is a job here to be taken. */
	public static final BooleanProperty HAS_JOB = BooleanProperty.create("has_job");

	public WorkerStationBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any()
			.setValue(HAS_JOB, false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HAS_JOB);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new WorkerStationBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		if (level.isClientSide())
			return null;
		return createTickerHelper(type, CWBlockEntities.WORKER_STATION.get(), WorkerStationBlockEntity::serverTick);
	}

	/** A hat in hand goes in; anything else is not a station's business. */
	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hit) {
		if (!(stack.getItem() instanceof HardHatItem))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!(level.getBlockEntity(pos) instanceof WorkerStationBlockEntity station))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		if (level.isClientSide())
			return ItemInteractionResult.sidedSuccess(true);
		if (!station.addHat(stack))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!player.getAbilities().instabuild)
			stack.shrink(1);
		return ItemInteractionResult.SUCCESS;
	}

	/**
	 * An empty hand opens the rack.
	 *
	 * <p>Which is also how a job is ended, there being no other way to fire a villager: take its hat
	 * out of the rack. A hat in hand still goes straight in without opening anything, the way a
	 * lectern takes a book — a one-job station should not need a screen to set up.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof WorkerStationBlockEntity station))
			return InteractionResult.PASS;
		if (level.isClientSide())
			return InteractionResult.SUCCESS;

		player.openMenu(new SimpleMenuProvider((id, inventory, opener) ->
			WorkerStationMenu.create(id, inventory, station), state.getBlock()
				.getName()),
			buffer -> buffer.writeBlockPos(pos));
		return InteractionResult.CONSUME;
	}

	/**
	 * Breaking the office sacks the worker, because there is no longer anything else it could mean.
	 *
	 * <p>With hiring by hand gone for villagers, a worker whose station has been broken would be
	 * employed by nobody, firable by nothing, and impossible to get the hat back from — so breaking
	 * the block ends the job, drops the hat, and leaves an ordinary unemployed villager that vanilla
	 * will find another job for.
	 */
	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (state.is(newState.getBlock()))
			return;

		if (level.getBlockEntity(pos) instanceof WorkerStationBlockEntity station) {
			station.dismissAll();
			for (WorkerStationBlockEntity.Slot slot : station.slots())
				Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), slot.hat());
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}
}
