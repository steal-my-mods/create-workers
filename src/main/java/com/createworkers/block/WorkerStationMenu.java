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
	 * exists and the two must agree exactly.
	 */
	public static final int PANEL_WIDTH = 214;
	public static final int ROW_HEIGHT = 18;
	public static final int FIRST_ROW_Y = 30;
	public static final int SLOT_X = 8;

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
			addSlot(new SlotItemHandler(contentHolder.rack(), i, SLOT_X + 1, FIRST_ROW_Y + 1 + i * ROW_HEIGHT) {
				@Override
				public int getMaxStackSize() {
					return 1;
				}
			});

		// The screen pushes the rows it is not showing off-screen and moves the player's inventory up
		// to meet the last one, so these are only where they start.
		addPlayerSlots(SLOT_X + 1, FIRST_ROW_Y + WorkerStationBlockEntity.MAX_SLOTS * ROW_HEIGHT + 20);
	}

	@Override
	protected void saveData(WorkerStationBlockEntity station) {
	}

	/**
	 * Shift-clicking, which is the only way a stack moves without the cursor.
	 *
	 * <p>A hat out of the rack goes to the player; anything from the player's inventory is offered to
	 * the rack, which takes it only if it is a hard hat and only at the end of the list — the rack is
	 * an ordered list of jobs and its order is load-bearing, so there is exactly one place a new one
	 * can go.
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem())
			return ItemStack.EMPTY;

		ItemStack stack = slot.getItem();
		int rackSlots = WorkerStationBlockEntity.MAX_SLOTS;
		boolean fromRack = index < rackSlots;

		ItemStack before = stack.copy();
		if (fromRack) {
			if (!moveItemStackTo(stack, rackSlots, slots.size(), false))
				return ItemStack.EMPTY;
		} else if (!moveItemStackTo(stack, RACK_SLOT_START, rackSlots, false)) {
			return ItemStack.EMPTY;
		}

		slot.setChanged();
		return before;
	}
}
