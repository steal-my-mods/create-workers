package com.createworkers.worker;

import java.util.List;

import com.createworkers.CreateWorkers;
import com.createworkers.item.HardHatItem;
import com.createworkers.net.WorkerStatePacket;
import com.createworkers.program.WorkerProgram;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Server-side glue: hiring, firing, keeping clients in the loop and cleaning up. */
@EventBusSubscriber(modid = CreateWorkers.ID)
public class WorkerEvents {

	/**
	 * Every villager and enderman gets the job goal. It costs almost nothing while they are
	 * unemployed — the goal bails out as soon as it sees no hard hat — and it means a worker
	 * that is hired, saved and reloaded starts working again with no extra bookkeeping.
	 */
	@SubscribeEvent
	public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
		if (event.getLevel()
			.isClientSide())
			return;
		if (!(event.getEntity() instanceof Mob mob))
			return;
		if (!Workers.canBeEmployed(mob))
			return;

		WorkerLocomotion locomotion = Workers.locomotionFor(mob);
		if (locomotion == null)
			return;

		mob.goalSelector.addGoal(0, new WorkerJobGoal(mob, locomotion));
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		Entity target = event.getTarget();
		if (!Workers.canBeEmployed(target))
			return;

		Player player = event.getEntity();
		Level level = target.level();
		ItemStack stack = player.getItemInHand(event.getHand());

		if (stack.isEmpty() && player.isShiftKeyDown()) {
			retire(event, target, player, level);
			return;
		}

		if (!(stack.getItem() instanceof HardHatItem))
			return;

		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
		if (level.isClientSide())
			return;

		WorkerProgram program = HardHatItem.getProgram(stack);
		if (program.isEmpty()) {
			player.displayClientMessage(Component.translatable("createworkers.message.hat_not_programmed")
				.withStyle(ChatFormatting.RED), true);
			return;
		}

		WorkerData data = Workers.getOrCreate(target);
		if (data.isEmployed()) {
			player.displayClientMessage(Component.translatable("createworkers.message.already_employed")
				.withStyle(ChatFormatting.RED), true);
			return;
		}

		data.employ(stack, program, target.blockPosition());
		if (!player.getAbilities().instabuild)
			stack.shrink(1);

		if (target instanceof Mob mob)
			Workers.updateCargoAppearance(mob, data.getHeld());

		WorkerStatePacket.sync(target, data);
		player.displayClientMessage(Component.translatable("createworkers.message.hired", program.size())
			.withStyle(ChatFormatting.GREEN), true);
	}

	private static void retire(PlayerInteractEvent.EntityInteract event, Entity target, Player player, Level level) {
		WorkerData data = Workers.get(target);
		if (data == null || !data.isEmployed())
			return;

		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
		if (level.isClientSide())
			return;

		List<ItemStack> returned = data.dismiss();
		for (ItemStack drop : returned)
			if (!player.getInventory()
				.add(drop))
				player.drop(drop, false);

		if (target instanceof Mob mob) {
			Workers.updateCargoAppearance(mob, ItemStack.EMPTY);
			WorkerLocomotion locomotion = Workers.locomotionFor(mob);
			if (locomotion != null)
				locomotion.stop(mob);
		}

		WorkerStatePacket.sync(target, data);
		player.displayClientMessage(Component.translatable("createworkers.message.retired")
			.withStyle(ChatFormatting.YELLOW), true);
	}

	/** A worker that dies on the job drops its hat and whatever it was carrying. */
	@SubscribeEvent
	public static void onLivingDrops(LivingDropsEvent event) {
		LivingEntity entity = event.getEntity();
		WorkerData data = Workers.get(entity);
		if (data == null || !data.isEmployed())
			return;

		Level level = entity.level();
		for (ItemStack drop : data.dismiss()) {
			ItemEntity item = new ItemEntity(level, entity.getX(), entity.getY(), entity.getZ(), drop);
			item.setDefaultPickUpDelay();
			event.getDrops()
				.add(item);
		}
	}

	/** New viewers need to be told what a worker looks like. */
	@SubscribeEvent
	public static void onStartTracking(PlayerEvent.StartTracking event) {
		if (!(event.getEntity() instanceof ServerPlayer player))
			return;
		Entity target = event.getTarget();
		WorkerData data = Workers.get(target);
		if (data == null || !data.isEmployed())
			return;
		net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, WorkerStatePacket.of(target, data));
	}

	/**
	 * Interaction points hold capability caches registered against the level. Releasing them
	 * when the worker unloads stops those caches outliving the entity.
	 */
	@SubscribeEvent
	public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
		if (event.getLevel()
			.isClientSide())
			return;
		WorkerData data = Workers.get(event.getEntity());
		if (data != null)
			data.releasePoints();
	}
}
