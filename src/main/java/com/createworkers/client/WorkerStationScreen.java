package com.createworkers.client;

import java.util.EnumSet;
import java.util.List;

import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.block.WorkerStationMenu;
import com.createworkers.net.StationRenamePacket;
import com.createworkers.net.StationRosterPacket;
import com.createworkers.worker.Shift;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import org.lwjgl.glfw.GLFW;

/**
 * The rack, arranged.
 *
 * <p>Everything here is a view of the block entity, which keeps itself synced to whoever is
 * watching — so a worker hired or lost while the screen is open appears without anything being asked
 * for, and every change the player makes goes to the server and comes back the same way rather than
 * being applied locally and hoped for.
 *
 * <p>Drawn in <b>vanilla's own furniture</b>: the same face grey, the same one-pixel bevel raised on a
 * panel and sunk into a well, the same dark label grey. None of it is a texture, which means none of it
 * is anybody's art — every shape here is four rectangles and the colours are the ones already on every
 * screen in the game.
 */
public class WorkerStationScreen extends AbstractSimiContainerScreen<WorkerStationMenu> {

	/** Vanilla's GUI palette, so this looks like the rest of the game rather than like a mod. */
	private static final int FACE = 0xFF_C6C6C6;
	private static final int HIGHLIGHT = 0xFF_FFFFFF;
	private static final int SHADOW = 0xFF_555555;
	private static final int WELL = 0xFF_8B8B8B;
	private static final int WELL_EDGE = 0xFF_373737;
	private static final int LABEL = 0xFF_404040;
	private static final int LABEL_FAINT = 0xFF_7A7A7A;
	/** A shift this job wants somebody on, and one somebody is actually on. */
	private static final int WANTED = 0xFF_B6A44A;
	private static final int COVERED = 0xFF_6A9E43;

	private static final int NAME_X = 22;
	private static final int NAME_WIDTH = 62;
	private static final int FIRST_TOGGLE_X = 88;
	private static final int TOGGLE_WIDTH = 20;
	private static final int TOGGLE_HEIGHT = 14;
	private static final int ARROW_X = 150;
	private static final int ARROW_WIDTH = 9;
	private static final int ARROW_HEIGHT = 8;
	private static final int MAX_NAME_LENGTH = 32;
	/** How wide the name becomes while it is being edited: the row, short of its arrows. */
	private static final int EDIT_WIDTH = ARROW_X - NAME_X - 2;

	/** The job whose name is being typed, or -1. */
	private int editing = -1;
	private EditBox nameBox;

	public WorkerStationScreen(WorkerStationMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	protected void init() {
		setWindowSize(WorkerStationMenu.PANEL_WIDTH, WorkerStationMenu.PANEL_HEIGHT);
		super.init();

		nameBox = new EditBox(font, 0, 0, EDIT_WIDTH, 10, Component.empty());
		nameBox.setMaxLength(MAX_NAME_LENGTH);
		nameBox.setBordered(false);
		// Dark text on a light panel with vanilla's drop shadow underneath it reads as a smear, the
		// shadow being a darkened copy of a colour that was already dark.
		nameBox.setTextShadow(false);
		nameBox.setTextColor(LABEL);
		nameBox.visible = false;
		addRenderableWidget(nameBox);
	}

	// --- drawing -------------------------------------------------------------------------

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = leftPos;
		int y = topPos;
		bevel(graphics, x, y, imageWidth, imageHeight, FACE, true);
		graphics.drawString(font, title, x + WorkerStationMenu.FIRST_COLUMN_X, y + 7, LABEL, false);

		for (int column = 0; column < 2; column++) {
			int cx = x + (column == 0 ? WorkerStationMenu.FIRST_COLUMN_X : WorkerStationMenu.SECOND_COLUMN_X);
			for (Shift shift : Shift.VALUES) {
				String initial = initial(shift);
				int tx = cx + FIRST_TOGGLE_X + shift.ordinal() * TOGGLE_WIDTH;
				graphics.drawString(font, initial, tx + (TOGGLE_WIDTH - 2 - font.width(initial)) / 2, y + 7,
					LABEL_FAINT, false);
			}
		}

		for (int i = 0; i < WorkerStationBlockEntity.MAX_SLOTS; i++)
			renderJob(graphics, x, y, i);

		renderReadout(graphics, x, y);
		renderInventoryWells(graphics, x, y);
	}

	private void renderJob(GuiGraphics graphics, int x, int y, int index) {
		int jx = x + WorkerStationMenu.columnX(index);
		int jy = y + WorkerStationMenu.rowY(index);
		bevel(graphics, jx, jy, 18, 18, WELL, false);

		WorkerStationBlockEntity.Slot job = jobAt(index);
		if (job == null) {
			// Every empty place takes a hat, so there is nothing to point at -- except on a rack with
			// nothing in it at all, where one line says what the block is for.
			if (index == 0 && jobCount() == 0)
				graphics.drawString(font, Component.translatable("createworkers.station.empty_slot"), jx + NAME_X,
					jy + 5, LABEL_FAINT, false);
			return;
		}

		if (editing == index) {
			// The whole row becomes one sunken field while it is being typed into. The name is worth
			// more room than it has beside three buttons, and a row that has visibly changed shape is
			// how the screen says which one is listening to the keyboard.
			bevel(graphics, jx + NAME_X - 1, jy + 3, EDIT_WIDTH + 2, 12, WELL, false);
			return;
		}

		graphics.drawString(font, font.plainSubstrByWidth(job.hat()
			.getHoverName()
			.getString(), NAME_WIDTH), jx + NAME_X, jy + 5, LABEL, false);

		for (Shift shift : Shift.VALUES) {
			int tx = jx + FIRST_TOGGLE_X + shift.ordinal() * TOGGLE_WIDTH;
			boolean runs = job.runs(shift);
			boolean covered = job.worker(shift) != null;
			// A shift the job does not run reads as an empty seat, one it runs reads as a raised button,
			// and one somebody is on is the only bright thing in the row -- which makes "who is short"
			// the question a glance answers.
			bevel(graphics, tx, jy + 2, TOGGLE_WIDTH - 2, TOGGLE_HEIGHT, !runs ? WELL : covered ? COVERED : WANTED,
				runs);
		}

		// Up and down run through the whole rack rather than stopping at the foot of a column, because
		// the order they change is one order of twelve -- the second column is its second half, not a
		// separate list. Either may swap a job into an empty place, which is how a job is moved down
		// without another one to trade with.
		if (index > 0)
			arrow(graphics, jx + ARROW_X, jy, true);
		if (index < WorkerStationBlockEntity.MAX_SLOTS - 1)
			arrow(graphics, jx + ARROW_X, jy + ARROW_HEIGHT + 2, false);
	}

	/**
	 * Drawn rather than typed. The font's triangles are glyphs on a nine-pixel line with a baseline
	 * that has nothing to do with this button, so they sat low and overhung an eight-pixel face. Four
	 * rectangles sit exactly where they are put.
	 */
	private void arrow(GuiGraphics graphics, int x, int y, boolean up) {
		bevel(graphics, x, y, ARROW_WIDTH, ARROW_HEIGHT, FACE, true);
		for (int row = 0; row < 4; row++) {
			int width = 1 + row * 2;
			int ry = y + (up ? 2 + row : 5 - row);
			int rx = x + (ARROW_WIDTH - width) / 2;
			graphics.fill(rx, ry, rx + width, ry + 1, LABEL);
		}
	}

	private void renderReadout(GuiGraphics graphics, int x, int y) {
		WorkerStationBlockEntity station = menu.contentHolder;
		if (station == null)
			return;

		int readoutY = y + WorkerStationMenu.FIRST_ROW_Y
			+ WorkerStationMenu.ROWS_PER_COLUMN * WorkerStationMenu.ROW_HEIGHT + 4;
		StringBuilder line = new StringBuilder();
		for (Shift shift : Shift.VALUES) {
			if (line.length() > 0)
				line.append("    ");
			line.append(Component.translatable(shift.translationKey())
				.getString())
				.append(' ')
				.append(station.staffed(shift))
				.append('/')
				.append(station.positions(shift));
		}
		graphics.drawString(font, line.toString(), x + WorkerStationMenu.FIRST_COLUMN_X, readoutY, LABEL, false);
	}

	private void renderInventoryWells(GuiGraphics graphics, int x, int y) {
		for (int i = 0; i < 36; i++) {
			int sx = x + WorkerStationMenu.INVENTORY_X + (i % 9) * 18;
			int sy = y + WorkerStationMenu.INVENTORY_Y + (i < 27 ? (i / 9) * 18 : 58);
			bevel(graphics, sx, sy, 18, 18, WELL, false);
		}
	}

	/**
	 * Vanilla's one-pixel edge: light along the top and left of anything raised, dark along its bottom
	 * and right, and the other way round for anything sunk into the panel. Every well, button and panel
	 * in the game is this shape, which is why four rectangles are enough to belong beside them.
	 */
	private static void bevel(GuiGraphics graphics, int x, int y, int width, int height, int face, boolean raised) {
		int top = raised ? HIGHLIGHT : WELL_EDGE;
		int bottom = raised ? SHADOW : HIGHLIGHT;
		graphics.fill(x, y, x + width, y + height, face);
		graphics.fill(x, y, x + width - 1, y + 1, top);
		graphics.fill(x, y, x + 1, y + height - 1, top);
		graphics.fill(x + 1, y + height - 1, x + width, y + height, bottom);
		graphics.fill(x + width - 1, y + 1, x + width, y + height, bottom);
	}

	@Override
	protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		// Drawn against the panel in renderBg, where every other coordinate here already is.
	}

	// --- renaming ------------------------------------------------------------------------

	/**
	 * Naming a job, which is the thing that makes a stuck worker findable.
	 *
	 * <p>Edited in place on its own row rather than in a field elsewhere on the panel: a rack of twelve
	 * with one name box at the top would need a selection to go with it, and clicking the name you want
	 * to change <em>is</em> the selection. No anvil and no experience either, because a label on a job
	 * is not an enchantment — Create names its Frogports and Train Stations in the block for the same
	 * reason.
	 */
	private void startEditing(int index) {
		WorkerStationBlockEntity.Slot job = jobAt(index);
		if (job == null)
			return;

		commitName();
		editing = index;
		nameBox.setX(leftPos + WorkerStationMenu.columnX(index) + NAME_X);
		nameBox.setY(topPos + WorkerStationMenu.rowY(index) + 5);
		nameBox.setValue(job.hat()
			.getHoverName()
			.getString());
		nameBox.visible = true;
		nameBox.setFocused(true);
		setFocused(nameBox);
	}

	private void commitName() {
		if (editing < 0)
			return;

		int index = editing;
		editing = -1;
		nameBox.visible = false;
		nameBox.setFocused(false);

		WorkerStationBlockEntity.Slot job = jobAt(index);
		String typed = nameBox.getValue()
			.trim();
		// An unchanged name is not a change. Sending one anyway would put a custom-name component on
		// every hat a player merely clicked, which survives the hat coming back out of the rack.
		if (job != null && !typed.equals(job.hat()
			.getHoverName()
			.getString()))
			PacketDistributor.sendToServer(new StationRenamePacket(index, typed));
	}

	@Override
	public boolean keyPressed(int key, int scan, int modifiers) {
		if (editing >= 0) {
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				commitName();
				return true;
			}
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				// Abandoned rather than committed, and the screen stays open -- which is what escape
				// means while something is being typed into it.
				editing = -1;
				nameBox.visible = false;
				nameBox.setFocused(false);
				return true;
			}
			if (nameBox.keyPressed(key, scan, modifiers))
				return true;
		}
		return super.keyPressed(key, scan, modifiers);
	}

	// --- clicking ------------------------------------------------------------------------

	/**
	 * The toggles, the arrows and the names, hit-tested rather than built as widgets.
	 *
	 * <p>Sixty buttons for twelve jobs would be sixty objects to rebuild every time a hat moved, and
	 * the rows are already drawn from the block entity on every frame — so the same arithmetic that
	 * draws them decides what was clicked.
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (editing >= 0 && !nameBox.isMouseOver(mouseX, mouseY))
			commitName();
		if (button == 0 && clickedRoster(mouseX, mouseY))
			return true;
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private boolean clickedRoster(double mouseX, double mouseY) {
		for (int index = 0; index < WorkerStationBlockEntity.MAX_SLOTS; index++) {
			WorkerStationBlockEntity.Slot job = jobAt(index);
			if (job == null)
				continue;

			int jx = leftPos + WorkerStationMenu.columnX(index);
			int jy = topPos + WorkerStationMenu.rowY(index);

			for (Shift shift : Shift.VALUES) {
				int tx = jx + FIRST_TOGGLE_X + shift.ordinal() * TOGGLE_WIDTH;
				if (within(mouseX, mouseY, tx, jy + 2, TOGGLE_WIDTH - 2, TOGGLE_HEIGHT)) {
					toggle(index, job, shift);
					return true;
				}
			}

			if (within(mouseX, mouseY, jx + NAME_X, jy + 4, NAME_WIDTH, 10)) {
				startEditing(index);
				return true;
			}

			int ax = jx + ARROW_X;
			if (index > 0 && within(mouseX, mouseY, ax, jy, ARROW_WIDTH, ARROW_HEIGHT)) {
				send(StationRosterPacket.move(index, index - 1));
				return true;
			}
			if (index < WorkerStationBlockEntity.MAX_SLOTS - 1
				&& within(mouseX, mouseY, ax, jy + ARROW_HEIGHT + 2, ARROW_WIDTH, ARROW_HEIGHT)) {
				send(StationRosterPacket.move(index, index + 1));
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
	private void toggle(int index, WorkerStationBlockEntity.Slot job, Shift shift) {
		EnumSet<Shift> wanted = EnumSet.noneOf(Shift.class);
		for (Shift candidate : Shift.VALUES)
			if (job.runs(candidate) != (candidate == shift))
				wanted.add(candidate);
		if (wanted.isEmpty())
			return;

		send(StationRosterPacket.shifts(index, wanted));
		playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 1.0F);
	}

	private static void send(StationRosterPacket packet) {
		PacketDistributor.sendToServer(packet);
	}

	private static boolean within(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	private int jobCount() {
		WorkerStationBlockEntity station = menu.contentHolder;
		return station == null ? 0 : station.jobCount();
	}

	/** Asked several times a row, several times a frame, so it indexes rather than copies. */
	private WorkerStationBlockEntity.Slot jobAt(int index) {
		WorkerStationBlockEntity station = menu.contentHolder;
		if (station == null || index < 0)
			return null;
		List<WorkerStationBlockEntity.Slot> jobs = station.slots();
		return index < jobs.size() ? jobs.get(index) : null;
	}

	private static String initial(Shift shift) {
		String name = Component.translatable(shift.translationKey())
			.getString();
		return name.isEmpty() ? "?" : name.substring(0, 1);
	}
}
