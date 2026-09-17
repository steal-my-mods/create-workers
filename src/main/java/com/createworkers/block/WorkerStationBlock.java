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
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The block that hires workers: put a programmed hard hat in it and an unemployed villager takes the
 * job — near enough to one, anyway: the station goes and finds them, because vanilla's own
 * workstation route refuses to put a second villager on a block that already has one.
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

	/** Which way the board faces, which is at whoever put the block down. */
	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

	/**
	 * A workbench with a board standing at the back of it.
	 *
	 * <p>Create's blocks are read by silhouette before anything else, and a full cube with a stripe on
	 * it reads as scenery — but the first attempt at fixing that went too far the other way, a thin
	 * board on a low plinth that looked slight beside a lectern or a smithing table. A profession block
	 * wants the weight of one. So: a bench filling the block's footprint, and a board rising from the
	 * back of it, which is an L from the side and a counter you can put something on from the front.
	 *
	 * <p>Shaped honestly rather than as a full cube. The space over the counter is open, so a player
	 * standing at one is standing at it rather than bumping into air.
	 */
	private static final VoxelShape BENCH = Block.box(0, 0, 0, 16, 11, 16);
	private static final VoxelShape[] SHAPES = new VoxelShape[Direction.values().length];

	static {
		// Authored facing north, which puts the board along the far edge -- the high-z side.
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			VoxelShape board = switch (facing) {
				case SOUTH -> Block.box(0, 11, 0, 16, 16, 6);
				case WEST -> Block.box(0, 11, 0, 6, 16, 16);
				case EAST -> Block.box(10, 11, 0, 16, 16, 16);
				default -> Block.box(0, 11, 10, 16, 16, 16);
			};
			SHAPES[facing.ordinal()] = Shapes.or(BENCH, board);
		}
	}

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
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(FACING)
			.ordinal()];
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
