package com.createworkers.test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.createworkers.CWConfig;
import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlock;
import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.block.WorkerStationMenu;
import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWBlocks;
import com.createworkers.registry.CWItems;
import com.createworkers.registry.CWPoiTypes;
import com.createworkers.registry.CWProfessions;
import com.createworkers.worker.Shift;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.AllBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.common.NeoForge;
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

	/** A cell in the far corner, outside the tightened wander radius this batch runs with. */
	private static final BlockPos CELL = new BlockPos(9, 1, 1);
	/** Short enough to watch, long enough that a worker at its post is plainly not being sacked for time. */
	private static final int ABSENTEE_TICKS = 100;
	private static final int TIGHT_WANDER_RADIUS = 4;

	private static int absenteeWas = 6000;
	private static int wanderRadiusWas = 12;

	private static final int STOCK = 16;
	/** Long enough for the brain to acquire a point of interest and the station to notice. */
	private static final int HIRING_TICKS = 200;

	/**
	 * A timeout short enough to watch, and a wander radius tight enough that an eleven-block test site
	 * can put a worker outside it at all.
	 */
	@BeforeBatch(batch = "absentee")
	public static void impatience(ServerLevel level) {
		absenteeWas = CWConfig.ABSENTEE_TIMEOUT.get();
		wanderRadiusWas = CWConfig.WANDER_RADIUS.get();
		CWConfig.ABSENTEE_TIMEOUT.set(ABSENTEE_TICKS);
		CWConfig.WANDER_RADIUS.set(TIGHT_WANDER_RADIUS);
	}

	@AfterBatch(batch = "absentee")
	public static void patience(ServerLevel level) {
		CWConfig.ABSENTEE_TIMEOUT.set(absenteeWas);
		CWConfig.WANDER_RADIUS.set(wanderRadiusWas);
	}

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
		boolean[] struckOff = new boolean[1];

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(Workers.isEmployed(first), "the first villager should be hired"))
			.thenExecute(() -> first.die(helper.getLevel()
				.damageSources()
				.genericKill()))
			// A dying villager is unemployed from the moment it dies, still holds this block as its
			// job site, and stays in the world for its death animation -- so it is exactly what the
			// station finds when it goes looking to fill the vacancy that same death opened. Checked
			// every tick across the window rather than once, the station only looking every twenty.
			.thenExecuteFor(20, () -> {
				if (!station(helper).employs(first.getUUID()))
					struckOff[0] = true;
				else
					helper.assertTrue(!struckOff[0],
						"a station should never hire back the worker that is in the middle of dying on it");
			})
			.thenExecute(() -> {
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

				station(helper).removeHat(0);

				helper.assertTrue(!Workers.isEmployed(villager),
					"and should be sacked when the job is taken away, there being no other way to fire one");
			})
			.thenExecute(() -> helper.assertTrue(!helper.getLevel()
				.getBlockState(helper.absolutePos(STATION))
				.getValue(WorkerStationBlock.HAS_JOB), "and the station should no longer offer a job"))
			.thenSucceed();
	}

	/**
	 * Vanilla tidies up after a sacked worker, which is the assumption the whole design rests on.
	 *
	 * <p>Nothing in this mod puts a fired villager's profession back any more. It does not have to:
	 * losing the station loses the job site, and {@code ResetProfession} clears the profession of any
	 * villager with no job site that has never traded and is still on trade level one — which a worker
	 * now always is, since nothing raises it. The brain refresh that comes with it is also what puts
	 * the village's schedule back.
	 *
	 * <p>If that ever stopped being true, a fired worker would be stuck as a Worker for good: its only
	 * workstation would be a block it no longer has, so it could never take another job. Hence a test
	 * on vanilla's behaviour rather than on ours.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void aSackedWorkerIsTidiedUpByVanilla(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		Villager villager = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(Workers.isEmployed(villager), "the villager should be hired"))
			.thenExecute(() -> {
				helper.assertTrue(villager.getVillagerData()
					.getLevel() <= 1, "precondition: nothing should have raised its trade level");
				station(helper).removeHat(0);
			})
			.thenWaitUntil(() -> {
				helper.assertTrue(villager.getVillagerData()
					.getProfession() == VillagerProfession.NONE,
					"vanilla should have cleared the profession by now, and it is "
						+ villager.getVillagerData()
							.getProfession());
				helper.assertTrue(villager.getBrain()
					.getSchedule() == Schedule.VILLAGER_DEFAULT,
					"and the brain refresh that comes with it should have restored the village's hours");
			})
			.thenSucceed();
	}

	/**
	 * A child is never hired, and vanilla is what refuses it.
	 *
	 * <p>This mod used to turn children away itself, with a config to allow them. Both are gone,
	 * because once hiring goes through a workstation the check has nothing to do: the job-site
	 * {@code AcquirePoi} in the villager CORE package is built with {@code onlyIfAdult}, so a baby
	 * never acquires one and never arrives at a station at all. A dial that cannot change anything is
	 * worse than no dial, so this pins the guarantee that replaced it.
	 */
	@GameTest(template = "work_site", timeoutTicks = 600)
	public static void aChildNeverTakesTheJob(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);

		Villager baby = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);
		baby.setBaby(true);

		helper.startSequence()
			.thenIdle(HIRING_TICKS)
			.thenExecute(() -> {
				helper.assertTrue(baby.isBaby(), "precondition: it should still be a child");
				helper.assertTrue(baby.getBrain()
					.getMemory(MemoryModuleType.JOB_SITE)
					.isEmpty(), "a child should never have acquired the station as a job site");
				helper.assertTrue(!Workers.isEmployed(baby), "and so should never have been hired");
				helper.assertTrue(station(helper).hasJob(), "the job should still be waiting for an adult");
			})
			.thenSucceed();
	}

	/**
	 * A worker that stops turning up loses the job; one that is simply there keeps it.
	 *
	 * <p>Both halves matter, and the first half is the one that makes the test worth anything. The
	 * station can already replace a worker that <em>dies</em>, because dying frees its ticket. What it
	 * could not do before is replace one that is alive and never coming back — and the temptation is
	 * to measure that by how much work is getting done, which would sack the wrong villager every time
	 * an input ran dry. So the worker here is left standing at its post for longer than the timeout
	 * first, and must still have its job.
	 */
	@GameTest(template = "work_site", timeoutTicks = 1200, batch = "absentee")
	public static void aWorkerThatStopsTurningUpLosesTheJob(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		Villager villager = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);
		BlockPos station = helper.absolutePos(STATION);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(Workers.isEmployed(villager), "the villager should be hired"))
			// Standing about at its own work for well past the timeout is not absence. A worker with
			// nothing to haul is idle, and sacking it would be sacking it for the input running dry.
			.thenExecuteAfter(ABSENTEE_TICKS * 2, () -> {
				helper.assertTrue(Workers.isEmployed(villager),
					"a worker standing at its own post should keep its job however long it stands there");
				sealIn(helper, villager, CELL);
			})
			.thenWaitUntil(() -> {
				helper.assertTrue(!Workers.isEmployed(villager),
					"a worker walled away from its work should have been let go by now");
				helper.assertTrue(helper.getLevel()
					.getPoiManager()
					.getFreeTickets(station) > 0,
					"and its ticket should be back, or nobody could ever replace it");
			})
			.thenExecute(() -> helper.spawn(EntityType.VILLAGER, BESIDE_STATION))
			.thenWaitUntil(() -> helper.assertTrue(someoneIsEmployed(helper),
				"so that somebody else can take the job on"))
			.thenSucceed();
	}

	// --- helpers ---

	/**
	 * Walls a worker into a cell it cannot path out of, far enough from its work to count as away.
	 * A hole would not do — a villager steps up one block and climbs straight out.
	 */

	// --- the roster ----------------------------------------------------------------------

	/**
	 * The answer to "how do we fill shifts one at a time": <b>shift-major, slot-minor</b>.
	 *
	 * <p>This is the whole reason a station holds a line rather than a job. Workers in a factory are a
	 * chain, so a shift missing one of them usually produces <em>nothing</em> rather than less — the
	 * worker before the gap fills a depot that never drains and then stops entirely. A short crew
	 * therefore has to be concentrated on one shift rather than spread thinly over three, and a rule
	 * about several jobs at once needs a block that can see several jobs at once.
	 *
	 * <p>Two jobs, both wanting a day and an evening crew, and two villagers to fill four positions.
	 * Both must land on the day shift. Fill slot-first instead and one job runs around the clock while
	 * the other never starts, which is the shape that produces nothing.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void shortCrewsFillWholeShiftsBeforeDeepOnes(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		putHatIn(helper, STATION);
		station(helper).setShifts(0, Set.of(Shift.DAY, Shift.EVENING));
		station(helper).setShifts(1, Set.of(Shift.DAY, Shift.EVENING));

		// One villager at a time, and the second only once the first has a job. Two arriving together
		// would leave the order they were hired in up to whichever reached the block first, which is
		// exactly the thing under test.
		claimant(helper);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1,
				"the first villager should take the first job on the day shift"))
			.thenExecute(() -> {
				helper.assertTrue(station(helper).slots()
					.get(0)
					.worker(Shift.DAY) != null, "and specifically the first job, the rack being the priority order");
				claimant(helper);
			})
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY)
				+ station(helper).staffed(Shift.EVENING) == 2, "the second villager should take a job too"))
			.thenExecute(() -> {
				helper.assertTrue(station(helper).staffed(Shift.DAY) == 2,
					"a crew of two should cover both jobs on one shift, and it covers "
						+ station(helper).staffed(Shift.DAY) + " of them");
				helper.assertTrue(station(helper).staffed(Shift.EVENING) == 0,
					"rather than starting a second shift nobody can complete");
			})
			.thenSucceed();
	}


	/**
	 * Losing a day-shift worker promotes somebody up from a later crew rather than leaving a hole.
	 *
	 * <p>Filling whole shifts before deep ones only holds while a roster is <em>growing</em>. Without
	 * this, every death degrades it for good: two jobs on two shifts with three villagers is a complete
	 * day crew and one evening worker, and losing one of the day crew leaves both lines broken and
	 * three surviving workers producing nothing. The evening worker moving up is the difference between
	 * a running factory and a stopped one — and it costs nothing, because a replacement villager fills
	 * the last place in the order either way.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void losingADayWorkerPromotesSomebodyUpToIt(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		putHatIn(helper, STATION);
		station(helper).setShifts(0, Set.of(Shift.DAY, Shift.EVENING));
		station(helper).setShifts(1, Set.of(Shift.DAY, Shift.EVENING));

		claimant(helper);
		UUID[] doomed = new UUID[1];
		boolean[] struckOff = new boolean[1];

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "one on days"))
			.thenExecute(() -> claimant(helper))
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 2, "two on days"))
			.thenExecute(() -> claimant(helper))
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.EVENING) == 1,
				"and the third starts the evening crew, both day jobs being covered"))
			.thenExecute(() -> {
				UUID onDays = station(helper).slots()
					.get(0)
					.worker(Shift.DAY);
				helper.assertTrue(helper.getLevel()
					.getEntity(onDays) instanceof Villager, "precondition: the first day worker is there");
				Villager victim = (Villager) helper.getLevel()
					.getEntity(onDays);
				victim.setNoAi(false);
				victim.hurt(helper.getLevel()
					.damageSources()
					.genericKill(), Float.MAX_VALUE);
				doomed[0] = onDays;
			})
			// A dying villager is unemployed and still holding this block as its job site for the
			// twenty ticks of its death animation, so it is exactly what a station looking for
			// somebody to hire would find. Checked every tick across that window rather than once,
			// because the station only looks every twenty and a single sample is a coin toss.
			.thenExecuteFor(25, () -> {
				if (!station(helper).employs(doomed[0]))
					struckOff[0] = true;
				else
					helper.assertTrue(!struckOff[0],
						"a station should never hire back a villager in the middle of dying");
			})
			// Both at once, and deliberately not in two steps. A villager killed this way is alive for
			// its death animation, so the roster still reads 2/1 for a while and an assertion on the
			// day crew alone would pass before anything had happened.
			.thenWaitUntil(() -> {
				helper.assertTrue(station(helper).staffed(Shift.DAY) == 2,
					"the day crew should be whole again, and it is " + station(helper).staffed(Shift.DAY) + "/2");
				helper.assertTrue(station(helper).staffed(Shift.EVENING) == 0,
					"because the evening worker moved up to it rather than a hole being left on days, and "
						+ station(helper).staffed(Shift.EVENING) + " is still on evenings");
			})
			.thenSucceed();
	}


	/**
	 * The rack is an ordinary inventory with places that stay where they are put.
	 *
	 * <p>A job's place in the rack is the player's statement of what matters most, so it has to be
	 * theirs to choose: a hat you know is your least important job goes at the bottom, before there is
	 * anything above it. And taking one out of the middle leaves a gap rather than promoting everything
	 * below it into a priority nobody asked for.
	 *
	 * <p>The dense list this replaced also disagreed with vanilla about what a slot is.
	 * {@code moveItemStackTo} shrinks the stack it finds in place, so shift-clicking a hat out left a
	 * job holding a zero-count stack — which cannot be encoded, and took the server down the next time
	 * the chunk was saved.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theRackKeepsJobsWhereTheyArePut(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());

		IItemHandler rack = helper.getLevel()
			.getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(STATION), null);
		helper.assertTrue(rack != null, "a station should be an inventory other machines can reach");

		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(hat, programme(helper));

		helper.assertTrue(rack.insertItem(5, hat.copy(), false)
			.isEmpty(), "a hat should go where it is put, with nothing above it");
		helper.assertTrue(station(helper).jobAt(5) != null, "so the sixth place holds a job");
		helper.assertTrue(station(helper).jobAt(0) == null, "and the first is still empty");
		helper.assertTrue(station(helper).jobCount() == 1, "one job, in the place it was asked for");

		helper.assertTrue(!rack.insertItem(5, hat.copy(), false)
			.isEmpty(), "a place that is taken refuses a second hat");

		helper.assertTrue(rack.insertItem(2, hat.copy(), false)
			.isEmpty(), "a place above one that is taken is still free");

		ItemStack pulled = rack.extractItem(2, 1, false);
		helper.assertTrue(!pulled.isEmpty(), "taking a job out gives its hat back");
		helper.assertTrue(station(helper).jobAt(2) == null, "leaving that place empty");
		helper.assertTrue(station(helper).jobAt(5) != null,
			"and the job below it exactly where the player left it, not shuffled up");
		helper.succeed();
	}


	/**
	 * Shift-clicking a hat out of the rack ends the job, rather than leaving one holding nothing.
	 *
	 * <p>The exact crash this replaced, reproduced end to end. {@code moveItemStackTo} shrinks the stack
	 * it was handed <em>in place</em>, and the stack a menu hands it is the live one in the rack — so
	 * without the source slot being emptied afterwards the rack kept a job whose hat had a count of
	 * zero. Nothing noticed until the chunk was written, at which point {@code ItemStack.save} threw
	 * "Cannot encode empty ItemStack" and took the server down with it.
	 *
	 * <p>Hence the save at the end: the assertion that matters is not only that the job is gone but
	 * that what is left can be written to disk.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void shiftClickingAHatOutEndsItsJob(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		WorkerStationMenu menu = WorkerStationMenu.create(1, player.getInventory(), station(helper));
		helper.assertTrue(station(helper).jobCount() == 1, "precondition: one job on the rack");

		menu.quickMoveStack(player, 0);

		helper.assertTrue(station(helper).jobAt(0) == null,
			"the place should be empty, not holding a hat with nothing in it");
		helper.assertTrue(station(helper).jobCount() == 0, "so the rack holds no jobs");
		// countItem, not contains: contains matches components too, and this hat carries a programme.
		helper.assertTrue(player.getInventory()
			.countItem(CWItems.HARD_HAT.get()) == 1, "and the player has the hat");

		// The crash was here, a tick or two later, not at the click.
		station(helper).saveWithoutMetadata(helper.getLevel()
			.registryAccess());
		helper.succeed();
	}

	/**
	 * The station's menu builds, and its slots are the rack's slots.
	 *
	 * <p>Half a screen's worth of coverage, and the half that can be had: a menu is built on the server
	 * as well as on the client, so everything about its shape — how many slots, which inventory they
	 * are over, what shift-clicking a hat does — is answerable here. What is not is the client's own
	 * constructor, which takes a buffer instead of a block and is where the first version of this
	 * crashed.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theStationMenuIsBuiltOverTheRack(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		// A mock player is made wherever the framework likes, which is not at the block under test.
		BlockPos standing = helper.absolutePos(BESIDE_STATION);
		player.setPos(standing.getX() + 0.5D, standing.getY(), standing.getZ() + 0.5D);

		ItemStack spare = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(spare, programme(helper));
		player.getInventory()
			.add(spare);

		WorkerStationMenu menu = WorkerStationMenu.create(1, player.getInventory(), station(helper));
		helper.assertTrue(menu.slots.size() == WorkerStationBlockEntity.MAX_SLOTS + 36,
			"every place on the rack plus the player's own inventory, and it has " + menu.slots.size());
		helper.assertTrue(menu.getSlot(0)
			.hasItem(), "the first slot is the first job");
		helper.assertTrue(!menu.getSlot(1)
			.hasItem(), "and the second is the empty place after it");

		// Shift-clicking the spare out of the player's inventory: the rack has exactly one index that
		// will take it, so this is also the check that quickMoveStack finds it rather than giving up.
		int playerSlot = -1;
		for (int i = WorkerStationBlockEntity.MAX_SLOTS; i < menu.slots.size(); i++)
			if (menu.getSlot(i)
				.hasItem()) {
				playerSlot = i;
				break;
			}
		helper.assertTrue(playerSlot >= 0, "precondition: the player is holding a hat somewhere");

		// The one thing the roster packet checks before it lets a client reorder a rack or toggle a
		// shift -- which hires and fires villagers. MenuBase.stillValid answers true for any content
		// holder that does not implement Create's IInteractionChecker, so this is a test that ours
		// does: without it the check is not a weak check, it is no check.
		helper.assertTrue(menu.stillValid(player), "a player at the station may use its rack");
		player.setPos(player.getX() + 40, player.getY(), player.getZ());
		helper.assertTrue(!menu.stillValid(player), "a player forty blocks away may not");
		player.setPos(standing.getX() + 0.5D, standing.getY(), standing.getZ() + 0.5D);

		menu.quickMoveStack(player, playerSlot);
		helper.assertTrue(station(helper).jobCount() == 2, "shift-clicking a hat in should rack it, and the rack holds "
				+ station(helper).jobCount());
		helper.assertTrue(menu.getSlot(1)
			.hasItem(), "in the place that was empty");
		helper.succeed();
	}


	/**
	 * Every slot the station's screen lays out is inside its panel, and no two of them are in the same
	 * place.
	 *
	 * <p>The only automated check there is on a layout. The screen itself cannot be tested — client
	 * classes do not load on a dedicated server — but a menu's slot positions are ordinary arithmetic in
	 * a common class, and they are where a window that does not fit shows up first: the rack was one
	 * column of twelve before it was two of six, which made a panel over three hundred pixels tall and
	 * fitted on nobody's screen.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theStationScreenLaysOutInsideItsPanel(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		WorkerStationMenu menu = WorkerStationMenu.create(1, player.getInventory(), station(helper));

		// A window taller than this does not fit a 1080p screen at the GUI scale most players use.
		helper.assertTrue(WorkerStationMenu.PANEL_HEIGHT <= 256,
			"the panel should fit a screen, and it is " + WorkerStationMenu.PANEL_HEIGHT + " tall");

		Set<Long> taken = new java.util.HashSet<>();
		for (Slot slot : menu.slots) {
			helper.assertTrue(slot.x >= 0 && slot.x + 16 <= WorkerStationMenu.PANEL_WIDTH,
				"a slot at x=" + slot.x + " hangs outside a panel " + WorkerStationMenu.PANEL_WIDTH + " wide");
			helper.assertTrue(slot.y >= 0 && slot.y + 16 <= WorkerStationMenu.PANEL_HEIGHT,
				"a slot at y=" + slot.y + " hangs outside a panel " + WorkerStationMenu.PANEL_HEIGHT + " tall");
			helper.assertTrue(taken.add((long) slot.x << 32 | slot.y),
				"two slots share the position " + slot.x + "," + slot.y);
		}

		// The rack is read down one column and then down the other, so the second column's jobs must
		// start again at the top rather than carrying on below the first.
		helper.assertTrue(menu.getSlot(WorkerStationMenu.ROWS_PER_COLUMN).y == menu.getSlot(0).y,
			"the second column should start level with the first");
		helper.assertTrue(menu.getSlot(WorkerStationMenu.ROWS_PER_COLUMN).x > menu.getSlot(0).x,
			"and to the right of it");
		helper.succeed();
	}


	/**
	 * Losing an early job's worker promotes from a <b>later job on the same shift</b>, not only from a
	 * later shift.
	 *
	 * <p>The fill order is one order, read shift first and then down the rack: every job's day crew in
	 * rack order, then every job's evening crew in rack order, then night. Rebalancing restores that
	 * order wherever it was broken, so the two cases are the same rule and not two rules — a day
	 * vacancy is filled from the last job in the fill order whether that worker was on nights or merely
	 * further down the rack.
	 *
	 * <p>Which means a worker's <em>route</em> changes when it is promoted, because the hat changes with
	 * the job. That is the intended behaviour: the rack says which work matters most, and a short crew
	 * should be doing the work that matters most.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void losingAnEarlyJobPromotesFromALaterOneOnTheSameShift(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		putHatIn(helper, STATION);
		// Day only, so the only worker there is to promote is the one doing the less important job.
		station(helper).setShifts(0, Set.of(Shift.DAY));
		station(helper).setShifts(1, Set.of(Shift.DAY));

		claimant(helper);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "the first job"))
			.thenExecute(() -> claimant(helper))
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 2, "and the second"))
			.thenExecute(() -> {
				Villager victim = (Villager) helper.getLevel()
					.getEntity(station(helper).jobAt(0)
						.worker(Shift.DAY));
				victim.setNoAi(false);
				victim.hurt(helper.getLevel()
					.damageSources()
					.genericKill(), Float.MAX_VALUE);
			})
			.thenWaitUntil(() -> {
				helper.assertTrue(station(helper).jobAt(0)
					.worker(Shift.DAY) != null,
					"the more important job should be covered again");
				helper.assertTrue(station(helper).jobAt(1)
					.worker(Shift.DAY) == null,
					"by the worker from the less important one, which is now the empty job");
			})
			.thenSucceed();
	}


	/**
	 * One job set to run all three shifts is staffed by three villagers, and advertises exactly the
	 * openings it has at every step along the way.
	 *
	 * <p>The free-ticket count is the thing real villagers actually consult — {@code AcquirePoi} only
	 * looks at points of interest with space — so a station whose reserve arithmetic drifted would look
	 * perfectly correct on its own roster while no villager in the world could ever claim it. Checked
	 * after every change rather than only at the end, because "it advertised nothing for a while" is
	 * exactly the shape of that failure.
	 */
	@GameTest(template = "work_site", timeoutTicks = 600)
	public static void aJobOnThreeShiftsTakesThreeVillagers(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		BlockPos pos = helper.absolutePos(STATION);
		boolean[] overAdvertised = new boolean[1];

		claimant(helper);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1,
				"the day shift should be covered"))
			.thenWaitUntil(() -> helper.assertTrue(freeTickets(helper, pos) == 0,
				"a fully staffed job should advertise nothing, and it advertises " + freeTickets(helper, pos)))
			.thenExecute(() -> station(helper).setShifts(0, Set.of(Shift.DAY, Shift.EVENING, Shift.NIGHT)))
			.thenWaitUntil(() -> helper.assertTrue(freeTickets(helper, pos) == 2,
				"opening two more shifts should advertise two more openings, and it advertises "
					+ freeTickets(helper, pos)))
			.thenExecute(() -> {
				claimant(helper);
				claimant(helper);
			})
			// Sampled every tick across the hiring, because the failure is a single tick wide: a
			// station that reconciles its tickets before it hires spends the rest of that tick still
			// advertising the openings it has just filled.
			.thenExecuteFor(60, () -> {
				if (freeTickets(helper, pos) > station(helper).vacancies())
					overAdvertised[0] = true;
			})
			.thenExecute(() -> {
				helper.assertTrue(station(helper).staffed(Shift.EVENING) == 1, "the evening shift is covered");
				helper.assertTrue(station(helper).staffed(Shift.NIGHT) == 1, "and the night shift");
				helper.assertTrue(station(helper).jobCount() == 1, "all of it one job");
				helper.assertTrue(freeTickets(helper, pos) == 0, "with nothing left to advertise, and it has "
					+ freeTickets(helper, pos));
				// A villager sent to a station with nothing for it is not merely turned away: AcquirePoi
				// puts that position on a backoff growing to four hundred ticks, so a station that
				// over-advertises teaches the village to stop asking.
				helper.assertTrue(!overAdvertised[0],
					"a station should never advertise an opening it does not have, even for one tick");
			})
			.thenSucceed();
	}


	/**
	 * A promoted worker carries its load into the new job rather than dropping it where it stood.
	 *
	 * <p>A promotion is not a sacking and should not look like one. Dismissing and re-hiring scattered
	 * whatever the worker was holding on the floor — items out of the player's own machines, dropped in
	 * the middle of a shift for a reason nothing in the world explains — and the player watching it
	 * happen has no way to tell it from a bug.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void aPromotedWorkerCarriesItsLoadIntoTheNewJob(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		putHatIn(helper, STATION);
		station(helper).setShifts(0, Set.of(Shift.DAY));
		station(helper).setShifts(1, Set.of(Shift.DAY));

		claimant(helper);
		UUID[] carrierId = new UUID[1];

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "the first job"))
			.thenExecute(() -> claimant(helper))
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 2, "and the second"))
			.thenExecute(() -> {
				// The worker on the less important job is the one that will be promoted, so it is the
				// one given something to carry.
				carrierId[0] = station(helper).jobAt(1)
					.worker(Shift.DAY);
				Villager carrier = (Villager) helper.getLevel()
					.getEntity(carrierId[0]);
				Workers.get(carrier)
					.setHeld(new ItemStack(Items.COBBLESTONE, 4));

				Villager victim = (Villager) helper.getLevel()
					.getEntity(station(helper).jobAt(0)
						.worker(Shift.DAY));
				victim.setNoAi(false);
				victim.hurt(helper.getLevel()
					.damageSources()
					.genericKill(), Float.MAX_VALUE);
			})
			// Named, not merely non-null: the dead worker's record is still on the rack until the audit
			// strikes it off, so "somebody is on the day shift" is true from the moment it is killed.
			.thenWaitUntil(() -> helper.assertTrue(carrierId[0].equals(station(helper).jobAt(0)
				.worker(Shift.DAY)), "the carrier should have been promoted onto the more important job"))
			.thenExecute(() -> {
				Villager promoted = (Villager) helper.getLevel()
					.getEntity(carrierId[0]);
				helper.assertTrue(Workers.get(promoted)
					.getHeld()
					.getCount() == 4, "the promoted worker should still be carrying its load");
				helper.assertItemEntityNotPresent(Items.COBBLESTONE);
			})
			.thenSucceed();
	}

	/**
	 * A station advertises exactly as many openings as it has, and no more.
	 *
	 * <p>{@code maxTickets} belongs to the point-of-interest type rather than to the block, so every
	 * station is registered with room for the largest roster the mod allows. Left alone, a station with
	 * one job would have three dozen villagers walk across the village to be turned away, each of them
	 * made a Worker on arrival and un-made again a tick later. Holding back the tickets it has no
	 * opening for is what makes "free tickets" mean "openings" — after which vanilla's own
	 * {@code AcquirePoi} does the enforcing, exactly as it does for one librarian per lectern.
	 *
	 * <p>No villagers in this one on purpose: what is being measured is the advertisement, and an
	 * entity walking over would only add a way for it to be right by accident.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void aStationAdvertisesOnlyTheOpeningsItHas(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		putHatIn(helper, STATION);
		station(helper).setShifts(1, Set.of(Shift.DAY, Shift.EVENING, Shift.NIGHT));

		BlockPos pos = helper.absolutePos(STATION);
		helper.assertTrue(CWPoiTypes.MAX_TICKETS > 4,
			"precondition: the type must advertise more than this station wants, or nothing is being held back");

		helper.succeedWhen(() -> helper.assertTrue(freeTickets(helper, pos) == 4,
			"one job on one shift and one on three is four openings, and it advertises "
				+ freeTickets(helper, pos)));
	}

	/**
	 * Taking one hat out sacks that job's crew and nobody else's.
	 *
	 * <p>Firing a villager is taking its hat out of the rack, there being no other way now — so the
	 * rack has to be able to say whose hat it was. A station that sacked everybody when one job ended
	 * would make the priority order unusable: rearranging the rack is a thing a player does while the
	 * factory runs.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void takingOneHatOutLeavesTheRestOfTheCrewWorking(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		putHatIn(helper, STATION);

		claimant(helper);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "one job covered"))
			.thenExecute(() -> claimant(helper))
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 2,
				"both jobs should be covered before anything is taken away"))
			.thenExecute(() -> {
				WorkerStationBlockEntity rack = station(helper);
				UUID sacked = rack.slots()
					.get(0)
					.worker(Shift.DAY);
				UUID kept = rack.slots()
					.get(1)
					.worker(Shift.DAY);
				helper.assertTrue(sacked != null && kept != null && !sacked.equals(kept),
					"precondition: two jobs, two different villagers");

				rack.removeHat(0);

				helper.assertTrue(!Workers.isEmployed(helper.getLevel()
					.getEntity(sacked)), "the crew of the job that was taken away should be sacked");
				helper.assertTrue(Workers.isEmployed(helper.getLevel()
					.getEntity(kept)), "and the crew of the job that was not should still be working");
			})
			.thenSucceed();
	}

	/**
	 * A worker asks its station, on every load, whether it is still on the books.
	 *
	 * <p>The other half of the roster being the authority. A station cannot tell an unloaded worker
	 * from a dead one — {@code getEntity} finds only loaded entities, and a point-of-interest ticket is
	 * a counter rather than a name — so it sometimes strikes off a worker that was only away, and hires
	 * somebody else into the job. Without this check that worker would come back and carry on doing a
	 * job the station has given to somebody else, invisible to the one block that is supposed to know
	 * who works there.
	 *
	 * <p>Set up by employing a villager against a station that has never heard of it, which is exactly
	 * the state the returning worker is in.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void aWorkerStruckOffTheRosterSacksItselfWhenItLoads(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);

		Villager stray = helper.spawn(EntityType.VILLAGER, BESIDE_STATION.east(2));
		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(hat, programme(helper));
		Workers.employ(stray, hat, HardHatItem.getProgram(hat),
			GlobalPos.of(helper.getLevel()
				.dimension(), helper.absolutePos(STATION)),
			Shift.DAY);

		helper.assertTrue(Workers.isEmployed(stray), "precondition: it is employed, and by that station");
		helper.assertTrue(!station(helper).employs(stray.getUUID()),
			"precondition: and the station has never heard of it");

		NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(stray, helper.getLevel()));

		helper.assertTrue(!Workers.isEmployed(stray),
			"a worker its station has struck off should sack itself rather than carry on unseen");
		helper.succeed();
	}

	/**
	 * A villager standing at the station having already claimed it, exactly as vanilla leaves one.
	 *
	 * <p>Two villagers finding the same station by themselves is not a thing to build a test on. Each
	 * one's {@code AcquirePoi} searches 48 blocks, which on a game-test grid reaches several other
	 * tests' stations, and a claim it loses puts that position on a backoff that grows to 400 ticks —
	 * so whether the second one is hired inside any particular window is a coin toss. That the wiring
	 * into vanilla works at all is what the single-villager tests above are for; what these need is
	 * two claimants, arriving in a known order.
	 */
	private static Villager claimant(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos station = helper.absolutePos(STATION);
		Villager villager = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);

		// Everything AssignProfessionFromJobSite would have done on arrival, and the ticket AcquirePoi
		// would have taken on the way -- without which the station's own accounting is being handed a
		// world that could not happen.
		level.getPoiManager()
			.take(type -> type.is(CWPoiTypes.WORKER_STATION_KEY), (type, pos) -> pos.equals(station), station, 1);
		villager.setVillagerData(villager.getVillagerData()
			.setProfession(CWProfessions.WORKER.get()));
		villager.getBrain()
			.setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), station));
		// And held where it stands. An unemployed villager strolls, and one that strolls out of the
		// station's hiring range between two of its twenty-tick looks is a test that fails on the
		// villager's legs rather than on the rack's bookkeeping. A real claimant cannot do this: it is
		// hired within a tick or two of arriving, because arriving is what made it a Worker.
		villager.setNoAi(true);
		return villager;
	}

	private static int freeTickets(GameTestHelper helper, BlockPos pos) {
		return helper.getLevel()
			.getPoiManager()
			.getFreeTickets(pos);
	}

	private static void sealIn(GameTestHelper helper, Villager villager, BlockPos cell) {
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(cell.offset(1, y - 1, 0), Blocks.POLISHED_ANDESITE);
			helper.setBlock(cell.offset(-1, y - 1, 0), Blocks.POLISHED_ANDESITE);
			helper.setBlock(cell.offset(0, y - 1, 1), Blocks.POLISHED_ANDESITE);
			helper.setBlock(cell.offset(0, y - 1, -1), Blocks.POLISHED_ANDESITE);
		}
		helper.setBlock(cell.above(2), Blocks.POLISHED_ANDESITE);

		BlockPos inside = helper.absolutePos(cell);
		villager.moveTo(inside.getX() + 0.5D, inside.getY(), inside.getZ() + 0.5D, 0, 0);
	}

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
		station(helper).addHat(hat);
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
