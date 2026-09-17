package com.createworkers.test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.createworkers.CWConfig;
import com.createworkers.CreateWorkers;
import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWItems;
import com.createworkers.worker.Shift;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.WorkerJobGoal;
import com.createworkers.worker.WorkerShift;
import com.createworkers.worker.Workers;
import com.createworkers.worker.target.WorkerTarget;
import com.simibubi.create.AllBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Working hours: knocking off, walking home, sleeping, and starting again in the morning.
 *
 * <p>Anything that depends on it being night runs in a <b>batch of its own</b>. The time of day is
 * one clock for the whole server, so a test that pushed the world into the small hours would stop
 * every other worker on it — and the game test framework runs batches strictly one after another,
 * which is the only isolation available for a thing that global. Tests that need no particular time
 * stay in the default batch, and the bed hunt is deliberately among them: it is a function of the
 * world rather than of the hour.
 */
@GameTestHolder(CreateWorkers.ID)
@PrefixGameTestTemplate(false)
public class WorkerShiftGameTests {

	private static final int SITE_SIZE = 11;
	private static final BlockPos SOURCE = new BlockPos(1, 1, 1);
	private static final BlockPos TARGET = new BlockPos(9, 1, 9);
	private static final BlockPos SPAWN = new BlockPos(5, 1, 5);

	/** A bed on the floor, a few strides from where the worker starts. */
	private static final BlockPos BED_FOOT = new BlockPos(5, 1, 2);
	private static final BlockPos BED_HEAD = new BlockPos(5, 1, 1);
	/** The same bed, hoisted into the air where nothing can walk to it. */
	private static final BlockPos AIRBORNE_FOOT = new BlockPos(5, 5, 2);
	private static final BlockPos AIRBORNE_HEAD = new BlockPos(5, 5, 1);

	private static final int STOCK = 16;
	/** Long enough to walk the length of the site, lie down, and be seen doing it. */
	private static final int COMMUTE_TICKS = 300;
	/** Long enough for a worker that was going to haul anything to have hauled it. */
	private static final int A_SHIFT_OF_WORK = 300;

	/** Midday, which is what the world is put back to for whatever runs next. */
	private static final int WORKING_HOURS_TIME = 1000;
	private static final int NIGHT_TIME = 13000;

	/** What the batch that turns working hours off found the setting at, to put it back. */
	private static boolean workingHoursWere = true;
	private static boolean recallWas = false;
	private static int wanderRadiusWas = 12;
	/** ...and the same for the batch that inverts the hours onto a night shift. */
	private static int clockOnWas = 0;
	private static int clockOffWas = 12000;

	/** A sealed box in the far corner, for a worker that genuinely cannot walk anywhere. */
	private static final BlockPos CELL = new BlockPos(9, 1, 1);
	/** Small enough that the cell is off station, which an 11-block site cannot manage at the default. */
	private static final int TIGHT_WANDER_RADIUS = 4;
	/** Three failures at a growing rest, plus the attempts between them, with room to spare. */
	private static final int LONG_ENOUGH_TO_GIVE_UP_THRICE = 3000;

	/** A night shift: on at dusk, off at dawn-ish, so its off-hours are broad daylight. */
	private static final int NIGHT_SHIFT_ON = 12000;
	private static final int NIGHT_SHIFT_OFF = 6000;
	/** Mid-morning: the middle of a night-shift worker's rest, and nowhere near the village's. */
	private static final int MID_MORNING = 9000;

	@BeforeBatch(batch = "night")
	public static void nightFalls(ServerLevel level) {
		level.setDayTime(NIGHT_TIME);
	}

	@AfterBatch(batch = "night")
	public static void morningComes(ServerLevel level) {
		level.setDayTime(WORKING_HOURS_TIME);
	}

	/**
	 * The same night, with working hours switched off — which is the promise the config makes, so it
	 * is worth a test rather than an inspection of the branch that reads it. Its own batch because
	 * the setting, like the time of day, is one value for the whole server.
	 */
	@BeforeBatch(batch = "night_off_the_clock")
	public static void nightFallsOnAWorldThatIgnoresIt(ServerLevel level) {
		level.setDayTime(NIGHT_TIME);
		workingHoursWere = CWConfig.WORKING_HOURS.get();
		CWConfig.WORKING_HOURS.set(false);
	}

	@AfterBatch(batch = "night_off_the_clock")
	public static void restoreWorkingHours(ServerLevel level) {
		level.setDayTime(WORKING_HOURS_TIME);
		// What the world came in with, rather than what the default happens to be today.
		CWConfig.WORKING_HOURS.set(workingHoursWere);
	}

	/**
	 * The one test that moves the clock from inside itself puts it back on the way out — but only on
	 * the path where it succeeds. This is the other path: a failure or a timeout would otherwise leave
	 * every batch scheduled after this one running at midnight, and the whole suite would fail behind
	 * the one test that actually broke.
	 */
	@AfterBatch(batch = "dawn")
	public static void dawnBreaksWhateverHappened(ServerLevel level) {
		level.setDayTime(WORKING_HOURS_TIME);
	}

	/**
	 * The hours inverted onto a night shift, with the world in broad daylight — which is the one
	 * arrangement that could not work before workers carried a schedule of their own.
	 */
	@BeforeBatch(batch = "night_shift")
	public static void invertTheHours(ServerLevel level) {
		clockOnWas = CWConfig.CLOCK_ON.get();
		clockOffWas = CWConfig.CLOCK_OFF.get();
		CWConfig.CLOCK_ON.set(NIGHT_SHIFT_ON);
		CWConfig.CLOCK_OFF.set(NIGHT_SHIFT_OFF);
		level.setDayTime(MID_MORNING);
	}

	@AfterBatch(batch = "night_shift")
	public static void restoreTheHours(ServerLevel level) {
		CWConfig.CLOCK_ON.set(clockOnWas);
		CWConfig.CLOCK_OFF.set(clockOffWas);
		level.setDayTime(WORKING_HOURS_TIME);
	}

	/**
	 * A world that teleports workers it cannot get home, and a wander radius tight enough that the
	 * test site can put one outside it.
	 */
	@BeforeBatch(batch = "recall")
	public static void allowRecalls(ServerLevel level) {
		recallWas = CWConfig.RECALL_STUCK_WORKERS.get();
		wanderRadiusWas = CWConfig.WANDER_RADIUS.get();
		CWConfig.RECALL_STUCK_WORKERS.set(true);
		CWConfig.WANDER_RADIUS.set(TIGHT_WANDER_RADIUS);
	}

	@AfterBatch(batch = "recall")
	public static void restoreRecalls(ServerLevel level) {
		CWConfig.RECALL_STUCK_WORKERS.set(recallWas);
		CWConfig.WANDER_RADIUS.set(wanderRadiusWas);
	}

	// --- the clock ---------------------------------------------------------------------------

	/**
	 * The leash waits longer each time it fails to get a worker home.
	 *
	 * <p>Asserted as arithmetic rather than by watching a worker for ten thousand ticks, which is what
	 * the growth is for: each attempt is {@code pathTimeout} ticks of pinning a walk target that the
	 * sink re-paths for whenever it is not already following one, and at a flat rest that is a quarter
	 * of every tick of a stuck worker's life spent pathfinding somewhere it cannot reach.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theLeashWaitsLongerEachTimeItFails(GameTestHelper helper) {
		int first = WorkerJobGoal.restAfter(1);
		helper.assertTrue(first > 0, "a first failure should still rest");
		helper.assertTrue(WorkerJobGoal.restAfter(2) > first, "a second failure should wait longer than the first");
		helper.assertTrue(WorkerJobGoal.restAfter(3) > WorkerJobGoal.restAfter(2), "and a third longer again");

		// Capped, so the leash never stops trying altogether -- a door opened at midnight should
		// still be noticed before morning.
		int capped = WorkerJobGoal.restAfter(1000);
		helper.assertTrue(capped == WorkerJobGoal.restAfter(10000), "the wait should be capped, not unbounded");
		helper.assertTrue(capped < WorkerShift.DAY_LENGTH, "and the cap should be well under a day");

		// Never negative or zero however it is asked, since a rest of nothing is the busy loop this
		// whole mechanism exists to avoid.
		helper.assertTrue(WorkerJobGoal.restAfter(0) > 0, "even an uncounted failure should rest");
		helper.succeed();
	}

	/**
	 * A worker's schedule is its own hours, not the village's.
	 *
	 * <p>Asserted on the bare schedule, so no world and no villager are involved. The thing being
	 * checked is that {@code REST} lands on the worker's off-shift window whenever that is — which is
	 * what lets {@code WakeUp} and this mod agree instead of fighting, because vanilla's rule is
	 * "asleep and not in REST gets stood up" and under this schedule a resting worker is in REST.
	 *
	 * <p>A vanilla schedule is asked the same question at the same hour, to show the two genuinely
	 * differ rather than the test having proved nothing.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void aWorkerKeepsItsOwnHoursNotTheVillages(GameTestHelper helper) {
		Schedule dayShift = WorkerShift.workerSchedule(0, 12000);
		helper.assertTrue(dayShift != null, "a day shift should have a schedule");
		helper.assertTrue(dayShift.getActivityAt(6000) == Activity.IDLE, "noon is working time on a day shift");
		helper.assertTrue(dayShift.getActivityAt(18000) == Activity.REST, "midnight is not");

		// The interesting one: on at dusk, off at dawn, so its rest is in broad daylight.
		Schedule nightShift = WorkerShift.workerSchedule(NIGHT_SHIFT_ON, NIGHT_SHIFT_OFF);
		helper.assertTrue(nightShift != null, "a night shift should have a schedule");
		helper.assertTrue(nightShift.getActivityAt(18000) == Activity.IDLE, "midnight is working time on nights");
		helper.assertTrue(nightShift.getActivityAt(MID_MORNING) == Activity.REST,
			"a night-shift worker should be resting mid-morning, which is the whole point");
		// Before the first keyframe the timeline wraps to the last, which is how a shift crosses
		// midnight at all: 3000 is inside a window that opened at 12000 the previous day.
		helper.assertTrue(nightShift.getActivityAt(3000) == Activity.IDLE, "the small hours are still the shift");

		helper.assertTrue(Schedule.VILLAGER_DEFAULT.getActivityAt(MID_MORNING) != Activity.REST,
			"precondition: the village is awake mid-morning, so the two schedules really do differ");

		helper.assertTrue(WorkerShift.workerSchedule(4000, 4000) == null,
			"a shift that never ends has no resting half to build");
		helper.succeed();
	}

	/**
	 * Hiring hands the schedule over, and retiring gives the village's back.
	 *
	 * <p>The order is the trap: {@code refreshBrain} rebuilds the brain and sets
	 * {@code VILLAGER_DEFAULT}, so a schedule applied before it is thrown away a line later.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void hiringGivesAWorkerItsOwnScheduleAndRetiringTakesItBack(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, null);

		Schedule ours = WorkerShift.scheduleFor(Shift.DAY);
		helper.assertTrue(villager.getBrain()
			.getSchedule() == ours, "a hired worker should be keeping the worker schedule");
		helper.assertTrue(villager.getBrain()
			.getSchedule() != Schedule.VILLAGER_DEFAULT, "...which is not the village's");

		// Putting the village's schedule back is vanilla's job now rather than this mod's. A
		// dismissed worker loses its job site, and ResetProfession then clears the profession and
		// refreshes the brain — which is what sets VILLAGER_DEFAULT. That path is covered where there
		// is a real job site to lose, in WorkerStationGameTests.
		retire(helper, villager);
		helper.assertTrue(!Workers.isEmployed(villager), "and retiring should take the hat back off it");
		helper.succeed();
	}

	/**
	 * The shift is a window measured from when it starts, not a comparison against dusk, which is
	 * what lets it wrap midnight — and what makes a night shift nothing more than a start time later
	 * than the end time. Asserted on the bare function, so the world's clock is not involved.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theShiftClockWrapsMidnight(GameTestHelper helper) {
		// A day shift: on at dawn, off at dusk.
		helper.assertTrue(!WorkerShift.isOffShift(0L, 0, 12000), "dawn is working time on a day shift");
		helper.assertTrue(!WorkerShift.isOffShift(11999L, 0, 12000), "the last tick before dusk is still work");
		helper.assertTrue(WorkerShift.isOffShift(12000L, 0, 12000), "dusk is the end of a day shift");
		helper.assertTrue(WorkerShift.isOffShift(23999L, 0, 12000), "the small hours are not working time");

		// The same shift, on the fourth day of the world: the clock is the time of day, not the age
		// of the world, and nothing here may drift as the days accumulate.
		helper.assertTrue(WorkerShift.isOffShift(3L * WorkerShift.DAY_LENGTH + 13000L, 0, 12000),
			"the fourth night should be night too");

		// A night shift, which is the same window turned around and therefore has to wrap.
		helper.assertTrue(!WorkerShift.isOffShift(13000L, 12000, 6000), "a night shift works at midnight");
		helper.assertTrue(!WorkerShift.isOffShift(0L, 12000, 6000), "...and through dawn, because it wraps");
		helper.assertTrue(WorkerShift.isOffShift(8000L, 12000, 6000), "a night shift is off duty in the morning");

		// Clocking off at the moment you clock on is read as a day that never ends, because that is
		// the reading in which a misconfigured pair still moves items.
		helper.assertTrue(!WorkerShift.isOffShift(18000L, 4000, 4000), "a zero-length shift should never end");
		helper.succeed();
	}



	/**
	 * The shipped working day is short enough that the three crews tile the clock instead of
	 * overlapping.
	 *
	 * <p>Arithmetic rather than taste. The crews are {@link Shift#OFFSET} apart, so a working day
	 * longer than that means two of them are on at once for the difference — and the default was
	 * 12000, left over from when there was one shift and a worker simply stopped at dusk. Three
	 * villagers then bought about one and a half crews of cover, and at night the evening crew was
	 * still going when the night crew clocked on, which is what it looked like from the floor.
	 *
	 * <p>Asserted on the <b>default</b> rather than the loaded value, because what is being pinned is
	 * what a server gets before anybody edits anything.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theShippedCrewsDoNotOverlap(GameTestHelper helper) {
		int on = CWConfig.CLOCK_ON.getDefault();
		int off = CWConfig.CLOCK_OFF.getDefault();
		int span = Math.floorMod(off - on, WorkerShift.DAY_LENGTH);

		helper.assertTrue(span > 0 && span <= Shift.OFFSET,
			"a crew's working day must fit in its own third of the clock, and the default is " + span
				+ " against a " + Shift.OFFSET + " tick slot");

		// And the consequence, stated the way a player would see it: never two crews on at once.
		for (long time = 0; time < WorkerShift.DAY_LENGTH; time += 500) {
			int working = 0;
			for (Shift shift : Shift.VALUES)
				if (!WorkerShift.isOffShift(time, Math.floorMod(on + shift.offset(), WorkerShift.DAY_LENGTH),
					Math.floorMod(off + shift.offset(), WorkerShift.DAY_LENGTH)))
					working++;
			helper.assertTrue(working <= 1, working + " crews were on the clock at once at " + time);
		}
		helper.succeed();
	}

	/**
	 * The three crews keep one working day between them, each started a third of a day later.
	 *
	 * <p>A shift is an offset rather than a pair of times, which is the whole reason there is still
	 * only one pair of settings: shorten the working day and every crew's day shortens, move dawn and
	 * every crew moves with it, and no two of them can ever be made to contradict each other. What the
	 * player is left choosing is the <em>span</em>, and the consequences of that choice are visible
	 * here — a span equal to the offset tiles the clock exactly, and the default's longer one puts two
	 * crews on at the changeover, which is what stops a chain of workers stalling at the hand-over.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theThreeCrewsShareOneWorkingDayAtDifferentHours(GameTestHelper helper) {
		int on = CWConfig.CLOCK_ON.get();
		int off = CWConfig.CLOCK_OFF.get();
		int span = Math.floorMod(off - on, WorkerShift.DAY_LENGTH);

		helper.assertTrue(Shift.DAY.clockOn() == on, "the day crew keeps the hours as configured");
		for (Shift shift : Shift.VALUES) {
			helper.assertTrue(shift.clockOn() == Math.floorMod(on + shift.offset(), WorkerShift.DAY_LENGTH),
				shift + " should start a third of a day after the one before it");
			helper.assertTrue(
				Math.floorMod(shift.clockOff() - shift.clockOn(), WorkerShift.DAY_LENGTH) == span,
				shift + " should work exactly as long as every other crew");
		}

		// Three offsets of a third of a day each get back to where they started, so the crews tile the
		// clock rather than drifting round it.
		helper.assertTrue(Shift.VALUES.length * Shift.OFFSET == WorkerShift.DAY_LENGTH,
			"three crews at a third of a day apart should cover the day exactly");

		// And the point of the whole thing: at the moment one crew clocks on, another has not.
		long changeover = Shift.EVENING.clockOn();
		helper.assertTrue(!WorkerShift.isOffShift(changeover, Shift.EVENING),
			"the evening crew is on the clock when its shift begins");
		helper.assertTrue(WorkerShift.isOffShift(changeover, Shift.NIGHT),
			"while the night crew is not, or there would be no shifts at all");
		helper.succeed();
	}

	/**
	 * The Ponder scene's plate really does have a bed in it, and Minecraft really can read the file
	 * the generator wrote.
	 *
	 * <p>The only headless check there is on a scene. Ponder is client-side — these tests run on a
	 * dedicated server, where none of that code loads — so the storyboard itself can only be reviewed
	 * by watching it. What can be checked is the thing under it: the plate is generated by a script
	 * that writes NBT by hand, the bed was the first block in it to need block-state properties, and
	 * a palette entry written wrong is a file that fails to parse the first time a player holds W
	 * over a Hard Hat and never before.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theWorkingHoursPlateHasABedInIt(GameTestHelper helper) {
		CompoundTag tag;
		try (InputStream source = WorkerShiftGameTests.class
			.getResourceAsStream("/assets/createworkers/ponder/working_hours.nbt")) {
			helper.assertTrue(source != null, "the working-hours ponder plate should be in the jar");
			tag = NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap());
		} catch (IOException failure) {
			throw new IllegalStateException("could not read the working-hours ponder plate", failure);
		}

		StructureTemplate plate = new StructureTemplate();
		plate.load(helper.getLevel()
			.holderLookup(Registries.BLOCK), tag);

		helper.assertTrue(plate.getSize()
			.getX() > 0, "a plate that parsed to nothing is a plate that will render as nothing");
		List<StructureTemplate.StructureBlockInfo> bed =
			plate.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.RED_BED);
		helper.assertTrue(bed.size() == 2, "a bed is two blocks; the plate has " + bed.size());

		boolean head = false;
		boolean foot = false;
		for (StructureTemplate.StructureBlockInfo half : bed) {
			// Properties are the point: without them both halves parse as the same default state, and
			// a bed with two heads and no foot erases itself the moment anything touches it.
			BedPart part = half.state()
				.getValue(BedBlock.PART);
			head |= part == BedPart.HEAD;
			foot |= part == BedPart.FOOT;
		}
		helper.assertTrue(head && foot, "the plate's bed should have one head and one foot");
		helper.succeed();
	}

	// --- finding a bed -----------------------------------------------------------------------

	/** A bed near the job site, with a floor between, is one a worker will take for itself. */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void bedsAreFoundNearTheJobSite(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager, null);

		helper.succeedWhen(() -> {
			forgetTheVillageBed(villager);
			BlockPos bed = WorkerShift.findBed(villager, data);
			helper.assertTrue(bed != null, "a worker should find the bed laid out beside its job site");
			helper.assertTrue(helper.absolutePos(BED_HEAD)
				.equals(bed), "it should sleep at the head of the bed, which is the half a village knows about");
		});
	}

	/**
	 * Both halves of a bed can find each other.
	 *
	 * <p>A bed is two blocks, and everything mechanical here keeps only the head — it is the point of
	 * interest, the block a sleeper lies at, and what a click is normalised to. Anything a player
	 * looks at needs the pair, though, and the outline drawn as a bed is assigned got exactly this
	 * wrong once: a box around the stored position is a box around half a bed.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void bothHalvesOfABedKnowAboutEachOther(GameTestHelper helper) {
		layFloor(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		ServerLevel level = helper.getLevel();
		BlockPos head = helper.absolutePos(BED_HEAD);
		BlockPos foot = helper.absolutePos(BED_FOOT);

		helper.assertTrue(foot.equals(WorkerShift.otherHalfOfBed(level, head)),
			"the head should find its foot, and found " + WorkerShift.otherHalfOfBed(level, head));
		helper.assertTrue(head.equals(WorkerShift.otherHalfOfBed(level, foot)),
			"the foot should find its head, and found " + WorkerShift.otherHalfOfBed(level, foot));
		helper.assertTrue(head.equals(WorkerShift.bedHead(level, foot)),
			"a click on the foot is a click on the bed, and belongs to the head");

		// Nothing else is half of anything.
		helper.assertTrue(WorkerShift.otherHalfOfBed(level, helper.absolutePos(SPAWN)) == null,
			"a patch of floor has no other half");

		// And half a bed is not a bed. Pulling the foot out takes the head with it — vanilla erases a
		// bed half the moment its partner is gone, which is also why placeBed lays them in the order
		// it does — so there is nothing left on either side to pair with.
		level.setBlock(foot, Blocks.AIR.defaultBlockState(), 3);
		helper.assertTrue(!(level.getBlockState(head)
			.getBlock() instanceof BedBlock), "vanilla should have taken the widowed head with it");
		helper.assertTrue(WorkerShift.otherHalfOfBed(level, head) == null, "leaving nothing to pair with");
		helper.succeed();
	}

	/**
	 * A bed the village has already put down as this worker's is the one it takes, and it is taken as
	 * given — vanilla found it a path and took a ticket on it when it handed it over, and the ticket
	 * is released by the same code for every other villager.
	 *
	 * <p>Proved with a bed nothing could path to, because that is the only way to tell this answer
	 * apart from the hunt that would otherwise follow it.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theBedTheVillageGaveThemIsTakenAsGiven(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, AIRBORNE_FOOT, AIRBORNE_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager, null);
		BlockPos bed = helper.absolutePos(AIRBORNE_HEAD);

		helper.runAfterDelay(20, () -> {
			villager.getBrain()
				.setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel()
					.dimension(), bed));
			helper.assertTrue(bed.equals(WorkerShift.findBed(villager, data)),
				"a worker should sleep in the bed the village has down as its own");
			helper.succeed();
		});
	}

	/**
	 * A bed nobody assigned has to come with a path that reaches it.
	 *
	 * <p>This is the whole reason the hunt is not a proximity search. The bed here is four blocks
	 * above the floor: nearer to the job site than most beds a worker would ever be given, and
	 * completely unreachable. Vanilla's own {@code NearestBedSensor} would hand it over, which is how
	 * you get a villager staring at a bed across a gap it cannot cross.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void anUnreachableBedIsNeverChosen(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, AIRBORNE_FOOT, AIRBORNE_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager, null);

		BlockPos bed = helper.absolutePos(AIRBORNE_HEAD);
		helper.runAfterDelay(20, () -> {
			forgetTheVillageBed(villager);

			// Precondition, and the whole weight of the test: this bed really is a candidate the hunt
			// considered and turned down, rather than one it never saw. Named exactly, because game
			// tests run side by side and the beds other tests lay out are real beds in the same world.
			helper.assertTrue(isKnownToTheVillage(helper, bed, data),
				"precondition: the bed to be refused should be a home the search can see at all");
			helper.assertTrue(!bed.equals(WorkerShift.findBed(villager, data)),
				"a bed with no path to it must not be chosen, however close it looks");
			helper.succeed();
		});
	}

	/**
	 * A bed named on the hat is trusted where a discovered one is proved, and the asymmetry is the
	 * design rather than an oversight: a player who clicked a bed took responsibility for the route,
	 * exactly as they do for every inventory they assign. What still refuses it is the bed itself
	 * being unusable — gone, or somebody else already in it.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void aDesignatedBedIsTrustedWhereADiscoveredOneIsProved(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, AIRBORNE_FOOT, AIRBORNE_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		BlockPos bed = helper.absolutePos(AIRBORNE_HEAD);
		WorkerData data = employ(helper, villager, bed);

		helper.runAfterDelay(20, () -> {
			helper.assertTrue(bed.equals(WorkerShift.findBed(villager, data)),
				"a bed on the hat should be taken as given, not path-checked out of existence");

			// Someone else is in it. Both of the assertions below now say "and there was nothing else
			// to fall back to", which is true here because this plate's only bed is the one in the air
			// -- see aNamedBedThatIsGoneFallsBackToTheHunt for the other half of that rule.
			helper.getLevel()
				.setBlock(bed, helper.getLevel()
					.getBlockState(bed)
					.setValue(BedBlock.OCCUPIED, true), 3);
			helper.assertTrue(WorkerShift.findBed(villager, data) == null,
				"an occupied bed is nobody else's to sleep in");

			// And gone entirely.
			helper.getLevel()
				.setBlock(bed, Blocks.AIR.defaultBlockState(), 3);
			helper.assertTrue(WorkerShift.findBed(villager, data) == null, "a bed that was torn down is no bed");
			helper.succeed();
		});
	}

	/**
	 * A named bed that has been torn down is not a life sentence.
	 *
	 * <p>The hat still names it, and nothing a player can do removes that once the block is gone, so a
	 * worker that refused to look any further would stand in the dark for the rest of the world's life
	 * with a perfectly good bed beside it. An instruction that cannot be followed falls back to the
	 * hunt a worker with no bed at all gets.
	 */
	@GameTest(template = "work_site", timeoutTicks = 300)
	public static void aNamedBedThatIsGoneFallsBackToTheHunt(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		// Named on the hat: a bed in mid-air, which is then never built.
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		WorkerData data = employ(helper, villager, helper.absolutePos(AIRBORNE_HEAD));

		helper.succeedWhen(() -> {
			forgetTheVillageBed(villager);
			BlockPos found = WorkerShift.findBed(villager, data);
			helper.assertTrue(helper.absolutePos(BED_HEAD)
				.equals(found), "a worker whose named bed does not exist should find the one that does, not "
					+ found);
		});
	}

	/**
	 * The bed rides along with the programme, is bound by the same spread rule as the inventories,
	 * and does not move the job site. That last part is the one worth guarding: the job site is the
	 * anchor for the wander leash, so a bed that dragged it would have an on-shift worker's whole
	 * patch creeping towards the bedroom.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void theBedRidesAlongWithTheProgramme(GameTestHelper helper) {
		prepareWorkSite(helper);
		WorkerTarget in = target(helper, SOURCE);
		in.cycleMode();
		WorkerProgram plain = WorkerProgram.of(List.of(in, target(helper, TARGET)));
		BlockPos bed = helper.absolutePos(BED_HEAD);
		WorkerProgram withBed = plain.withBed(bed);

		helper.assertTrue(bed.equals(withBed.bed()), "the bed should be stored on the programme");
		helper.assertTrue(withBed.size() == plain.size(), "a bed is not an inventory and must not be counted as one");
		helper.assertTrue(withBed.centre()
			.equals(plain.centre()), "the bed must not move the job site");
		helper.assertTrue(withBed.hasTargets(), "a hat with inventories still has work to do");
		helper.assertTrue(!WorkerProgram.EMPTY.withBed(bed)
			.hasTargets(), "a bed on its own is somewhere to sleep, not a job");

		// Through the item and back, the way a hat that was dropped and picked up again goes.
		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(hat, withBed);
		helper.assertTrue(bed.equals(HardHatItem.getProgram(hat)
			.bed()), "the bed should survive a round trip through the item");

		// And it is inside the spread rule rather than outside it: the commute is part of the beat.
		int spread = CWConfig.MAX_TARGET_SPREAD.get();
		helper.assertTrue(!withBed.exceedsSpread(spread), "a bed beside the work is within the spread");
		helper.assertTrue(plain.withBed(bed.offset(spread * 2, 0, 0))
			.exceedsSpread(spread), "a bed half a world away is not, and the spread rule has to say so");
		helper.succeed();
	}

	// --- the night itself --------------------------------------------------------------------

	/** The headline: at the end of the day a worker walks to the bed on its hat and gets into it. */
	@GameTest(template = "work_site", timeoutTicks = 500, batch = "night")
	public static void workersSleepInTheBedOnTheirHat(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, helper.absolutePos(BED_HEAD));

		BlockPos bed = helper.absolutePos(BED_HEAD);
		helper.succeedWhen(() -> {
			// Said in full, because there are three separate ways to be standing awake next to a bed:
			// never having got there, the village not having turned in yet, and the bed being taken.
			helper.assertTrue(villager.isSleeping(),
				"a worker off the clock should be asleep in its bed, but it is " + villager.position()
					.distanceTo(bed.getCenter()) + " away from it, and the village is "
					+ (WorkerShift.isBedtime(villager) ? "" : "not ") + "at rest");
			helper.assertTrue(villager.getSleepingPos()
				.map(bed::equals)
				.orElse(false), "it should be asleep in the bed it was given, not one it found");
		});
	}

	/**
	 * A sleeping worker moves nothing. The depots are stocked and the worker has both of them on its
	 * hat, so the only thing between the cobblestone and its destination is the hour.
	 */
	@GameTest(template = "work_site", timeoutTicks = 500, batch = "night")
	public static void nothingIsHauledThroughTheNight(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, helper.absolutePos(BED_HEAD));

		helper.runAfterDelay(A_SHIFT_OF_WORK, () -> {
			helper.assertTrue(stockRemaining(helper, SOURCE) == STOCK,
				"the input should not have been touched while the worker was off the clock");
			helper.assertTrue(!hasDelivered(helper), "nothing should have been delivered overnight");
			helper.succeed();
		});
	}

	/**
	 * The Nether has no night to knock off for.
	 *
	 * <p>It has a day <em>time</em> — the overworld's, shared through the level data, which is why
	 * vanilla villagers down there go to bed at a midnight they cannot see — so this has to be a
	 * decision about the sky rather than about the clock. Asserted against the overworld at the same
	 * instant, so it is the dimension that differs and not the hour.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200, batch = "night")
	public static void theNetherHasNoNightToKnockOffFor(GameTestHelper helper) {
		ServerLevel overworld = helper.getLevel();
		ServerLevel nether = overworld.getServer()
			.getLevel(Level.NETHER);
		helper.assertTrue(nether != null, "precondition: the test server should have a Nether");

		helper.assertTrue(WorkerShift.isOffShift(overworld, Shift.DAY), "precondition: it should be night in the overworld");
		helper.assertTrue(nether.getDayTime() == overworld.getDayTime(),
			"precondition: the Nether should be keeping the overworld's clock, which is the whole trap");
		helper.assertTrue(!WorkerShift.isOffShift(nether, Shift.DAY), "nothing knocks off under a sky that never changes");
		helper.succeed();
	}

	/**
	 * Endermen are exempt, and that is a decision rather than an omission: they have no bed to walk
	 * to and no schedule to keep, and a base staffed with them is one that runs around the clock.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900, batch = "night")
	public static void endermenWorkThroughTheNight(GameTestHelper helper) {
		prepareWorkSite(helper);
		EnderMan enderman = helper.spawn(EntityType.ENDERMAN, SPAWN);
		hire(helper, enderman, null);

		helper.succeedWhen(() -> helper.assertTrue(hasDelivered(helper),
			"an enderman should still be hauling at midnight"));
	}

	/**
	 * The delivery already in a worker's hands outlives the whistle.
	 *
	 * <p>Clocking off holding a stack would take those items out of the factory until morning, with
	 * nothing on the machines to say where they went — so a worker caught mid-haul finishes the trip
	 * it is on, and only then goes to bed. It starts no new one: the input is stocked here too, and
	 * must still be stocked at the end.
	 */
	@GameTest(template = "work_site", timeoutTicks = 500, batch = "night")
	public static void aWorkerFinishesTheDeliveryInItsHands(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, helper.absolutePos(BED_HEAD));

		// Caught with a stack in hand at the moment the whistle went.
		WorkerData data = Workers.getOrCreate(villager);
		data.setHeld(new ItemStack(Items.COBBLESTONE, 4));
		data.setPhase(WorkerData.Phase.SEARCH_OUTPUTS);

		helper.succeedWhen(() -> {
			helper.assertTrue(hasDelivered(helper), "the stack in hand should have been handed over before bed");
			helper.assertTrue(stockRemaining(helper, SOURCE) == STOCK,
				"finishing a delivery is not licence to start another one");
		});
	}

	/**
	 * A worker with nowhere to sleep holds its station, which is exactly what an idle worker does.
	 * Off shift with nowhere to go is idling that does not haul: no new destinations, no new risk,
	 * and no demand that a bedroom be built before the feature stops being an irritation.
	 */
	@GameTest(template = "work_site", timeoutTicks = 500, batch = "night")
	public static void withNoBedAWorkerHoldsItsStation(GameTestHelper helper) {
		prepareWorkSite(helper);
		// Deliberately no bed anywhere.
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, null);
		BlockPos station = helper.absolutePos(SPAWN);

		helper.runAfterDelay(A_SHIFT_OF_WORK, () -> {
			helper.assertTrue(!villager.isSleeping(), "there is nothing here to sleep in");
			helper.assertTrue(villager.blockPosition()
				.closerThan(station, 4.0D),
				"a worker with nowhere to sleep should stand its ground, not go looking, and it ended up at "
					+ villager.blockPosition());
			helper.assertTrue(!hasDelivered(helper), "it is still off the clock, bed or no bed");
			helper.succeed();
		});
	}

	/**
	 * A worker holding station overnight holds the spot it knocked off at, not wherever it happens to
	 * be standing.
	 *
	 * <p>The distinction only shows when something moves the worker that is not the worker: a shove
	 * from a passing mob, or a flight from a zombie that {@code holdAt} stood aside for. An anchor
	 * re-taken every tick would simply accept the new position and hold there until morning — and the
	 * wander leash, which catches exactly that during the day, is deliberately switched off for the
	 * night. Displacing it by hand here stands in for the shove.
	 */
	@GameTest(template = "work_site", timeoutTicks = 400, batch = "night")
	public static void aWorkerHoldsTheSpotItKnockedOffAt(GameTestHelper helper) {
		prepareWorkSite(helper);
		// No bed, so holding station is the whole of the worker's night.
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, null);
		BlockPos station = helper.absolutePos(SPAWN);

		helper.startSequence()
			.thenIdle(40)
			.thenExecute(() -> {
				helper.assertTrue(villager.blockPosition()
					.closerThan(station, 2.0D), "precondition: the worker should have knocked off where it stood");
				// Shoved, as a passing mob would.
				BlockPos shoved = helper.absolutePos(new BlockPos(9, 1, 5));
				villager.moveTo(shoved.getX() + 0.5D, shoved.getY(), shoved.getZ() + 0.5D, 0, 0);
			})
			.thenIdle(200)
			.thenExecute(() -> helper.assertTrue(villager.blockPosition()
				.closerThan(station, 3.0D),
				"a shoved worker should walk back to where it knocked off, and instead held "
					+ villager.blockPosition()))
			.thenSucceed();
	}

	/**
	 * A worker gets its schedule back when it comes off the disk.
	 *
	 * <p>The easiest part of this to forget, and the one with no symptom until somebody changes the
	 * hours: a brain does not serialize its schedule, and building one — which happens on every load —
	 * sets the village's. So the reset is simulated here exactly as a load performs it, and the join
	 * event posted the way the level posts it.
	 */
	@GameTest(template = "work_site", timeoutTicks = 200)
	public static void aWorkerGetsItsScheduleBackWhenItLoads(GameTestHelper helper) {
		prepareWorkSite(helper);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, null);

		Schedule ours = WorkerShift.scheduleFor(Shift.DAY);
		helper.assertTrue(villager.getBrain()
			.getSchedule() == ours, "precondition: hiring should have set the worker schedule");

		// What registerBrainGoals does to every villager it builds, which is every villager that loads.
		villager.getBrain()
			.setSchedule(Schedule.VILLAGER_DEFAULT);
		NeoForge.EVENT_BUS.post(new EntityJoinLevelEvent(villager, helper.getLevel()));

		helper.assertTrue(villager.getBrain()
			.getSchedule() == ours, "a worker coming back from disk should be put back on its own hours");
		helper.succeed();
	}

	/**
	 * The payoff: a night-shift worker sleeps in broad daylight.
	 *
	 * <p>This could not happen before workers carried a schedule of their own. Its off-hours are the
	 * middle of the village's working day, so vanilla's {@code WakeUp} would have stood it up on the
	 * tick it lay down, and all it could do was stand beside the bed until dusk.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900, batch = "night_shift")
	public static void aNightShiftWorkerSleepsThroughTheDay(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, helper.absolutePos(BED_HEAD));

		helper.assertTrue(WorkerShift.isOffShift(helper.getLevel(), Shift.DAY),
			"precondition: a night shift should be off the clock mid-morning");
		helper.assertTrue(Schedule.VILLAGER_DEFAULT.getActivityAt(MID_MORNING) != Activity.REST,
			"precondition: the village itself is awake, so only the worker's own schedule can do this");

		BlockPos bed = helper.absolutePos(BED_HEAD);
		helper.succeedWhen(() -> helper.assertTrue(villager.isSleeping() && villager.getSleepingPos()
			.map(bed::equals)
			.orElse(false),
			"a night-shift worker should be asleep in daylight, and it is "
				+ villager.position()
					.distanceTo(bed.getCenter())
				+ " from its bed with the village "
				+ (WorkerShift.isBedtime(villager) ? "" : "not ") + "at rest"));
	}

	/**
	 * A worker that genuinely cannot walk home gives up in stages, and is fetched if the server asked
	 * for that.
	 *
	 * <p>Sealed into a box it cannot path out of, so the leash can never succeed. What is asserted is
	 * the count climbing — which is the signal a stuck worker now carries, and what the growing rest
	 * and the distress particles are both computed from — and then the recall putting it back.
	 */
	@GameTest(template = "work_site", timeoutTicks = 4000, batch = "recall")
	public static void aStuckWorkerIsCountedAndThenFetched(GameTestHelper helper) {
		// Deliberately nothing to haul. A worker with work keeps a target selected, and a worker
		// headed somewhere is not a worker the leash has any business dragging home -- so a stocked
		// site would be testing the target set-aside clock instead of this one.
		layFloor(helper);
		helper.setBlock(SOURCE, AllBlocks.DEPOT.getDefaultState());
		helper.setBlock(TARGET, AllBlocks.DEPOT.getDefaultState());

		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, null);
		WorkerData data = Workers.getOrCreate(villager);
		BlockPos jobSite = data.getJobSite();

		sealIn(helper, villager, CELL);
		helper.assertTrue(Workers.isOffStation(villager.blockPosition(), data, CWConfig.WANDER_RADIUS.get()),
			"precondition: the cell should be off station, or the leash never runs at all");

		helper.startSequence()
			.thenIdle(LONG_ENOUGH_TO_GIVE_UP_THRICE / 2)
			.thenExecute(() -> helper.assertTrue(data.leashFailures() > 0,
				"a walled-in worker should be counting its failures, and has counted "
					+ data.leashFailures()))
			.thenIdle(LONG_ENOUGH_TO_GIVE_UP_THRICE / 2)
			.thenExecute(() -> helper.assertTrue(villager.blockPosition()
				.closerThan(jobSite, 3.0D),
				"a server that asked for recalls should have had this one fetched, and it is at "
					+ villager.blockPosition() + " with " + data.leashFailures() + " failures counted"))
			.thenSucceed();
	}

	/**
	 * Morning. A batch of one, because this is the only test that moves the clock itself — and it
	 * puts the world back to working hours on the way out, for whatever runs next.
	 */
	@GameTest(template = "work_site", timeoutTicks = 900, batch = "dawn")
	public static void workersClockBackOnInTheMorning(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, helper.absolutePos(BED_HEAD));
		helper.setDayTime(NIGHT_TIME);

		helper.startSequence()
			.thenIdle(COMMUTE_TICKS)
			.thenExecute(() -> {
				helper.assertTrue(villager.isSleeping(), "the worker should be asleep before dawn breaks");
				helper.setDayTime(WORKING_HOURS_TIME);
			})
			.thenExecuteAfter(20, () -> helper.assertTrue(!villager.isSleeping(),
				"a worker should be up and about once its shift has started"))
			.thenWaitUntil(() -> helper.assertTrue(hasDelivered(helper), "and back at work"))
			.thenExecute(() -> helper.setDayTime(WORKING_HOURS_TIME))
			.thenSucceed();
	}

	/** Turning working hours off is a promise the config makes, and it is kept. */
	@GameTest(template = "work_site", timeoutTicks = 900, batch = "night_off_the_clock")
	public static void turningWorkingHoursOffKeepsThemWorking(GameTestHelper helper) {
		prepareWorkSite(helper);
		placeBed(helper, BED_FOOT, BED_HEAD);
		Villager villager = helper.spawn(EntityType.VILLAGER, SPAWN);
		hire(helper, villager, helper.absolutePos(BED_HEAD));

		helper.assertTrue(!CWConfig.WORKING_HOURS.get(), "this batch is the one where workers keep no hours");
		helper.succeedWhen(() -> {
			helper.assertTrue(!villager.isSleeping(), "nobody clocks off in a world with no working hours");
			helper.assertTrue(hasDelivered(helper), "a worker off the clock entirely should haul at midnight");
		});
	}

	// --- helpers ---

	/**
	 * Lays a bed, foot first.
	 *
	 * <p>Order matters. A bed half whose partner is missing erases itself the moment anything nudges
	 * it, and the block placed second is the one that gives the first its partner — so both halves
	 * exist, in that order, before anything else touches them.
	 */
	private static void placeBed(GameTestHelper helper, BlockPos foot, BlockPos head) {
		// FACING points from the foot towards the head, which here is one step north.
		helper.setBlock(foot, Blocks.RED_BED.defaultBlockState()
			.setValue(BedBlock.FACING, Direction.NORTH)
			.setValue(BedBlock.PART, BedPart.FOOT));
		helper.setBlock(head, Blocks.RED_BED.defaultBlockState()
			.setValue(BedBlock.FACING, Direction.NORTH)
			.setValue(BedBlock.PART, BedPart.HEAD));

		if (!(helper.getLevel()
			.getBlockState(helper.absolutePos(foot))
			.getBlock() instanceof BedBlock))
			throw new IllegalStateException("the bed's foot did not survive being laid at " + foot);
	}

	/**
	 * Takes back whatever bed the village has already given this villager.
	 *
	 * <p>The bed hunt answers from the {@code HOME} memory before it searches for anything, and an
	 * employed villager's own brain claims a home within moments of one being laid down — so a test
	 * about the search has to clear that answer first, or it is testing the memory. The ticket goes
	 * back with it: a bed this villager still holds a ticket on is one the search itself would skip.
	 */
	private static void forgetTheVillageBed(Villager villager) {
		villager.releasePoi(MemoryModuleType.HOME);
		villager.getBrain()
			.eraseMemory(MemoryModuleType.HOME);
	}

	/** Whether the point-of-interest storage has {@code bed} down as a home within the search radius. */
	private static boolean isKnownToTheVillage(GameTestHelper helper, BlockPos bed, WorkerData data) {
		return helper.getLevel()
			.getPoiManager()
			.findAllClosestFirstWithType(type -> type.is(PoiTypes.HOME), bed::equals, data.getJobSite(),
				CWConfig.BED_SEARCH_RADIUS.get(), PoiManager.Occupancy.ANY)
			.findAny()
			.isPresent();
	}

	/**
	 * Walls a worker into a one-block cell it cannot path out of: four sides at head and foot height,
	 * and a lid. A hole would not do — a villager steps up one block and climbs straight out.
	 */
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

	/** The work site's own programme, with a bed on it if one was named. */
	private static WorkerProgram program(GameTestHelper helper, BlockPos bed) {
		WorkerTarget in = target(helper, SOURCE);
		in.cycleMode(); // targets start as DEPOSIT; one cycle makes this the input
		return WorkerProgram.of(List.of(in, target(helper, TARGET)))
			.withBed(bed);
	}

	/** Employs a mob directly, for the tests that are about the data rather than about hiring. */
	private static WorkerData employ(GameTestHelper helper, Mob mob, BlockPos bed) {
		WorkerData data = Workers.getOrCreate(mob);
		data.employ(new ItemStack(CWItems.HARD_HAT.get()), program(helper, bed));
		data.resolvePoints(mob);
		return data;
	}

	/**
	 * Hires a mob the way a player does, through the interact event — which is what puts a villager
	 * on the worker profession and rebuilds its brain around it. The night behaviour reads that
	 * brain, so going the short way round here would be testing a villager nobody ever hired.
	 */
	private static void hire(GameTestHelper helper, Mob mob, BlockPos bed) {
		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		HardHatItem.setProgram(hat, program(helper, bed));
		Workers.employ(mob, hat, HardHatItem.getProgram(hat), null, Shift.DAY);
		helper.assertTrue(Workers.isEmployed(mob), "the mob should have been hired");
	}

	private static void retire(GameTestHelper helper, Mob mob) {
		Workers.dismiss(mob);
		helper.assertTrue(!Workers.isEmployed(mob), "the mob should have been retired");
	}

	private static int stockRemaining(GameTestHelper helper, BlockPos relative) {
		IItemHandler handler = handlerAt(helper, relative);
		if (handler == null)
			return 0;
		int found = 0;
		for (int slot = 0; slot < handler.getSlots(); slot++)
			if (handler.getStackInSlot(slot)
				.is(Items.COBBLESTONE))
				found += handler.getStackInSlot(slot)
					.getCount();
		return found;
	}

	private static boolean hasDelivered(GameTestHelper helper) {
		return stockRemaining(helper, TARGET) > 0;
	}
}
