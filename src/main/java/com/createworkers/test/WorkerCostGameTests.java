package com.createworkers.test;

import java.util.ArrayList;
import java.util.List;

import com.createworkers.CreateWorkers;
import com.createworkers.item.HardHatItem;
import com.createworkers.net.WorkerStatePacket;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWComponents;
import com.createworkers.registry.CWItems;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.AllBlocks;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * What a worker costs, asserted rather than timed.
 *
 * <p>These are the performance tests. None of them measures a duration, because a duration is a
 * fact about the machine that ran it: a threshold loose enough to pass on a busy CI runner is loose
 * enough to sleep through a tenfold regression, and one tight enough to catch that regression fails
 * on somebody's laptop. So each test counts the work instead — the block reads, the slots looked
 * into, the simulated deliveries priced, the bytes put on the wire — and asserts a bound
 * <em>derived from the size of the programme</em> rather than a number somebody once measured.
 *
 * <p>That makes them worth the same on any hardware, and it makes them assertions about complexity
 * rather than about speed. "Each output is checked once per search, not once per slot of every
 * input" is a claim about the shape of the work; it holds at any size, and it fails the moment a
 * check drifts back inside a loop — which is exactly the regression that is easy to write and
 * impossible to notice in play.
 *
 * <p>Behaviour lives in {@link WorkerGameTests}. These tests assert cost, and every one of them
 * checks its own preconditions first, so a change in Create that quietly made the setup meaningless
 * fails loudly instead of measuring nothing.
 */
@GameTestHolder(CreateWorkers.ID)
@PrefixGameTestTemplate(false)
public class WorkerCostGameTests {

	private static final int SITE_SIZE = 11;
	private static final BlockPos SPAWN = new BlockPos(5, 1, 5);
	/** Inputs along one edge, outputs along the other. */
	private static final List<BlockPos> INPUTS =
		List.of(new BlockPos(1, 1, 1), new BlockPos(3, 1, 1), new BlockPos(5, 1, 1));
	private static final List<BlockPos> OUTPUTS =
		List.of(new BlockPos(1, 1, 9), new BlockPos(3, 1, 9), new BlockPos(5, 1, 9));
	private static final int STOCK = 64;

	/**
	 * A search prices each output once, not once per slot of every input.
	 *
	 * <p>This is the bound that matters, because the validity check behind it is a block read:
	 * Create's {@code ArmInteractionPoint.isValid} refreshes its cached state with a plain
	 * {@code Level.getBlockState}, and a belt point reads a second one above itself. Asked once per
	 * output, a search costs inputs + outputs reads. Asked from inside the loop that prices a stack
	 * — which is where it used to live — it costs inputs + inputs × slots × outputs, so the reads
	 * grow with the *square* of the programme while the programme itself grows linearly.
	 *
	 * <p>The bound is derived from the sizes rather than written down, so it says the same thing at
	 * any programme size and needs no revisiting when the site changes. With the three-and-three
	 * site below it is 6 reads; the per-slot version was 12, and at the config's ceiling of 256
	 * targets the gap is four orders of magnitude.
	 *
	 * <p>Deliberately arranged so the search finds nothing: inputs stocked, outputs already full.
	 * A search that finds work returns on the first slot it likes and never walks the programme, so
	 * only a hopeless one exercises the whole cost — and a hopeless one is not a corner case, it is
	 * what a backed-up line pays every rescan for as long as it stays backed up.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void aSearchPricesEachOutputOncePerSearch(GameTestHelper helper) {
		layFloor(helper);
		for (BlockPos pos : INPUTS) {
			helper.setBlock(pos, AllBlocks.DEPOT.getDefaultState());
			stock(helper, pos);
		}
		for (BlockPos pos : OUTPUTS) {
			helper.setBlock(pos, AllBlocks.DEPOT.getDefaultState());
			stock(helper, pos); // full, so nothing the inputs hold can go anywhere
		}

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager, INPUTS, OUTPUTS);
		data.resolvePoints(villager);

		int inputs = data.getInputs()
			.size();
		int outputs = data.getOutputs()
			.size();
		helper.assertTrue(inputs == INPUTS.size() && outputs == OUTPUTS.size(),
			"expected " + INPUTS.size() + " inputs and " + OUTPUTS.size() + " outputs, got " + inputs + " and "
				+ outputs);

		// Derived, not assumed. A Create depot turns out to expose nine slots — one for the item on
		// it and eight for processing results — so both of these differ from the target count, and
		// only the empty ones are cheap: a slot with nothing in it costs a probe and no pricing.
		int slots = 0;
		int stocked = 0;
		for (WorkerTarget input : data.getInputs()) {
			int count = input.getSlotCount();
			slots += count;
			for (int slot = 0; slot < count; slot++)
				if (!input.extract(slot, true)
					.isEmpty())
					stocked++;
		}
		helper.assertTrue(slots > 0 && stocked > 0,
			"precondition: the inputs should expose slots and hold stock, got " + slots + " slots and " + stocked
				+ " stocked");

		// Precondition: the search really has to walk the whole programme for the count to mean
		// anything. If Create ever lets a full depot accept more, this is what says so.
		long now = helper.getLevel()
			.getGameTime();
		helper.assertTrue(data.searchForItem(now) == -1,
			"precondition: with every output full the search should find nothing to move");

		helper.assertTrue(data.validityChecks() == inputs + outputs,
			"a search should check each target once: expected " + (inputs + outputs) + " validity checks, made "
				+ data.validityChecks() + " (pricing each stocked slot against every output would be "
				+ (inputs + stocked * outputs) + ")");

		// The work that is inherent to the arm's rule -- take nothing you cannot place -- and so is
		// meant to still be here. Skipping it would be cheaper and wrong.
		helper.assertTrue(data.slotProbes() == slots,
			"every slot of every input should have been looked into: expected " + slots + ", probed "
				+ data.slotProbes());
		helper.assertTrue(data.deliveryProbes() == stocked * outputs,
			"every stocked slot should have been priced against every usable output: expected "
				+ (stocked * outputs) + ", priced " + data.deliveryProbes());

		record("hopeless search over " + inputs + " inputs / " + outputs + " outputs (" + slots + " slots, " + stocked
			+ " stocked): " + data.validityChecks() + " validity checks, " + data.slotProbes() + " slot probes, "
			+ data.deliveryProbes() + " delivery probes -- checking validity per slot instead would be "
			+ (inputs + stocked * outputs));
		helper.succeed();
	}

	/**
	 * An output the worker cannot reach drops off the price list, and takes its block read with it.
	 *
	 * <p>The corollary of gathering the outputs once: the gathering is where the set-aside clock is
	 * read, so a target under it costs a search nothing at all — not a read, and not a delivery to
	 * price against. Worth asserting as a cost and not only as behaviour, because the cheap way to
	 * get the behaviour wrong is to filter after pricing rather than before.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void setAsideOutputsCostASearchNothing(GameTestHelper helper) {
		layFloor(helper);
		for (BlockPos pos : INPUTS) {
			helper.setBlock(pos, AllBlocks.DEPOT.getDefaultState());
			stock(helper, pos);
		}
		for (BlockPos pos : OUTPUTS) {
			helper.setBlock(pos, AllBlocks.DEPOT.getDefaultState());
			stock(helper, pos);
		}

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager, INPUTS, OUTPUTS);
		data.resolvePoints(villager);

		int inputs = data.getInputs()
			.size();
		int outputs = data.getOutputs()
			.size();
		long now = helper.getLevel()
			.getGameTime();

		data.searchForItem(now);
		int priced = data.deliveryProbes();
		helper.assertTrue(data.validityChecks() == inputs + outputs, "precondition: every target checked once");
		helper.assertTrue(priced > 0, "precondition: the search should have priced some deliveries");

		// One output set aside. It should cost nothing from here on.
		data.markUnreachable(helper.absolutePos(OUTPUTS.get(0)), now + 100);
		data.searchForItem(now);

		helper.assertTrue(data.validityChecks() == inputs + outputs - 1,
			"a set-aside output should not be read: expected " + (inputs + outputs - 1) + " validity checks, made "
				+ data.validityChecks());
		helper.assertTrue(data.deliveryProbes() < priced,
			"a set-aside output should not be priced: was " + priced + ", still " + data.deliveryProbes());

		record("setting one of " + outputs + " outputs aside: validity checks " + (inputs + outputs) + " -> "
			+ data.validityChecks() + ", delivery probes " + priced + " -> " + data.deliveryProbes());
		helper.succeed();
	}

	/**
	 * Collecting does not walk the inventory the search already walked.
	 *
	 * <p>A search finds an input <em>and</em> a slot; handing only the input back made the collect
	 * re-walk from slot zero, looking into — and pricing against every output — every slot it had
	 * already passed over. On a wide inventory whose earlier slots are not the useful ones that is
	 * the walk paid twice for one pickup.
	 *
	 * <p>A basin, because a depot has a single slot and could not tell the two apart. The stock goes
	 * in a late slot deliberately: the whole saving is the slots before it.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void collectingDoesNotWalkTheInventoryAgain(GameTestHelper helper) {
		layFloor(helper);
		BlockPos in = INPUTS.get(0);
		BlockPos out = OUTPUTS.get(0);
		helper.setBlock(in, AllBlocks.BASIN.getDefaultState());
		helper.setBlock(out, AllBlocks.DEPOT.getDefaultState());

		IItemHandler basin = handlerAt(helper, in);
		helper.assertTrue(basin instanceof IItemHandlerModifiable,
			"precondition: the basin's handler should be modifiable, so the stock can be put in a late slot");
		int slots = basin.getSlots();
		helper.assertTrue(slots > 4, "precondition: a basin should have more than four slots, has " + slots);

		// Placed rather than inserted: a basin only accepts an ordinary insert into slot 0, and slot 0
		// is exactly the slot that would make this test pass either way.
		int stocked = 3;
		((IItemHandlerModifiable) basin).setStackInSlot(stocked, new ItemStack(Items.COBBLESTONE, 8));
		for (int slot = 0; slot < stocked; slot++)
			helper.assertTrue(basin.getStackInSlot(slot)
				.isEmpty(), "precondition: slot " + slot + " should be empty, so the walk to slot "
					+ stocked + " is what is being saved");
		helper.assertTrue(!basin.getStackInSlot(stocked)
			.isEmpty(), "precondition: the stock should be sitting in slot " + stocked);

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager, List.of(in), List.of(out));
		data.resolvePoints(villager);
		helper.assertTrue(data.getInputs()
			.size() == 1 && data.getOutputs()
				.size() == 1, "precondition: expected one input and one output");

		long now = helper.getLevel()
			.getGameTime();
		helper.assertTrue(data.searchForItem(now) == 0, "the stocked basin should be found");
		helper.assertTrue(data.slotProbes() == stocked + 1,
			"the search should have looked into slots 0.." + stocked + ": expected " + (stocked + 1) + ", probed "
				+ data.slotProbes());

		helper.assertTrue(data.collectFrom(data.getInputs()
			.get(0), now), "should have collected from the basin");
		helper.assertTrue(!data.getHeld()
			.isEmpty(), "the worker should be carrying the stock");
		helper.assertTrue(data.slotProbes() == 1,
			"collecting should have gone straight to the slot the search found: expected 1 probe, made "
				+ data.slotProbes() + " (walking the inventory again would be " + (stocked + 1) + ")");

		record("collecting from slot " + stocked + " of a " + slots + "-slot basin: " + data.slotProbes()
			+ " slot probe -- walking the inventory again would be " + (stocked + 1));
		helper.succeed();
	}

	/**
	 * A worker's render state does not carry its programme, whatever size the programme is.
	 *
	 * <p>Measured in bytes off the codec, which is the only honest unit here — this packet goes to
	 * every client tracking the worker on every item it moves, twice a second apiece, so its size is
	 * multiplied by workers × viewers and nothing else.
	 *
	 * <p>The assertion is that the size does not move when the programme grows, which needs no
	 * threshold at all: it is the difference between "the programme is not on the wire" and "the
	 * programme is on the wire", and no measurement of the machine enters into it. The packet is
	 * then decoded back to make sure this is a stripped packet and not merely a small one — a worker
	 * still has to arrive employed and holding what it is holding.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void syncingAWorkerDoesNotSendItsProgramme(GameTestHelper helper) {
		layFloor(helper);
		List<BlockPos> all = new ArrayList<>(INPUTS);
		all.addAll(OUTPUTS);
		for (BlockPos pos : all)
			helper.setBlock(pos, AllBlocks.DEPOT.getDefaultState());

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = Workers.getOrCreate(villager);

		WorkerProgram small = programmeOver(helper, all.subList(0, 2));
		WorkerProgram large = programmeOver(helper, all);
		helper.assertTrue(large.size() > small.size(), "precondition: the second programme should be the bigger one");
		helper.assertTrue(large.tag()
			.toString()
			.length() > small.tag()
				.toString()
				.length() * 2,
			"precondition: the bigger programme should be substantially more NBT");

		ItemStack cargo = new ItemStack(Items.COBBLESTONE, 7);

		data.employ(hatCarrying(small), small);
		data.setHeld(cargo.copy());
		int smallBytes = syncBytes(helper, villager, data);

		data.employ(hatCarrying(large), large);
		data.setHeld(cargo.copy());
		int largeBytes = syncBytes(helper, villager, data);

		helper.assertTrue(smallBytes == largeBytes,
			"the render packet must not grow with the programme: " + small.size() + " targets took " + smallBytes
				+ " bytes, " + large.size() + " targets took " + largeBytes);

		// Small because it is stripped, not because there was nothing on the hat.
		WorkerStatePacket decoded = decodeSync(helper, villager, data);
		helper.assertTrue(!decoded.hat()
			.isEmpty(), "a worker must still arrive wearing a hat, or it renders unemployed");
		helper.assertTrue(!decoded.hat()
			.has(CWComponents.PROGRAM.get()), "the synced hat must carry no programme component");
		helper.assertTrue(HardHatItem.getProgram(decoded.hat())
			.isEmpty(), "the synced hat's programme should read as empty");
		helper.assertTrue(ItemStack.matches(decoded.held(), cargo),
			"the cargo must survive the trip, got " + decoded.held());

		// And the worker itself keeps the real thing, so what it drops is still programmed.
		helper.assertTrue(data.getProgram()
			.size() == large.size(), "stripping the packet must not touch the worker's own hat");
		helper.assertTrue(HardHatItem.getProgram(data.getHat())
			.size() == large.size(), "stripping the packet must not touch the hat the worker will drop");

		record("render packet: " + small.size() + " targets -> " + smallBytes + " bytes, " + large.size()
			+ " targets -> " + largeBytes + " bytes (unstripped, this grew by about "
			+ (large.tag()
				.toString()
				.length() / large.size())
			+ " bytes a target)");
		helper.succeed();
	}

	// --- helpers ---------------------------------------------------------------------------

	/**
	 * Puts what a test measured in the log, whether or not it passed.
	 *
	 * <p>An assertion only speaks up when something breaks, and these numbers are worth reading when
	 * nothing has: they are the whole cost model of the mod in four lines, and seeing them move is
	 * how a change gets checked before there is a bound to write down for it.
	 */
	private static void record(String measurement) {
		CreateWorkers.LOGGER.info("[cost] {}", measurement);
	}

	private static int syncBytes(GameTestHelper helper, Villager villager, WorkerData data) {
		RegistryFriendlyByteBuf buffer = buffer(helper);
		WorkerStatePacket.STREAM_CODEC.encode(buffer, WorkerStatePacket.of(villager, data));
		return buffer.writerIndex();
	}

	private static WorkerStatePacket decodeSync(GameTestHelper helper, Villager villager, WorkerData data) {
		RegistryFriendlyByteBuf buffer = buffer(helper);
		WorkerStatePacket.STREAM_CODEC.encode(buffer, WorkerStatePacket.of(villager, data));
		return WorkerStatePacket.STREAM_CODEC.decode(buffer);
	}

	private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel()
			.registryAccess(), ConnectionType.NEOFORGE);
	}

	private static ItemStack hatCarrying(WorkerProgram programme) {
		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(hat, programme);
		return hat;
	}

	/** A programme over the given blocks, the first half taking and the rest depositing. */
	private static WorkerProgram programmeOver(GameTestHelper helper, List<BlockPos> relative) {
		List<WorkerTarget> targets = new ArrayList<>();
		for (int i = 0; i < relative.size(); i++) {
			WorkerTarget target = target(helper, relative.get(i));
			if (i < (relative.size() + 1) / 2)
				target.cycleMode(); // targets start as DEPOSIT; one cycle makes this an input
			targets.add(target);
		}
		return WorkerProgram.of(targets);
	}

	private static WorkerData employ(GameTestHelper helper, Villager villager, List<BlockPos> inputs,
		List<BlockPos> outputs) {
		List<WorkerTarget> targets = new ArrayList<>();
		for (BlockPos pos : inputs) {
			WorkerTarget target = target(helper, pos);
			target.cycleMode();
			targets.add(target);
		}
		for (BlockPos pos : outputs)
			targets.add(target(helper, pos));

		WorkerProgram programme = WorkerProgram.of(targets);
		WorkerData data = Workers.getOrCreate(villager);
		data.employ(hatCarrying(programme), programme);
		return data;
	}

	private static WorkerTarget target(GameTestHelper helper, BlockPos relative) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(relative);
		WorkerTarget target = WorkerTarget.create(level, pos, level.getBlockState(pos));
		if (target == null)
			throw new IllegalStateException("no worker target at " + relative);
		return target;
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

	private static void layFloor(GameTestHelper helper) {
		for (int x = 0; x < SITE_SIZE; x++)
			for (int z = 0; z < SITE_SIZE; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE);
	}
}
