package com.createworkers.block;

import org.jetbrains.annotations.Nullable;

import com.createworkers.registry.CWBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The block that hires workers: put a programmed hard hat in it and an unemployed villager takes the
 * job — near enough to one, anyway: the station goes and finds them, because vanilla's own
 * workstation route refuses to put a second villager on a block that already has one.
 *
 * <p>{@code HAS_JOB} is not decoration. The point of interest is registered over the states where it
 * is true, so a station with no hat in it is not a job site at all — which is what stops a villager
 * crossing a village to become a Worker with nothing to do, and then being unable to take any other
 * job, a Worker's only workstation being the block it is standing at.
 *
 * <p><b>It is a full cube, and that is a decision.</b> It went through a thin board on a low plinth,
 * which read as slight beside a lectern, and then a bench with a board rising from the back of it.
 * Both were shaped, and being shaped is what kept costing: a model that does not fill its block cannot
 * occlude, so it needs {@code noOcclusion}, and then every face of it that does not truly span the
 * block boundary is a hole waiting to be left undrawn. That happened twice, in two different places,
 * and both times it looked like the world showing through the block. A full cube has none of those
 * failures available to it — it occludes, lights and culls like any other solid block, it stacks into
 * a wall, and it needs no {@code getShape} of its own. What the Station has to say it says on its
 * front face, where {@link com.createworkers.client.WorkerStationRenderer} draws a lamp for every
 * place in the rack.
 */
public class WorkerStationBlock extends BaseEntityBlock {

	public static final MapCodec<WorkerStationBlock> CODEC = simpleCodec(WorkerStationBlock::new);

	/** Whether there is a hat in it, and so whether there is a job here to be taken. */
	public static final BooleanProperty HAS_JOB = BooleanProperty.create("has_job");

	/** Which way the board faces, which is at whoever put the block down. */
	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

	public WorkerStationBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any()
			.setValue(HAS_JOB, false)
			.setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HAS_JOB, FACING);
	}

	/** Placed facing the player, the way every sign and lectern in the game is. */
	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection()
			.getOpposite());
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
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

	/**
	 * Right-Clicking opens the rack, whatever is in the player's hand.
	 *
	 * <p>A hat in hand used to go straight into the first free place without opening anything, on the
	 * reasoning that a one-job station should not need a screen. In practice it surprises: the rack is
	 * where a player expects to put a hat once they know the screen exists, and a click that silently
	 * files one somewhere reads as the block taking it. One gesture, one result.
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
				if (slot != null)
					Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), slot.hat());
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}
}
