package com.createworkers.item;

import java.util.List;

import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWComponents;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wearable hard hat that doubles as the worker programming tool.
 *
 * <p>Held in hand it behaves like the Mechanical Arm item: right-clicking an inventory
 * selects it and toggles between extract and insert, left-clicking deselects. Right-clicking
 * a villager or an enderman hands the hat over and puts them to work.
 */
public class HardHatItem extends ArmorItem {

	public HardHatItem(Holder<ArmorMaterial> material, ArmorItem.Type type, Properties properties) {
		super(material, type, properties);
	}

	public static WorkerProgram getProgram(ItemStack stack) {
		return stack.getOrDefault(CWComponents.PROGRAM.get(), WorkerProgram.EMPTY);
	}

	public static void setProgram(ItemStack stack, WorkerProgram program) {
		if (program.isEmpty())
			stack.remove(CWComponents.PROGRAM.get());
		else
			stack.set(CWComponents.PROGRAM.get(), program);
	}

	/**
	 * Swallows the click so that right-clicking a chest with the hat selects it instead of
	 * opening it. The actual selection happens client-side in the selection handler, exactly
	 * like the Mechanical Arm.
	 */
	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState state = level.getBlockState(pos);
		if (WorkerTarget.isTargetable(level, pos, state))
			return InteractionResult.SUCCESS;
		return super.useOn(context);
	}

	@Override
	public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.player.Player player) {
		return !WorkerTarget.isTargetable(level, pos, state);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
		TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);

		WorkerProgram program = getProgram(stack);
		if (program.isEmpty()) {
			tooltip.add(Component.translatable("createworkers.hard_hat.unprogrammed")
				.withStyle(ChatFormatting.GRAY));
			tooltip.add(Component.translatable("createworkers.hard_hat.hint")
				.withStyle(ChatFormatting.DARK_GRAY));
			return;
		}

		int inputs = program.countWithMode(Mode.TAKE);
		int outputs = program.countWithMode(Mode.DEPOSIT);
		tooltip.add(Component.translatable("createworkers.hard_hat.summary", inputs, outputs)
			.withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable("createworkers.hard_hat.assign_hint")
			.withStyle(ChatFormatting.DARK_GRAY));
	}
}
