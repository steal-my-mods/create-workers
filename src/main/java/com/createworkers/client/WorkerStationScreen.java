package com.createworkers.client;

import java.util.EnumSet;
import java.util.List;

import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.block.WorkerStationMenu;
import com.createworkers.net.StationRosterPacket;
import com.createworkers.worker.Shift;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The rack, arranged.
 *
 * <p>Everything here is a view of the block entity, which keeps itself synced to whoever is
 * watching — so a worker hired or lost while the screen is open appears without anything being asked
 * for, and every change the player makes goes to the server and comes back the same way rather than
 * being applied locally and hoped for.
 *
 * <p>Every row the rack could ever hold is drawn, whether or not there is a job in it. A window that
 * grew and shrank as hats went in would be friendlier, but a {@code Slot}'s position is final once the
 * menu is built and the menu is built before any screen exists — so the alternative is rebuilding the
 * menu whenever the rack changes, which is a great deal of machinery for a window that is the right
 * height. The row count is the one number to revisit once somebody has actually used it.
 */
public class WorkerStationScreen extends AbstractSimiContainerScreen<WorkerStationMenu> {

	private static final int TOGGLE_WIDTH = 22;
	private static final int TOGGLE_HEIGHT = 14;
	private static final int FIRST_TOGGLE_X = 112;
	private static final int ARROW_X = 186;
	private static final int ARROW_SIZE = 9;
	/** Room under the last row for the staffing readout. */
	private static final int READOUT_HEIGHT = 20;

	private static final int PANEL = 0xFF_3A3A44;
	private static final int PANEL_EDGE = 0xFF_23232A;
	private static final int PANEL_LIGHT = 0xFF_4A4A56;
	private static final int SLOT_BG = 0xFF_26262C;
	private static final int ON = 0xFF_5A8C3A;
	private static final int STAFFED = 0xFF_7FC24C;
	private static final int OFF = 0xFF_2E2E36;
	private static final int TEXT = 0xFF_E0E0E6;
	private static final int TEXT_DIM = 0xFF_9A9AA6;

	/** Every place the rack could hold, drawn whether or not it holds one. */
	private static final int ROWS = WorkerStationBlockEntity.MAX_SLOTS;

	public WorkerStationScreen(WorkerStationMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	protected void init() {
		setWindowSize(WorkerStationMenu.PANEL_WIDTH,
			WorkerStationMenu.FIRST_ROW_Y + ROWS * WorkerStationMenu.ROW_HEIGHT + READOUT_HEIGHT + 82);
		super.init();
	}

	// --- drawing -------------------------------------------------------------------------

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = leftPos;
		int y = topPos;
		graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_EDGE);
		graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);

		graphics.drawString(font, title, x + 8, y + 8, TEXT, false);
		for (Shift shift : Shift.VALUES) {
			int tx = x + FIRST_TOGGLE_X + shift.ordinal() * TOGGLE_WIDTH;
			graphics.drawString(font, initial(shift), tx + 8, y + 9, TEXT_DIM, false);
		}

		for (int i = 0; i < ROWS; i++)
			renderRow(graphics, x, y, i);
		renderReadout(graphics, x, y);
		renderPlayerPanel(graphics, x, y);
	}

	private void renderRow(GuiGraphics graphics, int x, int y, int row) {
		int rowY = y + WorkerStationMenu.FIRST_ROW_Y + row * WorkerStationMenu.ROW_HEIGHT;
		int slotX = x + WorkerStationMenu.SLOT_X;
		graphics.fill(slotX, rowY, slotX + 18, rowY + 18, SLOT_BG);

		WorkerStationBlockEntity.Slot job = jobAt(row);
		if (job == null) {
			// Only on the first empty row. Eleven copies of the same invitation is not an invitation.
			if (jobAt(row - 1) != null || row == 0)
				graphics.drawString(font, Component.translatable("createworkers.station.empty_slot"), x + 30,
					rowY + 5, TEXT_DIM, false);
			return;
		}

		// Clipped rather than allowed to run under the toggles: a hat can be named anything on an anvil,
		// and the columns to its right are the part of the row a player is reading.
		graphics.drawString(font, font.plainSubstrByWidth(job.hat()
			.getHoverName()
			.getString(), FIRST_TOGGLE_X - 34), x + 30, rowY + 5, TEXT, false);

		for (Shift shift : Shift.VALUES) {
			int tx = x + FIRST_TOGGLE_X + shift.ordinal() * TOGGLE_WIDTH;
			int ty = rowY + 2;
			boolean runs = job.runs(shift);
			// Three states rather than two, because "this job wants somebody on nights" and "somebody
			// is on nights" are the questions a player is actually asking, and one colour can carry
			// both: dark is off, dull green is wanted, bright green is covered.
			graphics.fill(tx, ty, tx + TOGGLE_WIDTH - 2, ty + TOGGLE_HEIGHT,
				!runs ? OFF : job.worker(shift) != null ? STAFFED : ON);
		}

		if (row > 0)
			arrow(graphics, x + ARROW_X, rowY + 1, true);
		if (jobAt(row + 1) != null)
			arrow(graphics, x + ARROW_X, rowY + 9, false);
	}

	private void arrow(GuiGraphics graphics, int x, int y, boolean up) {
		graphics.fill(x, y, x + ARROW_SIZE, y + 7, PANEL_LIGHT);
		graphics.drawString(font, up ? "▲" : "▼", x + 1, y - 1, TEXT_DIM, false);
	}

	private void renderReadout(GuiGraphics graphics, int x, int y) {
		WorkerStationBlockEntity station = menu.contentHolder;
		if (station == null)
			return;

		int readoutY = y + WorkerStationMenu.FIRST_ROW_Y + ROWS * WorkerStationMenu.ROW_HEIGHT + 5;
		StringBuilder line = new StringBuilder();
		for (Shift shift : Shift.VALUES) {
			if (line.length() > 0)
				line.append("   ");
			line.append(Component.translatable(shift.translationKey())
				.getString())
				.append(' ')
				.append(station.staffed(shift))
				.append('/')
				.append(station.positions(shift));
		}
		graphics.drawString(font, line.toString(), x + 8, readoutY, TEXT_DIM, false);
	}

	private void renderPlayerPanel(GuiGraphics graphics, int x, int y) {
		int top = y + WorkerStationMenu.FIRST_ROW_Y + ROWS * WorkerStationMenu.ROW_HEIGHT + READOUT_HEIGHT;
		for (int i = 0; i < 36; i++) {
			int sx = x + WorkerStationMenu.SLOT_X + (i % 9) * 18;
			int sy = top + (i < 27 ? (i / 9) * 18 : 58);
			graphics.fill(sx, sy, sx + 18, sy + 18, SLOT_BG);
		}
	}

	@Override
	protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		// Drawn in renderBg against the panel, where the coordinates are the ones everything else uses.
	}

	// --- clicking ------------------------------------------------------------------------

	/**
	 * The toggles and the arrows, hit-tested rather than built as widgets.
	 *
	 * <p>Sixty buttons for twelve rows would be sixty objects to rebuild every time a hat moved, and
	 * the rows are already redrawn from the block entity on every frame — so the same arithmetic that
	 * draws them decides what was clicked.
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && clickedRoster(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private boolean clickedRoster(double mouseX, double mouseY) {
		for (int row = 0; row < ROWS; row++) {
			WorkerStationBlockEntity.Slot job = jobAt(row);
			if (job == null)
				continue;

			int rowY = topPos + WorkerStationMenu.FIRST_ROW_Y + row * WorkerStationMenu.ROW_HEIGHT;
			for (Shift shift : Shift.VALUES) {
				int tx = leftPos + FIRST_TOGGLE_X + shift.ordinal() * TOGGLE_WIDTH;
				if (within(mouseX, mouseY, tx, rowY + 2, TOGGLE_WIDTH - 2, TOGGLE_HEIGHT)) {
					toggle(row, job, shift);
					return true;
				}
			}

			int ax = leftPos + ARROW_X;
			if (row > 0 && within(mouseX, mouseY, ax, rowY + 1, ARROW_SIZE, 7)) {
				send(StationRosterPacket.move(row, row - 1));
				return true;
			}
			if (jobAt(row + 1) != null && within(mouseX, mouseY, ax, rowY + 9, ARROW_SIZE, 7)) {
				send(StationRosterPacket.move(row, row + 1));
				return true;
			}
		}
		return false;
	}

	/**
	 * Turning a shift off is firing whoever was covering it, so the last one on cannot be turned off —
	 * a job that runs on no shift could never be filled and could never be seen to be empty. Ending the
	 * job altogether is taking its hat out, which is a thing the player does deliberately.
	 */
	private void toggle(int row, WorkerStationBlockEntity.Slot job, Shift shift) {
		EnumSet<Shift> wanted = EnumSet.noneOf(Shift.class);
		for (Shift candidate : Shift.VALUES)
			if (job.runs(candidate) != (candidate == shift))
				wanted.add(candidate);
		if (wanted.isEmpty())
			return;

		send(StationRosterPacket.shifts(row, wanted));
		playUiSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 1.0F);
	}

	private static void send(StationRosterPacket packet) {
		PacketDistributor.sendToServer(packet);
	}

	private static boolean within(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	/** Asked several times a row, several times a frame, so it indexes rather than copies. */
	private WorkerStationBlockEntity.Slot jobAt(int row) {
		WorkerStationBlockEntity station = menu.contentHolder;
		if (station == null || row < 0)
			return null;
		List<WorkerStationBlockEntity.Slot> jobs = station.slots();
		return row < jobs.size() ? jobs.get(row) : null;
	}

	private static String initial(Shift shift) {
		return Component.translatable(shift.translationKey())
			.getString()
			.substring(0, 1);
	}
}
