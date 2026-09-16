package com.createworkers.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.CreateWorkers;
import com.createworkers.item.HardHatItem;
import com.createworkers.net.ConfigureHatPacket;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWItems;
import com.createworkers.worker.WorkerShift;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Programming a hard hat, modelled on the Mechanical Arm: right-click an inventory to select it
 * and to flip between extracting and inserting, left-click to drop it, with every selection
 * outlined in the world.
 *
 * <p>Two things differ from the arm. Selections are loaded back off the item when you pick it up,
 * so a programmed hat can be edited instead of redone from scratch; and there is no placement
 * step to commit on, so each change is written straight back to the item.
 */
@EventBusSubscriber(modid = CreateWorkers.ID, value = Dist.CLIENT)
public class HatSelectionHandler {

	/**
	 * The colour a designated bed is outlined in, since it has no arm {@code Mode} to take one from.
	 *
	 * <p>Picked against the two that do: Create's {@code TAKE} is a pale cyan (0x7FD0E0) and its
	 * {@code DEPOSIT} a sand yellow (0xDDC166). A blue here — the obvious choice for a bed — is a
	 * shade of the same thing as the cyan once it is a one-pixel line seen across a room, so this is a
	 * violet, a third of the wheel from either. The lightness and saturation are deliberately theirs,
	 * so the three read as one set rather than as two of Create's and one of ours.
	 */
	private static final int BED_COLOR = 0xC77BE0;

	private static final List<WorkerTarget> selection = new ArrayList<>();
	/** The bed the worker is to sleep in, or null to leave it to find its own. */
	@Nullable
	private static BlockPos selectedBed;
	private static ItemStack currentItem = ItemStack.EMPTY;

	/**
	 * Sneak and Right-Click a bed to send the worker there for the night; the same click on the bed
	 * it already sleeps in takes the assignment off again.
	 *
	 * <p>The slot is free: {@link com.createworkers.item.HardHatItem#useOn} only swallows clicks on
	 * blocks a Mechanical Arm would accept, and a bed is not one. Cancelling the event is what keeps
	 * it free — sneak-clicking a bed normally sets the player's spawn point, and the cancel happens
	 * before the client has sent anything, so the server never hears about the click at all.
	 *
	 * <p>The bed has to pass the same spread rule as an inventory. It is one more place a worker
	 * walks, so leaving it out would make the commute the one unbounded trip in a programme whose
	 * every other distance is bounded.
	 *
	 * @return whether the click was a bed assignment, and so is not a selection click.
	 */
	private static boolean assignBed(Player player, Level level, BlockPos pos, BlockState state) {
		if (!player.isShiftKeyDown() || !state.is(BlockTags.BEDS))
			return false;

		BlockPos head = WorkerShift.bedHead(level, pos);
		if (head.equals(selectedBed)) {
			selectedBed = null;
			player.displayClientMessage(Component.translatable("createworkers.message.bed_cleared")
				.withStyle(ChatFormatting.GRAY), true);
			pushToServer();
			return true;
		}

		int maxSpread = CWConfig.MAX_TARGET_SPREAD.get();
		BlockPos tooFar = WorkerProgram.firstTooFar(selectedPositions(), head, maxSpread);
		if (tooFar != null) {
			player.displayClientMessage(Component.translatable("createworkers.message.bed_too_far",
				Mth.floor(Math.sqrt(head.distSqr(tooFar))), maxSpread)
				.withStyle(ChatFormatting.RED), true);
			return true;
		}

		selectedBed = head;
		player.displayClientMessage(Component.translatable("createworkers.message.bed_assigned")
			.withStyle(ChatFormatting.GREEN), true);
		pushToServer();
		return true;
	}

	@SubscribeEvent
	public static void rightClickingBlocksSelectsThem(PlayerInteractEvent.RightClickBlock event) {
		if (currentItem.isEmpty())
			return;
		Level level = event.getLevel();
		if (!level.isClientSide())
			return;
		Player player = event.getEntity();
		if (player == null || player.isSpectator())
			return;

		BlockPos pos = event.getPos();
		BlockState state = level.getBlockState(pos);

		if (assignBed(player, level, pos, state)) {
			event.setCanceled(true);
			event.setCancellationResult(InteractionResult.SUCCESS);
			return;
		}

		WorkerTarget point = find(pos);
		if (point == null) {
			point = WorkerTarget.create(level, pos, state);
			if (point == null)
				return;

			// One worker has to work all of these, so the whole programme has to stay inside what
			// one worker can serve -- a beat it can walk, and a list it can rescan without costing
			// the server a fortune. Refused here rather than dropped later, so nothing is ever
			// quietly missing from a hat.
			int maxTargets = CWConfig.MAX_TARGETS.get();
			if (selection.size() >= maxTargets) {
				player.displayClientMessage(Component.translatable("createworkers.message.too_many_targets",
					maxTargets)
					.withStyle(ChatFormatting.RED), true);
				event.setCanceled(true);
				event.setCancellationResult(InteractionResult.SUCCESS);
				return;
			}

			int maxSpread = CWConfig.MAX_TARGET_SPREAD.get();
			BlockPos tooFar = WorkerProgram.firstTooFar(spreadAnchors(), pos, maxSpread);
			if (tooFar != null) {
				player.displayClientMessage(Component.translatable("createworkers.message.target_too_far",
					Mth.floor(Math.sqrt(pos.distSqr(tooFar))), maxSpread)
					.withStyle(ChatFormatting.RED), true);
				event.setCanceled(true);
				event.setCancellationResult(InteractionResult.SUCCESS);
				return;
			}

			selection.add(point);
		}

		point.cycleMode();
		Mode mode = point.getMode();
		// Create's own mode strings, which live under its namespace prefix.
		player.displayClientMessage(Component.translatable("create." + mode.getTranslationKey(), state.getBlock()
			.getName()), true);

		pushToServer();
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}

	@SubscribeEvent
	public static void leftClickingBlocksDeselectsThem(PlayerInteractEvent.LeftClickBlock event) {
		if (currentItem.isEmpty())
			return;
		if (!event.getLevel()
			.isClientSide())
			return;

		WorkerTarget point = find(event.getPos());
		if (point == null)
			return;

		selection.remove(point);
		pushToServer();
		event.setCanceled(true);
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Player player = Minecraft.getInstance().player;
		if (player == null) {
			forget();
			return;
		}

		ItemStack held = player.getMainHandItem();
		if (!held.is(CWItems.HARD_HAT.get())) {
			forget();
			return;
		}

		if (held != currentItem) {
			currentItem = held;
			loadFrom(held, player.level());
		}

		drawOutlines();
	}

	private static void forget() {
		if (currentItem.isEmpty())
			return;
		selection.clear();
		selectedBed = null;
		currentItem = ItemStack.EMPTY;
	}

	/** Rebuilds the working selection from whatever is already stored on the hat. */
	private static void loadFrom(ItemStack stack, Level level) {
		selection.clear();
		WorkerProgram program = HardHatItem.getProgram(stack);
		selectedBed = program.bed();
		int maxTargets = CWConfig.MAX_TARGETS.get();
		for (Tag entry : program.points()) {
			if (!(entry instanceof CompoundTag compound))
				continue;
			if (selection.size() >= maxTargets)
				break; // a hat from a command, or from a config that used to allow more
			WorkerTarget target = WorkerTarget.deserialize(compound, level);
			if (target != null)
				selection.add(target);
		}
	}

	private static void pushToServer() {
		PacketDistributor.sendToServer(new ConfigureHatPacket(WorkerProgram.of(selection)
			.withBed(selectedBed)));

		Player player = Minecraft.getInstance().player;
		if (player == null)
			return;
		int inputs = 0;
		int outputs = 0;
		for (WorkerTarget point : selection) {
			if (point.getMode() == Mode.DEPOSIT)
				outputs++;
			else
				inputs++;
		}
		if (inputs + outputs == 0 && selectedBed == null)
			player.displayClientMessage(Component.translatable("createworkers.message.selection_cleared")
				.withStyle(ChatFormatting.GRAY), true);
	}

	/**
	 * Outlines every selection, and drops the ones that are no longer inventories.
	 *
	 * <p>Only the ones it can actually see are dropped. A position whose chunk the client has
	 * unloaded reads as "no longer an inventory" — the block there is air as far as this side is
	 * concerned — and forgetting it here would quietly shorten the programme the next click pushes
	 * back to the server. Walking away from a hat's beat and clicking a block elsewhere is enough to
	 * do it, which is a strange way to lose a programmed hat.
	 */
	private static void drawOutlines() {
		drawBedOutline();
		for (Iterator<WorkerTarget> iterator = selection.iterator(); iterator.hasNext();) {
			WorkerTarget point = iterator.next();
			if (!point.isLoaded())
				continue; // out of view: still on the hat, just nothing to draw
			if (!point.isValid()) {
				iterator.remove();
				continue;
			}

			AABB box = boundsAt(point.getLevel(), point.getPos());
			if (box == null)
				continue;

			Outliner.getInstance()
				.showAABB(point, box)
				.colored(point.getMode()
					.getColor())
				.lineWidth(1 / 16f);
		}
	}

	/**
	 * Outlines the bed, if there is one and this side can see it.
	 *
	 * <p>Never dropped for not being a bed any more, unlike a selected inventory: the client is free
	 * to be looking at a chunk it has not loaded, and forgetting the assignment on that basis would
	 * quietly take the bed off a hat because its owner walked away from the bedroom.
	 */
	private static void drawBedOutline() {
		if (selectedBed == null)
			return;
		Level level = Minecraft.getInstance().level;
		if (level == null || !level.isLoaded(selectedBed))
			return;

		if (!level.getBlockState(selectedBed)
			.is(BlockTags.BEDS)) {
			// Mined since it was assigned, and dropped for the same reason a selection that has stopped
			// being an inventory is dropped. Without this the assignment cannot be removed at all --
			// clearing one is a sneak-click on the bed, and there is no longer a bed to click -- while
			// the position it names goes on constraining the spread of everything else on the hat.
			selectedBed = null;
			pushToServer();
			return;
		}

		AABB box = boundsAt(level, selectedBed);
		if (box == null)
			return;

		// Both halves. Only the head is stored -- it is the half the game itself keeps a bed under --
		// so a box around the stored position is a box around half a bed, which is what it looks like.
		BlockPos other = WorkerShift.otherHalfOfBed(level, selectedBed);
		AABB otherHalf = other == null ? null : boundsAt(level, other);
		if (otherHalf != null)
			box = box.minmax(otherHalf);

		Outliner.getInstance()
			.showAABB(selectedBed, box)
			.colored(BED_COLOR)
			.lineWidth(1 / 16f);
	}

	/** The block's own shape, placed where the block is, or null if it has none to draw. */
	@Nullable
	private static AABB boundsAt(Level level, BlockPos pos) {
		VoxelShape shape = level.getBlockState(pos)
			.getShape(level, pos);
		return shape.isEmpty() ? null : shape.bounds()
			.move(pos);
	}

	/**
	 * Everything a new selection has to stay within reach of.
	 *
	 * <p>The bed among them, because the server measures {@code exceedsSpread} over the whole
	 * programme and refuses it silently if anything is out of range. A client that checked only the
	 * inventories would accept a block the server then dropped on the floor — and worse, keep it in
	 * the selection, so every push after it is refused too and the hat quietly stops updating.
	 * Refusing as you click is the whole contract here.
	 */
	private static List<BlockPos> spreadAnchors() {
		List<BlockPos> anchors = selectedPositions();
		if (selectedBed != null)
			anchors.add(selectedBed);
		return anchors;
	}

	private static List<BlockPos> selectedPositions() {
		List<BlockPos> positions = new ArrayList<>(selection.size());
		for (WorkerTarget point : selection)
			positions.add(point.getPos());
		return positions;
	}

	private static WorkerTarget find(BlockPos pos) {
		for (WorkerTarget point : selection)
			if (point.getPos()
				.equals(pos))
				return point;
		return null;
	}
}
