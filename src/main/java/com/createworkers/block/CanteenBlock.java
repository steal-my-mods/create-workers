package com.createworkers.block;


import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A trough of food, which is the one thing vanilla gives no way to build.
 *
 * <p>Checked rather than assumed: <b>there is no container a villager will take food out of.</b> Food
 * reaches a villager by being picked up off the ground, by being thrown to it by another villager,
 * or — for farmers — by being harvested. A crew on the night shift is fed by none of those: the
 * behaviour that shares food lives in the idle package, so the farmer who would hand it over is
 * asleep at the hour the night crew is awake. Feeding a workforce therefore needs a block of this
 * mod's own, and this is it.
 *
 * <p>It is deliberately <b>not a machine</b>. No stress, no recipe, no processing: it is an inventory
 * that only accepts food, which is enough to make feeding a crew an ordinary Create automation
 * problem — a funnel, a chute, a belt or an arm fills it, and so does a worker whose hat names it,
 * which makes "a line that feeds the workers who run the line" something a player can build out of
 * parts they already have.
 *
 * <p><b>No screen, on purpose.</b> The Worker Station has one because its rack is an ordered list of
 * jobs with toggles on each; there is nothing to arrange in a trough. Right-Clicking with food puts
 * the stack in, Right-Clicking with an empty hand takes the top one back out, and a comparator reads
 * how full it is — which is the whole interface, and it is the same one a composter or a jukebox
 * offers for the same reason.
 */
public class CanteenBlock extends BaseEntityBlock {

	public static final MapCodec<CanteenBlock> CODEC = simpleCodec(CanteenBlock::new);

	public CanteenBlock(Properties properties) {
		super(properties);
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
		return new CanteenBlockEntity(pos, state);
	}

	/**
	 * Food in hand goes in.
	 *
	 * <p>Anything else is refused rather than swallowed, and refusing it is a {@code PASS} rather than
	 * a failure so the item's own use still happens — a player holding a bucket at a canteen is
	 * trying to use the bucket.
	 */
	@Override
	protected ItemInteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos,
		Player player, InteractionHand hand, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof CanteenBlockEntity canteen))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!CanteenBlockEntity.isFood(held))
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (level.isClientSide())
			return ItemInteractionResult.SUCCESS;

		ItemStack left = canteen.stock(held);
		if (left.getCount() == held.getCount())
			return ItemInteractionResult.CONSUME; // full, and saying so by doing nothing
		if (!player.isCreative())
			player.setItemInHand(hand, left);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7F, 1.2F);
		return ItemInteractionResult.CONSUME;
	}

	/** An empty hand takes the last stack back, so a canteen filled by mistake is not a loss. */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
		BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof CanteenBlockEntity canteen))
			return InteractionResult.PASS;
		if (level.isClientSide())
			return InteractionResult.SUCCESS;

		ItemStack taken = canteen.takeBack();
		if (taken.isEmpty())
			return InteractionResult.CONSUME;

		player.getInventory()
			.placeItemBackInInventory(taken);
		level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.7F, 1.2F);
		return InteractionResult.CONSUME;
	}

	/**
	 * A comparator reads how full it is, which is the only readout this block has.
	 *
	 * <p>Worth having rather than skipping: "is the canteen running dry" is exactly the thing a player
	 * wants to wire an alarm or a restock to, and it is the question the block exists to raise.
	 */
	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof CanteenBlockEntity canteen ? canteen.comparatorOutput() : 0;
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (state.is(newState.getBlock()))
			return;

		if (level.getBlockEntity(pos) instanceof CanteenBlockEntity canteen)
			for (ItemStack stack : canteen.contents())
				Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
		super.onRemove(state, level, pos, newState, movedByPiston);
	}
}
