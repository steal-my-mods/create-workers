package com.createworkers.test;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.createworkers.CWConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;
import com.createworkers.net.WorkerStatePacket;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import com.createworkers.CreateWorkers;
import com.createworkers.block.CanteenBlockEntity;
import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.recipe.ClearProgramRecipe;
import com.createworkers.registry.CWBlocks;
import com.createworkers.registry.CWItems;
import com.createworkers.registry.CWPoiTypes;
import com.createworkers.registry.CWProfessions;
import com.createworkers.worker.TeleportLocomotion;
import com.createworkers.worker.WalkLocomotion;
import com.createworkers.worker.Shift;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.WorkerTrades;
import com.createworkers.worker.Workers;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.funnel.AbstractDirectionalFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Workers are tested against Depots rather than chests on purpose: a worker may only target what a
 * Mechanical Arm can target, and a plain chest is not one of those things.
 */
@GameTestHolder(CreateWorkers.ID)
@PrefixGameTestTemplate(false)
public class WorkerGameTests {

	/** Long enough for a spawned mob to be standing on the floor. */
	private static final int SETTLE_TICKS = 5;

	/** Speeds are carried as floats and read back as doubles. */
	private static final float TOLERANCE = 1.0E-4F;

	private static final BlockPos SOURCE = new BlockPos(1, 1, 1);
	private static final BlockPos SOURCE_B = new BlockPos(1, 1, 9);
	private static final BlockPos TARGET = new BlockPos(9, 1, 9);
	private static final BlockPos SPAWN = new BlockPos(5, 1, 5);

	/** Somewhere on the floor for a workstation, clear of the depots. */
	private static final BlockPos COMPOSTER = new BlockPos(2, 1, 8);

	/**
	 * Long enough for the villager brain to have had its say. AcquirePoi retries on a jittered clock
	 * of a few tens of ticks, and ResetProfession runs every tick once a job site is absent.
	 */
	private static final int BRAIN_SETTLE_TICKS = 100;
	private static final int STOCK = 16;
	private static final int SITE_SIZE = 11;

	// Sorting-office rig: a chest beneath each filtered funnel.
	private static final BlockPos SMELTING_CHEST = new BlockPos(2, 1, 1);
	private static final BlockPos SMELTING_FUNNEL = new BlockPos(2, 2, 1);
	private static final BlockPos STORAGE_CHEST = new BlockPos(8, 1, 1);
	private static final BlockPos STORAGE_FUNNEL = new BlockPos(8, 2, 1);
	/** Ticks to let the funnels latch onto the chests beneath them. */
	private static final int FUNNEL_WARMUP = 10;

	/** Directly over TARGET, for the funnel that stocks a Canteen standing there. */
	private static final BlockPos CANTEEN_FUNNEL = new BlockPos(9, 2, 9);

	/** Comfortably longer than WorkerData's retry interval for targets that would not resolve. */
	private static final int RESOLVE_RETRY_WAIT = 110;

	/** Longer than the idle grace plus the stall timeout, so the rounds would have written a stop off. */
	private static final int ROUNDS_WOULD_HAVE_GIVEN_UP = 320;

	/**
	 * Workers must accept exactly what an arm accepts — Create blocks yes, plain inventories no.
	 *
	 * <p><b>This mod's own inventories are on the "no" side of that line, and the Canteen is the one
	 * that had to be argued.</b> It was briefly a registered {@code ArmInteractionPointType}, which
	 * made it a legal destination on a hat — and the tell that it was wrong was having to invent a
	 * rule no other target here has (deposit only) to stop a bread-in-bread-out loop that existed
	 * <em>because</em> of the registration. It bought one funnel, and it cost the only answer a player
	 * can be given for why a chest needs one: because a worker is an arm with legs, and an arm cannot
	 * reach into a chest either.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void targetsMatchTheMechanicalArm(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(TARGET, Blocks.CHEST);
		helper.setBlock(SOURCE_B, CWBlocks.CANTEEN.get());

		ServerLevel level = helper.getLevel();
		BlockPos depot = helper.absolutePos(SOURCE);
		BlockPos chest = helper.absolutePos(TARGET);
		BlockPos canteen = helper.absolutePos(SOURCE_B);

		helper.assertTrue(WorkerTarget.isTargetable(level, depot, level.getBlockState(depot)),
			"a depot should be a valid worker target");
		helper.assertTrue(!WorkerTarget.isTargetable(level, chest, level.getBlockState(chest)),
			"a plain chest should not be a worker target, just as it is not an arm target");
		helper.assertTrue(!WorkerTarget.isTargetable(level, canteen, level.getBlockState(canteen)),
			"a canteen is an inventory like any other: put a funnel on it, the same as a chest");

		WorkerTarget target = WorkerTarget.create(level, depot, level.getBlockState(depot));
		helper.assertTrue(target != null, "a depot should produce a target");
		helper.assertTrue(WorkerTarget.create(level, chest, level.getBlockState(chest)) == null,
			"a chest should produce no target");
		helper.assertTrue(WorkerTarget.create(level, canteen, level.getBlockState(canteen)) == null,
			"and neither should a canteen");
		helper.succeed();
	}

	/**
	 * The transfer algorithm on its own, with no walking involved, so a failure here points at the
	 * item handling rather than at pathfinding.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void transferLogicMovesItems(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);

		data.resolvePoints(villager);
		helper.assertTrue(data.getInputs()
			.size() == 1, "expected exactly one input, got " + data.getInputs()
				.size());
		helper.assertTrue(data.getOutputs()
			.size() == 1, "expected exactly one output, got " + data.getOutputs()
				.size());

		helper.assertTrue(data.searchForItem(now(helper)) == 0, "should have found the stocked input depot");
		helper.assertTrue(data.collectFrom(data.getInputs()
			.get(0), now(helper)), "should have collected from the source depot");
		helper.assertTrue(!data.getHeld()
			.isEmpty(), "worker should be carrying something after collecting");
		helper.assertTrue(data.depositTo(data.getOutputs()
			.get(0)), "should have deposited into the target depot");

		assertDelivered(helper);
		helper.succeed();
	}

	/**
	 * A worker never picks up what it has nowhere to put, exactly as an arm does not.
	 *
	 * <p>The outputs a scan prices a stack against are gathered once for the whole scan rather than
	 * re-checked per slot, which is the difference between two dozen block reads and a few thousand.
	 * This is the rule that gathering has to preserve: an output the worker has lately failed to
	 * reach is not somewhere it can deliver, so a stack that fits only there does not count as
	 * distributable and the input holding it is not worth walking to.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void nothingIsCollectedWithNowhereToPutIt(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);
		data.resolvePoints(villager);

		long now = now(helper);
		helper.assertTrue(data.searchForItem(now) == 0, "the stocked input should be found while the output is usable");

		// The only place the stock could go, set aside.
		data.markUnreachable(helper.absolutePos(TARGET), now + 100);

		helper.assertTrue(data.searchForItem(now) == -1,
			"an input must not be chosen when the only output is one the worker cannot reach");
		helper.assertTrue(!data.collectFrom(data.getInputs()
			.get(0), now), "nothing should be collected with nowhere to deliver it");
		helper.assertTrue(data.getHeld()
			.isEmpty(), "the worker should be carrying nothing");

		// Set aside, not written off.
		helper.assertTrue(data.searchForItem(now + 100) == 0, "the input should be back once the set-aside expires");
		helper.succeed();
	}

	/**
	 * Collecting works with no scan in front of it.
	 *
	 * <p>{@code collectFrom} tries the slot the scan settled on before walking the inventory, to
	 * avoid pricing every earlier slot against every output twice for one pickup. The hint is an
	 * optimisation and never a precondition: there may not be one — the worker may have been sent to
	 * a target some other way, or the slot may have emptied while it walked — and the full walk has
	 * to remain the thing that actually decides.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void collectingWorksWithoutAScanToHintAt(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);
		data.resolvePoints(villager);

		// Deliberately no searchForItem first, so there is no hint to lean on.
		helper.assertTrue(data.collectFrom(data.getInputs()
			.get(0), now(helper)), "should have collected from the source depot with no scan beforehand");
		helper.assertTrue(!data.getHeld()
			.isEmpty(), "worker should be carrying something after collecting");
		helper.succeed();
	}

	/** A programmed hat has to survive being written to NBT and read back. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void programSurvivesRoundTrip(GameTestHelper helper) {
		prepareWorkSite(helper);
		ServerLevel level = helper.getLevel();

		WorkerTarget in = target(helper, SOURCE);
		WorkerTarget out = target(helper, TARGET);
		in.cycleMode();

		WorkerProgram program = WorkerProgram.of(List.of(in, out));
		helper.assertTrue(program.size() == 2, "program should hold two targets");
		helper.assertTrue(program.countWithMode(Mode.TAKE) == 1, "program should hold one input");
		helper.assertTrue(program.countWithMode(Mode.DEPOSIT) == 1, "program should hold one output");

		WorkerProgram reloaded = new WorkerProgram(program.tag()
			.copy());
		WorkerTarget first = WorkerTarget.deserialize(reloaded.points()
			.getCompound(0), level);
		helper.assertTrue(first != null, "target should deserialize");
		helper.assertTrue(first.getPos()
			.equals(helper.absolutePos(SOURCE)), "position should survive the round trip");
		helper.assertTrue(first.getMode() == Mode.TAKE, "mode should survive the round trip");
		helper.succeed();
	}

	/**
	 * Crafting a hat by itself clears it, the way crafting a Create filter by itself blanks it.
	 *
	 * <p>The hat has to come back out otherwise untouched: a plain shapeless recipe would hand over a
	 * factory-fresh helmet, repairing it for free and eating its enchantments, which is why the
	 * recipe is a class rather than four lines of JSON.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void craftingAHatByItselfClearsIt(GameTestHelper helper) {
		prepareWorkSite(helper);
		ServerLevel level = helper.getLevel();

		WorkerTarget in = target(helper, SOURCE);
		WorkerTarget out = target(helper, TARGET);
		in.cycleMode();

		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(hat, WorkerProgram.of(List.of(in, out)));
		hat.setDamageValue(100);
		hat.set(DataComponents.CUSTOM_NAME, Component.literal("Site Foreman"));

		CraftingInput input = CraftingInput.of(1, 1, List.of(hat));
		RecipeHolder<CraftingRecipe> recipe = level.getRecipeManager()
			.getRecipeFor(RecipeType.CRAFTING, input, level)
			.orElse(null);
		helper.assertTrue(recipe != null, "a lone hard hat should match a crafting recipe");
		helper.assertTrue(recipe.value() instanceof ClearProgramRecipe,
			"a lone hard hat should match the clearing recipe, got " + recipe.id());

		ItemStack cleared = recipe.value()
			.assemble(input, level.registryAccess());
		helper.assertTrue(cleared.is(CWItems.HARD_HAT.get()), "clearing a hat should hand back a hat");
		helper.assertTrue(HardHatItem.getProgram(cleared)
			.isEmpty(), "the cleared hat should hold no inventories");
		helper.assertTrue(cleared.getDamageValue() == 100,
			"clearing must not repair the hat, damage came back as " + cleared.getDamageValue());
		helper.assertTrue(cleared.has(DataComponents.CUSTOM_NAME),
			"clearing must leave the rest of the hat alone");

		// Two hats are vanilla's repair recipe; clearing must not swallow that.
		CraftingInput pair = CraftingInput.of(2, 1, List.of(hat.copy(), hat.copy()));
		RecipeHolder<CraftingRecipe> repair = level.getRecipeManager()
			.getRecipeFor(RecipeType.CRAFTING, pair, level)
			.orElse(null);
		helper.assertTrue(repair == null || !(repair.value() instanceof ClearProgramRecipe),
			"two hats should still be a repair, not a clear");
		helper.succeed();
	}

	/**
	 * With more than one stocked input the scan must wrap around rather than running off the end of
	 * the list, or a worker idles for a full rescan delay every time it uses the last input.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void inputScanWrapsAround(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(SOURCE_B, AllBlocks.DEPOT.getDefaultState());
		stock(helper, SOURCE_B);

		WorkerTarget inA = target(helper, SOURCE);
		WorkerTarget inB = target(helper, SOURCE_B);
		WorkerTarget out = target(helper, TARGET);
		inA.cycleMode();
		inB.cycleMode();

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = Workers.getOrCreate(villager);
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.of(List.of(inA, inB, out)));
		data.resolvePoints(villager);

		helper.assertTrue(data.getInputs()
			.size() == 2, "expected two inputs");

		// Three scans in a row must all find work: 0, 1, then wrap back to 0.
		helper.assertTrue(data.searchForItem(now(helper)) == 0, "first scan should pick input 0");
		helper.assertTrue(data.searchForItem(now(helper)) == 1, "second scan should advance to input 1");
		helper.assertTrue(data.searchForItem(now(helper)) == 0, "third scan should wrap back to input 0");
		helper.succeed();
	}

	/** End to end: a hatted villager should walk over and shift the stock across by itself. */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void villagerHaulsBetweenDepots(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.assertBlockPresent(Blocks.POLISHED_ANDESITE, new BlockPos(SPAWN.getX(), 0, SPAWN.getZ()));

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		villager.setPersistenceRequired();
		employ(helper, villager);

		helper.succeedWhen(() -> {
			helper.assertTrue(villager.isAlive(), "the villager should still be alive");
			assertDelivered(helper);
		});
	}

	/** End to end for the teleporting variant. */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void endermanHaulsBetweenDepots(GameTestHelper helper) {
		prepareWorkSite(helper);
		EnderMan enderman = helper.spawn(EntityType.ENDERMAN, SPAWN);
		enderman.setPersistenceRequired();
		employ(helper, enderman);

		helper.succeedWhen(() -> assertDelivered(helper));
	}

	/**
	 * Teleporting must cost time, or an enderman is a strictly better Mechanical Arm.
	 *
	 * <p>Asserted against the mechanism rather than against wall-clock delivery time: the enderman is
	 * put back where it started between calls, so the only thing that can keep it there is the
	 * cooldown gate. A first version of this measured "not delivered within 25 ticks" and passed
	 * happily with the cooldown turned down to 1, guarding nothing.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void teleportsRespectTheirCooldown(GameTestHelper helper) {
		prepareWorkSite(helper);
		EnderMan enderman = helper.spawn(EntityType.ENDERMAN, SPAWN);
		WorkerTarget target = target(helper, SOURCE);
		TeleportLocomotion locomotion = new TeleportLocomotion();

		Vec3 home = enderman.position();
		locomotion.approach(enderman, target.getPos());
		helper.assertTrue(enderman.position()
			.distanceToSqr(home) > 1.0D, "the first approach should teleport the enderman");

		int cooldown = CWConfig.TELEPORT_COOLDOWN.get();
		for (int tick = 1; tick <= cooldown; tick++) {
			enderman.teleportTo(home.x, home.y, home.z);
			locomotion.approach(enderman, target.getPos());
			helper.assertTrue(enderman.position()
				.distanceToSqr(home) < 1.0D,
				"teleported again after only " + tick + " of " + cooldown + " cooldown ticks");
		}

		enderman.teleportTo(home.x, home.y, home.z);
		locomotion.approach(enderman, target.getPos());
		helper.assertTrue(enderman.position()
			.distanceToSqr(home) > 1.0D, "should teleport again once the cooldown has elapsed");
		helper.succeed();
	}

	/** Whatever else happens, an enderman must not blink into water and start drowning. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void endermanNeverLandsInWater(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		for (int x = 0; x < SITE_SIZE; x++)
			for (int z = 0; z < SITE_SIZE; z++)
				if (x != SOURCE.getX() || z != SOURCE.getZ())
					helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);

		EnderMan enderman = helper.spawn(EntityType.ENDERMAN, new BlockPos(5, 3, 5));
		ServerLevel level = helper.getLevel();
		BlockPos spot = TeleportLocomotion.findLandingSpot(enderman, helper.absolutePos(SOURCE), 2);

		if (spot != null)
			helper.assertTrue(level.getFluidState(spot)
				.isEmpty()
				&& level.getFluidState(spot.above())
					.isEmpty(),
				"chose a landing spot standing in fluid: " + spot);
		helper.succeed();
	}

	/**
	 * Vanilla teleports are vetoed for anyone on the clock.
	 *
	 * <p>Asserted through the very hook vanilla goes through, because every one of its own teleports
	 * is a private call this test cannot make: the daylight wander that fires several times a minute
	 * under an open sky, the projectile dodge, the jump towards a staring player. The unemployed
	 * control matters as much as the employed case — this handler sees every enderman in the world,
	 * and a wild one must still behave like a wild one.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void employedEndermenOnlyTeleportForWork(GameTestHelper helper) {
		prepareWorkSite(helper);
		EnderMan worker = helper.spawn(EntityType.ENDERMAN, SPAWN);
		EnderMan wild = helper.spawn(EntityType.ENDERMAN, new BlockPos(5, 1, 7));
		employ(helper, worker);

		Vec3 somewhere = wild.position();
		helper.assertTrue(EventHooks.onEnderTeleport(worker, somewhere.x, somewhere.y, somewhere.z)
			.isCanceled(), "a worker's own vanilla teleports should be refused");
		helper.assertTrue(!EventHooks.onEnderTeleport(wild, somewhere.x, somewhere.y, somewhere.z)
			.isCanceled(), "an unemployed enderman should teleport as it always has");
		helper.succeed();
	}

	/**
	 * A worker enderman leaves the scenery alone: no digging its own floor up, and no planting its
	 * cargo in the world while still holding the item.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void employedEndermenLeaveBlocksAlone(GameTestHelper helper) {
		prepareWorkSite(helper);
		ServerLevel level = helper.getLevel();
		level.getGameRules()
			.getRule(GameRules.RULE_MOBGRIEFING)
			.set(true, level.getServer());

		EnderMan worker = helper.spawn(EntityType.ENDERMAN, SPAWN);
		EnderMan wild = helper.spawn(EntityType.ENDERMAN, new BlockPos(5, 1, 7));
		employ(helper, worker);

		helper.assertTrue(!EventHooks.canEntityGrief(level, worker),
			"a worker should not be allowed to move blocks about");
		helper.assertTrue(EventHooks.canEntityGrief(level, wild),
			"an unemployed enderman should still obey the game rule, not the hard hat");
		helper.succeed();
	}

	/**
	 * A hop too long for one teleport must still be a step of a journey.
	 *
	 * <p>The waypoint the worker aims at is a point in mid-air on the line to the target, so the
	 * footing nearest to <em>it</em> is as often behind the worker as ahead of it. The negative case
	 * is the one with teeth: every foothold near this waypoint is further from the target than the
	 * worker already is, so the only correct answer is to refuse the hop. Scoring by nearness to the
	 * waypoint instead — the obvious reading — hands back a landing spot that goes backwards, which
	 * is what a run of these looks like from the ground.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void longHopsOnlyLandCloserToTheTarget(GameTestHelper helper) {
		prepareWorkSite(helper);
		EnderMan enderman = helper.spawn(EntityType.ENDERMAN, SPAWN);
		BlockPos target = helper.absolutePos(TARGET);
		double covered = enderman.blockPosition()
			.distSqr(target);

		BlockPos backwards = TeleportLocomotion.findWaypointSpot(enderman, helper.absolutePos(SOURCE), target);
		helper.assertTrue(backwards == null,
			"should refuse a hop that ends further from the target than it started, but chose " + backwards);

		BlockPos onwards = TeleportLocomotion.findWaypointSpot(enderman, helper.absolutePos(new BlockPos(7, 1, 7)),
			target);
		helper.assertTrue(onwards != null, "should find a foothold on open ground towards the target");
		helper.assertTrue(onwards.distSqr(target) < covered,
			"a hop should close the distance, but " + onwards + " is no nearer the target than the start");
		helper.succeed();
	}

	/** Targets count as posts, so a worker at the far end of a long run is at work, not wandering. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void wanderLimitCountsTargetsAsPosts(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);
		data.resolvePoints(villager);

		helper.assertTrue(!Workers.isOffStation(helper.absolutePos(SPAWN), data, 2),
			"standing at the work site should count as being at work");
		helper.assertTrue(!Workers.isOffStation(helper.absolutePos(TARGET), data, 2),
			"standing at a programmed target should count as being at work, however far from the hire spot");
		helper.assertTrue(Workers.isOffStation(helper.absolutePos(new BlockPos(9, 1, 1)), data, 2),
			"a corner with no target nearby should count as wandering");
		helper.succeed();
	}

	/** A strayed villager gets walked back — unless it is running for its life. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void strayingVillagersAreSentBackUnlessPanicking(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		BlockPos post = helper.absolutePos(SOURCE);
		WalkLocomotion locomotion = new WalkLocomotion();
		Brain<Villager> brain = villager.getBrain();

		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		locomotion.returnTo(villager, post);
		WalkTarget walkTarget = brain.getMemory(MemoryModuleType.WALK_TARGET)
			.orElse(null);
		helper.assertTrue(walkTarget != null, "a strayed villager should be given a walk target");
		helper.assertTrue(walkTarget.getTarget()
			.currentBlockPosition()
			.equals(post), "the walk target should be its post");

		// Now panicking: leashing it home would walk it straight back into whatever it is fleeing.
		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		brain.setMemory(MemoryModuleType.HURT_BY, villager.damageSources()
			.generic());
		locomotion.returnTo(villager, post);
		helper.assertTrue(brain.getMemory(MemoryModuleType.WALK_TARGET)
			.isEmpty(), "a panicking villager must not be dragged back to its post");
		helper.succeed();
	}

	/**
	 * Addressed packages route themselves.
	 *
	 * <p>None of this is our routing code: Create's {@code FunnelPoint.insert} refuses a stack its
	 * filter rejects, a Package Filter tests by address, and the arm transfer algorithm we already
	 * port simulates each output in turn and keeps whichever one accepts. Put a package filter on a
	 * brass funnel and a worker becomes a postman.
	 */
	@GameTest(template = "work_site", timeoutTicks = 300)
	public static void packagesRouteByFunnelAddress(GameTestHelper helper) {
		Villager postman = setUpSortingOffice(helper, "Storage");

		helper.runAfterDelay(FUNNEL_WARMUP, () -> {
			WorkerData data = resolved(postman);
			helper.assertTrue(data.getOutputs()
				.size() == 2, "expected two funnel outputs, got " + data.getOutputs()
					.size());

			// The filters themselves, before any worker logic is involved.
			ItemStack parcel = addressedPackage("Storage");
			helper.assertTrue(accepts(outputAt(data, helper, STORAGE_FUNNEL), parcel),
				"the Storage funnel should accept a package addressed to Storage");
			helper.assertTrue(!accepts(outputAt(data, helper, SMELTING_FUNNEL), parcel),
				"the Smelting funnel should refuse a package addressed to Storage");

			// And now the worker, choosing for itself.
			helper.assertTrue(data.searchForItem(now(helper)) == 0, "should have found the package on the depot");
			helper.assertTrue(data.collectFrom(data.getInputs()
				.get(0), now(helper)), "should have collected the package");
			helper.assertTrue(PackageItem.isPackage(data.getHeld()), "should be carrying a package");

			int chosen = data.searchForDestination(now(helper));
			helper.assertTrue(chosen >= 0, "should have found a funnel willing to take a Storage package");
			WorkerTarget destination = data.getOutputs()
				.get(chosen);
			helper.assertTrue(destination.getPos()
				.equals(helper.absolutePos(STORAGE_FUNNEL)),
				"should route to the Storage funnel, not the Smelting one");

			data.depositTo(destination);
			helper.assertTrue(countPackages(helper, STORAGE_CHEST) == 1,
				"the Storage chest should hold the package");
			helper.assertTrue(countPackages(helper, SMELTING_CHEST) == 0,
				"the Smelting chest should be untouched");
			helper.succeed();
		});
	}

	/**
	 * A package nobody will take is left where it is rather than carried around forever — the same
	 * "only pick up what you can put down" rule the arm follows, applied to addresses.
	 *
	 * <p>The sanity check on the Storage funnel matters: without it this passes just as happily when
	 * the funnels are rejecting <em>everything</em>, which is exactly how it first fooled me.
	 */
	@GameTest(template = "work_site", timeoutTicks = 300)
	public static void unroutablePackagesAreLeftAlone(GameTestHelper helper) {
		Villager postman = setUpSortingOffice(helper, "Nowhere");

		helper.runAfterDelay(FUNNEL_WARMUP, () -> {
			WorkerData data = resolved(postman);

			helper.assertTrue(accepts(outputAt(data, helper, STORAGE_FUNNEL), addressedPackage("Storage")),
				"the rig is broken if the Storage funnel will not even take a Storage package");

			helper.assertTrue(data.searchForItem(now(helper)) == -1,
				"a package no funnel will accept should be left on the depot");
			helper.assertTrue(data.getHeld()
				.isEmpty(), "the worker should not have picked anything up");
			helper.succeed();
		});
	}

	/**
	 * The job site is derived from the programme, and the spread rule is a diameter rather than a
	 * chain of short links — the distinction that decides whether one worker can be handed a run
	 * stretching hundreds of blocks.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void jobSiteAndSpreadAreDerivedFromTheProgramme(GameTestHelper helper) {
		List<BlockPos> run = List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0));

		helper.assertTrue(WorkerProgram.centre(run)
			.equals(new BlockPos(20, 64, 0)), "the job site should be the middle of the run");

		helper.assertTrue(WorkerProgram.firstTooFar(run, new BlockPos(20, 64, 0), 64) == null,
			"a block inside the run should be accepted");

		// 60 from its nearest neighbour, but 100 from the far end: a chain rule would allow this and
		// let one worker be given an arbitrarily long line. The diameter rule refuses it.
		BlockPos blocker = WorkerProgram.firstTooFar(run, new BlockPos(100, 64, 0), 64);
		helper.assertTrue(blocker != null, "a block beyond the far end of the run should be refused");
		helper.assertTrue(blocker.equals(new BlockPos(0, 64, 0)),
			"it should be refused against the far end of the run, not its nearest neighbour");

		helper.succeed();
	}

	/** A hat whose targets are too far apart must be refused by the server, not just the client. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void overSpreadProgrammesAreRejected(GameTestHelper helper) {
		prepareWorkSite(helper);
		WorkerTarget near = target(helper, SOURCE);
		WorkerTarget far = target(helper, TARGET);

		WorkerProgram tight = WorkerProgram.of(List.of(near, far));
		helper.assertTrue(!tight.exceedsSpread(64), "two depots a few blocks apart are well within the spread");
		helper.assertTrue(tight.exceedsSpread(4), "the same pair should breach a spread of 4");
		helper.succeed();
	}

	/**
	 * A worker must never claim a workstation, and that is a property of the profession rather than
	 * of anything the mod does at runtime.
	 *
	 * <p>Clearing to vanilla's unemployed profession instead would look equivalent and is not:
	 * {@code AcquirePoi} takes a workstation's ticket the moment a path to it exists, without ever
	 * arriving, and an unemployed villager's acquirable predicate matches every job site there is. A
	 * worker's walk target is pinned every tick by its job goal, so arriving is precisely what it
	 * would never do, and the ticket would sit taken forever.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void workersNeverClaimAWorkstation(GameTestHelper helper) {
		Holder<PoiType> composter = helper.getLevel()
			.registryAccess()
			.registryOrThrow(Registries.POINT_OF_INTEREST_TYPE)
			.getHolderOrThrow(PoiTypes.FARMER);

		helper.assertTrue(!CWProfessions.WORKER.get()
			.acquirableJobSite()
			.test(composter), "a worker should never go looking for a workstation");
		helper.assertTrue(!CWProfessions.WORKER.get()
			.heldJobSite()
			.test(composter), "a worker should hold no workstation");
		helper.assertTrue(VillagerProfession.NONE.acquirableJobSite()
			.test(composter),
			"the contrast this rests on: an unemployed villager would claim that composter");

		// It does claim exactly one thing, and that is what lets a worker hold a real job site --
		// which is what the whole village-job stash was replaced by.
		Holder<PoiType> station = helper.getLevel()
			.registryAccess()
			.registryOrThrow(Registries.POINT_OF_INTEREST_TYPE)
			.getHolderOrThrow(CWPoiTypes.WORKER_STATION_KEY);
		helper.assertTrue(!CWProfessions.WORKER.get()
			.acquirableJobSite()
			.test(station), "a worker should not go looking for a station of its own accord");
		helper.assertTrue(!CWProfessions.WORKER.get()
			.heldJobSite()
			.test(station), "nor hold one, because vanilla lets only one villager hold any job site");
		helper.succeed();
	}

	/** Endermen have no profession, and hiring one must not go looking for it. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void hiringAnEndermanLeavesProfessionsAlone(GameTestHelper helper) {
		prepareWorkSite(helper);
		EnderMan enderman = helper.spawn(EntityType.ENDERMAN, SPAWN);

		hire(helper, enderman);
		helper.assertTrue(Workers.isEmployed(enderman), "an enderman should still be hireable");

		retire(helper, enderman);
		helper.assertTrue(!Workers.isEmployed(enderman), "and retiring one should still work");
		helper.succeed();
	}

	/**
	 * A worker bitten by a zombie clocks off on the way out: the hat drops where it stood, and the
	 * village job goes back on before anything copies it.
	 *
	 * <p>Conversion is a replacement rather than a death — no death event, no drops event — so
	 * without this the hat would simply cease to exist, and the profession would outlive the
	 * attachment: the zombie villager copies the villager data, curing copies it back, and the
	 * result is a villager holding a profession with no employment behind it that can never take a
	 * village job again.
	 *
	 * <p>The sequence below is the one {@code Zombie.killedEntity} runs, less its difficulty gate and
	 * its coin flip on NORMAL: the conversion's own check first, which is where
	 * {@code LivingConversionEvent.Pre} is fired, and then the replacement, which copies whatever
	 * villager data it finds by then.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void aBittenWorkerClocksOffFirst(GameTestHelper helper) {
		prepareWorkSite(helper);

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager);

		helper.assertTrue(
			EventHooks.canLivingConvert(villager, EntityType.ZOMBIE_VILLAGER, timer -> {}),
			"nothing should be vetoing the conversion");
		helper.assertTrue(!Workers.isEmployed(villager),
			"the worker should have clocked off while it was still a villager");

		ZombieVillager zombie = villager.convertTo(EntityType.ZOMBIE_VILLAGER, false);
		helper.assertTrue(zombie != null, "the villager should have been replaced");

		// This one was hired by hand, with no station holding its hat, so the hat drops. A
		// station-hired worker drops nothing here: its hat never left the block.
		helper.assertItemEntityCountIs(CWItems.HARD_HAT.get(), SPAWN, 3.0, 1);
		helper.succeed();
	}

	/**
	 * An idle villager must hold its station rather than strolling. The idle package's wanderers are
	 * one-shots that only write WALK_TARGET, so occupying that memory is what pins them — and the
	 * check that matters is that it survives a stroll having already written its own destination.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void idleVillagersHoldTheirStation(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		BlockPos station = helper.absolutePos(SPAWN);
		WalkLocomotion locomotion = new WalkLocomotion();
		Brain<Villager> brain = villager.getBrain();

		// Stand in for a stroll that has already fired and chosen somewhere to go.
		brain.setMemory(MemoryModuleType.WALK_TARGET,
			new WalkTarget(helper.absolutePos(new BlockPos(9, 1, 1)), 0.5F, 1));

		locomotion.holdAt(villager, station);

		WalkTarget held = brain.getMemory(MemoryModuleType.WALK_TARGET)
			.orElse(null);
		helper.assertTrue(held != null, "holding station should occupy the walk target");
		helper.assertTrue(held.getTarget()
			.currentBlockPosition()
			.equals(station), "a stroll's destination should have been overwritten by the station");
		helper.assertTrue(brain.getMemory(MemoryModuleType.LOOK_TARGET)
			.isEmpty(), "holding station should leave the worker free to look around");

		// Still no leashing of a villager that is running for its life.
		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		brain.setMemory(MemoryModuleType.HURT_BY, villager.damageSources()
			.generic());
		locomotion.holdAt(villager, station);
		helper.assertTrue(brain.getMemory(MemoryModuleType.WALK_TARGET)
			.isEmpty(), "a panicking villager must not be pinned to its station");
		helper.succeed();
	}

	/**
	 * Idle rounds may only visit blocks the worker was programmed with. That is the whole safety
	 * argument: those are the places it already walks to in order to work, so idling can never strand
	 * it somewhere it could not get back from.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void idleRoundsOnlyVisitProgrammedBlocks(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);
		data.resolvePoints(villager);

		List<BlockPos> stops = Workers.patrolStops(data, now(helper));
		helper.assertTrue(stops.size() == 2, "both assigned depots should be stops, got " + stops.size());
		helper.assertTrue(stops.contains(helper.absolutePos(SOURCE)), "the input depot should be a stop");
		helper.assertTrue(stops.contains(helper.absolutePos(TARGET)), "the output depot should be a stop");

		// Ambling is slower than working travel, and aimed at a stop.
		WalkLocomotion locomotion = new WalkLocomotion();
		locomotion.patrolTo(villager, helper.absolutePos(TARGET));
		WalkTarget stroll = villager.getBrain()
			.getMemory(MemoryModuleType.WALK_TARGET)
			.orElse(null);
		helper.assertTrue(stroll != null, "patrolling should set a walk target");
		helper.assertTrue(stroll.getTarget()
			.currentBlockPosition()
			.equals(helper.absolutePos(TARGET)), "it should head for the stop it was given");
		float amble = WalkLocomotion.amblingSpeed();
		helper.assertTrue(Math.abs(stroll.getSpeedModifier() - amble) < TOLERANCE,
			"rounds should walk at the idle fraction of working speed, got " + stroll.getSpeedModifier());
		helper.succeed();
	}

	/**
	 * Work turning up mid-amble must be walked to at working pace, and the rounds themselves ambled.
	 *
	 * <p>The walk target alone proves nothing here. MoveToTargetSink hands a speed to the navigation
	 * only when it paths, and once running it re-paths only if the destination has moved more than two
	 * blocks -- while a stop on the rounds is very often the exact block the job is at. So what has to
	 * be asserted is the speed the navigation the mob is actually following ends up with.
	 *
	 * <p>The sequence is the real one: the goal writes the walk target, the sink paths for it, and
	 * later ticks ask for the same destination again. Standing in for the sink here, so the trip can
	 * be started at a speed that is neither pace -- then each leg below has to move the navigation off
	 * it rather than passing on a speed this test supplied.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void workFoundOnTheRoundsIsWalkedAtWorkingPace(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);

		// Ground navigation refuses to path for a mob that is not standing on anything yet, so let
		// the freshly spawned villager land before asking it to walk anywhere.
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			data.resolvePoints(villager);

			WalkLocomotion locomotion = new WalkLocomotion();
			float working = WalkLocomotion.workingSpeed();
			float amble = WalkLocomotion.amblingSpeed();
			// Slower than any amble the config allows (a quarter of walking speed at the least), so
			// each leg below has to move the navigation off it.
			float setup = working / 8.0F;

			// The worker's own output depot, which is both a stop on its rounds and a place it works.
			WorkerTarget work = data.getOutputs()
				.get(0);
			BlockPos stop = work.getPos();
			helper.assertTrue(stop.equals(helper.absolutePos(TARGET)), "the output depot should be the stop");

			// Off on the rounds: the goal names the stop, then the sink paths for it -- here at a speed
			// that is neither pace, standing in for the sink.
			locomotion.patrolTo(villager, stop);
			PathNavigation navigation = villager.getNavigation();
			helper.assertTrue(navigation.moveTo(navigation.createPath(stop, 0), setup),
				"the villager should be able to path to one of its own stops");
			navigation.tick();
			helper.assertTrue(Math.abs(travelSpeed(villager) - setup) < TOLERANCE,
				"the setup walk should be under way at its own speed, got " + travelSpeed(villager));

			// Another tick of the rounds, same stop: the sink will not re-path, so the amble has to be
			// put on the navigation directly.
			locomotion.patrolTo(villager, stop);
			navigation.tick();
			helper.assertTrue(Math.abs(travelSpeed(villager) - amble) < TOLERANCE,
				"the rounds should be walked at idle pace, got " + travelSpeed(villager));

			// Work turns up, at that very block.
			locomotion.approach(villager, work.getPos());
			navigation.tick();
			helper.assertTrue(Math.abs(travelSpeed(villager) - working) < TOLERANCE,
				"a worker that finds work mid-amble should walk to it at working pace, got " + travelSpeed(villager));
			helper.succeed();
		});
	}

	/**
	 * The stride into a stop keeps its amble.
	 *
	 * <p>A worker that has arrived is held at where it is standing, which is a different block from
	 * the stop and no more than PATROL_ARRIVED from it -- close enough that the sink will not re-path.
	 * The speed nudge has to leave that trip alone, or every idle round ends with a sprint.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void arrivingOnTheRoundsKeepsTheAmblePace(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			data.resolvePoints(villager);

			WalkLocomotion locomotion = new WalkLocomotion();
			float amble = WalkLocomotion.amblingSpeed();
			BlockPos stop = data.getOutputs()
				.get(0)
				.getPos();

			// Ambling to the stop, as the goal and the sink leave it.
			locomotion.patrolTo(villager, stop);
			PathNavigation navigation = villager.getNavigation();
			helper.assertTrue(navigation.moveTo(navigation.createPath(stop, 0), amble),
				"the villager should be able to path to one of its own stops");
			navigation.tick();

			// Arrived: held at the block it is standing on, one over from the stop.
			locomotion.holdAt(villager, stop.west());
			navigation.tick();
			helper.assertTrue(Math.abs(travelSpeed(villager) - amble) < TOLERANCE,
				"the stride into a stop should keep its amble, got " + travelSpeed(villager));
			helper.succeed();
		});
	}

	/** A worker on its way to a job flees like any other villager. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void workersOnTheirWayToAJobStillPanic(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			data.resolvePoints(villager);

			WalkLocomotion locomotion = new WalkLocomotion();
			WorkerTarget work = data.getOutputs()
				.get(0);
			Brain<Villager> brain = villager.getBrain();
			// A flight already under way, at the faster pace the panic behaviour asks for.
			float fleeing = WalkLocomotion.workingSpeed() * 1.5F;
			BlockPos away = helper.absolutePos(SOURCE_B);

			brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(away, fleeing, 1));
			PathNavigation navigation = villager.getNavigation();
			helper.assertTrue(navigation.moveTo(navigation.createPath(away, 0), fleeing),
				"the villager should be able to flee across the site");
			navigation.tick();
			brain.setMemory(MemoryModuleType.HURT_BY, villager.damageSources()
				.generic());

			locomotion.approach(villager, work.getPos());
			navigation.tick();
			helper.assertTrue(brain.getMemory(MemoryModuleType.WALK_TARGET)
				.map(target -> target.getTarget()
					.currentBlockPosition()
					.equals(away))
				.orElse(false), "a panicking worker must not be walked to its job");
			helper.assertTrue(Math.abs(travelSpeed(villager) - fleeing) < TOLERANCE,
				"a panicking worker should still be fleeing at the brain's pace, got " + travelSpeed(villager));
			helper.succeed();
		});
	}

	/**
	 * A programme longer than the limit is not honoured in full.
	 *
	 * <p>Clicking cannot build one — the selection handler refuses past the limit and the server
	 * refuses the packet — but a hat from a command can carry any number of points, and the cost of a
	 * scan that finds nothing is inputs times outputs times the slots in them. The limit is the only
	 * thing standing between a server and a hat with a thousand depots on it.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void oversizedProgrammesAreCapped(GameTestHelper helper) {
		prepareWorkSite(helper);

		int limit = CWConfig.MAX_TARGETS.get();
		List<WorkerTarget> surplus = new ArrayList<>();
		for (int i = 0; i < limit + 5; i++)
			surplus.add(target(helper, TARGET));

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = Workers.getOrCreate(villager);
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.of(surplus));
		data.resolvePoints(villager);

		int resolved = data.getInputs()
			.size()
			+ data.getOutputs()
				.size();
		helper.assertTrue(resolved == limit,
			"a programme of " + (limit + 5) + " targets should resolve to " + limit + ", got " + resolved);
		helper.succeed();
	}

	/** A programme has to have come from inside the beat it describes. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void programmesMustArriveFromInsideTheirOwnBeat(GameTestHelper helper) {
		prepareWorkSite(helper);
		WorkerProgram program = WorkerProgram.of(List.of(target(helper, SOURCE), target(helper, TARGET)));
		BlockPos site = helper.absolutePos(SPAWN);

		helper.assertTrue(program.within(site, SITE_SIZE), "the work site is inside its own programme");
		helper.assertTrue(!program.within(site.offset(1000, 0, 0), SITE_SIZE),
			"a programme naming blocks a thousand away from the sender is not one a click could have built");
		helper.succeed();
	}

	/**
	 * A target the worker could not get to is left out of the scan and off the idle rounds until the
	 * set-aside runs out.
	 *
	 * <p>Not politeness — it is the only thing bounding what an unreachable target costs. A walk
	 * target that is pinned every tick and never arrived at has MoveToTargetSink asking the navigation
	 * for a fresh path every few ticks, and each of those is an A* over a region as wide as the
	 * villager's follow range. Picking the same target again as soon as the round-robin comes back to
	 * it means that never stops.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void unreachableTargetsAreSetAside(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager);
		data.resolvePoints(villager);

		long now = now(helper);
		helper.assertTrue(data.searchForItem(now) == 0, "the stocked input should be found to begin with");
		helper.assertTrue(Workers.patrolStops(data, now)
			.size() == 2, "both targets should be on the rounds to begin with");

		data.markUnreachable(helper.absolutePos(SOURCE), now + 100);

		helper.assertTrue(data.searchForItem(now) == -1, "a set-aside input must not be chosen");
		List<BlockPos> stops = Workers.patrolStops(data, now);
		helper.assertTrue(stops.size() == 1 && stops.contains(helper.absolutePos(TARGET)),
			"a set-aside target must be off the rounds, got " + stops);

		// Set aside, not written off.
		helper.assertTrue(data.searchForItem(now + 100) == 0, "the input should be back once the set-aside expires");
		helper.succeed();
	}

	/**
	 * Resolving a programme must never pull in a chunk.
	 *
	 * <p>Create's points read their block state with a plain {@code Level.getBlockState}, and that on a
	 * server loads — generating, if nobody has ever been there — whatever chunk the position is in. A
	 * worker resolves on load and rescans once a second, so a target outside the loaded area would have
	 * its chunk dragged in and dropped again for as long as the worker ticks.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void resolvingNeverLoadsAChunk(GameTestHelper helper) {
		prepareWorkSite(helper);
		ServerLevel level = helper.getLevel();

		// A real depot's point, filed under coordinates a long way from anything loaded.
		CompoundTag point = target(helper, SOURCE).serialize();
		BlockPos far = helper.absolutePos(SOURCE)
			.offset(6000, 0, 6000);
		point.put("Pos", NbtUtils.writeBlockPos(far));
		helper.assertTrue(!level.isLoaded(far), "the test needs somewhere that is not loaded to aim at");

		ListTag points = new ListTag();
		points.add(point);
		CompoundTag programTag = new CompoundTag();
		programTag.put(WorkerProgram.POINTS_KEY, points);

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = Workers.getOrCreate(villager);
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), new WorkerProgram(programTag));
		data.resolvePoints(villager);

		helper.assertTrue(!level.isLoaded(far), "resolving a far-off target must not have loaded its chunk");
		helper.assertTrue(!data.hasWork(), "a target nobody can read is not work");
		helper.succeed();
	}

	/**
	 * A point the client cannot read must be readable enough to keep.
	 *
	 * <p>The client rebuilds its working selection out of the hat every time the held stack changes,
	 * and {@code ArmInteractionPoint.deserialize} answers null both for a block that has been broken
	 * and for one in a chunk this side has not loaded. Treating the second as the first is a silent
	 * deletion: walk away from half a hat's beat, click one block, and the points you were standing
	 * away from are gone with no message and nothing to undo. The server cannot catch it, because a
	 * shorter programme is a legal programme — it is how a target is removed at all.
	 *
	 * <p>So the two are told apart by reading the position straight out of the tag and asking whether
	 * that chunk is loaded. <b>The part worth pinning is that the position read without a level is the
	 * same one {@code deserialize} would have used</b>: get the key or the anchor wrong and
	 * {@code peekPos} hands back somewhere else, whose chunk is loaded, and every unreadable point is
	 * dropped again with the bug looking exactly like the fix. {@code HatSelectionHandler} itself is a
	 * client class and cannot run here; these are the two common pieces it is built out of.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void aPointTheClientCannotReadStaysOnTheHat(GameTestHelper helper) {
		prepareWorkSite(helper);
		ServerLevel level = helper.getLevel();

		WorkerTarget here = target(helper, SOURCE);
		// Put on TAKE before anything is serialized, and that is not fussiness. DEPOSIT is the first
		// constant of Create's Mode, so a tag with no Mode in it reads back as DEPOSIT — which is also
		// what a fresh point is. A point left on DEPOSIT therefore cannot tell a mode that was carried
		// through from one that was lost and defaulted back, and the last assertion below passed with
		// the mode stripped out of the kept tag until this line was here.
		here.cycleMode();
		helper.assertTrue(here.getMode() == Mode.TAKE,
			"the test needs a point on the mode that is not the fallback, and cycling left it on "
				+ here.getMode());

		helper.assertTrue(helper.absolutePos(SOURCE)
			.equals(WorkerTarget.peekPos(here.serialize())),
			"a position read without a level should be the one the point was made at, and was "
				+ WorkerTarget.peekPos(here.serialize()));

		// The same point, filed a long way out -- which is what a target in a chunk the client has not
		// loaded looks like from here.
		CompoundTag away = here.serialize();
		BlockPos far = helper.absolutePos(SOURCE)
			.offset(6000, 0, 6000);
		away.put("Pos", NbtUtils.writeBlockPos(far));

		helper.assertTrue(!level.isLoaded(far), "the test needs somewhere that is not loaded to aim at");
		helper.assertTrue(WorkerTarget.deserialize(away, level) == null,
			"precondition: a point in an unloaded chunk does not resolve");
		helper.assertTrue(far.equals(WorkerTarget.peekPos(away)),
			"and its position must still be readable, or there is no way to tell it from a broken block");

		// Rebuilt the way the client rebuilds it: the target it could resolve, plus the tag it could
		// not. Both have to come out the other side.
		WorkerProgram pushed = WorkerProgram.of(List.of(here), List.of(away));
		helper.assertTrue(pushed.size() == 2,
			"a programme rebuilt around an unreadable point should still have it, and has " + pushed.size()
				+ " point(s)");
		helper.assertTrue(pushed.positions()
			.contains(far), "and it should be the same point, at the same place");

		// Held is only half of it: a point out of view has to be able to come *back*, or it is a
		// target nothing can outline, nothing can find and nothing can remove -- and a click on its
		// block reads as a new selection while the old tag is still waiting to be pushed, which puts
		// the same inventory on the hat twice. So a kept tag must still deserialize into the point it
		// came from, mode and all, once its chunk is readable again.
		CompoundTag kept = (CompoundTag) pushed.points()
			.get(1);
		kept.put("Pos", NbtUtils.writeBlockPos(helper.absolutePos(SOURCE)));
		WorkerTarget back = WorkerTarget.deserialize(kept, level);
		helper.assertTrue(back != null, "a point held while it was out of view should resolve once it is back");
		helper.assertTrue(helper.absolutePos(SOURCE)
			.equals(back.getPos()), "and it should be the same block");
		helper.assertTrue(back.getMode() == here.getMode(),
			"and keep its mode -- a held point that came back as an input would quietly reverse the job");
		helper.succeed();
	}

	/**
	 * A Canteen takes food, and refuses everything else.
	 *
	 * <p>The filter is the block. A trough that accepted any item would be a chest with a worse
	 * interface, and the one thing this block has to guarantee is that a hungry villager sent to it
	 * finds something it can eat — a canteen packed with cobblestone by a misaimed funnel is a
	 * starving crew standing next to a full inventory.
	 *
	 * <p>Asked of the {@code IItemHandler} rather than of the block, because that is the way in that
	 * nothing supervises: a player's hand is checked by the block, but a funnel, a chute, a belt and a
	 * worker all go straight through the capability.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aCanteenTakesFoodAndNothingElse(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());
		if (!(helper.getBlockEntity(SOURCE) instanceof CanteenBlockEntity canteen))
			throw new GameTestAssertException("the canteen should have a block entity");

		IItemHandler stock = canteen.stock();
		ItemStack bread = new ItemStack(Items.BREAD, 8);
		helper.assertTrue(ItemHandlerHelper.insertItem(stock, bread.copy(), false)
			.isEmpty(), "a canteen should take bread");

		ItemStack rubble = new ItemStack(Items.COBBLESTONE, 8);
		ItemStack refused = ItemHandlerHelper.insertItem(stock, rubble.copy(), false);
		helper.assertTrue(refused.getCount() == rubble.getCount(),
			"a canteen should refuse everything that is not food, and it kept "
				+ (rubble.getCount() - refused.getCount()) + " cobblestone");

		// Cooked beef is food to a *player* and not to a villager, and this is the assertion that
		// matters: a villager eats bread, potatoes, carrots and beetroot, and nothing else. The filter
		// asked for a FOOD component at first -- the obvious reading -- and a canteen of cooked beef
		// then read as stocked on the comparator and through the goggles while feeding nobody. The one
		// promise this block makes is that a hungry villager sent to it finds something it can eat.
		helper.assertTrue(!ItemHandlerHelper.insertItem(stock, new ItemStack(Items.COOKED_BEEF, 8), false)
			.isEmpty(), "a canteen should refuse food a villager will not eat");
		for (Item edible : Villager.FOOD_POINTS.keySet())
			helper.assertTrue(CanteenBlockEntity.isFood(new ItemStack(edible)),
				"a canteen should take everything a villager eats, and refused " + edible);
		helper.succeed();
	}

	/**
	 * A crew that is not a crew is refused, rather than thrown out of the middle of the decode.
	 *
	 * <p>{@code ByteBufCodecs.idMapper} hands its mapper whatever VarInt it read and checks nothing,
	 * so indexing {@code Shift.VALUES} straight turned a damaged or mismatched stream into an
	 * {@code ArrayIndexOutOfBoundsException} raised inside netty's pipeline — where the right answer
	 * is a {@code DecoderException} and a clean disconnect. This is clientbound, so reaching it needs
	 * a bad server rather than a bad player; the failure mode is still the wrong one.
	 *
	 * <p>Encoded and then corrupted rather than hand-written: the shift is the last field and
	 * {@code Shift.DAY} is a single zero byte, so overwriting it is the whole of the mutation and the
	 * rest of the packet stays exactly as the codec would have produced it.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aWorkerStatePacketRefusesAShiftThatIsNotOne(GameTestHelper helper) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel()
			.registryAccess());
		WorkerStatePacket.STREAM_CODEC.encode(buffer,
			new WorkerStatePacket(1, ItemStack.EMPTY, ItemStack.EMPTY, Shift.DAY));

		int end = buffer.writerIndex() - 1;
		helper.assertTrue(buffer.getByte(end) == 0, "precondition: the day shift is one zero byte");
		buffer.setByte(end, 99);

		try {
			WorkerStatePacket.STREAM_CODEC.decode(buffer);
			throw new GameTestAssertException("a shift ordinal of 99 should not decode");
		} catch (DecoderException expected) {
			helper.succeed();
		} catch (IndexOutOfBoundsException raw) {
			throw new GameTestAssertException(
				"a bad ordinal should be a DecoderException, and was " + raw);
		}
	}

	/**
	 * The welcome ration is a gift, once per villager — not an income from the rack.
	 *
	 * <p><b>This shipped as an exploit.</b> The guard was {@code !isEmployed()}, and every path that
	 * ends a job runs {@code dismiss()}, which clears the hat — so a villager was "not employed" again
	 * the instant it was let go, and taking a hat out of a Station and putting it straight back
	 * <em>reassigned</em> a full tank. Two shifts of food for about twenty-five ticks of clicking,
	 * which is cheaper than any wheat farm and makes {@code requireFood} a formality.
	 *
	 * <p>The rule that replaced it: {@code fuel} survives a dismissal and the ration is spent on the
	 * first hire only. A worker that was fed yesterday is not hungrier for having been un-hatted, and
	 * one that starved on the job still has an empty tank when it is taken back on — which is the
	 * answer a player can act on, because the fix for it is food.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theWelcomeRationIsSpentOnce(GameTestHelper helper) {
		layFloor(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = Workers.getOrCreate(villager);

		data.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.EMPTY);
		int rations = data.fuel();
		helper.assertTrue(rations > 0, "a first hire should turn up having eaten");

		// Run it dry with nothing in its pockets, the way a worker with no Canteen does.
		for (int tick = 0; tick < rations + 4; tick++)
			data.chargeForWork(villager);
		helper.assertTrue(data.isHungry(villager), "a worker out of food and out of fuel is hungry");

		// The exploit, exactly: let it go and take it straight back on.
		data.dismiss();
		helper.assertTrue(data.fuel() <= 0,
			"a dismissal should leave the tank where it was and left " + data.fuel());

		data.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.EMPTY);
		helper.assertTrue(data.fuel() <= 0,
			"re-hiring a starving villager must not refill it, and left " + data.fuel());
		helper.assertTrue(data.isHungry(villager),
			"so it is still hungry, and still wants feeding rather than re-racking");
		helper.succeed();
	}

	/**
	 * A worker eats what it hauls for, out of its own pocket.
	 *
	 * <p>Food has to be <b>items</b>, and that is not a preference. {@code Villager.foodLevel} is
	 * private, nothing public reads it, and the only public thing that moves it is
	 * {@code eatAndDigestFood()}, which fills it and spends twelve in one go for breeding — so there
	 * is no way to ask a villager how hungry it is or to make it slightly hungrier. The gauge here is
	 * this mod's own, and the thing that refills it is a loaf leaving the villager's inventory, which
	 * is the half a player can see and count.
	 *
	 * <p><b>Charged by time on the clock, and the version this replaced was backwards.</b> Per-delivery
	 * pricing counts <em>transactions</em>, and a compact line makes more of them per unit time — so a
	 * worker on a four-block beat ate three times what one on a sixteen-block beat did while walking
	 * less far. The food bill rewarded spreading your depots out, which is the opposite of what Create
	 * asks you to build. Time is neutral to layout, and it makes the bill a function of headcount,
	 * which is the thing a player actually decides.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aWorkerEatsForItsTimeOnTheClock(GameTestHelper helper) {
		layFloor(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = Workers.getOrCreate(villager);
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.EMPTY);

		int rations = data.fuel();
		helper.assertTrue(rations > 0,
			"a new hire should turn up having eaten -- starting empty makes it hungry within a tick of "
				+ "being hired, which is a punishment for hiring rather than a supply line to build");

		helper.assertTrue(!data.chargeForWork(villager), "a fed worker is not hungry");
		helper.assertTrue(data.fuel() == rations - 1, "and a tick on the clock costs it one");

		// Run it dry with nothing in its pockets.
		for (int tick = 0; tick < rations + 4; tick++)
			data.chargeForWork(villager);
		helper.assertTrue(data.isHungry(villager), "a worker out of food and out of fuel is hungry");

		// A loaf is worth four points, and one point is worth ticksPerFoodPoint ticks of work.
		villager.getInventory()
			.addItem(new ItemStack(Items.BREAD, 1));
		helper.assertTrue(!data.chargeForWork(villager), "with bread in its pocket it should eat rather than starve");
		helper.assertTrue(villager.getInventory()
			.countItem(Items.BREAD) == 0, "and the loaf should be gone -- eaten, not merely counted");
		helper.assertTrue(data.fuel() == 4 * CWConfig.TICKS_PER_FOOD_POINT.get() - 1,
			"a loaf should be worth four points of working time, and left " + data.fuel());
		helper.assertTrue(!data.isHungry(villager), "and it should not be hungry any more");
		helper.succeed();
	}

	/**
	 * And the appetite is actually wired into the working day.
	 *
	 * <p>The test above drives {@code chargeForWork} by hand, which says what a charge <em>does</em>
	 * and nothing about whether anything calls it. Deleting the call from {@code WorkerJobGoal.tick}
	 * left the whole suite green: workers would never eat, never go hungry, and the entire feature
	 * would be dead with every one of its unit tests passing.
	 *
	 * <p>So this one hires a worker, lets it work, and watches the gauge fall. It also pins the other
	 * half of the rule — that the charge is on the <b>working</b> branch — because a worker charged
	 * during leisure or sleep would eat around the clock and no unit test would see that either.
	 */
	@GameTest(template = "work_site", timeoutTicks = 300)
	public static void timeOnTheClockIsWhatActuallyCharges(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager);

		int[] before = new int[1];
		helper.startSequence()
			.thenExecute(() -> before[0] = resolved(villager).fuel())
			.thenIdle(100)
			.thenExecute(() -> {
				int spent = before[0] - resolved(villager).fuel();
				helper.assertTrue(spent > 0,
					"a worker on the clock should be eating into its rations, and spent nothing in a "
						+ "hundred ticks -- which is what a charge nothing calls looks like");
				// Loose on purpose: the goal does not run on every one of those ticks (canUse has to
				// resolve first), so this is "most of them", not an exact count.
				helper.assertTrue(spent >= 50,
					"and it should be charged about once a tick, not occasionally; spent " + spent + " in 100");
			})
			.thenSucceed();
	}

	/**
	 * A Worker has something to sell, and none of it is a Hard Hat.
	 *
	 * <p>The rule the trade list is built on is <b>nothing that skips a gate</b>: shafts, cogs and
	 * andesite alloy are available in the first hour and gated behind nothing, and making them by hand
	 * in quantity is exactly the tedium Create wants you to automate past. What a labourer has to sell
	 * is the product of labour.
	 *
	 * <p><b>The Hard Hat is the assertion that matters</b>, and it is about this mod holding itself to
	 * its own rule. The hat is this mod's gate: a player who can buy one has bought past the item the
	 * mod is about. A list is easy to add to later and this is what will be there when somebody does.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aWorkerHasTradesAndNoneOfThemIsAHardHat(GameTestHelper helper) {
		layFloor(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		villager.setVillagerData(villager.getVillagerData()
			.setProfession(CWProfessions.WORKER.get()));

		MerchantOffers offers = villager.getOffers();
		helper.assertTrue(!offers.isEmpty(),
			"a Worker should have trades -- NeoForge fires VillagerTradesEvent for every registered "
				+ "profession, so an empty list here means nothing is listening for ours");

		for (MerchantOffer offer : offers) {
			helper.assertTrue(!offer.getResult()
				.is(CWItems.HARD_HAT.get()), "a Worker must never sell a Hard Hat: that is this mod's own gate");
			helper.assertTrue(!offer.getCostA()
				.is(CWItems.HARD_HAT.get()), "and must never ask for one either");
		}
		helper.succeed();
	}

	/**
	 * Every trade this mod ships names an item that exists, and none of them is a Hard Hat.
	 *
	 * <p>Item ids are <b>strings</b>, deliberately — {@code WorkerTrades.listing} skips one it cannot
	 * resolve so that an id Create moves between versions costs a shop line rather than a world. The
	 * cost of that leniency is that a typo is invisible: the compiler sees a string and the trade
	 * merely never appears, with one line in a log nobody reads. So every id is resolved here.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void everyShippedTradeNamesRealItems(GameTestHelper helper) {
		List<WorkerTrades.Trade> shipped = WorkerTrades.table();
		helper.assertTrue(!shipped.isEmpty(), "the shipped trade list should not be empty");

		for (WorkerTrades.Trade trade : shipped) {
			for (String id : new String[] { trade.cost(), trade.result() }) {
				helper.assertTrue(WorkerTrades.item(id) != null,
					"a shipped trade names an item that does not exist, so it would be dropped with a "
						+ "log warning: " + id);
				helper.assertTrue(!id.equals("createworkers:hard_hat"),
					"nothing this mod ships may trade a Hard Hat -- it is our own gate");
			}
			helper.assertTrue(trade.level() >= 1 && trade.level() <= 5,
				"a trade level outside 1-5 has nowhere to go: " + trade.goods());
			helper.assertTrue(trade.isSale() != trade.isPurchase(),
				"a trade is one direction or the other, never both or neither: " + trade.goods());
		}
		helper.succeed();
	}

	/**
	 * Nothing the Worker sells can be crafted into something it buys for more than it cost.
	 *
	 * <p><b>This is the rule that makes the buy side safe at all.</b> Crafting runs one way, so a
	 * table that buys upstream and sells downstream cannot be looped; one that sells a material and
	 * buys what the material becomes is an emerald printer, and the multiplication is not subtle.
	 * Create's saw cuts <em>one</em> Andesite Alloy into six Shafts, a Shaft and a plank make a
	 * Cogwheel, and a Zinc Ingot mixes into nine alloy — so a zinc ingot reaches fifty-four cogwheels.
	 * Selling zinc was in the shipped table until this test was written, at a price that would have
	 * returned four emeralds on every one spent, for ever, with no factory behind it.
	 *
	 * <p>It walks <b>the server's own recipe manager</b> rather than a list kept here, so it stays
	 * true as Create changes its recipes and covers any other mod's recipes too. Create's processing
	 * recipes are read through {@code ProcessingRecipe} for their <em>secondary</em> outputs, the
	 * bonus nugget a crushing recipe rolls; their primary output already arrives through
	 * {@code Recipe.getResultItem}, so <b>nothing in the table as it stands depends on that branch</b>
	 * — mutation-checked by removing it, which still catches zinc at the same factor. It is here
	 * because a secondary output is a real way to multiply an item and the next edit to this table
	 * should not have to know that.
	 *
	 * <p>Every approximation here is deliberately generous to the loop: output chances are ignored
	 * (a rollable result counts at its full stack), an ingredient slot counts for every item it
	 * accepts, and the search takes the best multiplication it can find. A guard that errs towards
	 * finding loops is the right kind of wrong.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void theTradeTableHasNoEmeraldLoop(GameTestHelper helper) {
		Map<Item, List<Craft>> byIngredient = recipeGraph(helper);
		helper.assertTrue(byIngredient.size() > 100,
			"the recipe graph looks empty (" + byIngredient.size() + " ingredients), so this test "
				+ "would pass whatever the table said");

		Map<Item, Double> bid = new HashMap<>();
		Map<Item, Double> ask = new HashMap<>();
		for (WorkerTrades.Trade trade : WorkerTrades.table()) {
			Item goods = WorkerTrades.item(trade.goods());
			if (goods == null)
				continue;
			if (trade.isPurchase())
				bid.merge(goods, trade.emeraldsEach(), Math::max);
			else
				ask.merge(goods, trade.emeraldsEach(), Math::min);
		}
		for (Item both : ask.keySet())
			helper.assertTrue(!bid.containsKey(both),
				"the Worker both sells and buys " + id(both) + ", which is a loop on its own");

		for (Map.Entry<Item, Double> sold : ask.entrySet()) {
			for (Map.Entry<Item, Double> reached : downstream(helper, sold.getKey(), byIngredient)
				.entrySet()) {
				Double pays = bid.get(reached.getKey());
				if (pays == null)
					continue;
				double back = pays * reached.getValue();
				helper.assertTrue(back < sold.getValue(),
					"emerald loop: " + id(sold.getKey()) + " sells at " + sold.getValue()
						+ "e and crafts x" + reached.getValue() + " into " + id(reached.getKey())
						+ ", which the Worker buys back for " + back + "e");
			}
		}
		helper.succeed();
	}

	/** One recipe, flattened to what it consumes and the one output being considered. */
	private record Craft(Map<Item, Integer> consumes, Item produces, int yield) {}

	/** How far a craft chain is followed. Deep enough for any real chain, shallow enough to end. */
	private static final int CRAFT_DEPTH = 6;

	/** Everything craftable from {@code start}, with the best per-unit multiplication found. */
	private static Map<Item, Double> downstream(GameTestHelper helper, Item start,
		Map<Item, List<Craft>> byIngredient) {
		Map<Item, Double> best = new HashMap<>();
		best.put(start, 1.0);
		Deque<Object[]> frontier = new ArrayDeque<>();
		frontier.push(new Object[] { start, 1.0, 0 });
		int expansions = 0;
		while (!frontier.isEmpty()) {
			Object[] at = frontier.pop();
			Item item = (Item) at[0];
			double factor = (Double) at[1];
			int depth = (Integer) at[2];
			if (depth >= CRAFT_DEPTH)
				continue;
			for (Craft craft : byIngredient.getOrDefault(item, List.of())) {
				// A guard that quietly gives up is cover, so say so instead.
				helper.assertTrue(++expansions < 200_000,
					"the craft walk from " + id(start) + " did not settle, so this test cannot vouch "
						+ "for the table");
				double next = factor * craft.yield() / craft.consumes()
					.get(item);
				if (next > best.getOrDefault(craft.produces(), 0.0) * 1.000001) {
					best.put(craft.produces(), next);
					frontier.push(new Object[] { craft.produces(), next, depth + 1 });
				}
			}
		}
		best.remove(start);
		return best;
	}

	/** Every recipe on the server, indexed by the items that go into it. */
	private static Map<Item, List<Craft>> recipeGraph(GameTestHelper helper) {
		HolderLookup.Provider registries = helper.getLevel()
			.registryAccess();
		Map<Item, List<Craft>> byIngredient = new HashMap<>();
		for (RecipeHolder<?> held : helper.getLevel()
			.getServer()
			.getRecipeManager()
			.getRecipes()) {
			Recipe<?> recipe = held.value();

			Map<Item, Integer> consumes = new HashMap<>();
			try {
				for (Ingredient slot : recipe.getIngredients())
					for (ItemStack accepted : slot.getItems())
						consumes.merge(accepted.getItem(), 1, Integer::sum);
			} catch (RuntimeException awkward) {
				continue;   // a recipe that will not describe its inputs cannot be walked
			}
			if (consumes.isEmpty())
				continue;

			List<ItemStack> outputs = new ArrayList<>();
			try {
				// Create's processing recipes carry several outputs; Recipe.getResultItem reports one.
				if (recipe instanceof ProcessingRecipe<?, ?> processing)
					outputs.addAll(processing.getRollableResultsAsItemStacks());
				else
					outputs.add(recipe.getResultItem(registries));
			} catch (RuntimeException awkward) {
				continue;
			}

			for (ItemStack out : outputs) {
				if (out == null || out.isEmpty())
					continue;
				for (Map.Entry<Item, Integer> in : consumes.entrySet())
					byIngredient.computeIfAbsent(in.getKey(), any -> new ArrayList<>())
						.add(new Craft(consumes, out.getItem(), out.getCount()));
			}
		}
		return byIngredient;
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item)
			.toString();
	}

	/**
	 * Trading locks a villager to its profession, and a Station hires such a worker back.
	 *
	 * <p>This is the interaction the trade design did not anticipate, and {@code Workers.dismiss}'s own
	 * comment had already named the precondition it breaks: retirement works "because nothing raises
	 * its trade level any more". {@code ResetProfession} wants experience of zero <em>and</em> trade
	 * level one, so a Worker a player has bought a stack of shafts from is one vanilla will never hand
	 * back — and without somewhere for it to go, that is a villager holding a profession whose
	 * job-site predicates match nothing, unemployable by us and by the village, forever.
	 *
	 * <p>Forcing the reset would mean stripping levels a player earned. Leaning on the lock is better
	 * and reads better: a career labourer. So {@code allowReset} hands back only a worker that never
	 * really traded, and {@code isCareerWorker} is the one definition both sides use.
	 *
	 * <p>It has to be <b>narrower than "wears the Worker profession"</b>, which is the part that bit: a
	 * worker just let go still wears it for the tick or two before {@code ResetProfession} clears it,
	 * so a Station that hired anything wearing it re-hired the villager it had that moment released.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aWorkerThatHasTradedIsACareerWorker(GameTestHelper helper) {
		layFloor(helper);
		Villager fresh = helper.spawn(EntityType.VILLAGER, SPAWN);
		fresh.setVillagerData(fresh.getVillagerData()
			.setProfession(CWProfessions.WORKER.get()));
		Workers.protectFromReset(fresh);

		helper.assertTrue(!Workers.isCareerWorker(fresh),
			"a worker holding only the protective point of experience has not traded, and vanilla will "
				+ "take its profession back -- treating it as a career worker is what re-hired a villager "
				+ "the station had just let go");
		Workers.allowReset(fresh);
		helper.assertTrue(fresh.getVillagerXp() == 0, "so retirement should hand it back to vanilla");

		Villager traded = helper.spawn(EntityType.VILLAGER, SPAWN);
		traded.setVillagerData(traded.getVillagerData()
			.setProfession(CWProfessions.WORKER.get())
			.setLevel(2));
		traded.setVillagerXp(20);

		helper.assertTrue(Workers.isCareerWorker(traded), "a worker that has actually traded is a career worker");
		Workers.allowReset(traded);
		helper.assertTrue(traded.getVillagerXp() == 20,
			"and retirement must not strip the experience a player earned -- vanilla has already "
				+ "refused to reset it, and taking the levels away to force the issue is not ours to do");
		helper.succeed();
	}

	/**
	 * A Canteen hands food out to whoever is near it. Nobody walks to a Canteen.
	 *
	 * <p><b>Pushing is what makes this block work at all.</b> Vanilla's route for a villager to pick
	 * food up needs {@code WALK_TARGET} absent, and a working Worker has it pinned every tick — which
	 * is why {@code shift-rotation.md} treats food and leisure as one feature. An explicit trip would
	 * mean unpinning a Worker mid-shift and driving the walk by hand: a paced point-of-interest hunt,
	 * another stall clock, and a Worker off its post long enough for its own Station to strike it off
	 * as an absentee. None of that exists if the food comes to the worker.
	 *
	 * <p>So a Canteen is something you put where the people are. The villager here is fed without
	 * moving, and one out of range is not — which is the whole of the mechanic, and makes feeding a
	 * factory a question of where the troughs go.
	 */
	@GameTest(template = "work_site", timeoutTicks = 300)
	public static void aCanteenFeedsWhoeverIsNearItWithoutAnybodyWalking(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());
		if (!(helper.getBlockEntity(SOURCE) instanceof CanteenBlockEntity canteen))
			throw new GameTestAssertException("the canteen should have a block entity");
		// Carrots, not bread, and that is what gives the last assertion any teeth. A villager stops at
		// twelve points, which is three loaves but twelve carrots -- so on bread "filled in one pass"
		// and "filled in three" both end with a full villager and only differ in how long they took,
		// which a waiting assertion cannot see. On carrots the difference is 1 against 12, in one tick.
		ItemHandlerHelper.insertItem(canteen.stock(), new ItemStack(Items.CARROT, 32), false);

		Villager beside = helper.spawn(EntityType.VILLAGER, SOURCE.offset(1, 0, 0));
		// Held still on purpose: what is being asserted is that nobody had to walk anywhere, so a
		// villager that strolled into range would prove nothing.
		beside.setNoAi(true);
		beside.setOnGround(true);
		helper.assertTrue(beside.getInventory()
			.isEmpty(), "precondition: the villager starts with nothing to eat");
		helper.assertTrue(beside.wantsMoreFood(), "precondition: and vanilla agrees it is short of food");

		helper.startSequence()
			.thenWaitUntil(() -> {
				helper.assertTrue(!beside.getInventory()
					.isEmpty(), "a canteen should hand food to a villager standing next to it");
				helper.assertTrue(CanteenBlockEntity.foodPoints(beside.getInventory()
					.getItem(0)) > 0, "and what it hands over should be food");
			})
			// Checked on the tick *after* the food first appears, which is still inside the same serving
			// -- the next one is a hundred ticks away. So this sees exactly what one pass handed over.
			//
			// **Filled in one serving, not one item at a time.** The case it protects is the Worker that
			// merely walks past a Canteen on its way to and from work: crossing a sixteen-block reach is
			// about three servings, which at one item apiece was a quarter of a top-up in carrots -- so a
			// crew clipping the edge starved slowly while walking past a full trough twice a day.
			// Vanilla caps the villager at twelve points either way, so nothing is spent faster; it just
			// arrives while they are still in range.
			.thenExecute(() -> helper.assertTrue(carriedPoints(beside) >= CanteenBlockEntity.FILL_POINTS,
				"one serving should fill a villager rather than top it up by one item, and this one is "
					+ "still short after a whole pass -- a Worker walking past is only in range for about "
					+ "three of them"))
			.thenSucceed();
	}

	/**
	 * What a Canteen reports about its own stock is what is in its slots, and it is per slot.
	 *
	 * <p>The top of the block draws one heap per slot and the gauge on its flanks one row per slot,
	 * both from {@link CanteenBlockEntity#servings()} — so the thing worth pinning on a server, where
	 * no renderer exists, is that the report itself is right. The renderer is arithmetic over this;
	 * if this is wrong it is wrong nine times.
	 *
	 * <p><b>A slot's fraction is what decides how much of it is drawn</b>, which is the half that a
	 * count alone cannot carry: a rack of nine slots holding one carrot each has the same number of
	 * occupied slots as a rack of nine full ones, and drawing them alike would be the block lying
	 * about how much feeding is left in it.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aCanteenReportsWhatIsInEachSlot(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());
		if (!(helper.getBlockEntity(SOURCE) instanceof CanteenBlockEntity canteen))
			throw new GameTestAssertException("the canteen should have a block entity");

		canteen.stock()
			.setStackInSlot(0, new ItemStack(Items.BREAD, 64));
		canteen.stock()
			.setStackInSlot(1, new ItemStack(Items.CARROT, 16));

		List<CanteenBlockEntity.Serving> servings = canteen.servings();
		helper.assertTrue(servings.size() == CanteenBlockEntity.SLOTS,
			"a canteen should report every slot, full or not, so a slot always owns its own place");

		helper.assertTrue(servings.get(0)
			.food() == CanteenBlockEntity.DRAWN_FOODS.indexOf(Items.BREAD), "slot 0 is holding bread");
		helper.assertTrue(servings.get(0)
			.fraction() == 1.0F, "and a full stack is a full slot");

		helper.assertTrue(servings.get(1)
			.food() == CanteenBlockEntity.DRAWN_FOODS.indexOf(Items.CARROT), "slot 1 is holding carrots");
		helper.assertTrue(Math.abs(servings.get(1)
			.fraction() - 0.25F) < 1.0e-4F,
			"and a quarter stack is a quarter of a slot -- sixteen carrots must not draw like "
				+ "sixty-four, or the block says it is stocked when it is nearly out");

		helper.assertTrue(servings.get(2)
			.isEmpty(), "an untouched slot is empty and draws nothing");
		for (CanteenBlockEntity.Serving serving : servings)
			helper.assertTrue(serving.isEmpty()
				|| (serving.food() >= 0 && serving.food() < CanteenBlockEntity.DRAWN_FOODS.size()),
				"every reported food has to index DRAWN_FOODS, which is what the sheet is drawn in");
		helper.succeed();
	}

	/**
	 * A Canteen stops short of the point at which villagers start breeding.
	 *
	 * <p><b>This is the block's most expensive failure mode, and it cost food rather than performance.</b>
	 * {@code Villager.canBreed} is {@code foodLevel + countFoodPointsInInventory() >= 12}, so a trough
	 * that topped everything in range up to vanilla's own ceiling held the breeding gate open for every
	 * villager near it, permanently. What came through was not mainly children:
	 * {@code VillagerMakeLove.tick} spends twelve points on <em>each</em> parent <b>before</b>
	 * {@code tryToGiveBirth} goes looking for a vacant bed, so once the beds are claimed the
	 * twenty-four points are burnt for nothing — and {@code setAge}, which is what would put the pair
	 * on a cooldown, lives in {@code breed} and never runs. The pair restarts immediately. Against a
	 * Worker's one point per {@code ticksPerFoodPoint} of actual work, that is food leaving a base
	 * about two orders of magnitude faster than the mechanic this block exists to supply, with hearts
	 * over a factory as the only symptom.
	 *
	 * <p>Feeding villagers to twelve is vanilla's idea of a <em>player's</em> deliberate act. A Canteen
	 * does the part that keeps a crew working and leaves that last stretch alone.
	 *
	 * <p>Asserted in points rather than through {@code canBreed} on purpose, because {@code foodLevel}
	 * is private and starts at zero: a test that only asked {@code canBreed} would pass on a villager
	 * filled to eleven and say nothing about the three points of digest residue that make eleven
	 * enough. The margin is what is being pinned, so the margin is what is measured.
	 */
	@GameTest(template = "work_site", timeoutTicks = 500)
	public static void aCanteenStopsShortOfTheBreedingThreshold(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());
		if (!(helper.getBlockEntity(SOURCE) instanceof CanteenBlockEntity canteen))
			throw new GameTestAssertException("the canteen should have a block entity");
		// Bread, because four-point food is what can overshoot a cap that merely stops rather than
		// refuses: seven points plus a loaf is eleven, and eleven plus the residue is twelve.
		ItemHandlerHelper.insertItem(canteen.stock(), new ItemStack(Items.BREAD, 64), false);

		Villager beside = helper.spawn(EntityType.VILLAGER, SOURCE.offset(1, 0, 0));
		beside.setNoAi(true);
		beside.setOnGround(true);

		// **The one that exercises the refusal rather than the stop**, and it has to carry odd change
		// to do it. On bread alone a villager climbs 0, 4, 8 and halts on the cap exactly, so a serve
		// loop that merely stopped at eight would pass. Three carrots put it on seven, where the next
		// loaf is the difference between a villager holding seven and one holding eleven -- and eleven
		// plus the digest residue is twelve, which breeds.
		Villager oddChange = helper.spawn(EntityType.VILLAGER, SOURCE.offset(-1, 0, 0));
		oddChange.setNoAi(true);
		oddChange.setOnGround(true);
		oddChange.getInventory()
			.addItem(new ItemStack(Items.CARROT, 3));

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(carriedPoints(beside) > 0,
				"a canteen should hand food to a villager standing next to it"))
			// Two whole servings further on, so this is the steady state and not a pass caught halfway.
			.thenIdle(220)
			.thenExecute(() -> {
				for (Villager fed : List.of(beside, oddChange)) {
					int points = carriedPoints(fed);
					helper.assertTrue(points <= CanteenBlockEntity.FILL_POINTS,
						"a canteen must never fill a villager past " + CanteenBlockEntity.FILL_POINTS
							+ " points, and this one is holding " + points);
					helper.assertTrue(points + 3 < 12,
						"and it has to stay clear of the breeding threshold even with the three points "
							+ "digestFood can leave behind, which nothing here can read; holding " + points);
					helper.assertTrue(!fed.canBreed(),
						"so vanilla should agree the villager cannot breed on what a canteen alone gave it");
				}
			})
			.thenSucceed();
	}

	/** The food points a villager is carrying, which is the half of {@code canBreed} we can see. */
	private static int carriedPoints(Villager villager) {
		int points = 0;
		for (int slot = 0; slot < villager.getInventory()
			.getContainerSize(); slot++) {
			ItemStack held = villager.getInventory()
				.getItem(slot);
			points += CanteenBlockEntity.foodPoints(held) * held.getCount();
		}
		return points;
	}

	/**
	 * A hungry worker is slow, and never stopped.
	 *
	 * <p>A line that halts is a line whose owner has to go and find out why, and food must not be the
	 * one mechanic here that fails invisibly — but a hard stop turns a supply hiccup into an outage,
	 * which over-corrects in the other direction. So {@code hungryPace} is a <b>floor</b>: it is as
	 * slow as hunger ever makes anybody, and a base that runs out of bread limps rather than dies.
	 *
	 * <p>Asserted as a comparison against the fed pace rather than against a number, so the test says
	 * what the rule is rather than what the config currently holds.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aHungryWorkerIsSlowedAndNeverStopped(GameTestHelper helper) {
		layFloor(helper);
		Villager fed = helper.spawn(EntityType.VILLAGER, SPAWN);
		Villager starving = helper.spawn(EntityType.VILLAGER, SPAWN);

		Workers.getOrCreate(fed)
			.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.EMPTY);
		WorkerData hungry = Workers.getOrCreate(starving);
		hungry.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.EMPTY);
		while (!hungry.isHungry(starving))
			hungry.chargeForWork(starving);

		float fedPace = WalkLocomotion.workingSpeed(fed);
		float hungryPace = WalkLocomotion.workingSpeed(starving);
		helper.assertTrue(hungryPace < fedPace,
			"a hungry worker should walk slower than a fed one (" + hungryPace + " against " + fedPace + ")");
		helper.assertTrue(hungryPace > 0,
			"and it should still be walking -- hunger is a limp, not a halt, or a supply hiccup becomes "
				+ "an outage nobody can see the cause of");
		helper.assertTrue(fedPace == WalkLocomotion.workingSpeed(),
			"a fed worker should be at the ordinary pace, or this test is measuring the wrong thing");
		helper.succeed();
	}

	/**
	 * To a machine, a Canteen is an ordinary inventory — fillable <em>and</em> drainable.
	 *
	 * <p>This is a decision that has now been made twice, in opposite directions, which is why it is
	 * pinned. The capability was briefly insert-only, on the reasoning that the only thing which should
	 * ever empty a canteen is a villager eating, and that a belt keeping a trough empty is a trough
	 * that never feeds anybody.
	 *
	 * <p>It went back because <b>no Create block behaves that way</b>. Its deposit-only idea exists
	 * only for arm interaction points, and only on blocks that <em>consume</em> what they are given —
	 * a Blaze Burner exposes no item handler at all, while Basins, Depots and Vaults all hand out
	 * ordinary extractable ones. And a player who filled a canteen with the wrong food would have had
	 * no way to change it but breaking the block. A funnel draining a trough is a build the player
	 * made, visible on the comparator and through the goggles, and no different from a funnel draining
	 * a chest they wanted full; protecting them from it is the same instinct that briefly made this
	 * block an arm interaction point.
	 *
	 * <p>What stays is the filter: an ordinary inventory, not an ordinary inventory that holds
	 * anything.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aCanteenIsAnOrdinaryInventoryToMachines(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());

		IItemHandler machines = handlerAt(helper, SOURCE);
		helper.assertTrue(machines != null, "a canteen should expose an item handler, or nothing can fill it");
		helper.assertTrue(ItemHandlerHelper.insertItem(machines, new ItemStack(Items.BREAD, 6), false)
			.isEmpty(), "a machine should be able to fill a canteen");

		ItemStack back = ItemStack.EMPTY;
		for (int slot = 0; slot < machines.getSlots() && back.isEmpty(); slot++)
			back = machines.extractItem(slot, 64, false);
		helper.assertTrue(back.is(Items.BREAD) && back.getCount() == 6,
			"a machine should be able to empty a canteen too -- swapping one food for another must not "
				+ "mean breaking the block. Got " + back);

		helper.assertTrue(!ItemHandlerHelper.insertItem(machines, new ItemStack(Items.COBBLESTONE, 4), false)
			.isEmpty(), "and it should still be a canteen: food in, anything else refused");
		helper.succeed();
	}

	/**
	 * A Canteen's comparator measures how full it is, and agrees with the block's own face.
	 *
	 * <p><b>It measured food <em>points</em> until the block could draw its own stock.</b> That was
	 * defensible on its own terms — vanilla prices bread at four and every root at one, so a restock
	 * line on a points scale fires at the right time whatever the trough holds — but it stopped being
	 * defensible once the top drew a heap per slot and the flanks a bar per slot. A full rack of
	 * beetroot would have been nine bright cells, a full bar, and a comparator reading of four. One
	 * block cannot answer "how full" two ways and be trusted on either.
	 *
	 * <p>So the thing this pins is <b>agreement</b>, not a formula: what the comparator says and what
	 * {@code servings()} draws have to move together. Testing the formula alone would pass just as
	 * happily with the two out of step again.
	 *
	 * <p>The cost is real and is the reason this was argued twice: carrots and bread now read alike at
	 * the same item count while holding four times different feeding. What carries that difference now
	 * is the top of the block, which is orange for one and tan for the other from across the room.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aCanteensComparatorAgreesWithWhatItDraws(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());
		if (!(helper.getBlockEntity(SOURCE) instanceof CanteenBlockEntity canteen))
			throw new GameTestAssertException("the canteen should have a block entity");

		helper.assertTrue(canteen.comparatorOutput() == 0, "an empty canteen should read zero");

		// The same item count of two foods worth four times different amounts of feeding. They fill
		// the block equally, so they read equally -- which is the change, and is what every other
		// container in the game does.
		ItemHandlerHelper.insertItem(canteen.stock(), new ItemStack(Items.CARROT, 32), false);
		int roots = canteen.comparatorOutput();
		canteen.stock()
			.extractItem(0, 64, false);
		ItemHandlerHelper.insertItem(canteen.stock(), new ItemStack(Items.BREAD, 32), false);
		helper.assertTrue(canteen.comparatorOutput() == roots,
			"half a slot of carrots and half a slot of bread fill the block equally, so they should "
				+ "read equally; got " + roots + " and " + canteen.comparatorOutput());

		// And the signal has to track the same per-slot fullness the block draws, or the face and the
		// redstone disagree about one block.
		canteen.stock()
			.extractItem(0, 64, false);
		int previous = 0;
		for (int slot = 0; slot < CanteenBlockEntity.SLOTS; slot++) {
			canteen.stock()
				.setStackInSlot(slot, new ItemStack(Items.BEETROOT, 64));

			int signal = canteen.comparatorOutput();
			helper.assertTrue(signal >= previous,
				"filling another slot must never lower the signal; slot " + slot + " took it from "
					+ previous + " to " + signal);
			previous = signal;

			int drawn = 0;
			for (CanteenBlockEntity.Serving serving : canteen.servings())
				if (!serving.isEmpty())
					drawn++;
			helper.assertTrue(drawn == slot + 1,
				"the block should be drawing " + (slot + 1) + " slots of food and is drawing " + drawn);
		}
		helper.assertTrue(previous == 15,
			"a physically full canteen reads full whatever is in it, and read " + previous);

		// A rack of dribbles against the same rack full. Every slot is occupied either way, so
		// anything asking only "is this slot empty" cannot tell them apart -- which is exactly how
		// the gauge came to draw a brim-full bar over a block the comparator was calling 1 of 15,
		// and why the assertion above could not see it: it only ever inserted whole stacks.
		int full = 0;
		for (CanteenBlockEntity.Serving serving : canteen.servings())
			full += serving.cells(2);
		int solid = canteen.comparatorOutput();

		for (int slot = 0; slot < CanteenBlockEntity.SLOTS; slot++)
			canteen.stock()
				.setStackInSlot(slot, new ItemStack(Items.BEETROOT, 1));

		int dribbles = 0;
		for (CanteenBlockEntity.Serving serving : canteen.servings())
			dribbles += serving.cells(2);

		helper.assertTrue(dribbles < full,
			"a slot holding one beetroot must draw less than a slot holding sixty-four; both drew "
				+ dribbles + " against " + full);
		helper.assertTrue(canteen.comparatorOutput() < solid,
			"and the comparator has to agree with the face: a rack of single beetroots read "
				+ canteen.comparatorOutput() + " against " + solid + " for a full one");
		helper.succeed();
	}

	/**
	 * A Canteen's stock reaches the client, or the goggles lie about it.
	 *
	 * <p>This shipped broken: a chute filled a canteen all morning and a pair of goggles read "Empty"
	 * the whole time. Both were right, about different worlds. **A block entity's contents are the
	 * server's and reach a client only if the block sends them**, and the goggle overlay is drawn on
	 * the client from the client's copy — which, for a block that never syncs, is whatever it was
	 * given when the chunk loaded. Nothing in the game says so, and the natural reading is that the
	 * chute is not working.
	 *
	 * <p>What a server can check is the half that was missing: that the update tag a client would be
	 * sent actually carries the stock. The Worker Station has had these overrides since its screen was
	 * built; this block simply never got them.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aCanteenSendsItsStockToTheClient(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());
		if (!(helper.getBlockEntity(SOURCE) instanceof CanteenBlockEntity canteen))
			throw new GameTestAssertException("the canteen should have a block entity");

		ItemHandlerHelper.insertItem(canteen.stock(), new ItemStack(Items.BREAD, 5), false);

		CompoundTag update = canteen.getUpdateTag(helper.getLevel()
			.registryAccess());
		// Read back the way a client would: build a fresh block entity and load the tag into it.
		CanteenBlockEntity asClientSeesIt =
			new CanteenBlockEntity(helper.absolutePos(SOURCE), CWBlocks.CANTEEN.get()
				.defaultBlockState());
		asClientSeesIt.loadWithComponents(update, helper.getLevel()
			.registryAccess());

		helper.assertTrue(!asClientSeesIt.contents()
			.isEmpty(), "the update a client is sent should carry the canteen's stock, and carried nothing");
		helper.assertTrue(asClientSeesIt.goggleSummary()
			.size() == canteen.goggleSummary()
				.size(), "so a client's goggles should say what the server's canteen holds");
		helper.succeed();
	}

	/**
	 * A Canteen says what is in it to a pair of Engineer's Goggles.
	 *
	 * <p>Nothing goes in or out of one by hand, so a player's only other way to know is a comparator —
	 * which answers "how full" and never "full of what". A trough a misaimed funnel filled with cake
	 * instead of bread reads as a working one, and the whole point of the block is whether the crew
	 * can eat.
	 *
	 * <p>Nothing else covers this. The overlay itself is drawn by a client class that cannot load on a
	 * dedicated server, but {@code addToGoggleTooltip} is ordinary common code and the lines it builds
	 * are ordinary {@code Component}s — so what a server can check is that there are any, that they say
	 * different things full and empty, and that the keys behind them are in the lang file rather than
	 * rendering to a player as raw key text. That last one is the failure this shape of feature
	 * actually has: Ponder has burnt this repo for it once already.
	 */
	@GameTest(template = "work_site", timeoutTicks = 100)
	public static void aCanteenTellsGogglesWhatIsInIt(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());
		if (!(helper.getBlockEntity(SOURCE) instanceof CanteenBlockEntity canteen))
			throw new GameTestAssertException("the canteen should have a block entity");

		// goggleSummary rather than addToGoggleTooltip: the latter lays each line out through
		// LangBuilder.forGoggles, which reaches into Minecraft and is refused outright on a dedicated
		// server. Every decision worth checking is on this side of that split, which is why there is
		// one.
		List<Component> empty = canteen.goggleSummary();
		helper.assertTrue(empty.size() > 1,
			"an empty canteen should still have something to say -- 'empty' is the answer a player needs");

		ItemHandlerHelper.insertItem(canteen.stock(), new ItemStack(Items.BREAD, 7), false);
		List<Component> stocked = canteen.goggleSummary();

		String said = stocked.stream()
			.map(Component::getString)
			.collect(java.util.stream.Collectors.joining(" "));
		helper.assertTrue(said.contains("7"),
			"the goggles should say how much food there is, and said \"" + said + "\"");
		helper.assertTrue(!said.equals(empty.stream()
			.map(Component::getString)
			.collect(java.util.stream.Collectors.joining(" "))),
			"a stocked canteen should not read the same as an empty one");

		// Every key it builds has to be in the lang file. A missing one renders as the raw key in
		// front of a player, silently, because nothing on this side ever renders the overlay.
		for (String key : new String[] { "createworkers.goggles.canteen", "createworkers.goggles.canteen.empty" })
			helper.assertTrue(hasLangKey(helper, key), "no lang entry for " + key);
		helper.succeed();
	}

	/** Reads the shipped lang file out of the jar, the way the model checks read blockstates. */
	private static boolean hasLangKey(GameTestHelper helper, String key) {
		try (InputStream source = WorkerGameTests.class
			.getResourceAsStream("/assets/createworkers/lang/en_us.json")) {
			if (source == null)
				throw new GameTestAssertException("en_us.json is not in the jar");
			return com.google.gson.JsonParser
				.parseReader(new java.io.InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8))
				.getAsJsonObject()
				.has(key);
		} catch (java.io.IOException failure) {
			throw new GameTestAssertException("could not read en_us.json: " + failure.getMessage());
		}
	}

	/**
	 * A worker stocks a Canteen the way it stocks anything else: through a funnel on it.
	 *
	 * <p>This is the whole of the automation story, and it is deliberately an <em>unremarkable</em>
	 * one. "A line that hauls bread into the canteen that feeds the workers running the line" is
	 * something a player builds out of parts they already have, and the parts are the ones every other
	 * inventory needs — because the alternative, registering the block as an arm interaction point,
	 * buys exactly one funnel and costs the only answer there is to "why does my chest need one".
	 *
	 * <p>What the funnel is standing in for here is every other way in: a chute, a belt, a hopper, a
	 * player's hand. They all arrive at the same {@code IItemHandler}, which is where the food filter
	 * lives for exactly that reason.
	 */
	@GameTest(template = "work_site", timeoutTicks = 600)
	public static void aWorkerStocksACanteenThroughAFunnel(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(TARGET, CWBlocks.CANTEEN.get());
		// Facing up and not extracting: the funnel's job is to take what is handed to it and push it
		// down into the block beneath, which is the shape every Create inventory is stocked through.
		helper.setBlock(CANTEEN_FUNNEL, AllBlocks.ANDESITE_FUNNEL.getDefaultState()
			.setValue(AbstractDirectionalFunnelBlock.FACING, Direction.UP)
			.setValue(FunnelBlock.EXTRACTING, false));

		helper.runAfterDelay(FUNNEL_WARMUP, () -> {
			WorkerTarget depot = target(helper, SOURCE);
			depot.cycleMode();
			helper.assertTrue(depot.getMode() == Mode.TAKE, "precondition: the depot is the input");
			WorkerTarget funnel = target(helper, CANTEEN_FUNNEL);
			helper.assertTrue(funnel.getMode() == Mode.DEPOSIT, "precondition: the funnel is the output");

			Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
			WorkerData data = Workers.getOrCreate(villager);
			data.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.of(List.of(depot, funnel)));
			stock(helper, SOURCE, new ItemStack(Items.BREAD, 4));

			helper.succeedWhen(() -> {
				if (!(helper.getBlockEntity(TARGET) instanceof CanteenBlockEntity stocked))
					throw new GameTestAssertException("the canteen should have a block entity");
				helper.assertTrue(!stocked.contents()
					.isEmpty(), "the bread should have reached the canteen through the funnel");
			});
		});
	}

	/**
	 * A Canteen is a landmark, never a workstation.
	 *
	 * <p>It is registered as a point of interest so that a hungry worker can <em>find</em> one — that
	 * query is the only thing in the game that answers "where is the nearest of these" without walking
	 * every block, and the bed hunt already leans on it. But it is registered with <b>no tickets</b>,
	 * because a ticket is a claim and nothing about eating is a claim: a trough serves everybody, and
	 * one held by a worker in an unloaded chunk would be out of service for the rest of the village.
	 *
	 * <p>What that has to buy is the guarantee below. A block a villager can claim is a block a
	 * villager will walk across a village to claim, and a villager that arrives at a workstation no
	 * profession matches is one vanilla has no story for.
	 */
	@GameTest(template = "work_site", timeoutTicks = 300)
	public static void aCanteenIsNeverTakenAsAJobSite(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, CWBlocks.CANTEEN.get());

		helper.assertTrue(CWPoiTypes.CANTEEN.get()
			.maxTickets() == 0, "a canteen must offer no tickets, or a villager can claim one");

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		BlockPos canteen = helper.absolutePos(SOURCE);

		helper.startSequence()
			// Long enough for AcquirePoi to have looked, which it does on a jittered retry rather than
			// every tick -- a test that asserted on the tick of the spawn would pass against anything.
			.thenIdle(BRAIN_SETTLE_TICKS)
			.thenExecute(() -> {
				helper.assertTrue(villager.getVillagerData()
					.getProfession() == VillagerProfession.NONE,
					"nobody should take a job at a canteen, and this villager became a "
						+ villager.getVillagerData()
							.getProfession());
				helper.assertTrue(!canteen.equals(villager.getBrain()
					.getMemory(MemoryModuleType.JOB_SITE)
					.map(GlobalPos::pos)
					.orElse(null)), "and none of them should hold a canteen as a job site");
			})
			.thenSucceed();
	}

	/**
	 * A target that would not resolve is tried again — and only that one.
	 *
	 * <p>Resolution runs once per load, so without a retry a target whose chunk was unloaded at the
	 * time, or whose block had been broken and has since been rebuilt, would be missing for the rest of
	 * the worker's life. Re-resolving the whole programme instead would throw away the capability
	 * caches of every target that was working perfectly well.
	 */
	@GameTest(template = "work_site", timeoutTicks = 400)
	public static void unresolvedTargetsAreRetried(GameTestHelper helper) {
		prepareWorkSite(helper);

		WorkerTarget in = target(helper, SOURCE);
		WorkerTarget out = target(helper, TARGET);
		in.cycleMode();
		WorkerProgram program = WorkerProgram.of(List.of(in, out));

		// The output goes missing before the worker ever gets to resolve it.
		helper.setBlock(TARGET, Blocks.AIR);

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = Workers.getOrCreate(villager);
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), program);
		data.resolvePoints(villager);

		helper.assertTrue(data.getOutputs()
			.isEmpty(), "a missing block should not have resolved to a target");
		helper.assertTrue(data.getInputs()
			.size() == 1, "the input should have resolved");
		WorkerTarget kept = data.getInputs()
			.get(0);

		helper.setBlock(TARGET, AllBlocks.DEPOT.getDefaultState());
		helper.runAfterDelay(RESOLVE_RETRY_WAIT, () -> {
			data.resolvePoints(villager);
			helper.assertTrue(data.getOutputs()
				.size() == 1, "the rebuilt depot should have been picked up on a retry");
			helper.assertTrue(data.getInputs()
				.size() == 1 && data.getInputs()
					.get(0) == kept, "the target that already worked should not have been rebuilt");
			helper.succeed();
		});
	}

	/**
	 * An idle enderman must not be sent on the rounds.
	 *
	 * <p>It has no way to walk one — its locomotion only blinks where the job sends it — so a round it
	 * was given would be a stop it never arrived at, and the rounds give up on those by setting the
	 * target aside. An enderman left idling long enough would work through its own programme writing
	 * off every inventory on it, and then stand there with nothing it was willing to use.
	 */
	@GameTest(template = "work_site", timeoutTicks = 500)
	public static void idleEndermenAreNotSentOnRounds(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(TARGET, AllBlocks.DEPOT.getDefaultState());
		// Deliberately unstocked: the worker has a programme and nothing to do with it.

		EnderMan enderman = helper.spawn(EntityType.ENDERMAN, SPAWN);
		WorkerData data = employ(helper, enderman);

		helper.runAfterDelay(ROUNDS_WOULD_HAVE_GIVEN_UP, () -> {
			List<BlockPos> stops = Workers.patrolStops(data, now(helper));
			helper.assertTrue(stops.size() == 2,
				"an idle enderman should not have set its own targets aside, " + stops.size() + " of 2 left");
			helper.succeed();
		});
	}

	// --- helpers ---

	/**
	 * Builds a two-destination sorting office: a depot holding one package, and two brass funnels
	 * each filtered to a different address, each feeding its own chest.
	 */
	private static Villager setUpSortingOffice(GameTestHelper helper, String parcelAddress) {
		layFloor(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(SMELTING_CHEST, Blocks.CHEST);
		helper.setBlock(STORAGE_CHEST, Blocks.CHEST);
		placeFilteredFunnel(helper, SMELTING_FUNNEL, "Smelting");
		placeFilteredFunnel(helper, STORAGE_FUNNEL, "Storage");

		ItemStack parcel = addressedPackage(parcelAddress);
		IItemHandler depot = handlerAt(helper, SOURCE);
		if (depot == null)
			throw new IllegalStateException("depot exposed no item handler");
		if (!ItemHandlerHelper.insertItem(depot, parcel, false)
			.isEmpty())
			throw new IllegalStateException("could not put the package on the depot");

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerTarget in = target(helper, SOURCE);
		in.cycleMode(); // -> TAKE; funnels are deposit-only and stay as outputs
		WorkerProgram program = WorkerProgram.of(
			List.of(in, target(helper, SMELTING_FUNNEL), target(helper, STORAGE_FUNNEL)));

		Workers.getOrCreate(villager)
			.employ(new ItemStack(CWItems.HARD_HAT.get()), program);
		return villager;
	}

	/**
	 * A funnel only finds the inventory it feeds once its block entity has ticked, so the rig needs a
	 * moment to settle before anything is asserted against it.
	 */
	private static WorkerData resolved(Villager villager) {
		WorkerData data = Workers.getOrCreate(villager);
		data.invalidatePoints();
		data.resolvePoints(villager);
		return data;
	}

	private static ItemStack addressedPackage(String address) {
		ItemStack parcel = PackageItem.containing(List.of(new ItemStack(Items.COBBLESTONE, 8)));
		PackageItem.addAddress(parcel, address);
		return parcel;
	}

	private static WorkerTarget outputAt(WorkerData data, GameTestHelper helper, BlockPos relative) {
		BlockPos pos = helper.absolutePos(relative);
		for (WorkerTarget candidate : data.getOutputs())
			if (candidate.getPos()
				.equals(pos))
				return candidate;
		throw new IllegalStateException("no output resolved at " + relative);
	}

	/** Whether a simulated insert actually consumed any of the stack. */
	private static boolean accepts(WorkerTarget target, ItemStack stack) {
		return !ItemStack.matches(target.insert(stack.copy(), true), stack);
	}

	/**
	 * A brass funnel feeding the block below it, filtered to one package address.
	 *
	 * <p>A funnel's {@code FACING} points <em>away</em> from the inventory it serves — Create targets
	 * {@code getFunnelFacing(state).getOpposite()} — so one sitting on top of a chest faces UP, into
	 * the world where items arrive. Facing it DOWN aims it at the air above and it silently accepts
	 * nothing.
	 */
	private static void placeFilteredFunnel(GameTestHelper helper, BlockPos relative, String address) {
		helper.setBlock(relative, AllBlocks.BRASS_FUNNEL.getDefaultState()
			.setValue(AbstractDirectionalFunnelBlock.FACING, Direction.UP)
			.setValue(FunnelBlock.EXTRACTING, false));

		FilteringBehaviour filtering =
			BlockEntityBehaviour.get(helper.getLevel(), helper.absolutePos(relative), FilteringBehaviour.TYPE);
		if (filtering == null)
			throw new IllegalStateException("brass funnel has no filtering behaviour at " + relative);

		ItemStack filter = AllItems.PACKAGE_FILTER.asStack();
		PackageItem.addAddress(filter, address);
		if (!filtering.setFilter(filter))
			throw new IllegalStateException("funnel refused the package filter at " + relative);
	}

	private static int countPackages(GameTestHelper helper, BlockPos relative) {
		IItemHandler handler = handlerAt(helper, relative);
		if (handler == null)
			return 0;
		int found = 0;
		for (int slot = 0; slot < handler.getSlots(); slot++)
			if (PackageItem.isPackage(handler.getStackInSlot(slot)))
				found++;
		return found;
	}

	/** The speed the mob's move control is actually being driven at. */
	/** The level's clock, which is what target set-asides and resolve retries are measured against. */
	private static long now(GameTestHelper helper) {
		return helper.getLevel()
			.getGameTime();
	}

	private static double travelSpeed(Mob mob) {
		return mob.getMoveControl()
			.getSpeedModifier();
	}

	/**
	 * Lays the floor the workers stand on. The game test framework clears the volume to air, and
	 * relying on a hand-written structure template to supply the floor proved fragile, so the tests
	 * build their own.
	 */
	private static void layFloor(GameTestHelper helper) {
		for (int x = 0; x < SITE_SIZE; x++)
			for (int z = 0; z < SITE_SIZE; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE);
	}

	private static void prepareWorkSite(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(TARGET, AllBlocks.DEPOT.getDefaultState());
		stock(helper, SOURCE);
	}

	private static void stock(GameTestHelper helper, BlockPos relative) {
		stock(helper, relative, new ItemStack(Items.COBBLESTONE, STOCK));
	}

	/** The same, with something other than rubble in it — a canteen has no use for cobblestone. */
	private static void stock(GameTestHelper helper, BlockPos relative, ItemStack goods) {
		IItemHandler handler = handlerAt(helper, relative);
		if (handler == null)
			throw new IllegalStateException("no item handler at " + relative);
		ItemHandlerHelper.insertItem(handler, goods, false);
	}

	private static IItemHandler handlerAt(GameTestHelper helper, BlockPos relative) {
		return helper.getLevel()
			.getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(relative), null);
	}

	private static WorkerTarget target(GameTestHelper helper, BlockPos relative) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(relative);
		WorkerTarget target = WorkerTarget.create(level, pos, level.getBlockState(pos));
		if (target == null)
			throw new IllegalStateException("no worker target at " + relative);
		return target;
	}

	/** A hat carrying the work site's own programme, as clicking the two depots would have built it. */
	private static ItemStack programmedHat(GameTestHelper helper) {
		WorkerTarget in = target(helper, SOURCE);
		in.cycleMode(); // targets start as DEPOSIT; one cycle makes this the input

		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(hat, WorkerProgram.of(List.of(in, target(helper, TARGET))));
		return hat;
	}

	/**
	 * Hires a mob.
	 *
	 * <p>An enderman goes through the right-click, that being the only way one is ever hired. A
	 * villager is employed directly rather than stood beside a station and waited for: these tests are
	 * about hauling, and driving each of them through vanilla's point-of-interest acquisition would
	 * add hundreds of ticks apiece to test nothing they are about. {@code WorkerStationGameTests}
	 * covers the station route itself.
	 */
	private static void hire(GameTestHelper helper, Mob mob) {
		if (mob instanceof EnderMan) {
			Player player = helper.makeMockPlayer(GameType.SURVIVAL);
			player.setItemInHand(InteractionHand.MAIN_HAND, programmedHat(helper));
			NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, mob));
		} else {
			ItemStack hat = programmedHat(helper);
			Workers.employ(mob, hat, HardHatItem.getProgram(hat), null, Shift.DAY);
		}
		helper.assertTrue(Workers.isEmployed(mob), "the mob should have been hired");
	}

	private static void retire(GameTestHelper helper, Mob mob) {
		Workers.dismiss(mob);
	}

	private static WorkerData employ(GameTestHelper helper, Mob mob) {
		WorkerTarget in = target(helper, SOURCE);
		WorkerTarget out = target(helper, TARGET);
		in.cycleMode(); // targets start as DEPOSIT; one cycle makes this the input

		WorkerProgram program = WorkerProgram.of(List.of(in, out));
		WorkerData data = Workers.getOrCreate(mob);
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), program);
		return data;
	}

	private static void assertDelivered(GameTestHelper helper) {
		helper.assertTrue(handlerAt(helper, TARGET) != null, "target depot should expose an item handler");
		helper.assertTrue(hasDelivered(helper), "target depot should have received cobblestone but got none");
	}

	private static boolean hasDelivered(GameTestHelper helper) {
		IItemHandler handler = handlerAt(helper, TARGET);
		if (handler == null)
			return false;
		for (int slot = 0; slot < handler.getSlots(); slot++)
			if (handler.getStackInSlot(slot)
				.is(Items.COBBLESTONE))
				return true;
		return false;
	}
}
