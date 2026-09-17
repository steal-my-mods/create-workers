package com.createworkers.block;

import org.jetbrains.annotations.Nullable;

import com.createworkers.registry.CWBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

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
 * problem — a funnel, a chute, a belt or a hopper fills it, exactly as they fill a chest.
 *
 * <p><b>And it is not an arm interaction point, deliberately.</b> It was one briefly, which made it a
 * legal destination on a hard hat. The tell that this was wrong was having to invent a rule no other
 * target in this mod has — deposit only — to stop a bread-in-bread-out loop that existed *because* of
 * the registration. What it bought was one funnel. What it cost was the only answer a player can be
 * given for why their chest needs one: a worker is an arm with legs, and an arm cannot reach into a
 * chest either. So a line that feeds the workers who run the line is still something a player builds
 * out of parts they already have, and the parts are the ordinary ones.
 *
 * <p><b>Nothing goes in or out by hand, and that is the whole interface.</b> No screen, and no
 * Right-Click either: an Item Vault is the block this is modelled on, and Create already ships the
 * answer to "I want to put something in a container by hand" — it is the <i>Item Hatch</i>, which
 * deposits your held item into whatever it is placed on. A Right-Click-to-insert here would be that
 * block reimplemented on one block, worse and in the wrong place, and it would be the second time
 * this block got a shortcut nobody asked for; the first was being an arm interaction point.
 *
 * <p>So the ways in and out are a funnel, a chute, a belt, a hopper or an Item Hatch, exactly as for a
 * Vault, and the ways to read it are a comparator and a pair of Engineer's Goggles.
 *
 * <p><b>Machines can empty it as well as fill it, and that was argued both ways.</b> A funnel pulling
 * food back out of the crew's trough looks wrong, and for one commit it was forbidden — until two
 * things settled it: no Create block hands out an inventory that can be filled and not drained, and a
 * player who filled a canteen with the wrong food would have had no way to change it short of
 * breaking the block. A belt that keeps a trough empty is a build the player made, and it is visible
 * on the comparator and through the goggles.
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
	 * The tick exists only to push the stock out to clients, on a clock.
	 *
	 * <p>Nothing here processes anything. What it is for is that a block entity's contents never reach
	 * a client on their own, and the goggle overlay is drawn on the client — so without it, a canteen
	 * a chute has been filling all morning reads as empty through a pair of goggles, correctly, about
	 * a world the player is not in.
	 */
	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
		BlockEntityType<T> type) {
		if (level.isClientSide())
			return null;
		return createTickerHelper(type, CWBlockEntities.CANTEEN.get(), CanteenBlockEntity::serverTick);
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
