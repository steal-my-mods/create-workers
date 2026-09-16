package com.createworkers.worker;

import java.util.Set;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.entity.schedule.ScheduleBuilder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Path;

/**
 * The working day: when a worker is on the clock, and where it spends the night.
 *
 * <p>Two clocks are involved and they answer different questions. {@link #isOffShift} is the
 * operator's, configured in ticks of the day, and it decides when the tools go down — it is also the
 * only one an enderman has, having no brain schedule at all. {@link #isBedtime} reads the villager's
 * own brain, and it decides when a worker may actually lie down.
 *
 * <p>They no longer <em>disagree</em>, which they used to. Vanilla's {@code WakeUp} — CORE, priority
 * 0 — stands up any sleeping villager whose brain is not in {@code REST}, every tick, so a worker
 * whose hours were not the village's could never sleep. {@link #applySchedule} settles that by giving
 * each worker a schedule of its own whose {@code REST} window <em>is</em> its off-shift hours, so the
 * two clocks agree by construction rather than by coincidence. What {@link #isBedtime} still adds is
 * everything else vanilla knows about lying down: a panicking villager is in {@code PANIC} rather
 * than {@code REST}, and one just dragged out of bed waits before climbing back in.
 */
public final class WorkerShift {

	/** Ticks in a Minecraft day. */
	public static final int DAY_LENGTH = 24000;

	/**
	 * How many beds one search will path at. Vanilla's own bed hunt uses five, and the figure is a
	 * cost bound rather than a taste: {@code findPathToPois} is a single A* over the whole set, but
	 * every candidate widens the search it has to satisfy.
	 */
	private static final int BED_CANDIDATES = 5;

	/**
	 * How long after being woken a worker refuses to lie back down. Vanilla's {@code SleepInBed}
	 * keeps the same cooldown, so a villager pulled out of bed by a zombie does not simply climb back
	 * in the moment the fright passes.
	 */
	private static final int WOKEN_COOLDOWN_TICKS = 100;

	/**
	 * The schedule each crew keeps on the current hours, rebuilt when those hours change.
	 *
	 * <p>Three objects serve every worker on the server, because the hours are one server-wide pair of
	 * settings and a shift is only an offset into them. Cached rather than rebuilt per worker: a
	 * {@code Schedule} is immutable once built, and handing the same one to a thousand brains is a
	 * thousand field writes rather than a thousand allocations. Rebuilt only when the config they were
	 * built from has moved under them, which a config reload can do at any time.
	 */
	private static final Schedule[] cachedSchedules = new Schedule[Shift.VALUES.length];
	private static int cachedClockOn = -1;
	private static int cachedClockOff = -1;

	private WorkerShift() {
	}

	/**
	 * Gives a worker a schedule of its own, whose {@code REST} window is its own off-shift hours.
	 *
	 * <p>This is what lets a worker sleep at an hour the village does not. {@code WakeUp} — villager
	 * CORE, priority 0 — stands up any sleeping villager whose brain is not in {@code REST}, and the
	 * answer is not to fight it but to agree with it: give the worker a schedule under which its own
	 * off-hours <em>are</em> {@code REST}, and the two can never disagree. {@link #isBedtime} is
	 * unchanged by this; what changes is the schedule it reads.
	 *
	 * <p>Must be re-applied on <b>every load</b>, not only on hiring. A brain does not serialize its
	 * schedule — the codec carries memories — and {@code Villager.registerBrainGoals} sets
	 * {@code VILLAGER_DEFAULT} on every construction, which includes every time the chunk comes back.
	 *
	 * <p>Nothing to undo on retirement: {@code refreshBrain} puts the village's own schedule back.
	 */
	public static void applySchedule(Mob mob) {
		WorkerData data = Workers.get(mob);
		applySchedule(mob, data == null ? Shift.DAY : data.getShift());
	}

	/** The same, for a crew named rather than read off the worker. */
	public static void applySchedule(Mob mob, Shift shift) {
		if (!(mob instanceof Villager villager))
			return;
		// With hours switched off a worker never clocks off, so it should keep the village's schedule
		// and behave like any other villager -- not hold a REST window nothing will ever consult.
		if (!CWConfig.WORKING_HOURS.get())
			return;

		Schedule schedule = scheduleFor(shift);
		if (schedule != null)
			villager.getBrain()
				.setSchedule(schedule);
	}

	/**
	 * This crew's schedule on the current hours, built once and handed to every worker on it.
	 *
	 * <p>All three are thrown away together when the hours move, because they are all derived from the
	 * same pair of settings — there is no state in which one of them is stale and another is not.
	 */
	@Nullable
	public static Schedule scheduleFor(Shift shift) {
		int clockOn = CWConfig.CLOCK_ON.get();
		int clockOff = CWConfig.CLOCK_OFF.get();
		if (cachedClockOn != clockOn || cachedClockOff != clockOff) {
			java.util.Arrays.fill(cachedSchedules, null);
			cachedClockOn = clockOn;
			cachedClockOff = clockOff;
		}
		if (cachedSchedules[shift.ordinal()] == null)
			cachedSchedules[shift.ordinal()] = workerSchedule(shift.clockOn(), shift.clockOff());
		return cachedSchedules[shift.ordinal()];
	}

	/**
	 * The two-state schedule for a shift running {@code clockOn} to {@code clockOff}: awake for the
	 * shift, resting for the rest of the day.
	 *
	 * <p>Two transitions is a shape vanilla itself ships — {@code Schedule.SIMPLE} is exactly this —
	 * so nothing here is a trick. {@code IDLE} rather than {@code WORK} for the waking half because
	 * both are inert for a worker and {@code IDLE} is the quieter of the two: {@code WORK} runs
	 * {@code WorkAtPoi}, which wants a {@code JOB_SITE} memory a worker does not have, while the idle
	 * package's wanderers all need {@code WALK_TARGET} absent, which the job goal never allows.
	 *
	 * @return the schedule, or null for a shift that never ends and so has no resting half.
	 */
	@Nullable
	public static Schedule workerSchedule(int clockOn, int clockOff) {
		if (Math.floorMod(clockOff - clockOn, DAY_LENGTH) == 0)
			return null;

		return new ScheduleBuilder(new Schedule()).changeActivityAt(clockOn, Activity.IDLE)
			.changeActivityAt(clockOff, Activity.REST)
			.build();
	}

	/**
	 * @return whether workers are off the clock right now.
	 *
	 * <p>Nowhere with a fixed sky ever knocks off. The Nether and the End have no day to end, but they
	 * do have a day <em>time</em> — it is the overworld's, shared through the level data, and vanilla
	 * villagers down there keep to it and go to bed at a midnight they cannot see. For a villager
	 * that is a curiosity; for something wired into a factory it is the exact failure this feature was
	 * warned about, a line that stops for reasons invisible from the machine. So it does not stop.
	 */
	public static boolean isOffShift(Level level, Shift shift) {
		if (level.dimensionType()
			.hasFixedTime())
			return false;
		return isOffShift(level.getDayTime(), shift.clockOn(), shift.clockOff());
	}

	/**
	 * The clock on its own, so it can be reasoned about — and tested — without a world.
	 *
	 * <p>Everything is measured from {@code clockOn} rather than from dawn, which is what lets the
	 * shift wrap midnight without a special case, and what makes a night shift nothing more than a
	 * {@code clockOn} later than the {@code clockOff}. A span of zero is a shift that ends at the
	 * moment it begins; read as a day that never ends rather than one that never starts, because
	 * that is the reading in which a misconfigured clock still moves items.
	 */
	public static boolean isOffShift(long dayTime, Shift shift) {
		return isOffShift(dayTime, shift.clockOn(), shift.clockOff());
	}

	/** The same, with the two ends of the shift named rather than read from the config. */
	public static boolean isOffShift(long dayTime, int clockOn, int clockOff) {
		int span = Math.floorMod(clockOff - clockOn, DAY_LENGTH);
		if (span == 0)
			return false;
		return Math.floorMod(dayTime - clockOn, (long) DAY_LENGTH) >= span;
	}

	/**
	 * Whether the worker may lie down now — which is a question about the village's clock, not the
	 * operator's, and must agree exactly with vanilla's {@code WakeUp} or the two fight every tick.
	 *
	 * <p>The panic case comes free out of that agreement. A villager fleeing a zombie is in
	 * {@code PANIC}, not {@code REST}, so this says no for as long as the fright lasts — and the
	 * recently-woken cooldown, which is vanilla's own, stops a worker dragged out of bed from
	 * climbing straight back into it.
	 */
	public static boolean isBedtime(Mob mob) {
		Brain<?> brain = mob.getBrain();
		if (!brain.isActive(Activity.REST))
			return false;
		if (!brain.hasMemoryValue(MemoryModuleType.LAST_WOKEN))
			return true;
		long woken = brain.getMemory(MemoryModuleType.LAST_WOKEN)
			.orElse(0L);
		long since = mob.level()
			.getGameTime() - woken;
		// Strictly negative, not zero. A worker woken on this very tick -- a zombie that hurt it
		// earlier in the same tick's entity loop -- still reads as being in REST here, because goals
		// run before the brain and the brain has not yet switched to PANIC. Admitting zero puts it
		// straight back into bed for a tick, which is exactly what the cooldown exists to stop.
		// Negative is the clock having been moved backwards under us, which no cooldown can mean.
		return since < 0L || since >= WOKEN_COOLDOWN_TICKS;
	}

	/**
	 * Somewhere for this worker to sleep tonight, or null if there is nowhere it can prove it can get
	 * to. The caller is expected to pace this: it is a POI query and, for a bed nobody assigned, a
	 * pathfind.
	 *
	 * <p>Three places are asked, in this order, and the differences between them are the design.
	 *
	 * <p><b>The bed on the hat</b> was clicked by a player who took responsibility for the route,
	 * exactly as they do for every inventory they assign, so it is trusted: the only thing checked is
	 * that it is still a bed nobody is in. If the walk turns out to be impossible the commute's own
	 * stall clock gives up on it, the same way an unreachable depot is set aside.
	 *
	 * <p><b>The bed the village has already given this worker</b> — its {@code HOME} memory — is the
	 * best answer when there is one, and costs a memory read. Vanilla's {@code AcquirePoi} put it
	 * there, which means it came with a path that reached it and with a ticket taken and released by
	 * the same code that does so for every other villager. A worker sleeping in its own registered
	 * home is one that is not quietly squatting in somebody else's. It is held to
	 * {@code bedSearchRadius} like anything else, though, because that setting is what bounds the
	 * commute, and vanilla acquires a home by its own reckoning of what is near rather than by ours.
	 *
	 * <p><b>A bed nobody has claimed</b>, last. This one has no provenance of its own, and proximity
	 * is not a substitute for it — a bed six blocks away across a gap is further, in the only sense
	 * that matters, than one forty blocks along a corridor. So it has to come with a path that
	 * reaches it, which is what {@code AcquirePoi.findPathToPois} returns. No ticket is taken on it:
	 * a worker that claimed one and then died, unloaded or was retired would leave it ticketed to
	 * nobody for the rest of the world's life, which is the trap hiring already has to step around
	 * with workstations. Asking only for beds that still have space is what keeps this from becoming
	 * a worker moving into a villager's bedroom — the worker's own bed, if it has one, was already
	 * answered above — and the bed's {@code OCCUPIED} flag settles the last of it, so two workers who
	 * pick the same bed do not both get into it.
	 */
	@Nullable
	public static BlockPos findBed(Mob mob, WorkerData data) {
		// On the account before any branch can return: what this costs is the number of times it is
		// asked, and a worker with nowhere to sleep is the one that asks most.
		data.countBedSearch();
		Level level = mob.level();

		BlockPos designated = data.getProgram()
			.bed();
		if (designated != null && isUsableBed(level, designated, mob))
			return designated;
		// A named bed that has been mined, or that somebody else is in tonight, is not an instruction
		// that can be followed -- and refusing to look any further would leave a worker whose bedroom
		// was rebuilt standing in the dark for the rest of the world's life. So it falls through to
		// the same hunt a worker with no bed at all gets.

		int radius = CWConfig.BED_SEARCH_RADIUS.get();
		if (radius <= 0 || !(level instanceof ServerLevel server))
			return null;

		BlockPos home = registeredHome(mob, level);
		if (home != null && home.closerThan(data.getJobSite(), radius) && isUsableBed(level, home, mob))
			return home;

		Set<Pair<Holder<PoiType>, BlockPos>> candidates = server.getPoiManager()
			.findAllClosestFirstWithType(type -> type.is(PoiTypes.HOME), pos -> isUsableBed(level, pos, mob),
				data.getJobSite(), radius, PoiManager.Occupancy.HAS_SPACE)
			.limit(BED_CANDIDATES)
			.collect(Collectors.toSet());

		Path path = AcquirePoi.findPathToPois(mob, candidates);
		return path != null && path.canReach() ? path.getTarget() : null;
	}

	/** @return the bed the village has this worker down as sleeping in, or null if it has none here. */
	@Nullable
	private static BlockPos registeredHome(Mob mob, Level level) {
		if (!mob.getBrain()
			.hasMemoryValue(MemoryModuleType.HOME))
			return null;
		GlobalPos home = mob.getBrain()
			.getMemory(MemoryModuleType.HOME)
			.orElse(null);
		if (home == null || home.dimension() != level.dimension())
			return null;
		return home.pos();
	}

	/**
	 * Whether {@code pos} is a bed this worker could get into.
	 *
	 * <p>Never reads a block in a chunk nobody has loaded — that is what loads it, and this is asked
	 * of positions the POI storage will happily hand back from chunks that are not there.
	 */
	public static boolean isUsableBed(Level level, BlockPos pos, LivingEntity sleeper) {
		if (!level.isLoaded(pos))
			return false;

		BlockState state = level.getBlockState(pos);
		if (!state.isBed(level, pos, sleeper))
			return false;
		if (!state.hasProperty(BedBlock.OCCUPIED) || !state.getValue(BedBlock.OCCUPIED))
			return true;
		// Occupied is only a refusal when somebody else is in it.
		return sleeper.getSleepingPos()
			.map(pos::equals)
			.orElse(false);
	}

	/**
	 * The half of a bed a sleeper belongs at.
	 *
	 * <p>A bed is two blocks and only the head is a {@code home} point of interest, so a foot-end
	 * position would be a bed no search could ever match and no villager would ever recognise as
	 * theirs. Vanilla normalises the same way the moment a player clicks the foot of one.
	 */
	public static BlockPos bedHead(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		// Both properties, not just the part: this is reached from a player's click on anything in
		// BlockTags.BEDS, and a modded bed that declares one of the two and not the other would throw
		// out of getValue rather than simply not being normalised.
		if (!state.hasProperty(BedBlock.PART) || !state.hasProperty(BedBlock.FACING))
			return pos;
		if (state.getValue(BedBlock.PART) == BedPart.HEAD)
			return pos;
		return pos.relative(state.getValue(BedBlock.FACING));
	}

	/**
	 * The other half of the bed at {@code pos}, or null if there is not one.
	 *
	 * <p>Everything mechanical here keeps the head alone, because the head is the whole bed as far as
	 * the game's own bookkeeping is concerned — the point of interest, the block a sleeper is
	 * positioned at, the half {@code bedHead} normalises a click to. Anything a <em>player</em> looks
	 * at needs both, because to them a bed is one object two blocks long, and a box drawn around the
	 * stored position alone is a box around half of it.
	 *
	 * <p>{@code FACING} runs from the foot towards the head, so each half finds the other by stepping
	 * along it in the direction the other one lies. That is vanilla's own {@code getNeighbourDirection},
	 * which is private, restated.
	 */
	@Nullable
	public static BlockPos otherHalfOfBed(Level level, BlockPos pos) {
		if (!level.isLoaded(pos))
			return null;

		BlockState state = level.getBlockState(pos);
		if (!state.hasProperty(BedBlock.PART) || !state.hasProperty(BedBlock.FACING))
			return null;

		Direction facing = state.getValue(BedBlock.FACING);
		BlockPos other = pos.relative(state.getValue(BedBlock.PART) == BedPart.FOOT ? facing : facing.getOpposite());
		if (!level.isLoaded(other))
			return null;
		return level.getBlockState(other)
			.is(state.getBlock()) ? other : null;
	}
}
