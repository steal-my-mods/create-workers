package com.createworkers.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.createworkers.CWConfig;
import com.createworkers.CreateWorkers;
import com.createworkers.item.HardHatItem;
import com.createworkers.net.ConfigureHatPacket;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWItems;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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

	private static final List<WorkerTarget> selection = new ArrayList<>();
	private static ItemStack currentItem = ItemStack.EMPTY;

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

		WorkerTarget point = find(pos);
		if (point == null) {
			point = WorkerTarget.create(level, pos, state);
			if (point == null)
				return;

			// One worker walks between all of these, so the whole programme has to stay inside a
			// beat it can actually serve. Refused here rather than dropped later, so nothing is ever
			// quietly missing from a hat.
			int maxSpread = CWConfig.MAX_TARGET_SPREAD.get();
			BlockPos tooFar = WorkerProgram.firstTooFar(selectedPositions(), pos, maxSpread);
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
		currentItem = ItemStack.EMPTY;
	}

	/** Rebuilds the working selection from whatever is already stored on the hat. */
	private static void loadFrom(ItemStack stack, Level level) {
		selection.clear();
		WorkerProgram program = HardHatItem.getProgram(stack);
		for (Tag entry : program.points()) {
			if (!(entry instanceof CompoundTag compound))
				continue;
			WorkerTarget target = WorkerTarget.deserialize(compound, level);
			if (target != null)
				selection.add(target);
		}
	}

	private static void pushToServer() {
		PacketDistributor.sendToServer(new ConfigureHatPacket(WorkerProgram.of(selection)));

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
		if (inputs + outputs == 0)
			player.displayClientMessage(Component.translatable("createworkers.message.selection_cleared")
				.withStyle(ChatFormatting.GRAY), true);
	}

	private static void drawOutlines() {
		for (Iterator<WorkerTarget> iterator = selection.iterator(); iterator.hasNext();) {
			WorkerTarget point = iterator.next();
			if (!point.isValid()) {
				iterator.remove();
				continue;
			}

			Level level = point.getLevel();
			BlockPos pos = point.getPos();
			VoxelShape shape = level.getBlockState(pos)
				.getShape(level, pos);
			if (shape.isEmpty())
				continue;

			Outliner.getInstance()
				.showAABB(point, shape.bounds()
					.move(pos))
				.colored(point.getMode()
					.getColor())
				.lineWidth(1 / 16f);
		}
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
