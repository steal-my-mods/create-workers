package com.createworkers.worker;

import java.util.List;

import com.createworkers.CWConfig;
import com.createworkers.CreateWorkers;
import com.createworkers.item.HardHatItem;
import com.createworkers.net.WorkerStatePacket;
import com.createworkers.program.WorkerProgram;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
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

		// Before getOrCreate, so a mob that cannot be hired is not left carrying an attachment.
		if (!Workers.isOldEnoughToWork(target)) {
			player.displayClientMessage(Component.translatable("createworkers.message.too_young")
				.withStyle(ChatFormatting.RED), true);
			return;
		}

		WorkerData data = Workers.getOrCreate(target);
		if (data.isEmployed()) {
			player.displayClientMessage(Component.translatable("createworkers.message.already_employed")
				.withStyle(ChatFormatting.RED), true);
			return;
		}

		// Refuse rather than quietly dropping the targets they cannot get to. The wander leash will
		// walk them the rest of the way in, so this only has to catch the genuinely absurd.
		BlockPos jobSite = program.centre();
		int spread = CWConfig.MAX_TARGET_SPREAD.get();
		if (!target.blockPosition()
			.closerThan(jobSite, spread)) {
			player.displayClientMessage(Component.translatable("createworkers.message.too_far_from_job",
				jobSite.getX(), jobSite.getY(), jobSite.getZ())
				.withStyle(ChatFormatting.RED), true);
			return;
		}

		data.employ(stack, program);
		if (!player.getAbilities().instabuild)
			stack.shrink(1);

		if (target instanceof Mob mob) {
			Workers.clearVillageJob(mob, data);
			Workers.updateCargoAppearance(mob, data.getHeld());
		}

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
			Workers.restoreVillageJob(mob, data);
			Workers.updateCargoAppearance(mob, ItemStack.EMPTY);
			WorkerLocomotion locomotion = Workers.locomotionFor(mob);
			if (locomotion != null)
				locomotion.stop(mob);
		}

		WorkerStatePacket.sync(target, data);
		player.displayClientMessage(Component.translatable("createworkers.message.retired")
			.withStyle(ChatFormatting.YELLOW), true);
	}

	/**
	 * A worker enderman teleports where the job sends it and nowhere else.
	 *
	 * <p>Left alone, vanilla's own teleports swamp the deliberate hop {@link TeleportLocomotion}
	 * makes. {@code EnderMan.customServerAiStep} rolls, on every tick that it is day and the sky is
	 * visible overhead, a better-than-one-in-thirty chance of blinking to a random point up to 32
	 * blocks away — and it runs <em>after</em> the goals in {@code Mob.serverAiStep}, so it lands on
	 * top of the hop the worker has just made. The 600-tick grace period that normally holds it back
	 * is keyed off {@code targetChangeTime}, which {@link TeleportLocomotion#tickEmployed} resets to
	 * zero every tick by clearing the target, so a worker never gets that reprieve: it is a hop to
	 * the depot, then a jump to nowhere, over and over, and a haul that should take two hops times
	 * out instead.
	 *
	 * <p>Cancelling here catches every vanilla enderman teleport — the daylight wander, the
	 * projectile dodge, the jump towards a staring player — and none of the mod's own, which go
	 * straight to {@code LivingEntity.randomTeleport} and fire no event. Unlike a panicking villager,
	 * an enderman fleeing 32 blocks in a random direction has no way back: nothing walks it home,
	 * because it does not walk.
	 */
	@SubscribeEvent
	public static void onEnderTeleport(EntityTeleportEvent.EnderEntity event) {
		if (Workers.isEmployed(event.getEntity()))
			event.setCanceled(true);
	}

	/**
	 * A worker enderman does not rearrange the scenery. Both of the block-moving goals gate on the
	 * mob-griefing check, so vetoing that is enough to stop the pair of them without touching the
	 * goal list — and neither carries a {@code Goal.Flag}, so the job goal's hold on MOVE does not
	 * stop them by itself.
	 *
	 * <p>Taking is the frequent one, a one-in-twenty roll on every tick the enderman is not already
	 * holding a block, which is often enough that a worker standing on dirt digs up its own floor. It
	 * also lies about the cargo: the block an enderman holds is exactly what
	 * {@link Workers#updateCargoAppearance} hands a block cargo to, so a stolen block shows up as
	 * the worker's load and the next real one silently deletes it. Placing is rarer and worse — it
	 * plants the cargo in the world while {@link WorkerData} still holds the item, minting a copy.
	 *
	 * <p>Endermen only. An employed villager still gets to work its fields.
	 */
	@SubscribeEvent
	public static void onMobGriefing(EntityMobGriefingEvent event) {
		if (event.getEntity() instanceof EnderMan enderman && Workers.isEmployed(enderman))
			event.setCanGrief(false);
	}

	/**
	 * An enderman drops the block it is holding as loot of its own, and for a worker that block is
	 * its cargo — which {@link #onLivingDrops} is already handing back. Clearing it as the worker
	 * dies, before the death loot is gathered, is what stops a block cargo dropping twice.
	 */
	@SubscribeEvent
	public static void onLivingDeath(LivingDeathEvent event) {
		if (event.getEntity() instanceof Mob mob && Workers.isEmployed(mob))
			Workers.updateCargoAppearance(mob, ItemStack.EMPTY);
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

	/**
	 * A worker that is about to stop being a villager clocks off first.
	 *
	 * <p>A villager bitten by a zombie, or struck by lightning, is <em>replaced</em> rather than
	 * killed: {@code Mob.convertTo} spawns the new mob and discards the old one, so no death and no
	 * drops event ever fires and the hat simply ceases to exist. Worse, the profession outlives the
	 * worker where the attachment does not — {@code Zombie} copies the villager's {@code
	 * VillagerData} onto the zombie villager, and curing it copies that back — so a worker that was
	 * bitten and cured would come back holding a profession with no employment behind it, unable to
	 * take a village job ever again because a worker's job-site predicates match nothing.
	 *
	 * <p>Retiring it here fixes both: the hat and the cargo drop where the villager stood, and the
	 * village job goes back on before anything copies it. {@code LivingConversionEvent.Pre} is the
	 * one hook that runs while the villager is still whole — it is fired from the conversion's own
	 * check ({@code Zombie.doHurtTarget}, {@code Villager.thunderHit}) before the replacement is
	 * built. The event is not cancelled: becoming a zombie is the villager's business, and a worker
	 * is not owed protection from it.
	 */
	@SubscribeEvent
	public static void onLivingConversion(LivingConversionEvent.Pre event) {
		LivingEntity entity = event.getEntity();
		if (entity.level()
			.isClientSide())
			return;

		WorkerData data = Workers.get(entity);
		if (data == null || !data.isEmployed())
			return;

		if (entity instanceof Mob mob) {
			// Before dismiss, for the same reason onLivingDeath does it: a block cargo an enderman is
			// holding is loot of its own.
			Workers.updateCargoAppearance(mob, ItemStack.EMPTY);
			Workers.restoreVillageJob(mob, data);
		}

		for (ItemStack drop : data.dismiss())
			entity.spawnAtLocation(drop);

		WorkerStatePacket.sync(entity, data);
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
