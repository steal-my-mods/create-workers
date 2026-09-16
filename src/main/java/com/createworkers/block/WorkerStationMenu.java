package com.createworkers.block;

import com.createworkers.registry.CWMenuTypes;
import com.simibubi.create.foundation.gui.menu.MenuBase;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

/**
 * The rack, as something a player can click on.
 *
 * <p>A real menu over a real inventory rather than a picture of one, which is what makes a hat
 * shift-clickable in and out and makes a station something a funnel can stock. The slots are live
 * views of {@link WorkerStationBlockEntity#rack()} on both sides — the block entity keeps its whole
 * rack synced to tracking clients, so the screen reads shifts and staffing straight off it and this
 * class carries nothing of its own.
 */
public class WorkerStationMenu extends MenuBase<WorkerStationBlockEntity> {

	/** Where the rack's own slots start. Everything from here on is a job. */
	public static final int RACK_SLOT_START = 0;

	/**
	 * The screen's geometry, declared here because the menu has to place its slots before any screen
	 * exists and the two must agree exactly — a {@code Slot}'s position is final once set.
	 *
	 * <p><b>Two columns of six, not one column of twelve.</b> Twelve rows stacked made a window over
	 * three hundred pixels tall, which does not fit a screen at any GUI scale a player would choose.
	 * Splitting it is what lets the rack keep its twelve jobs without a scrollbar, and a rack read in
	 * two columns is still a rack: the order runs down the first and then down the second.
	 */
	public static final int ROWS_PER_COLUMN = 6;
	public static final int COLUMN_WIDTH = 160;
	public static final int FIRST_COLUMN_X = 8;
	public static final int SECOND_COLUMN_X = 178;
	public static final int FIRST_ROW_Y = 22;
	public static final int ROW_HEIGHT = 20;

	public static final int INVENTORY_X = 92;
	public static final int INVENTORY_Y = 162;
	public static final int PANEL_WIDTH = SECOND_COLUMN_X + COLUMN_WIDTH + FIRST_COLUMN_X;
	public static final int PANEL_HEIGHT = 244;

	/** The left edge of the job at {@code index}, its well included. */
	public static int columnX(int index) {
		return index < ROWS_PER_COLUMN ? FIRST_COLUMN_X : SECOND_COLUMN_X;
	}

	/** The top edge of the job at {@code index}, its well included. */
	public static int rowY(int index) {
		return FIRST_ROW_Y + (index % ROWS_PER_COLUMN) * ROW_HEIGHT;
	}

	public WorkerStationMenu(MenuType<?> type, int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
		super(type, id, inventory, buf);
	}

	public WorkerStationMenu(MenuType<?> type, int id, Inventory inventory, WorkerStationBlockEntity station) {
		super(type, id, inventory, station);
	}

	public static WorkerStationMenu create(int id, Inventory inventory, WorkerStationBlockEntity station) {
		return new WorkerStationMenu(CWMenuTypes.WORKER_STATION.get(), id, inventory, station);
	}

	/**
	 * Never used, and it cannot be.
	 *
	 * <p>{@code MenuBase}'s buffer constructor calls this <em>before</em> its {@code init} assigns any
	 * field, so there is no player here to ask for a level and no way to find the block this menu is
	 * for. Reaching for {@code Minecraft} instead is what Create's own menus do, and it loads a client
	 * class from a class a dedicated server also loads. So the buffer is read in
	 * {@code CWMenuTypes} instead, where the inventory — and through it the player, and the level — is
	 * an argument, and this menu is only ever built from a block it already has.
	 */
	@Override
	protected WorkerStationBlockEntity createOnClient(RegistryFriendlyByteBuf buf) {
		return null;
	}

	@Override
	protected void initAndReadInventory(WorkerStationBlockEntity station) {
	}

	@Override
	protected void addSlots() {
		if (contentHolder == null)
			return;

		for (int i = 0; i < WorkerStationBlockEntity.MAX_SLOTS; i++)
			addSlot(new SlotItemHandler(contentHolder.rack(), i, columnX(i) + 1, rowY(i) + 1) {
				@Override
				public int getMaxStackSize() {
					return 1;
				}
			});

		addPlayerSlots(INVENTORY_X + 1, INVENTORY_Y + 1);
	}

	@Override
	protected void saveData(WorkerStationBlockEntity station) {
	}

	/**
	 * Shift-clicking, which is the only way a stack moves without the cursor.
	 *
	 * <p>Vanilla's shape exactly, and the last two lines are the ones that matter.
	 * {@code moveItemStackTo} <b>shrinks the stack it was handed in place</b> rather than replacing it,
	 * and the stack it is handed here is the live one in the rack — so a hat shift-clicked out left the
	 * job holding a zero-count stack, which is a thing the block entity cannot save and which took the
	 * server down with "Cannot encode empty ItemStack" the next time the chunk was written. Emptying the
	 * source slot through {@code setByPlayer} is what ends the job instead.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem())
			return ItemStack.EMPTY;

		ItemStack stack = slot.getItem();
		ItemStack before = stack.copy();
		int rackSlots = WorkerStationBlockEntity.MAX_SLOTS;

		if (index < rackSlots) {
			if (!moveItemStackTo(stack, rackSlots, slots.size(), true))
				return ItemStack.EMPTY;
		} else if (!moveItemStackTo(stack, RACK_SLOT_START, rackSlots, false)) {
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty())
			slot.setByPlayer(ItemStack.EMPTY);
		else
			slot.setChanged();
		return before;
	}
}
