package com.createworkers.test;

import java.util.List;

import com.createworkers.CreateWorkers;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWItems;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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

	// --- helpers -----------------------------------------------------------------------------

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
		IItemHandler handler = handlerAt(helper, TARGET);
		helper.assertTrue(handler != null, "target depot should expose an item handler");

		int delivered = 0;
		for (int slot = 0; slot < handler.getSlots(); slot++) {
			ItemStack stack = handler.getStackInSlot(slot);
			if (stack.is(Items.COBBLESTONE))
				delivered += stack.getCount();
		}
		helper.assertTrue(delivered > 0, "target depot should have received cobblestone but got none");
	}
}
