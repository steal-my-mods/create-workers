package com.createworkers.test;

import java.util.List;

import com.createworkers.CWConfig;
import com.createworkers.CreateWorkers;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWItems;
import com.createworkers.worker.TeleportLocomotion;
import com.createworkers.worker.WalkLocomotion;
import com.createworkers.worker.WorkerData;
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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
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

	private static final BlockPos SOURCE = new BlockPos(1, 1, 1);
	private static final BlockPos SOURCE_B = new BlockPos(1, 1, 9);
	private static final BlockPos TARGET = new BlockPos(9, 1, 9);
	private static final BlockPos SPAWN = new BlockPos(5, 1, 5);
	private static final int STOCK = 16;
	private static final int SITE_SIZE = 11;

	// Sorting-office rig: a chest beneath each filtered funnel.
	private static final BlockPos SMELTING_CHEST = new BlockPos(2, 1, 1);
	private static final BlockPos SMELTING_FUNNEL = new BlockPos(2, 2, 1);
	private static final BlockPos STORAGE_CHEST = new BlockPos(8, 1, 1);
	private static final BlockPos STORAGE_FUNNEL = new BlockPos(8, 2, 1);
	/** Ticks to let the funnels latch onto the chests beneath them. */
	private static final int FUNNEL_WARMUP = 10;

	/** Workers must accept exactly what an arm accepts — Create blocks yes, plain inventories no. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void targetsMatchTheMechanicalArm(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(TARGET, Blocks.CHEST);

		ServerLevel level = helper.getLevel();
		BlockPos depot = helper.absolutePos(SOURCE);
		BlockPos chest = helper.absolutePos(TARGET);

		helper.assertTrue(WorkerTarget.isTargetable(level, depot, level.getBlockState(depot)),
			"a depot should be a valid worker target");
		helper.assertTrue(!WorkerTarget.isTargetable(level, chest, level.getBlockState(chest)),
			"a plain chest should not be a worker target, just as it is not an arm target");

		WorkerTarget target = WorkerTarget.create(level, depot, level.getBlockState(depot));
		helper.assertTrue(target != null, "a depot should produce a target");
		helper.assertTrue(WorkerTarget.create(level, chest, level.getBlockState(chest)) == null,
			"a chest should produce no target");
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

		helper.assertTrue(data.searchForItem() == 0, "should have found the stocked input depot");
		helper.assertTrue(data.collectFrom(data.getInputs()
			.get(0)), "should have collected from the source depot");
		helper.assertTrue(!data.getHeld()
			.isEmpty(), "worker should be carrying something after collecting");
		helper.assertTrue(data.depositTo(data.getOutputs()
			.get(0)), "should have deposited into the target depot");

		assertDelivered(helper);
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
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), WorkerProgram.of(List.of(inA, inB, out)),
			villager.blockPosition());
		data.resolvePoints(villager);

		helper.assertTrue(data.getInputs()
			.size() == 2, "expected two inputs");

		// Three scans in a row must all find work: 0, 1, then wrap back to 0.
		helper.assertTrue(data.searchForItem() == 0, "first scan should pick input 0");
		helper.assertTrue(data.searchForItem() == 1, "second scan should advance to input 1");
		helper.assertTrue(data.searchForItem() == 0, "third scan should wrap back to input 0");
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
		locomotion.approach(enderman, target);
		helper.assertTrue(enderman.position()
			.distanceToSqr(home) > 1.0D, "the first approach should teleport the enderman");

		int cooldown = CWConfig.TELEPORT_COOLDOWN.get();
		for (int tick = 1; tick <= cooldown; tick++) {
			enderman.teleportTo(home.x, home.y, home.z);
			locomotion.approach(enderman, target);
			helper.assertTrue(enderman.position()
				.distanceToSqr(home) < 1.0D,
				"teleported again after only " + tick + " of " + cooldown + " cooldown ticks");
		}

		enderman.teleportTo(home.x, home.y, home.z);
		locomotion.approach(enderman, target);
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
			helper.assertTrue(data.searchForItem() == 0, "should have found the package on the depot");
			helper.assertTrue(data.collectFrom(data.getInputs()
				.get(0)), "should have collected the package");
			helper.assertTrue(PackageItem.isPackage(data.getHeld()), "should be carrying a package");

			int chosen = data.searchForDestination();
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

			helper.assertTrue(data.searchForItem() == -1,
				"a package no funnel will accept should be left on the depot");
			helper.assertTrue(data.getHeld()
				.isEmpty(), "the worker should not have picked anything up");
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
			.employ(new ItemStack(CWItems.HARD_HAT.get()), program, villager.blockPosition());
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
		IItemHandler handler = handlerAt(helper, relative);
		if (handler == null)
			throw new IllegalStateException("no item handler at " + relative);
		ItemHandlerHelper.insertItem(handler, new ItemStack(Items.COBBLESTONE, STOCK), false);
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

	private static WorkerData employ(GameTestHelper helper, Mob mob) {
		WorkerTarget in = target(helper, SOURCE);
		WorkerTarget out = target(helper, TARGET);
		in.cycleMode(); // targets start as DEPOSIT; one cycle makes this the input

		WorkerProgram program = WorkerProgram.of(List.of(in, out));
		WorkerData data = Workers.getOrCreate(mob);
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), program, mob.blockPosition());
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
