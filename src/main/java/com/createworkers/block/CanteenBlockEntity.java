package com.createworkers.block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CreateWorkers;
import com.createworkers.registry.CWBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The canteen's stock: a handful of slots that will hold food and nothing else.
 *
 * <p>The filter is the block. A canteen that took any item would be a chest with a worse interface,
 * and the one thing it has to guarantee is that a hungry villager sent here finds something it can
 * eat — so "is this food" is asked once, in {@link #isFood}, and it is asked on the
 * {@code IItemHandler}, which is where every way in arrives: a funnel, a chute, a belt, a hopper, an
 * Item Hatch, and a worker delivering into any of them. Nothing goes in by hand, so there is no
 * second place for the rule to live and no second place for it to be forgotten.
 *
 * <p>Food is decided by the item's own {@code FOOD} component rather than by a list kept here. A list
 * would be wrong the day any mod adds a bread, and the component is exactly the question being
 * asked — the same one {@code Villager.wantsMoreFood} and {@code FOOD_POINTS} are built on.
 */
public class CanteenBlockEntity extends BlockEntity implements IHaveGoggleInformation {

	/**
	 * Nine, which is a chest's row and not an accident.
	 *
	 * <p>Big enough that stocking one is a thing you do occasionally rather than constantly — the
	 * bound worth holding is that a canteen should outlast a shift — and small enough that it is
	 * plainly a trough rather than storage. It is also what makes the comparator readable: nine slots
	 * of sixty-four map onto fifteen redstone steps without the first item jumping it to full.
	 */
	public static final int SLOTS = 9;

	/** Ticks between syncs while the stock is moving. Twice a second, which an overlay cannot outpace. */
	private static final int SYNC_INTERVAL = 10;

	private final ItemStackHandler stock = new ItemStackHandler(SLOTS) {

		@Override
		public boolean isItemValid(int slot, ItemStack stack) {
			return isFood(stack);
		}

		@Override
		protected void onContentsChanged(int slot) {
			setChanged();
			if (level == null || level.isClientSide())
				return;
			// A comparator is one of the two things that can see inside this block, so the level has to
			// be told the signal may have moved. setChanged alone saves the block and updates nothing.
			level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
			// The other is a pair of goggles, and that one needs the *client* to know. See serverTick.
			stockChanged = true;
		}
	};

	/** Set when the stock moves, cleared by the one sync that follows. */
	private boolean stockChanged;
	/** Ticks before the next sync is allowed, so a belt filling a canteen cannot send a packet an item. */
	private int untilSync;

	public CanteenBlockEntity(BlockPos pos, BlockState state) {
		super(CWBlockEntities.CANTEEN.get(), pos, state);
	}

	/** @return whether this is something a villager would eat, which is the whole of what a canteen takes. */
	public static boolean isFood(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.FOOD);
	}

	/** @return the stock as an inventory: what a funnel, a chute, a belt or a hopper holds. */
	public IItemHandlerModifiable stock() {
		return stock;
	}

	/**
	 * What a pair of Engineer's Goggles says about this block.
	 *
	 * <p>Nothing goes in or out of a Canteen by hand, so without this the only way to know what is in
	 * one is a comparator — which answers "how full" and never "full of what", and a trough that a
	 * misaimed funnel filled with cake instead of bread looks exactly like a working one. Goggles are
	 * Create's own answer to "look at a block and see its state", they need no screen, and they are an
	 * early item rather than a late one.
	 *
	 * <p>Create's own Item Vault does <em>not</em> implement this, and that is not an argument against
	 * it: a Vault holds anything, so "what is in it" is a question with no short answer, while a
	 * Canteen holds only food and its whole purpose is whether there is any left.
	 *
	 * @return whether anything was added, which is what tells Create to draw the overlay at all.
	 */
	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		List<Component> lines = goggleSummary();
		// The first line is the heading and the rest are its detail, which is the shape every Create
		// overlay has. forGoggles is the only part of this that is client-side -- see goggleSummary.
		CreateLang.builder(CreateWorkers.ID)
			.add(lines.get(0)
				.copy())
			.forGoggles(tooltip);
		for (Component line : lines.subList(1, lines.size()))
			CreateLang.builder(CreateWorkers.ID)
				.add(line.copy())
				.forGoggles(tooltip, 1);
		return true;
	}

	/**
	 * What the goggles will say, as plain components and nothing else.
	 *
	 * <p>Split from {@link #addToGoggleTooltip} because {@code LangBuilder.forGoggles} reaches into
	 * {@code Minecraft} to lay the line out, and a dedicated server refuses to load that class
	 * outright — "Attempted to load class net/minecraft/client/Minecraft for invalid dist
	 * DEDICATED_SERVER". That is fine in the game, where only the overlay renderer ever calls it, and
	 * fatal for a test: <b>the decisions worth checking are all on this side of the split</b>, and
	 * none of them can be reached with the formatting attached. The same trap as
	 * {@code MenuBase.createOnClient}, arrived at from the other direction.
	 *
	 * <p>Totalled by item rather than listed by slot. A slot listing is a fact about the inventory; a
	 * player wants a fact about the food, and four part-stacks of bread in four slots is one answer,
	 * not four.
	 */
	public List<Component> goggleSummary() {
		List<Component> lines = new ArrayList<>();
		lines.add(CreateLang.builder(CreateWorkers.ID)
			.translate("goggles.canteen")
			.component());

		List<ItemStack> inside = contents();
		if (inside.isEmpty()) {
			lines.add(CreateLang.builder(CreateWorkers.ID)
				.translate("goggles.canteen.empty")
				.style(ChatFormatting.RED)
				.component());
			return lines;
		}

		Map<Item, Integer> totals = new LinkedHashMap<>();
		for (ItemStack held : inside)
			totals.merge(held.getItem(), held.getCount(), Integer::sum);

		for (Map.Entry<Item, Integer> entry : totals.entrySet())
			lines.add(CreateLang.builder(CreateWorkers.ID)
				.text(entry.getValue() + " ")
				.add(Component.translatable(entry.getKey()
					.getDescriptionId()))
				.style(ChatFormatting.GRAY)
				.component());
		return lines;
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

	/**
	 * Pushes the stock to anybody watching, on a clock.
	 *
	 * <p><b>Without this the goggles read "Empty" forever, however much bread is in the block.</b> A
	 * block entity's contents are the server's; nothing sends them to a client on its own, and the
	 * goggle overlay is drawn on the client from the client's copy — which, for a block that never
	 * syncs, is the one it was given when the chunk loaded. A chute filling a canteen and the overlay
	 * saying it is empty are both correct, about different worlds, and nothing in the game says so.
	 * The Worker Station has carried the same three overrides since its screen was built; this block
	 * simply never got them.
	 *
	 * <p><b>On a clock, unlike the Station's.</b> A rack changes a handful of times an hour, so it
	 * sends on every change. A canteen under a belt changes several times a second, and the whole
	 * inventory to every tracking client per item is a packet storm for a readout nobody can perceive
	 * at that rate. Twice a second is well past what an overlay needs.
	 */
	public static void serverTick(Level level, BlockPos pos, BlockState state, CanteenBlockEntity canteen) {
		if (canteen.untilSync > 0)
			canteen.untilSync--;
		if (!canteen.stockChanged || canteen.untilSync > 0)
			return;
		canteen.stockChanged = false;
		canteen.untilSync = SYNC_INTERVAL;
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Nullable
	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
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
