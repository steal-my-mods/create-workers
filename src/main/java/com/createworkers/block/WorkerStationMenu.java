package com.createworkers.block;

import com.createworkers.registry.CWMenuTypes;
import com.createworkers.worker.Shift;
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
	public static final int COLUMN_WIDTH = 148;
	public static final int FIRST_COLUMN_X = 8;
	public static final int SECOND_COLUMN_X = 164;
	public static final int FIRST_ROW_Y = 22;
	/**
	 * Vanilla's slot pitch, and the panel has to fit inside 320x240 whatever the window is.
	 *
	 * <p>{@code Window.calculateScale} picks the largest scale whose logical area is still at least
	 * 320x240, so a panel inside that fits at every scale the game will choose and one outside it does
	 * not. This was 20, which made the panel 250 tall against a logical floor of 240 — and the width
	 * was 346, which is worse than it sounds: the scale is limited by whichever axis is tighter, so a
	 * 1280x1024 display lands on exactly 320 logical pixels across and clipped the second column's
	 * arrows off the edge. Not an exotic size; every 4:3 and 5:4 monitor does it.
	 */
	public static final int ROW_HEIGHT = 18;

	/**
	 * The two lines under the rack: what every shift is carrying, and — only when there is one — a
	 * warning about jobs this Station cannot staff.
	 *
	 * <p><b>They are declared here, with the slots, because a line of text that lands on top of the
	 * player's inventory is a layout bug and layout bugs are only catchable in the menu.</b> Nothing
	 * on a dedicated server can render a screen, so the one thing a test can read is arithmetic in a
	 * common class — and the warning line shipped four pixels into the top row of the inventory,
	 * because it was worked out in the screen where nothing could see it. The second line is reserved
	 * whether or not it is drawn: it appears and disappears with the state of the rack, and a panel
	 * that changed height would mean rebuilding the menu, since a {@code Slot}'s position is final.
	 */
	public static final int LINE_HEIGHT = 10;
	public static final int READOUT_Y = FIRST_ROW_Y + ROWS_PER_COLUMN * ROW_HEIGHT + 4;
	public static final int WARNING_Y = READOUT_Y + LINE_HEIGHT;

	public static final int INVENTORY_Y = WARNING_Y + LINE_HEIGHT + 2;
	public static final int PANEL_WIDTH = SECOND_COLUMN_X + COLUMN_WIDTH + FIRST_COLUMN_X;
	/** Vanilla's inventory is 162 wide; centring it is arithmetic, not a number to keep in step. */
	public static final int INVENTORY_X = (PANEL_WIDTH - 162) / 2;
	/** The inventory, its three rows, the gap vanilla leaves before the hotbar, and a margin. */
	public static final int PANEL_HEIGHT = INVENTORY_Y + 58 + 18 + 6;

	/** The left edge of the job at {@code index}, its well included. */
	public static int columnX(int index) {
		return index < ROWS_PER_COLUMN ? FIRST_COLUMN_X : SECOND_COLUMN_X;
	}

	/** The top edge of the job at {@code index}, its well included. */
	public static int rowY(int index) {
		return FIRST_ROW_Y + (index % ROWS_PER_COLUMN) * ROW_HEIGHT;
	}

	// --- a job row's controls ---------------------------------------------------------------------
	// Here rather than in the screen, and for the reason the slot positions already are: a screen does
	// not load on a dedicated server, so anything written inside one is unreachable by every test this
	// project has. What that cost last time was a warning line drawn four pixels inside the player's
	// inventory. What it cost this time was worse -- see hitRow.

	public static final int NAME_X = 22;
	public static final int NAME_WIDTH = 50;
	public static final int FIRST_TOGGLE_X = 76;
	public static final int TOGGLE_WIDTH = 20;
	public static final int TOGGLE_HEIGHT = 14;
	public static final int ARROW_X = 138;
	public static final int ARROW_WIDTH = 9;
	public static final int ARROW_HEIGHT = 8;
	/** As long a name as an anvil allows. */
	public static final int MAX_NAME_LENGTH = 32;
	/** How wide the name field becomes while it is being typed: the row, short of its arrows. */
	public static final int EDIT_WIDTH = ARROW_X - NAME_X - 2;

	/** What a click on a job row landed on. */
	public enum Control {
		NONE,
		/** A shift toggle; {@link RowHit#shift()} says which. */
		SHIFT,
		/** The job's name, which begins a rename. */
		NAME,
		MOVE_UP,
		MOVE_DOWN
	}

	/** @param shift the shift's ordinal for {@link Control#SHIFT}, otherwise -1. */
	public record RowHit(Control control, int shift) {

		public static final RowHit MISS = new RowHit(Control.NONE, -1);
	}

	/**
	 * What a click at {@code relX}/{@code relY} — measured from the panel's top-left — lands on in the
	 * job at {@code index}, given that {@code editing} is the job whose name is being typed, or -1.
	 *
	 * <p><b>A row being renamed has no controls, because none of them are drawn.</b> The name field
	 * grows to {@link #EDIT_WIDTH} while it is being typed, which is the row short of its arrows — so
	 * it covers all three shift toggles, and the screen stops drawing them for exactly that reason.
	 * Hit-testing them anyway made every click inside the field do something other than move the
	 * caret: a click in the right two thirds toggled a shift, which hires or serves notice on a real
	 * villager and swallowed the click so the field never saw it, and a click in the left third
	 * re-entered the rename and committed whatever had been half-typed. An invisible control that is
	 * still clickable is the defect, and the fix belongs here rather than at the call site so that a
	 * test can see it.
	 */
	public static RowHit hitRow(int index, int editing, double relX, double relY) {
		if (index == editing)
			return RowHit.MISS;

		int jx = columnX(index);
		int jy = rowY(index);

		for (int shift = 0; shift < Shift.VALUES.length; shift++) {
			int tx = jx + FIRST_TOGGLE_X + shift * TOGGLE_WIDTH;
			if (within(relX, relY, tx, jy + 2, TOGGLE_WIDTH - 2, TOGGLE_HEIGHT))
				return new RowHit(Control.SHIFT, shift);
		}
		if (within(relX, relY, jx + NAME_X, jy + 4, NAME_WIDTH, 10))
			return new RowHit(Control.NAME, -1);

		int ax = jx + ARROW_X;
		if (index > 0 && within(relX, relY, ax, jy, ARROW_WIDTH, ARROW_HEIGHT))
			return new RowHit(Control.MOVE_UP, -1);
		if (index < WorkerStationBlockEntity.MAX_SLOTS - 1 && within(relX, relY, ax, jy + ARROW_HEIGHT + 2, ARROW_WIDTH, ARROW_HEIGHT))
			return new RowHit(Control.MOVE_DOWN, -1);
		return RowHit.MISS;
	}

	private static boolean within(double x, double y, int left, int top, int width, int height) {
		return x >= left && x < left + width && y >= top && y < top + height;
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
