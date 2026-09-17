package com.createworkers.block;

import java.util.ArrayList;
import java.util.List;

import com.createworkers.registry.CWBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The canteen's stock: a handful of slots that will hold food and nothing else.
 *
 * <p>The filter is the block. A canteen that took any item would be a chest with a worse interface,
 * and the one thing it has to guarantee is that a hungry villager sent here finds something it can
 * eat — so "is this food" is asked once, in {@link #isFood}, and every way in goes through it: a
 * player's hand, a funnel, a chute, an arm, and a worker whose hat names this block.
 *
 * <p>Food is decided by the item's own {@code FOOD} component rather than by a list kept here. A list
 * would be wrong the day any mod adds a bread, and the component is exactly the question being
 * asked — the same one {@code Villager.wantsMoreFood} and {@code FOOD_POINTS} are built on.
 */
public class CanteenBlockEntity extends BlockEntity {

	/**
	 * Nine, which is a chest's row and not an accident.
	 *
	 * <p>Big enough that stocking one is a thing you do occasionally rather than constantly — the
	 * bound worth holding is that a canteen should outlast a shift — and small enough that it is
	 * plainly a trough rather than storage. It is also what makes the comparator readable: nine slots
	 * of sixty-four map onto fifteen redstone steps without the first item jumping it to full.
	 */
	public static final int SLOTS = 9;

	private final ItemStackHandler stock = new ItemStackHandler(SLOTS) {

		@Override
		public boolean isItemValid(int slot, ItemStack stack) {
			return isFood(stack);
		}

		@Override
		protected void onContentsChanged(int slot) {
			setChanged();
			// A comparator reading this block is the only thing that can see inside it, so the level
			// has to be told the signal may have moved. setChanged alone saves the block and updates
			// nothing.
			if (level != null && !level.isClientSide())
				level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
		}
	};

	public CanteenBlockEntity(BlockPos pos, BlockState state) {
		super(CWBlockEntities.CANTEEN.get(), pos, state);
	}

	/** @return whether this is something a villager would eat, which is the whole of what a canteen takes. */
	public static boolean isFood(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.FOOD);
	}

	/** @return the stock as an inventory, for anything piping food in — funnel, chute, arm or worker. */
	public IItemHandlerModifiable stock() {
		return stock;
	}

	/**
	 * Puts as much of {@code offered} in as will fit.
	 *
	 * @return what is left over, which is the whole stack if the canteen is full.
	 */
	public ItemStack stock(ItemStack offered) {
		ItemStack left = offered.copy();
		for (int slot = 0; slot < SLOTS && !left.isEmpty(); slot++)
			left = stock.insertItem(slot, left, false);
		return left;
	}

	/**
	 * Takes the last stack back out, which is the only way anything leaves by hand.
	 *
	 * <p>From the back rather than the front so that emptying a canteen undoes the order it was filled
	 * in — a player who has just put the wrong thing in gets that thing back, rather than the bread
	 * underneath it.
	 */
	public ItemStack takeBack() {
		for (int slot = SLOTS - 1; slot >= 0; slot--) {
			ItemStack held = stock.getStackInSlot(slot);
			if (!held.isEmpty())
				return stock.extractItem(slot, held.getCount(), false);
		}
		return ItemStack.EMPTY;
	}

	/** Everything in it, for the block to drop when it is broken. */
	public List<ItemStack> contents() {
		List<ItemStack> all = new ArrayList<>();
		for (int slot = 0; slot < SLOTS; slot++) {
			ItemStack held = stock.getStackInSlot(slot);
			if (!held.isEmpty())
				all.add(held.copy());
		}
		return all;
	}

	/** How full it is, on the fifteen steps a comparator has. Anything at all reads as at least one. */
	public int comparatorOutput() {
		float filled = 0;
		for (int slot = 0; slot < SLOTS; slot++) {
			ItemStack held = stock.getStackInSlot(slot);
			if (!held.isEmpty())
				filled += held.getCount() / (float) Math.min(stock.getSlotLimit(slot), held.getMaxStackSize());
		}
		if (filled == 0)
			return 0;
		return Math.max(1, Math.round(filled / SLOTS * 14) + 1);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		stock.deserializeNBT(registries, tag.getCompound("Stock"));
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.put("Stock", stock.serializeNBT(registries));
	}
}
