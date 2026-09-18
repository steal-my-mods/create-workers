package com.createworkers.worker;

import java.util.EnumSet;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.net.WorkerStatePacket;
import com.createworkers.worker.WorkerData.Phase;
import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

/**
 * Drives a hard-hatted mob through the same phases a Mechanical Arm uses — find an input,
 * go to it, take something, find an output, go to it, hand it over — with the going-there
 * part delegated to a {@link WorkerLocomotion}.
 *
 * <p>Every path through here that hands a villager somewhere to walk is on a clock. A walk target
 * that is pinned every tick and never arrived at is not a worker standing still: {@code
 * MoveToTargetSink} asks the navigation for a fresh path each time it is not already following one,
 * and each of those is an A* over a region the width of the villager's follow range. Left unbounded
 * that is a few of them a second, for as long as the worker lives, out of one mob that looks idle.
 */
public class WorkerJobGoal extends Goal {

	/** How long to idle before re-scanning when there was nothing to do. */
	private static final int IDLE_RESCAN_TICKS = 20;
	/** How long a worker lingers at a stop on its rounds -- long enough to read as a look, not a nap. */
	private static final int DWELL_MIN_TICKS = 40;
	private static final int DWELL_MAX_TICKS = 120;
	/** How long to idle after abandoning an unreachable target. */
	private static final int UNREACHABLE_TICKS = 60;
	/** How long a target the worker could not get to is left out of the scan and off the rounds. */
	private static final int SET_ASIDE_TICKS = 600;
	/** How long a worker must have had nothing to do before it starts making its rounds. */
	private static final int IDLE_GRACE_TICKS = 60;
	/** How long the leash stands down after failing to get a worker home, once. */
	private static final int LEASH_REST_TICKS = 600;
	/** The longest it will ever stand down, however many times it has failed. */
	private static final int LEASH_REST_MAX = 6000;
	/** How many failures in a row before a worker is treated as lost rather than delayed. */
	private static final int LEASH_LOST_AFTER = 3;
	/** How often a lost worker says so. */
	private static final int LOST_SIGNAL_TICKS = 100;
	/** How often a hungry one does. Slower: hunger is a condition, not an emergency. */
	private static final int HUNGER_SIGNAL_TICKS = 160;
	/** Vanilla's "this villager is unhappy" entity event, which is what the signal borrows. */
	private static final byte ANGRY_PARTICLES = 13;
	/** How long a worker keeps trying to hand over what is already in its hands after the whistle. */
	private static final int KNOCK_OFF_GRACE_TICKS = 200;
	/** How long between bed hunts for a worker that has nowhere to sleep. */
	private static final int BED_SEARCH_TICKS = 200;
	/** How long the commute stands down after failing to get a worker to its bed. */
	private static final int BED_REST_TICKS = 600;
	/**
	 * How close counts as at the bedside. Vanilla's own figure for being able to lie down, and the
	 * reason this is not {@code reachDistance}: that is configurable up to six blocks, and a worker
	 * that thought it had arrived from there would stand across the room all night.
	 */
	private static final double BEDSIDE = 2.0D;

	private final Mob mob;
	private final WorkerLocomotion locomotion;
	/** The trip to the block the worker is about to use. */
	private final Progress travel = new Progress();
	/** The trip to the current stop on the idle rounds. */
	private final Progress rounds = new Progress();
	/** The trip back onto the patch. */
	private final Progress homeward = new Progress();
	/** The commute to bed. */
	private final Progress commute = new Progress();
	/**
	 * The walk to the post before the shift. Its own clock rather than the leash's: the leash only
	 * ever runs for a worker that has strayed off its patch, while muster walks one that may be
	 * standing squarely on it and simply needs to be at the work rather than at the bed.
	 */
	private final Progress mustering = new Progress();
	/** Where the worker is sleeping tonight; null whenever it is on the clock. */
	@Nullable
	private BlockPos bed;
	/** Ticks left before the worker looks for a bed again, having had none or given up on one. */
	private int bedWait;
	/** How long the worker has been trying to put down what it was holding when the whistle went. */
	private int knockOffTicks;
	/** Where the worker was standing when it ran out of work; null whenever it has somewhere to be. */
	@Nullable
	private BlockPos station;
	/** The stop on the worker's idle rounds it is currently ambling towards. */
	@Nullable
	private BlockPos patrolStop;
	/** Ticks left standing at a stop, looking it over. */
	private int dwellTicks;
	/** How long the worker has had nothing to haul. */
	private int idleTicks;
	/** Ticks left before the leash tries again, after it could not get the worker home. */
	private int leashRest;
	/** Ticks left standing still during muster, having arrived at the post or given up on reaching it. */
	private int musterRest;
	/** Ticks until a lost worker next says so. */
	private int lostSignal;
	/** Ticks until a hungry one next says so. */
	private int hungerSignal;

	public WorkerJobGoal(Mob mob, WorkerLocomotion locomotion) {
		this.mob = mob;
		this.locomotion = locomotion;
		setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
	}

	private WorkerData data() {
		return Workers.getOrCreate(mob);
	}

	@Override
	public boolean canUse() {
		if (!(mob.level() instanceof ServerLevel))
			return false;
		WorkerData data = Workers.get(mob);
		if (data == null || !data.isEmployed())
			return false;
		data.resolvePoints(mob);
		return data.hasWork();
	}

	@Override
	public boolean canContinueToUse() {
		return canUse();
	}

	@Override
	public void stop() {
		locomotion.stop(mob);
		travel.reset();
		forgetIdling();
		clockOn();
	}

	@Override
	public boolean requiresUpdateEveryTick() {
		return true;
	}

	@Override
	public void tick() {
		WorkerData data = data();
		long now = mob.level()
			.getGameTime();
		locomotion.tickEmployed(mob);

		WorkerShift.Stint stint = stint();
		if (stint != WorkerShift.Stint.WORKING) {
			// **Off the clock is not absence, and the clock has to be told so.** The station sacks a
			// worker whose lastAtWork is older than absenteeTimeout, and the only thing that ever
			// refreshes it is keepNearPost -- which the returns below skip for the whole of the night.
			// With the shipped defaults a crew is off shift for 16000 ticks against a 6000-tick
			// timeout, so every loaded worker was struck off its own roster partway through every
			// night, woken, stripped of its name and replaced by a fresh hire at dawn. Stamping here
			// freezes the clock across the night exactly as loading freezes it across an unloaded
			// chunk: a worker asleep in its bed at two in the morning is doing what it is supposed to.
			//
			// Marked *before* the branches, because each of them returns in the ordinary case.
			data.markAtWork(now);

			switch (stint) {
				case LEISURE -> {
					if (leisure(data))
						return;
				}
				case MUSTER -> {
					muster(data);
					return;
				}
				default -> {
					if (clockOff(data))
						return;
				}
			}
		} else {
			clockOn();
			// **Being on the clock is what costs a worker its dinner**, not what it manages to move.
			// Charging per delivery made a compact line eat three times what a spread-out one did while
			// walking less far, so the food bill rewarded building badly. Time is neutral to layout, and
			// it is what makes "how much bread does a crew need" arithmetic instead of an estimate.
			// Only here: the branches above are leisure, muster and the night, and none of them is work.
			data.chargeForWork(mob);
		}

		keepNearPost(data, now);
		signalIfHungry(data);

		if (data.tickCooldown())
			return;

		switch (data.getPhase()) {
			case SEARCH_INPUTS -> {
				// A worker serving notice finishes what it is carrying and starts nothing new. Its
				// station is waiting for its hands to be empty before moving it or letting it go, and
				// a worker that kept picking things up would never get there.
				if (data.isServingNotice()) {
					data.setCooldown(IDLE_RESCAN_TICKS);
					break;
				}
				int index = data.searchForItem(now);
				if (index >= 0) {
					data.selectTarget(Phase.MOVE_TO_INPUT, index);
					travel.reset();
				} else {
					data.setCooldown(IDLE_RESCAN_TICKS);
				}
			}
			case SEARCH_OUTPUTS -> {
				int index = data.searchForDestination(now);
				if (index >= 0) {
					data.selectTarget(Phase.MOVE_TO_OUTPUT, index);
					travel.reset();
				} else {
					data.setCooldown(IDLE_RESCAN_TICKS);
				}
			}
			case MOVE_TO_INPUT, MOVE_TO_OUTPUT -> travel(data, now);
		}
	}

	/**
	 * Which part of its own day this worker is in.
	 *
	 * <p>Anything that does not keep hours is always {@code WORKING}: an enderman has no brain
	 * schedule to read, and a server with {@code workingHours} off has asked for workers that never
	 * clock off. A dimension with a fixed sky is the same answer for the reason
	 * {@link WorkerShift#isOffShift(net.minecraft.world.level.Level, Shift)} gives — the Nether has a
	 * day time it borrows from the overworld, and a line that stopped at a midnight nobody can see
	 * would be the exact invisible failure this mod is built against.
	 */
	private WorkerShift.Stint stint() {
		if (!CWConfig.WORKING_HOURS.get() || !locomotion.keepsWorkingHours())
			return WorkerShift.Stint.WORKING;
		if (mob.level()
			.dimensionType()
			.hasFixedTime())
			return WorkerShift.Stint.WORKING;
		return WorkerShift.stintAt(mob.level()
			.getDayTime(), data().getShift());
	}

	/**
	 * The worker's own time: the goal lets go, and vanilla has it.
	 *
	 * <p>This is the whole of leisure. The idle package's behaviours — strolling, socialising, handing
	 * food about, breeding, showing trades — are all already loaded and have been running through
	 * every shift; they simply never had any effect, because they write {@code WALK_TARGET} and the
	 * goal overwrote it every tick before {@code MoveToTargetSink} could act on it. Standing back is
	 * all that is needed.
	 *
	 * <p>The leash stays on, and is the only thing that does. It is the same backstop
	 * {@code IdleBehaviour.WANDER} already relies on during a cooldown, and the reason leisure is not
	 * the reckless thing it sounds: a worker that strolls off a catwalk is walked home.
	 *
	 * <p>A worker still holding a delivery finishes it first. Knocking off with a stack is already
	 * bounded by {@link #KNOCK_OFF_GRACE_TICKS}, and the same bound serves here — leisure that began
	 * with items in hand would otherwise scatter a delivery across a field for the evening.
	 *
	 * @return whether leisure has taken charge of this tick.
	 */
	private boolean leisure(WorkerData data) {
		if (!data.getHeld()
			.isEmpty() && knockOffTicks++ < KNOCK_OFF_GRACE_TICKS)
			return false;

		if (data.getTargetPoint() != null) {
			data.abandonTarget();
			locomotion.stop(mob);
			travel.reset();
		}
		forgetRounds();
		// Not forgetLeash: the leash is what makes letting go affordable, and it is the difference
		// between leisure and a worker wandering off for good.
		keepNearPost(data, mob.level()
			.getGameTime(), CWConfig.IdleBehaviour.WANDER);
		return true;
	}

	/**
	 * Before the shift: awake, and walking to work.
	 *
	 * <p>Three crews covering the day exactly still leave a gap at every changeover, because the crew
	 * coming on is in bed when the crew going off stops. Muster moves that walk off the clock — they
	 * arrive while the last crew is still working and start hauling the moment it stops — without ever
	 * putting two crews on the clock at once, which is the invariant
	 * {@code theShippedCrewsDoNotOverlap} holds.
	 *
	 * <p>Getting out of bed is this side's job rather than vanilla's, for the same reason
	 * {@link #clockOn} does it: {@code WakeUp} only fires once the brain leaves {@code REST}, and the
	 * schedule's waking keyframe is where muster begins.
	 */
	private void muster(WorkerData data) {
		if (mob.isSleeping())
			mob.stopSleeping();
		bed = null;
		commute.reset();
		forgetRounds();

		// Walked to the job site rather than held where it woke: the point is to be standing at the
		// work when the shift starts, and the bed is not the work.
		//
		// **On a clock, like every other walk here.** Pinning WALK_TARGET without one is the most
		// expensive thing this mod can do: MoveToTargetSink asks for a fresh path whenever it is not
		// already following one, and a destination that is pinned and never arrived at is an A* over a
		// 48-block region every few ticks, for the length of a muster window, every in-game day, out
		// of one villager that looks like it is standing still. A bedroom walled off from the factory
		// overnight is enough to cause it.
		//
		// The clock does not distinguish arriving from being walled off, and does not need to: it
		// measures a run of ticks getting no nearer, which a worker standing at its post also
		// produces, and holding the ground it is on is the right answer to both. What it must not do
		// is call either one lost -- a worker that has arrived is not stuck, so this keeps its own
		// rest rather than borrowing the leash's failure count and its distress signal.
		BlockPos post = data.getJobSite();
		if (musterRest > 0) {
			musterRest--;
			holdStation();
			return;
		}
		if (mustering.stalled(mob, post, CWConfig.PATH_TIMEOUT.get())) {
			mustering.reset();
			musterRest = LEASH_REST_TICKS;
			holdStation();
			return;
		}
		forgetIdling();
		locomotion.returnTo(mob, post);
	}

	/**
	 * The end of the shift.
	 *
	 * <p>The delivery already in a worker's hands is the one thing that outlives the whistle. A worker
	 * that downed tools holding a stack would carry it until morning, which is items out of the
	 * factory for the night with nothing on the machines to say where they went — so a full-handed
	 * worker plays out the phases it is already in, and only those, since nothing here starts a new
	 * pickup. That is bounded too: an output that will not take the stack would otherwise keep a
	 * worker on the clock all night, so after {@link #KNOCK_OFF_GRACE_TICKS} it goes to bed carrying
	 * the load and delivers it in the morning, which is visible on the worker rather than lost.
	 *
	 * @return whether the night has taken charge of this tick.
	 */
	private boolean clockOff(WorkerData data) {
		if (!data.getHeld()
			.isEmpty() && knockOffTicks++ < KNOCK_OFF_GRACE_TICKS)
			return false;

		if (data.getTargetPoint() != null) {
			data.abandonTarget();
			locomotion.stop(mob);
			travel.reset();
		}
		// The rounds, but not the station. Clearing the station every tick would re-anchor it to
		// wherever the worker is standing on each of them, which is the drifting anchor the whole
		// remembered-station idea exists to avoid: a worker shoved by a mob, or one that fled a zombie
		// while holdAt was standing down, would hold wherever it ended up and never walk back -- and
		// the wander leash, which would normally catch that, is deliberately off for the night.
		forgetRounds();
		forgetLeash(data);
		goToBed(data);
		return true;
	}

	/**
	 * Morning. Everything the night left behind is cleared before the first scan of the day.
	 *
	 * <p>Getting the worker out of bed is this side's job rather than vanilla's, even though vanilla
	 * would do it eventually: {@code WakeUp} only fires once the villager's brain leaves {@code REST},
	 * and the operator's clock may well start the shift before the village's own morning.
	 */
	/**
	 * Opening time, which for a worker with trades is also restocking time.
	 *
	 * <p><b>A worker would otherwise sell out once and stay sold out for the rest of the world's
	 * life.</b> The thing that restocks a villager is {@code WorkAtPoi}, and it requires a
	 * {@code JOB_SITE} memory — which a worker has none of, deliberately, because
	 * {@code PoiCompetitorScan} would erase it from all but one of a rack's crew and
	 * {@code ResetProfession} would strip the profession behind it. So restocking has to be ours.
	 *
	 * <p>The start of a shift is the natural hook: it is the villager equivalent of the shop opening,
	 * it needs no clock of its own, and it inherits vanilla's limits for free — {@code shouldRestock}
	 * is public and already enforces the twice-a-day cap through {@code numberOfRestocksToday}. Called
	 * on every working tick and guarded by that, which is what makes a call this cheap safe to make
	 * that often.
	 */
	private void clockOn() {
		if (mob instanceof Villager villager && villager.shouldRestock())
			villager.restock();
		if (mob.isSleeping())
			mob.stopSleeping();
		bed = null;
		commute.reset();
		mustering.reset();
		bedWait = 0;
		knockOffTicks = 0;
		musterRest = 0;
	}

	/**
	 * Off the clock: find somewhere to sleep, walk there, and turn in.
	 *
	 * <p>A worker with nowhere to sleep — no bed on its hat, none it can prove a path to, or one it
	 * could not get to tonight — holds its station instead. That is deliberately the same thing an
	 * idle worker does: off shift with nowhere to go is idling that does not haul, so it takes on no
	 * new risk and needs no setup before the feature stops being an irritation.
	 *
	 * <p>The wander leash is not run while any of this is happening. A bed is within the programme's
	 * spread but need not be within {@code wanderRadius} of anything, so the leash would spend the
	 * night hauling the worker back off its own commute. Nothing is needed to undo that in the
	 * morning: the leash resumes on its own and walks the worker back from the bed.
	 */
	private void goToBed(WorkerData data) {
		if (mob.isSleeping()) {
			// Asleep, and staying that way. Occupying WALK_TARGET is what stops the brain's own
			// bed-hunting behaviours walking a sleeping worker out of the bed it is already in --
			// they all require that memory to be absent. It costs nothing: MoveToTargetSink erases a
			// walk target it has already arrived at without ever asking for a path.
			locomotion.holdAt(mob, mob.getSleepingPos()
				.orElseGet(mob::blockPosition));
			return;
		}

		if (bed == null) {
			if (bedWait > 0) {
				bedWait--;
				holdStation();
				return;
			}
			// Paced whether or not it finds anything: a bed hunt is a point-of-interest query and, for
			// a bed nobody assigned, a pathfind, and the worker that wants one most is the one with
			// nowhere to sleep. Asking every tick would put an A* on every such worker, all night.
			bedWait = BED_SEARCH_TICKS;
			bed = WorkerShift.findBed(mob, data);
			commute.reset();
			if (bed == null) {
				holdStation();
				return;
			}
		}

		if (bed.closerToCenterThan(mob.position(), BEDSIDE)) {
			turnIn();
			return;
		}

		if (commute.stalled(mob, bed, CWConfig.PATH_TIMEOUT.get())) {
			// Could not get there. Same reasoning as the leash: there is nothing above this to give up
			// to, so it has to give up on the clock, or a bed behind a door somebody bricked up is a
			// pathfind every few ticks until dawn. Holding station puts it back where it knocked off,
			// which is somewhere it was standing an hour ago and can therefore certainly stand again.
			bed = null;
			commute.reset();
			bedWait = BED_REST_TICKS;
			holdStation();
			return;
		}

		locomotion.commuteTo(mob, bed);
	}

	/**
	 * At the bedside: lie down if the village has turned in, otherwise stand by the bed and wait for
	 * it to. See {@link WorkerShift#isBedtime} for why those are two different questions.
	 */
	private void turnIn() {
		if (!WorkerShift.isBedtime(mob)) {
			// Home early. Stand by it until the village turns in -- and ask nothing of the bed itself
			// yet, because that is a block read and this can be a long wait.
			locomotion.holdAt(mob, bed);
			return;
		}

		if (WorkerShift.isUsableBed(mob.level(), bed, mob)) {
			mob.startSleeping(bed);
			return;
		}

		// Taken, or torn down, since the walk began. Look for another rather than standing over it
		// all night; a designated bed has nowhere else to look, so that worker holds station instead.
		bed = null;
		bedWait = BED_SEARCH_TICKS;
		holdStation();
	}

	/**
	 * Keeps an idle worker on its patch. This matters for villagers: their brain fills the gaps
	 * between jobs with strolling, trips to a job site and trips to the village meeting point, and
	 * nothing in the job goal occupies them during a cooldown.
	 */
	private void keepNearPost(WorkerData data, long gameTime) {
		keepNearPost(data, gameTime, CWConfig.IDLE_BEHAVIOUR.get());
	}

	/**
	 * The same, with the idling named rather than read from the config.
	 *
	 * <p>Leisure always passes {@code WANDER}, whatever {@code idleBehaviour} says, because the two
	 * settings answer different questions. {@code idleBehaviour} is "there is nothing to haul at this
	 * moment" — a gap *inside* a shift, where patrolling the run or standing at the post are both
	 * reasonable answers and the operator may have a preference. Leisure is "this worker is not
	 * working", and the answer to that is vanilla's, or it is not leisure at all. Reading the config
	 * here had a worker on PATROL, the default, walking its rounds all evening: pinned, on the clock
	 * in every way that matters, and asserted at 0.0 blocks moved.
	 */
	private void keepNearPost(WorkerData data, long gameTime, CWConfig.IdleBehaviour idling) {
		// Where the worker is, asked first and regardless of what it thinks it is doing. This is the
		// signal a station's absentee rule reads, and it has to be proximity rather than activity: a
		// worker cycling through targets it can never reach has one selected every tick, so anything
		// keyed off "is it busy" would show it hard at work from the bottom of a hole. Computed once
		// and shared with the leash below, which asks the same question.
		boolean atWork = !Workers.isOffStation(mob.blockPosition(), data, CWConfig.WANDER_RADIUS.get());
		if (atWork)
			data.markAtWork(gameTime);

		if (data.getTargetPoint() != null) {
			forgetIdling();
			forgetLeash(data);
			return; // already headed somewhere, and that takes priority
		}

		idleTicks++;

		// Strayed off the patch entirely: walk back to the middle of the job. Only for workers that
		// can stray in the first place -- an enderman only ever moves where the job sends it, so
		// leashing one is bookkeeping with nothing behind it, and a distress signal from one would be
		// a false alarm.
		if (locomotion.needsLeash() && !atWork) {
			walkHome(data);
			return;
		}
		forgetLeash(data);

		switch (idling) {
			case WANDER -> forgetIdling(); // vanilla's problem now; the leash above is the backstop
			case HOLD_STATION -> holdStation();
			case PATROL -> {
				if (locomotion.makesRounds())
					patrol(data, gameTime);
				else
					holdStation();
			}
		}
	}

	/**
	 * Walks a strayed worker back to the middle of its job — for a while.
	 *
	 * <p>There is nowhere else for the leash to give up to, so it has to give up on the clock. A
	 * worker that cannot get home — it fell somewhere, or somebody walled the way — would otherwise
	 * be pinned at a destination it never reaches for the rest of the world's life, at the cost of a
	 * pathfind every few ticks. Holding the ground it is standing on instead still keeps it from
	 * drifting any further, which is most of what the leash was for.
	 */
	private void walkHome(WorkerData data) {
		signalIfLost(data);

		if (leashRest > 0) {
			leashRest--;
			holdStation(); // given up for now, but not letting it wander further
			return;
		}

		BlockPos home = data.getJobSite();
		if (!homeward.stalled(mob, home, CWConfig.PATH_TIMEOUT.get())) {
			forgetIdling();
			locomotion.returnTo(mob, home);
			return;
		}

		homeward.reset();
		data.recordLeashFailure();
		leashRest = restAfter(data.leashFailures());

		if (data.leashFailures() >= LEASH_LOST_AFTER && CWConfig.RECALL_STUCK_WORKERS.get()
			&& recall(data, home))
			return;

		holdStation();
	}

	/**
	 * How long the leash waits before trying again, after {@code failures} attempts in a row.
	 *
	 * <p>Growing, because a worker that has failed three times is not going to succeed on the fourth
	 * for any reason the fourth attempt can discover. Each attempt is {@code pathTimeout} ticks of
	 * pinning a walk target the sink re-paths for whenever it is not already following one, which is
	 * the most expensive thing this mod does; at a flat rest that is a quarter of every worker-tick
	 * spent pathfinding somewhere unreachable, forever. Capped so the leash never stops trying
	 * altogether: a door somebody opens at midnight should still be noticed.
	 *
	 * <p>Pure and static so it can be reasoned about without a world — the growth is the point, and it
	 * is easier to read as arithmetic than to infer from a worker's behaviour over ten thousand ticks.
	 */
	public static int restAfter(int failures) {
		return Math.min(LEASH_REST_TICKS * Math.max(1, failures), LEASH_REST_MAX);
	}

	/**
	 * Says, to anyone near enough to see, that this worker is not coming back on its own.
	 *
	 * <p>The one thing the leash never did. It has always retried — a worker that can get home does,
	 * unaided — but a worker that cannot has no symptom at all: the line quietly runs short and
	 * nothing says which villager to go and look for. Vanilla's own unhappy-villager particles are the
	 * cheapest possible answer, on a slow clock, and they cost nothing at all when nobody is close
	 * enough to be sent them.
	 */
	private void signalIfLost(WorkerData data) {
		if (data.leashFailures() < LEASH_LOST_AFTER)
			return;
		if (lostSignal-- > 0)
			return;

		lostSignal = LOST_SIGNAL_TICKS;
		if (mob.level() instanceof ServerLevel level)
			level.broadcastEntityEvent(mob, ANGRY_PARTICLES);
	}

	/**
	 * The last resort, and off unless an operator asked for it: put a worker that cannot walk home
	 * back where it works.
	 *
	 * <p>Deliberately not the default. A villager appearing out of thin air is not something this mod
	 * does anywhere else, and a worker stuck somewhere is a fact about the base worth discovering. But
	 * the alternative is a line running short forever over one villager in a hole, and some servers
	 * would rather have the teleport than the puzzle.
	 *
	 * @return whether it worked; a refused teleport falls through to holding station as before.
	 */
	private boolean recall(WorkerData data, BlockPos home) {
		if (!mob.randomTeleport(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D, true))
			return false;

		forgetLeash(data);
		forgetIdling();
		return true;
	}

	/**
	 * Holds the spot the worker was standing on when the work ran out.
	 *
	 * <p>Anchoring to a remembered position rather than to wherever it happens to be now is what
	 * stops it creeping — an anchor that followed the worker would inch along with every nudge. It is
	 * also somewhere it can definitely stand and get back to, which the geometric job site may not be.
	 */
	private void holdStation() {
		if (station == null)
			station = mob.blockPosition()
				.immutable();
		locomotion.holdAt(mob, station);
	}

	/**
	 * Idle rounds: amble to one of the worker's own assigned blocks, stand and look at it for a
	 * while, then pick another. Gives a waiting worker something to do that looks like work, without
	 * ever sending it anywhere it does not already walk to while working.
	 */
	private void patrol(WorkerData data, long gameTime) {
		// A worker between two hauls has not run out of work, it is waiting out a transfer cooldown.
		// Setting off on the rounds in that gap would have it start a trip -- and a pathfind -- between
		// every item it moves, so the rounds only begin once there has genuinely been nothing to do.
		if (idleTicks < IDLE_GRACE_TICKS) {
			holdStation();
			return;
		}

		if (dwellTicks > 0) {
			dwellTicks--;
			holdStation(); // stood at a stop, having a look at it
			return;
		}

		if (patrolStop == null) {
			patrolStop = pickPatrolStop(data, gameTime);
			rounds.reset();
			station = null;
			if (patrolStop == null) {
				holdStation(); // nothing to walk between
				return;
			}
		}

		if (mob.blockPosition()
			.closerThan(patrolStop, arrivedDistance())) {
			restHere();
			return;
		}

		if (rounds.stalled(mob, patrolStop, CWConfig.PATH_TIMEOUT.get())) {
			// Could not get there. Nothing else bounds the rounds -- there is no job waiting on this
			// stop and no timeout above it -- so a stop that cannot be arrived at has to be written
			// off here or it is walked at forever.
			data.markUnreachable(patrolStop, gameTime + SET_ASIDE_TICKS);
			restHere();
			return;
		}

		locomotion.patrolTo(mob, patrolStop);
	}

	/**
	 * How close counts as having reached a stop on the rounds.
	 *
	 * <p>Deliberately no tighter than the reach a worker needs to <em>use</em> a block. A stop it can
	 * work but can never be said to have arrived at would be walked at until the timeout above, and
	 * the write-off would then take a perfectly good target out of the scan as well.
	 */
	private static double arrivedDistance() {
		return Math.max(WalkLocomotion.PATROL_ARRIVED, Math.ceil(CWConfig.REACH_DISTANCE.get()) + 1);
	}

	/** Stands where the worker is, looking around, before it picks another stop. */
	private void restHere() {
		patrolStop = null;
		rounds.reset();
		station = mob.blockPosition()
			.immutable();
		dwellTicks = DWELL_MIN_TICKS
			+ mob.getRandom()
				.nextInt(DWELL_MAX_TICKS - DWELL_MIN_TICKS + 1);
	}

	@Nullable
	private BlockPos pickPatrolStop(WorkerData data, long gameTime) {
		List<BlockPos> stops = Workers.patrolStops(data, gameTime);
		if (stops.isEmpty())
			return null;
		return stops.get(mob.getRandom()
			.nextInt(stops.size()));
	}

	private void forgetIdling() {
		station = null;
		forgetRounds();
	}

	/** Gives up the idle rounds while keeping the station — see {@link #clockOff}. */
	private void forgetRounds() {
		patrolStop = null;
		rounds.reset();
		dwellTicks = 0;
		idleTicks = 0;
	}

	/**
	 * How long the worker pauses after moving something, which is where hunger is felt most.
	 *
	 * <p>Slower, never stopped. A line that halts is a line whose owner has to go and find out why,
	 * and food should not be the one mechanic here that fails invisibly — but a hard stop turns a
	 * supply hiccup into an outage, which is over-correcting in the other direction. So a hungry
	 * worker limps, and {@code hungryPace} is a <b>floor</b> rather than a slide: it is as slow as
	 * hunger ever makes anybody. Without a floor, "my base has been at twenty per cent for three
	 * days" is the same invisible failure in slow motion.
	 */
	private int transferCooldown(WorkerData data) {
		int normal = CWConfig.TRANSFER_COOLDOWN.get();
		if (!data.isHungry(mob))
			return normal;
		return (int) Math.ceil(normal / CWConfig.HUNGRY_PACE.get());
	}

	/**
	 * Says, to anyone near enough to look, that this worker has nothing to eat.
	 *
	 * <p>The same unhappy-villager particles a lost worker broadcasts, on the same kind of slow clock,
	 * and for the same reason: a worker quietly working at a third speed is a factory that has slowed
	 * down for no visible cause, which is the worst shape a mechanic can fail in. The station screen
	 * says it too, for a player who is not standing there.
	 */
	private void signalIfHungry(WorkerData data) {
		if (!data.isHungry(mob)) {
			hungerSignal = 0;
			return;
		}
		if (hungerSignal-- > 0)
			return;
		hungerSignal = HUNGER_SIGNAL_TICKS;
		mob.level()
			.broadcastEntityEvent(mob, ANGRY_PARTICLES);
	}

	/** Gives the leash a clean slate: whatever it was doing, the worker has moved on from it. */
	private void forgetLeash(WorkerData data) {
		homeward.reset();
		leashRest = 0;
		lostSignal = 0;
		data.clearLeashFailures();
	}

	/**
	 * A clock on one trip, which only runs while the worker is getting no nearer.
	 *
	 * <p>Timing the trip itself would not do. An amble from one end of a forty-block beat to the other
	 * takes longer than any timeout worth having, and a worker written off for being slow would have a
	 * perfectly good inventory taken out of its scan. What the timeout is really for is the trip that
	 * is not going anywhere — walled off, on the wrong side of a drop, a funnel nothing can stand
	 * beside — so it is reset by any progress at all and only expires on a run of ticks without.
	 */
	private static final class Progress {

		private double closest = Double.MAX_VALUE;
		private int stalledTicks;

		void reset() {
			closest = Double.MAX_VALUE;
			stalledTicks = 0;
		}

		/** @return whether the worker has now spent {@code timeout} ticks getting no closer. */
		boolean stalled(Mob mob, BlockPos destination, int timeout) {
			double distance = mob.distanceToSqr(destination.getX() + 0.5D, destination.getY() + 0.5D,
				destination.getZ() + 0.5D);
			if (distance < closest) {
				closest = distance;
				stalledTicks = 0;
				return false;
			}
			return ++stalledTicks > timeout;
		}
	}

	private void travel(WorkerData data, long gameTime) {
		WorkerTarget point = data.getTargetPoint();
		if (point == null || !point.isValid()) {
			data.abandonTarget();
			travel.reset();
			return;
		}

		if (locomotion.canReach(mob, point.getPos())) {
			boolean collecting = data.getPhase() == Phase.MOVE_TO_INPUT;
			ItemStack before = data.getHeld()
				.copy();

			boolean acted = collecting ? data.collectFrom(point, gameTime) : data.depositTo(point);

			locomotion.stop(mob);
			travel.reset();
			data.setCooldown(transferCooldown(data));

			if (acted || !ItemStack.matches(before, data.getHeld())) {
				Workers.updateCargoAppearance(mob, data.getHeld());
				WorkerStatePacket.sync(mob, data);
				mob.level()
					.playSound(null, mob.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.2F,
						collecting ? 1.0F : 0.7F);
			}
			return;
		}

		locomotion.approach(mob, point.getPos());

		if (travel.stalled(mob, point.getPos(), CWConfig.PATH_TIMEOUT.get())) {
			// Cannot get there -- set it aside and try something else rather than standing still.
			// Setting aside, rather than just skipping this round: the round-robin comes back to it
			// almost immediately, and a worker whose only target is out of reach would spend the whole
			// timeout pathfinding at it again, over and over, for the rest of its life.
			point.markUnreachable(gameTime + SET_ASIDE_TICKS);
			data.abandonTarget();
			locomotion.stop(mob);
			travel.reset();
			data.setCooldown(UNREACHABLE_TICKS);
		}
	}
}
