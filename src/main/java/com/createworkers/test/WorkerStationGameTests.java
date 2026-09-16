package com.createworkers.test;

import java.util.List;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlock;
import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWBlocks;
import com.createworkers.registry.CWItems;
import com.createworkers.registry.CWPoiTypes;
import com.createworkers.registry.CWProfessions;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.AllBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * The block that hires workers.
 *
 * <p>Almost everything here is vanilla's doing — an unemployed villager finds the station because it
 * is in the {@code acquirable_job_site} tag, walks to it, takes a ticket and is turned into a Worker
 * by {@code AssignProfessionFromJobSite}. So what these tests are really checking is that the wiring
 * into that machinery is right, and that the one thing the station does itself, handing the hat over
 * without letting go of it, survives a worker dying.
 */
@GameTestHolder(CreateWorkers.ID)
@PrefixGameTestTemplate(false)
public class WorkerStationGameTests {

	private static final int SITE_SIZE = 11;
	private static final BlockPos SOURCE = new BlockPos(1, 1, 1);
	private static final BlockPos TARGET = new BlockPos(9, 1, 9);
	private static final BlockPos STATION = new BlockPos(5, 1, 5);
	/** Right beside the station, so vanilla's two-block assignment range is never the variable. */
	private static final BlockPos BESIDE_STATION = new BlockPos(5, 1, 6);

	private static final int STOCK = 16;
	/** Long enough for the brain to acquire a point of interest and the station to notice. */
	private static final int HIRING_TICKS = 200;

	/**
	 * A station with a hat in it is a job site; an empty one is not.
	 *
	 * <p>The distinction is load-bearing rather than cosmetic. If an empty station were a job site, a
	 * villager would cross a village to reach it, be turned into a Worker on arrival, find nothing to
	 * do — and then be unable to take any other job for the rest of its life, a Worker's only
	 * workstation being the block it is standing at.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void onlyAStationWithAJobInItIsAJobSite(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		ServerLevel level = helper.getLevel();
		BlockPos station = helper.absolutePos(STATION);

		helper.assertTrue(!level.getBlockState(station)
			.getValue(WorkerStationBlock.HAS_JOB), "a fresh station holds no job");

		helper.succeedWhen(() -> {
			helper.assertTrue(isJobSite(level, station) == false,
				"an empty station should not be a point of interest at all");

			putHatIn(helper, STATION);
			helper.assertTrue(level.getBlockState(station)
				.getValue(WorkerStationBlock.HAS_JOB), "a hat in it should show in the block state");
			helper.assertTrue(isJobSite(level, station), "a station with a job should be a point of interest");
		});
	}

	/**
	 * The headline: a villager hires itself.
	 *
	 * <p>No player, no right-click. The villager is stood next to a station with a programmed hat in
	 * it and left alone, and vanilla's own workstation machinery does the rest.
	 */
	@GameTest(template = "work_site", timeoutTicks = 600)
	public static void anUnemployedVillagerTakesTheJob(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);

		Villager villager = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);
		helper.assertTrue(villager.getVillagerData()
			.getProfession() == VillagerProfession.NONE, "precondition: it should start out unemployed");

		helper.succeedWhen(() -> {
			helper.assertTrue(villager.getVillagerData()
				.getProfession() == CWProfessions.WORKER.get(),
				"vanilla should have made it a Worker by now, and its profession is "
					+ villager.getVillagerData()
						.getProfession());
			helper.assertTrue(Workers.isEmployed(villager), "and the station should have handed it the hat");
		});
	}

	/**
	 * The hat stays in the station, which is the whole of the self-healing property.
	 *
	 * <p>Nothing has to be handed back when a worker dies, because the job never left the block — so
	 * the station simply finds itself unstaffed and hires the next villager along. The thing to guard
	 * against is the opposite: a worker that dropped its hat would leave a second one in the world
	 * every time it died.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void theJobOutlivesTheWorker(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);

		Villager first = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(Workers.isEmployed(first), "the first villager should be hired"))
			.thenExecute(() -> first.die(helper.getLevel()
				.damageSources()
				.genericKill()))
			.thenExecuteAfter(20, () -> {
				helper.assertTrue(station(helper).hasJob(),
					"the job should still be in the station after its worker died");
				helper.assertItemEntityNotPresent(CWItems.HARD_HAT.get());
			})
			.thenExecute(() -> helper.spawn(EntityType.VILLAGER, BESIDE_STATION))
			.thenWaitUntil(() -> helper.assertTrue(someoneIsEmployed(helper),
				"a second villager should have taken the same job"))
			.thenSucceed();
	}

	/**
	 * Taking the hat out ends the job, and lets its worker go.
	 *
	 * <p>The worker carries on rather than being sacked on the spot — it is still wearing a hat and
	 * still has a programme — but it is nobody's to replace any more, which is exactly what a
	 * hand-hired worker is.
	 */
	@GameTest(template = "work_site", timeoutTicks = 600)
	public static void takingTheHatBackEndsTheJob(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		Villager villager = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(Workers.isEmployed(villager), "the villager should be hired"))
			.thenExecute(() -> {
				WorkerData data = Workers.getOrCreate(villager);
				helper.assertTrue(data.getStation() != null, "a station-hired worker should remember its station");

				station(helper).releaseWorker();
				station(helper).setHat(ItemStack.EMPTY);

				helper.assertTrue(data.getStation() == null, "and should be let go when the job is taken away");
				helper.assertTrue(Workers.isEmployed(villager), "but should still be wearing the hat and working");
			})
			.thenExecute(() -> helper.assertTrue(!helper.getLevel()
				.getBlockState(helper.absolutePos(STATION))
				.getValue(WorkerStationBlock.HAS_JOB), "and the station should no longer offer a job"))
			.thenSucceed();
	}

	// --- helpers ---

	private static boolean isJobSite(ServerLevel level, BlockPos pos) {
		PoiManager poi = level.getPoiManager();
		return poi.getType(pos)
			.map(type -> type.is(CWPoiTypes.WORKER_STATION_KEY))
			.orElse(false);
	}

	private static WorkerStationBlockEntity station(GameTestHelper helper) {
		if (helper.getLevel()
			.getBlockEntity(helper.absolutePos(STATION)) instanceof WorkerStationBlockEntity station)
			return station;
		throw new IllegalStateException("no worker station at " + STATION);
	}

	private static boolean someoneIsEmployed(GameTestHelper helper) {
		for (Villager villager : helper.getLevel()
			.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(helper.absolutePos(STATION))
				.inflate(8.0D)))
			if (Workers.isEmployed(villager))
				return true;
		return false;
	}

	private static void putHatIn(GameTestHelper helper, BlockPos relative) {
		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(hat, programme(helper));
		station(helper).setHat(hat);
	}

	private static WorkerProgram programme(GameTestHelper helper) {
		WorkerTarget in = target(helper, SOURCE);
		in.cycleMode(); // targets start as DEPOSIT; one cycle makes this the input
		return WorkerProgram.of(List.of(in, target(helper, TARGET)));
	}

	private static WorkerTarget target(GameTestHelper helper, BlockPos relative) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(relative);
		WorkerTarget target = WorkerTarget.create(level, pos, level.getBlockState(pos));
		if (target == null)
			throw new IllegalStateException("no worker target at " + relative);
		return target;
	}

	private static void prepareWorkSite(GameTestHelper helper) {
		for (int x = 0; x < SITE_SIZE; x++)
			for (int z = 0; z < SITE_SIZE; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.POLISHED_ANDESITE);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(TARGET, AllBlocks.DEPOT.getDefaultState());

		IItemHandler handler = helper.getLevel()
			.getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(SOURCE), null);
		if (handler == null)
			throw new IllegalStateException("no item handler at " + SOURCE);
		ItemHandlerHelper.insertItem(handler, new ItemStack(Items.COBBLESTONE, STOCK), false);
	}
}
