package com.createworkers.block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.CreateWorkers;
import com.createworkers.registry.CWBlockEntities;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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
 * <p>Food is <b>vanilla's four</b> — {@code Villager.FOOD_POINTS}, which is public — and not everything
 * carrying a {@code FOOD} component. See {@link #isFood} for why the obvious reading is the wrong one.
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

	/**
	 * Ticks between servings. Five seconds, which is an entity query per canteen per five seconds --
	 * nothing, and far faster than anybody eats: a loaf is a hundred deliveries of work.
	 */
	private static final int SERVING_INTERVAL = 100;

	/**
	 * The food value a full comparator signal means: a canteen filled with the cheapest food there is.
	 *
	 * <p>One point per item the block can hold. Anything full reads full, and bread — worth four
	 * apiece — is simply carrying three quarters of its value as headroom above the top of the scale.
	 */
	private static final int PLENTY = SLOTS * 64;

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
	/** Ticks before the next serving. */
	private int untilServing;

	public CanteenBlockEntity(BlockPos pos, BlockState state) {
		super(CWBlockEntities.CANTEEN.get(), pos, state);
	}

	/**
	 * @return whether this is something a <em>villager</em> will eat, which is the whole of what a
	 *         canteen takes.
	 *
	 * <p><b>Vanilla's four, not everything edible.</b> This asked for a {@code FOOD} component at
	 * first, which is the obvious reading of "food" and the wrong one here: a villager eats bread,
	 * potatoes, carrots and beetroot and nothing else — {@code Villager.FOOD_POINTS} is the whole
	 * list, and it is public, so there is no guessing involved. A canteen full of cooked chicken
	 * satisfied the component test, read as stocked on the comparator and through the goggles, and
	 * would have fed nobody. The one promise this block makes is that a hungry villager sent to it
	 * finds something it can eat, and the broad filter made that promise false.
	 *
	 * <p>Refusing at the door rather than accepting and ignoring is the same choice the hard hat makes
	 * about a chest: a block that takes what it cannot use is a block whose readouts lie.
	 */
	public static boolean isFood(ItemStack stack) {
		return !stack.isEmpty() && Villager.FOOD_POINTS.containsKey(stack.getItem());
	}

	/** @return what {@code stack}'s item is worth to a villager, or zero if it is not food. */
	public static int foodPoints(ItemStack stack) {
		return stack.isEmpty() ? 0 : Villager.FOOD_POINTS.getOrDefault(stack.getItem(), 0);
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

	/**
	 * How much <b>feeding</b> is left, on the fifteen steps a comparator has.
	 *
	 * <p>Points rather than stacks, and that is the whole change. Measured the ordinary way — how full
	 * the container is — a canteen of beetroot and a canteen of bread both read fifteen while holding
	 * four times different amounts of food, because vanilla prices bread at four points and every root
	 * at one. A player wiring a restock line to the cheap readout would have it fire at the wrong time
	 * for three of the four foods in the game, and nothing anywhere would say why. The question this
	 * block exists to answer is "is there enough feeding in here", so that is the question it answers.
	 *
	 * <p><b>Scaled against {@link #PLENTY} and clamped, rather than against the maximum possible.</b>
	 * A full canteen of bread is 2304 points and a full canteen of carrots is 576; scaling against the
	 * larger would leave a physically full carrot trough reading four out of fifteen, with the signal
	 * asking for a top-up that cannot happen — and <b>a readout that demands the impossible is worse
	 * than an imprecise one</b>. Against 576, any food reads full when the block is full, and bread
	 * simply carries headroom: a bread canteen holds at fifteen until it is down to a quarter, then
	 * falls. Which is right, because at that point it still holds more feeding than a full trough of
	 * roots.
	 *
	 * <p>What is given up is "can this accept more", which a comparator means everywhere else. Nothing
	 * needs it here: a funnel filling a canteen backs up by itself once the slots are full.
	 */
	public int comparatorOutput() {
		int points = 0;
		for (int slot = 0; slot < SLOTS; slot++) {
			ItemStack held = stock.getStackInSlot(slot);
			points += foodPoints(held) * held.getCount();
		}
		if (points == 0)
			return 0;
		return Math.min(15, 1 + Math.round(points / (float) PLENTY * 14));
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
		if (canteen.untilServing-- <= 0) {
			canteen.untilServing = SERVING_INTERVAL;
			canteen.serve(level, pos);
		}

		if (canteen.untilSync > 0)
			canteen.untilSync--;
		if (!canteen.stockChanged || canteen.untilSync > 0)
			return;
		canteen.stockChanged = false;
		canteen.untilSync = SYNC_INTERVAL;
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
	}

	/**
	 * Hands food to everybody nearby who is short of it.
	 *
	 * <p><b>The canteen pushes; nobody walks to it.</b> That is the whole reason this block works, and
	 * the alternative is worse than it looks. Vanilla's own way for a villager to pick food up needs
	 * {@code WALK_TARGET} <em>absent</em>, and a working Worker has it pinned every tick — which is
	 * why {@code docs/shift-rotation.md} treats food and leisure as one feature in the first place. An
	 * explicit trip would mean unpinning a Worker mid-shift and driving the walk by hand: a paced
	 * point-of-interest hunt, another stall clock, and a Worker that is off its post for long enough
	 * for its own Station to strike it off as an absentee. Pushing has none of that, because the food
	 * comes to the pinned worker.
	 *
	 * <p>It also settles what a Canteen <em>is</em>: something you put where the people are, rather
	 * than something they queue at. A line whose workers are out of reach of every Canteen goes hungry
	 * and visibly slows, so feeding a factory is a question of where the troughs go — a building
	 * problem, which is the genre.
	 *
	 * <p><b>Anybody, not only Workers.</b> {@code wantsMoreFood} is vanilla's own "this villager is
	 * short of food", so a Canteen near a farm feeds the farmers, and one near a Station feeds the
	 * crew whatever shift they are on. It does not sleep either, which is the point that started this:
	 * the behaviour that shares food between villagers runs in the idle package, so the farmer who
	 * would hand a night Worker its dinner is asleep at the hour it is awake.
	 *
	 * <p>One item per villager per serving, so a full trough drains at a rate a player can watch
	 * rather than emptying into the first passer-by.
	 */
	private void serve(Level level, BlockPos pos) {
		if (isEmpty())
			return;

		double range = CWConfig.CANTEEN_RANGE.get();
		List<Villager> nearby = level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(range),
			Villager::wantsMoreFood);
		for (Villager hungry : nearby) {
			int slot = firstFood();
			if (slot < 0)
				return; // emptied partway through the queue
			ItemStack meal = stock.extractItem(slot, 1, false);
			if (meal.isEmpty())
				return;
			// Straight into the inventory rather than onto the floor. A villager only picks items up
			// when its brain is free to want them, which a Worker's never is.
			ItemStack refused = hungry.getInventory()
				.addItem(meal);
			if (!refused.isEmpty())
				stock.insertItem(slot, refused, false); // its pockets are full; keep the loaf
		}
	}

	/** @return the first slot with food in it, or -1. */
	private int firstFood() {
		for (int slot = 0; slot < SLOTS; slot++)
			if (!stock.getStackInSlot(slot)
				.isEmpty())
				return slot;
		return -1;
	}

	/** @return whether there is nothing in it, asked before the entity query rather than after. */
	public boolean isEmpty() {
		return firstFood() < 0;
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
