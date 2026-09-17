package com.createworkers.test;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import com.createworkers.worker.WorkerShift;
import com.createworkers.worker.Workers;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.AllBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
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
 * <p>The station does its own hiring: it looks for an unemployed adult villager near enough to path
 * to it, gives it the Worker profession and the job site, and hands over a hat. It used to leave all
 * of that to vanilla — the station sat in the {@code acquirable_job_site} tag and villagers claimed
 * it like a lectern — and that route cannot staff one block with more than one villager, because
 * {@code YieldJobSite} makes every applicant after the first give up its claim to the worker already
 * standing there. What is still vanilla's is everything that kept working: the profession, the job
 * site that keeps {@code ResetProfession} off a worker's back, and the tickets that say who is alive.
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
	/** Midnight, which the shipped clock puts well outside the day crew's hours. */
	private static final int MIDNIGHT = 18000;
	private static final int WORKING_HOURS = 1000;
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
	 * The same impatience, at midnight — so the crew is off shift for the whole test.
	 *
	 * <p>Its own batch because it changes two things that are one value for the whole server, the time
	 * of day and the timeout, and the existing absentee batch deliberately runs in working hours.
	 */
	@BeforeBatch(batch = "absentee_at_night")
	public static void impatienceAfterDark(ServerLevel level) {
		absenteeWas = CWConfig.ABSENTEE_TIMEOUT.get();
		wanderRadiusWas = CWConfig.WANDER_RADIUS.get();
		CWConfig.ABSENTEE_TIMEOUT.set(ABSENTEE_TICKS);
		CWConfig.WANDER_RADIUS.set(TIGHT_WANDER_RADIUS);
		level.setDayTime(MIDNIGHT);
	}

	@AfterBatch(batch = "absentee_at_night")
	public static void patienceAtDawn(ServerLevel level) {
		CWConfig.ABSENTEE_TIMEOUT.set(absenteeWas);
		CWConfig.WANDER_RADIUS.set(wanderRadiusWas);
		level.setDayTime(WORKING_HOURS);
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
	 * it and left alone, and it ends up wearing the hat.
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
				"the station should have made it a Worker by now, and its profession is "
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


	/**
	 * A worker asleep at midnight is not an absentee.
	 *
	 * <p>The absentee clock is only ever refreshed from {@code keepNearPost}, and the job goal returns
	 * before it for the whole of the night — so left alone the clock runs out partway through every
	 * night and the station strikes off a crew that is doing exactly what it should. With the shipped
	 * defaults that is 6000 ticks into a 16000-tick night, on every worker on the server, every night:
	 * woken, dropped from the roster, name stripped, and replaced by a fresh hire at dawn.
	 *
	 * <p>Nothing caught it. The absentee tests run in working hours by design, and the night tests are
	 * about bedtime and run a few hundred ticks. It wants a test that is off shift <em>and</em> patient.
	 */
	@GameTest(template = "work_site", batch = "absentee_at_night", timeoutTicks = 900)
	public static void aSleepingWorkerIsNotAnAbsentee(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		Villager villager = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(Workers.isEmployed(villager), "the villager should be hired"))
			// Several times the timeout, off shift throughout. Standing at its own post is not the
			// point here and would not save it: the clock is not being refreshed at all.
			.thenExecuteAfter(ABSENTEE_TICKS * 5, () -> helper.assertTrue(Workers.isEmployed(villager),
				"a worker off shift should still have its job after several timeouts' worth of night"))
			.thenExecute(() -> helper.assertTrue(someoneIsEmployed(helper),
				"and the station should still think somebody is doing it"))
			.thenSucceed();
	}


	/**
	 * Naming a job names the villager doing it, and renaming it renames them again.
	 *
	 * <p>The name lives on the hat in the rack so that it survives the hat being moved or dropped, but
	 * a worker wears a <em>copy</em> taken when it was hired. So a rename reached the block and stopped
	 * there, and the label over the villager stayed whatever the job had been called when it took it
	 * on — which is the one thing the feature exists to do.
	 */
	@GameTest(template = "work_site", timeoutTicks = 400)
	public static void renamingAJobRenamesWhoeverIsDoingIt(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		Villager villager = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(Workers.isEmployed(villager), "the villager should be hired"))
			.thenExecute(() -> station(helper).renameJob(0, "Smelting feed"))
			.thenExecute(() -> {
				helper.assertTrue(villager.hasCustomName(), "the worker should be wearing the job's name");
				helper.assertTrue("Smelting feed".equals(villager.getCustomName()
					.getString()), "and it should be the name the job was given");
			})
			.thenExecute(() -> station(helper).renameJob(0, "Ore line"))
			.thenExecute(() -> helper.assertTrue("Ore line".equals(villager.getCustomName()
				.getString()), "renaming the job should rename the worker again, not only the hat"))
			// And clearing it takes the name back off rather than leaving the old one stuck on.
			.thenExecute(() -> station(helper).renameJob(0, "   "))
			.thenExecute(() -> helper.assertTrue(!villager.hasCustomName(),
				"clearing a job's name should clear its worker's"))
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
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void aJobOnThreeShiftsTakesThreeVillagers(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		station(helper).setShifts(0, Set.of(Shift.DAY, Shift.EVENING, Shift.NIGHT));

		claimant(helper);
		claimant(helper);
		claimant(helper);

		helper.startSequence()
			.thenWaitUntil(() -> {
				helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "the day shift should be covered");
				helper.assertTrue(station(helper).staffed(Shift.EVENING) == 1, "and the evening shift");
				helper.assertTrue(station(helper).staffed(Shift.NIGHT) == 1, "and the night shift");
			})
			.thenExecute(() -> helper.assertTrue(station(helper).jobCount() == 1,
				"all three of them on one job"))
			.thenSucceed();
	}

	/**
	 * A worker whose shift is switched off finishes what it is carrying before it is let go.
	 *
	 * <p>Turning a toggle off used to drop a half-finished delivery on the floor on the spot — items
	 * out of the player's own machines, scattered in the middle of a shift for a reason nothing in the
	 * world explains, and indistinguishable from a bug to the person watching it happen.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void aWorkerWhoseShiftIsTurnedOffFinishesFirst(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		station(helper).setShifts(0, Set.of(Shift.DAY));

		claimant(helper);
		UUID[] hired = new UUID[1];

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "somebody on days"))
			.thenExecute(() -> {
				hired[0] = station(helper).jobAt(0)
					.worker(Shift.DAY);
				Workers.get(helper.getLevel()
					.getEntity(hired[0]))
					.setHeld(new ItemStack(Items.COBBLESTONE, 4));
				// The job moves to evenings, so the villager on days is leaving.
				station(helper).setShifts(0, Set.of(Shift.EVENING));
			})
			.thenExecuteAfter(60, () -> {
				helper.assertTrue(Workers.isEmployed(helper.getLevel()
					.getEntity(hired[0])), "a worker with something in its hands is not sacked on the spot");
				helper.assertTrue(Workers.get(helper.getLevel()
					.getEntity(hired[0]))
					.isServingNotice(), "it is given notice instead");
				helper.assertItemEntityNotPresent(Items.COBBLESTONE);
			})
			.thenExecute(() -> Workers.get(helper.getLevel()
				.getEntity(hired[0]))
				.setHeld(ItemStack.EMPTY))
			.thenWaitUntil(() -> helper.assertTrue(!Workers.isEmployed(helper.getLevel()
				.getEntity(hired[0])), "and let go once its hands are empty"))
			.thenExecute(() -> helper.assertItemEntityNotPresent(Items.COBBLESTONE))
			.thenSucceed();
	}

	/**
	 * A promotion waits for the worker to put down what it is carrying.
	 *
	 * <p>A promotion is a <em>different</em> job, so the load in a worker's hands was picked up for
	 * somewhere the new job may have no business delivering to. Carrying it across would either strand
	 * the worker holding something nothing will accept, or — worse — put it somewhere that takes
	 * anything and should not have had it. So the old job finishes first.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void aPromotionWaitsForTheWorkerToPutItsLoadDown(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		putHatIn(helper, STATION);
		station(helper).setShifts(0, Set.of(Shift.DAY));
		station(helper).setShifts(1, Set.of(Shift.DAY));

		claimant(helper);
		UUID[] carrier = new UUID[1];

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "the first job"))
			.thenExecute(() -> claimant(helper))
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 2, "and the second"))
			.thenExecute(() -> {
				carrier[0] = station(helper).jobAt(1)
					.worker(Shift.DAY);
				Workers.get(helper.getLevel()
					.getEntity(carrier[0]))
					.setHeld(new ItemStack(Items.COBBLESTONE, 4));

				Villager victim = (Villager) helper.getLevel()
					.getEntity(station(helper).jobAt(0)
						.worker(Shift.DAY));
				victim.setNoAi(false);
				victim.hurt(helper.getLevel()
					.damageSources()
					.genericKill(), Float.MAX_VALUE);
			})
			.thenExecuteAfter(60, () -> {
				helper.assertTrue(!carrier[0].equals(station(helper).jobAt(0)
					.worker(Shift.DAY)), "a worker with a load in its hands should not have been moved yet");
				helper.assertTrue(Workers.get(helper.getLevel()
					.getEntity(carrier[0]))
					.isServingNotice(), "it should have been given notice to finish");
				helper.assertItemEntityNotPresent(Items.COBBLESTONE);
			})
			.thenExecute(() -> Workers.get(helper.getLevel()
				.getEntity(carrier[0]))
				.setHeld(ItemStack.EMPTY))
			.thenWaitUntil(() -> helper.assertTrue(carrier[0].equals(station(helper).jobAt(0)
				.worker(Shift.DAY)), "and be promoted once they are empty"))
			.thenSucceed();
	}



	/**
	 * The hiring scene's plate really has a Station in it, and that Station starts without a job.
	 *
	 * <p>The only automated check a Ponder scene admits — it does not load on a dedicated server, so
	 * nothing here can render one. What can be read is the plate, and two things about it are worth
	 * reading. That the Station is there at all: a scene whose subject is missing is a scene about an
	 * empty yard. And that {@code has_job} is <b>false</b>, because the scene's whole first beat is a
	 * hat going in and the block becoming a job site — it flips the property with
	 * {@code cycleBlockProperty}, so a plate that shipped the Station already staffed would show it
	 * quietly losing its job instead.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theHiringPlateHasAnEmptyStationInIt(GameTestHelper helper) {
		CompoundTag tag;
		try (InputStream source = WorkerStationGameTests.class
			.getResourceAsStream("/assets/createworkers/ponder/worker_station.nbt")) {
			helper.assertTrue(source != null, "the hiring ponder plate should be in the jar");
			tag = NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap());
		} catch (IOException failure) {
			throw new IllegalStateException("could not read the hiring ponder plate", failure);
		}

		StructureTemplate plate = new StructureTemplate();
		plate.load(helper.getLevel()
			.holderLookup(Registries.BLOCK), tag);

		List<StructureTemplate.StructureBlockInfo> stations =
			plate.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), CWBlocks.WORKER_STATION.get());
		helper.assertTrue(stations.size() == 1,
			"the plate should hold exactly one Worker Station, and it holds " + stations.size());
		helper.assertTrue(!stations.get(0)
			.state()
			.getValue(WorkerStationBlock.HAS_JOB), "and it should start with no job in it");
		helper.succeed();
	}


	/**
	 * A named hat names its wearer, and gives the name back when the job ends.
	 *
	 * <p>Which villager is it? — the question every diagnostic in this mod runs into. Naming a job in
	 * the station screen and having that name float over the villager standing in a hole is the whole
	 * answer, and it costs one component copy.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void aNamedHatNamesItsWearer(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		station(helper).renameJob(0, "Smelting feed");

		claimant(helper);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "somebody is hired"))
			.thenExecute(() -> {
				Villager worker = (Villager) helper.getLevel()
					.getEntity(station(helper).jobAt(0)
						.worker(Shift.DAY));
				helper.assertTrue(worker.hasCustomName(), "a worker on a named job should wear that name");
				helper.assertTrue("Smelting feed".equals(worker.getCustomName()
					.getString()), "and it should be the job's name, not " + worker.getCustomName());
				// Vanilla's own rules: shown when you are near and looking at it. A dozen workers with a
				// dozen permanent labels is a factory nobody can see.
				helper.assertTrue(!worker.isCustomNameVisible(),
					"but not floating over it permanently");

				station(helper).removeHat(0);
				helper.assertTrue(!worker.hasCustomName(),
					"and the name should go back when the job does");
			})
			.thenSucceed();
	}

	/**
	 * A villager that already had a name keeps it, and keeps it through being hired and let go.
	 *
	 * <p>Somebody may well have named a villager before it ever saw a station. Overwriting that would
	 * be taking something away — and then, on retirement, clearing a name this mod never gave.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void aVillagersOwnNameSurvivesTheJob(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		station(helper).renameJob(0, "Smelting feed");

		Villager villager = claimant(helper);
		villager.setCustomName(Component.literal("Bob"));

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1, "somebody is hired"))
			.thenExecute(() -> {
				helper.assertTrue("Bob".equals(villager.getCustomName()
					.getString()), "a villager's own name should survive being hired, and it is now "
						+ villager.getCustomName());

				station(helper).removeHat(0);
				helper.assertTrue(villager.hasCustomName() && "Bob".equals(villager.getCustomName()
					.getString()), "and survive being let go, rather than being cleared with the job's");
			})
			.thenSucceed();
	}


	/**
	 * Every state the Worker Station can be in has a model, and every model has its textures.
	 *
	 * <p>Nothing else checks this. A blockstate file missing a variant renders as the black-and-magenta
	 * cube, and a model naming a texture that is not there renders as the same thing — and both are
	 * silent, because a resource pack is loaded by the client and the tests run on a server. What a
	 * server <em>can</em> do is read its own jar and count.
	 *
	 * <p>The case that makes it worth having: adding a property to the block multiplies the number of
	 * states, and it is the blockstate file rather than the code that has to grow to match. Adding
	 * {@code facing} took the Station from two states to eight.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void everyStationStateHasAModelAndEveryModelItsTextures(GameTestHelper helper) {
		JsonObject variants = readJson(helper, "/assets/createworkers/blockstates/worker_station.json")
			.getAsJsonObject("variants");

		for (BlockState state : CWBlocks.WORKER_STATION.get()
			.getStateDefinition()
			.getPossibleStates()) {
			String key = variantKey(state);
			helper.assertTrue(variants.has(key), "no blockstate variant for " + key);

			JsonElement variant = variants.get(key);
			String model = (variant.isJsonArray() ? variant.getAsJsonArray()
				.get(0)
				.getAsJsonObject() : variant.getAsJsonObject()).get("model")
					.getAsString();
			checkModel(helper, model);
		}
		helper.succeed();
	}

	/** The key a blockstate file uses: every property as name=value, sorted by name. */
	private static String variantKey(BlockState state) {
		return state.getProperties()
			.stream()
			.map(property -> property.getName() + "=" + nameOf(state, property))
			.sorted()
			.collect(java.util.stream.Collectors.joining(","));
	}

	private static <T extends Comparable<T>> String nameOf(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	/** Follows a model to its file, checks the textures it names exist, then follows its parent. */
	private static void checkModel(GameTestHelper helper, String model) {
		if (!model.startsWith(CreateWorkers.ID + ":"))
			return; // vanilla's own, which ships with the game

		String path = model.substring(model.indexOf(':') + 1);
		JsonObject json = readJson(helper, "/assets/createworkers/models/" + path + ".json");

		if (json.has("textures"))
			for (java.util.Map.Entry<String, JsonElement> texture : json.getAsJsonObject("textures")
				.entrySet()) {
				String reference = texture.getValue()
					.getAsString();
				if (reference.startsWith("#") || !reference.startsWith(CreateWorkers.ID + ":"))
					continue;
				String file = "/assets/createworkers/textures/" + reference.substring(reference.indexOf(':') + 1)
					+ ".png";
				helper.assertTrue(exists(file), model + " names a texture that is not in the jar: " + file);
			}

		if (json.has("parent"))
			checkModel(helper, json.get("parent")
				.getAsString());
	}

	private static boolean exists(String resource) {
		try (InputStream source = WorkerStationGameTests.class.getResourceAsStream(resource)) {
			return source != null;
		} catch (IOException failure) {
			return false;
		}
	}

	private static JsonObject readJson(GameTestHelper helper, String resource) {
		try (InputStream source = WorkerStationGameTests.class.getResourceAsStream(resource)) {
			helper.assertTrue(source != null, "missing from the jar: " + resource);
			return JsonParser.parseReader(new InputStreamReader(source, StandardCharsets.UTF_8))
				.getAsJsonObject();
		} catch (IOException failure) {
			throw new IllegalStateException("could not read " + resource, failure);
		}
	}

	/**
	 * A second villager takes a job at a station that already has a worker on it.
	 *
	 * <p>The test that made the mod stop using vanilla's hiring. The station used to sit in
	 * {@code minecraft:acquirable_job_site} and let an unemployed villager find it, claim it and walk
	 * over — and every villager after the first one arrived, gave up its claim and stood there. Not a
	 * bug: {@code YieldJobSite}, villager CORE priority 8, makes a villager holding a
	 * {@code POTENTIAL_JOB_SITE} yield the moment it sees another villager whose profession already
	 * holds that same point of interest, which is exactly what a worker this station has hired is. The
	 * rule is right for vanilla, where a workstation holds one villager, and fatal here.
	 *
	 * <p>So this is deliberately end to end with real villagers rather than the {@code claimant}
	 * helper: what it is really asserting is that nothing in the villager brain gets a veto over a
	 * station's second hire.
	 */
	@GameTest(template = "work_site", timeoutTicks = 600)
	public static void aSecondVillagerTakesAJobAtAnOccupiedStation(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		station(helper).setShifts(0, Set.of(Shift.DAY, Shift.EVENING));

		helper.spawn(EntityType.VILLAGER, BESIDE_STATION);

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1,
				"the first villager should be hired"))
			.thenExecute(() -> helper.spawn(EntityType.VILLAGER, BESIDE_STATION.east()))
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.EVENING) == 1,
				"and the second should take the other shift rather than standing about, "
					+ "which it has " + (station(helper).staffed(Shift.EVENING) == 1 ? "" : "not ") + "done"))
			.thenSucceed();
	}


	/**
	 * A worker sacked from a station that is <em>still running</em> gets the village's hours back too.
	 *
	 * <p>Nothing in this mod puts a schedule back. `ResetProfession` does, through the `refreshBrain`
	 * that comes with clearing a profession — but it needs the villager to have no job site, and the
	 * two firings reach that differently. Take the last hat out and the block stops being a point of
	 * interest at all, so `ValidateNearbyPoi` erases the memory; take one hat out of several and the
	 * point of interest survives, and what erases the memory is the station itself turning the villager
	 * away for want of a vacancy.
	 *
	 * <p>Only the first was covered. A worker left on its own hours would keep a crew's clock forever —
	 * asleep at noon in a village that is awake, and nothing in the world to say why.
	 *
	 * <p>Real villagers rather than the {@code claimant} helper, deliberately: that helper holds them
	 * still with {@code setNoAi}, and a villager with no AI does not tick its brain, so none of the
	 * behaviours this is actually about would run.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void aWorkerSackedFromAStillRunningStationGetsItsHoursBack(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		putHatIn(helper, STATION);

		helper.spawn(EntityType.VILLAGER, BESIDE_STATION);
		helper.spawn(EntityType.VILLAGER, BESIDE_STATION.east());
		UUID[] sacked = new UUID[1];

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 2,
				"both jobs should be covered"))
			.thenExecute(() -> {
				sacked[0] = station(helper).jobAt(0)
					.worker(Shift.DAY);
				helper.assertTrue(((Villager) helper.getLevel()
					.getEntity(sacked[0])).getBrain()
					.getSchedule() != Schedule.VILLAGER_DEFAULT,
					"precondition: a worker keeps its own crew's hours, not the village's");
				station(helper).removeHat(0);
			})
			.thenWaitUntil(() -> {
				Villager freed = (Villager) helper.getLevel()
					.getEntity(sacked[0]);
				helper.assertTrue(freed.getVillagerData()
					.getProfession() == VillagerProfession.NONE,
					"the sacked worker should be an ordinary villager again, and it is a "
						+ freed.getVillagerData()
							.getProfession());
				helper.assertTrue(freed.getBrain()
					.getSchedule() == Schedule.VILLAGER_DEFAULT,
					"keeping the village's hours rather than a crew's");
			})
			.thenExecute(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1,
				"while the job that was left alone carries on"))
			.thenSucceed();
	}


	/**
	 * Two workers at one station keep their own crews' hours.
	 *
	 * <p>The second place vanilla insists a job site belongs to exactly one villager, and the more
	 * damaging of the two. {@code PoiCompetitorScan} — villager CORE, priority 2 — takes every villager
	 * whose {@code JOB_SITE} is the same position with a matching profession and <b>erases the loser's
	 * job site</b>, the loser being whichever has less trading experience. Every worker at a station has
	 * the same job site, the same profession and no experience at all, so they strip each other every
	 * tick.
	 *
	 * <p>Losing the job site is not the injury. What follows it is: {@code ResetProfession} wants a
	 * villager with no job site that has never traded, so it clears the profession and calls
	 * {@code refreshBrain} — and a refreshed brain is one back on {@code VILLAGER_DEFAULT}. The crew's
	 * schedule is the whole of shift work, so a night worker quietly goes back to sleeping at night.
	 *
	 * <p>Asserted on the schedules rather than on the memory, because the schedule is the thing a player
	 * would actually notice going wrong.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900)
	public static void twoWorkersAtOneStationKeepTheirOwnHours(GameTestHelper helper) {
		prepareWorkSite(helper);
		helper.setBlock(STATION, CWBlocks.WORKER_STATION.get());
		putHatIn(helper, STATION);
		station(helper).setShifts(0, Set.of(Shift.DAY, Shift.NIGHT));

		helper.spawn(EntityType.VILLAGER, BESIDE_STATION);
		helper.spawn(EntityType.VILLAGER, BESIDE_STATION.east());

		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(station(helper).staffed(Shift.DAY) == 1
				&& station(helper).staffed(Shift.NIGHT) == 1, "one villager on each crew"))
			// Long enough for a behaviour that runs every tick to have run a great many times.
			.thenExecuteAfter(100, () -> {
				for (Shift shift : Shift.VALUES) {
					UUID id = station(helper).jobAt(0)
						.worker(shift);
					if (id == null)
						continue;
					Villager worker = (Villager) helper.getLevel()
						.getEntity(id);
					helper.assertTrue(worker.getBrain()
						.getSchedule() == WorkerShift.scheduleFor(shift),
						"the " + shift + " worker should still be keeping its crew's hours");
					helper.assertTrue(worker.getVillagerData()
						.getProfession() == CWProfessions.WORKER.get(),
						"and still be a Worker, rather than having been reset out from under itself");
				}
			})
			.thenSucceed();
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
		Villager villager = helper.spawn(EntityType.VILLAGER, BESIDE_STATION);
		// Held where it stands, so a test about a rack's bookkeeping does not turn on a villager's
		// legs. Nothing else is needed to get it hired: the station finds it, path-checks it and takes
		// it on, which is the whole of hiring.
		villager.setNoAi(true);
		// And standing on the floor, which has to be said out loud. A villager with no AI never runs
		// travel(), so nothing applies gravity and nothing ever sets onGround -- and
		// PathNavigation.createPath refuses outright for a mob it believes is in mid-air, so the
		// station's path check fails and it is never hired at all. Nothing recomputes the flag for a
		// mob that does not move, which is what makes setting it here enough.
		villager.setOnGround(true);
		return villager;
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

	/**
	 * Walls the site in, because a station recruits from sixteen blocks and the test beside it is
	 * closer than that.
	 *
	 * <p>Hiring is the station's own now: it looks for any unemployed villager in range that it can
	 * path to. On a game-test grid that reaches straight into the neighbouring plot, so a test about
	 * what a station does when it is <em>short</em> of villagers would quietly be handed one of
	 * somebody else's. Since the check is a pathfind, a wall is enough to answer it — and every
	 * villager these tests care about is spawned inside.
	 */
	private static void sealPerimeter(GameTestHelper helper) {
		for (int along = -1; along <= SITE_SIZE; along++)
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(new BlockPos(along, y, -1), Blocks.POLISHED_ANDESITE);
				helper.setBlock(new BlockPos(along, y, SITE_SIZE), Blocks.POLISHED_ANDESITE);
				helper.setBlock(new BlockPos(-1, y, along), Blocks.POLISHED_ANDESITE);
				helper.setBlock(new BlockPos(SITE_SIZE, y, along), Blocks.POLISHED_ANDESITE);
			}
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
		sealPerimeter(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(TARGET, AllBlocks.DEPOT.getDefaultState());

		IItemHandler handler = helper.getLevel()
			.getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(SOURCE), null);
		if (handler == null)
			throw new IllegalStateException("no item handler at " + SOURCE);
		ItemHandlerHelper.insertItem(handler, new ItemStack(Items.COBBLESTONE, STOCK), false);
	}
}
